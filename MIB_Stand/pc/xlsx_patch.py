"""
Запись значений в ячейки .xlsx напрямую в XML.

Зачем: openpyxl при сохранении теряет расширение проверки данных (выпадающие списки),
которое есть в шаблоне. Здесь меняются только указанные ячейки, всё остальное
(формулы, массивные формулы, условное форматирование, списки) остаётся как в шаблоне.
В книгу добавляется пересчёт формул при открытии в Excel; устаревшая цепочка
вычислений (calcChain) удаляется, чтобы Excel не ругался на заменённые формулы.

patch_xlsx(src, dst, {"Данные": {"C9": 132, "B12": 0.002}, "Лист1": {"D7": "SN123"}})
Значение None очищает ячейку. read_list(src, "Ресурсы", "C") читает список из столбца.
"""
import re
import zipfile
from xml.sax.saxutils import escape, unescape


def _attrs(s: str) -> dict:
    return {k: unescape(v) for k, v in re.findall(r'([\w:]+)="([^"]*)"', s)}


def _sheet_files(z: zipfile.ZipFile) -> dict:
    wb = z.read("xl/workbook.xml").decode("utf-8")
    rels = z.read("xl/_rels/workbook.xml.rels").decode("utf-8")
    targets = {}
    for m in re.finditer(r"<Relationship\s([^>]*?)/?>", rels):
        a = _attrs(m.group(1))
        if "Id" in a and "Target" in a:
            t = a["Target"].lstrip("/")
            targets[a["Id"]] = t if t.startswith("xl/") else "xl/" + t
    out = {}
    for m in re.finditer(r"<sheet\s([^>]*?)/?>", wb):
        a = _attrs(m.group(1))
        out[a["name"]] = targets[a["r:id"]]
    return out


def _cell_xml(ref: str, style: str, value) -> str:
    s = f' s="{style}"' if style else ""
    if value is None:  # очистить ячейку, оформление сохраняется
        return f'<c r="{ref}"{s}/>'
    if isinstance(value, str):
        return f'<c r="{ref}"{s} t="inlineStr"><is><t xml:space="preserve">{escape(value)}</t></is></c>'
    return f'<c r="{ref}"{s}><v>{repr(float(value)) if isinstance(value, float) else int(value)}</v></c>'


def _patch_sheet(xml: str, cells: dict) -> str:
    for ref, value in cells.items():
        pat = re.compile(r'<c r="%s"([^>]*?)(?:/>|>.*?</c>)' % re.escape(ref), re.S)
        m = pat.search(xml)
        if not m:
            raise KeyError(f"ячейка {ref} отсутствует в XML листа (пустая ячейка без оформления)")
        style = _attrs(m.group(1)).get("s", "")
        xml = xml[:m.start()] + _cell_xml(ref, style, value) + xml[m.end():]
    return xml


_FORMULA_CELL = re.compile(r"<c\s([^>]*?)>(<f[^>]*?(?:/>|>.*?</f>))<v>.*?</v></c>", re.S)


def _strip_cached(xml: str) -> str:
    """Убрать устаревшие сохранённые результаты формул, чтобы программы пересчитали их сами."""
    def repl(m):
        attrs = re.sub(r'\s*t="[^"]*"', "", m.group(1))
        return f"<c {attrs}>{m.group(2)}</c>"
    return _FORMULA_CELL.sub(repl, xml)


def _force_recalc(wb_xml: str) -> str:
    if "<calcPr" in wb_xml:
        if "fullCalcOnLoad" in wb_xml:
            return wb_xml
        return wb_xml.replace("<calcPr", '<calcPr fullCalcOnLoad="1"', 1)
    for anchor in ("</definedNames>", "</sheets>"):
        if anchor in wb_xml:
            return wb_xml.replace(anchor, anchor + '<calcPr fullCalcOnLoad="1"/>', 1)
    return wb_xml


def _shared_strings(z: zipfile.ZipFile) -> list:
    if "xl/sharedStrings.xml" not in z.namelist():
        return []
    xml = z.read("xl/sharedStrings.xml").decode("utf-8")
    out = []
    for m in re.finditer(r"<si\b[^>]*?/>|<si\b[^>]*?>(.*?)</si>", xml, re.S):
        body = m.group(1) or ""
        out.append(unescape("".join(re.findall(r"<t[^>]*>(.*?)</t>", body, re.S))))
    return out


def read_list(src: str, sheet: str, column: str, first_row: int = 2, last_row: int = 500) -> list:
    """Прочитать столбец текстовых значений листа (например, списки на листе «Ресурсы»)."""
    with zipfile.ZipFile(src) as z:
        files = _sheet_files(z)
        xml = z.read(files[sheet]).decode("utf-8")
        sst = _shared_strings(z)
    found = []
    for m in re.finditer(r'<c r="%s(\d+)"([^>]*?)(?:/>|>(.*?)</c>)' % re.escape(column), xml, re.S):
        row = int(m.group(1))
        if not first_row <= row <= last_row:
            continue
        t, body = _attrs(m.group(2)).get("t"), m.group(3) or ""
        if t == "s":
            v = re.search(r"<v>(\d+)</v>", body)
            val = sst[int(v.group(1))] if v else ""
        elif t == "inlineStr":
            val = unescape("".join(re.findall(r"<t[^>]*>(.*?)</t>", body, re.S)))
        else:
            v = re.search(r"<v>(.*?)</v>", body)
            val = unescape(v.group(1)) if v else ""
        if val.strip():
            found.append((row, val.strip()))
    return [v for _, v in sorted(found)]


def patch_xlsx(src: str, dst: str, changes: dict):
    with zipfile.ZipFile(src) as zin:
        files = _sheet_files(zin)
        for sheet in changes:
            if sheet not in files:
                raise KeyError(f"лист {sheet!r} не найден, есть: {list(files)}")
        by_path = {files[name]: cells for name, cells in changes.items()}
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                if item.filename == "xl/calcChain.xml":
                    # цепочка вычислений ссылается на ячейки, где формулы заменены значениями,
                    # из-за чего Excel выдаёт ошибку восстановления; без неё он строит её заново
                    continue
                data = zin.read(item.filename)
                if item.filename == "[Content_Types].xml":
                    data = re.sub(rb"<Override[^>]*calcChain[^>]*/>", b"", data)
                elif item.filename == "xl/_rels/workbook.xml.rels":
                    data = re.sub(rb"<Relationship[^>]*calcChain[^>]*/>", b"", data)
                if item.filename in files.values():
                    xml = data.decode("utf-8")
                    if item.filename in by_path:
                        xml = _patch_sheet(xml, by_path[item.filename])
                    data = _strip_cached(xml).encode("utf-8")
                elif item.filename == "xl/workbook.xml":
                    data = _force_recalc(data.decode("utf-8")).encode("utf-8")
                zout.writestr(item, data)
