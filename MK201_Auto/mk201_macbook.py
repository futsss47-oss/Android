"""Журнал MAC-адресов (книга Excel на сетевом диске): по серийному номеру изделия найти MAC.

Формат журнала (лист «0016D6_1»): столбец A — MAC (00:16:D6:FF:F5:FD, заполнен почти миллион строк), столбец D —
серийный номер выданного изделия «s/n: 1002011002600085» (регистр приписки разный, иногда пробел в конце; обычно это
формула, но Excel сохраняет вычисленное значение). MAC выдан изделию, у которого серийный номер стоит в той же строке.

Книга читается потоково (read_only), только два столбца и только пока идут выданные строки: когда подряд
GAP строк без серийного номера — дальше не читаем (выданные строки идут сверху вниз без больших пропусков).
Читается КОПИЯ файла во временной папке — книга может быть открыта в Excel у коллег. Результат (индекс) хранится в
кэше рядом с настройками и обновляется, только когда файл изменился (дата/размер) или по кнопке «Обновить».
В журнал ничего не пишется.
"""
import json, os, re, shutil, tempfile, time

GAP = 10000                                       # столько строк подряд без серийного номера — конец выданных
MAC_RE = re.compile(r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")


def norm_serial(value):
    """«s/n: 1002011002600085 » / «S/N: …» -> «1002011002600085» (только цифры); пусто — ''."""
    return re.sub(r"\D", "", str(value or ""))


def _col(letter):
    n = 0
    for ch in str(letter).strip().upper():
        n = n * 26 + (ord(ch) - 64)
    return max(n, 1)


def _stamp(path):
    st = os.stat(path)
    return {"path": os.path.abspath(path), "mtime": int(st.st_mtime), "size": st.st_size}


def _read_book(path, sheet, col_mac, col_sn, log=lambda s: None):
    """-> {серийный (цифры): [MAC, строка]}. Читает копию файла."""
    import openpyxl
    tmp = tempfile.mkdtemp(prefix="mk201_mac_")
    try:
        copy = os.path.join(tmp, "book.xlsx")
        t0 = time.time()
        shutil.copyfile(path, copy)
        log(f"Журнал MAC: файл скопирован за {time.time() - t0:.1f} с")
        wb = openpyxl.load_workbook(copy, read_only=True, data_only=True)
        try:
            if sheet not in wb.sheetnames:
                raise KeyError(f"в книге нет листа «{sheet}» (есть: {', '.join(wb.sheetnames)})")
            ws = wb[sheet]
            cm, cs = _col(col_mac), _col(col_sn)
            out, last_used = {}, 0
            t1 = time.time()
            for i, row in enumerate(ws.iter_rows(min_row=1, max_col=max(cm, cs), values_only=True), start=1):
                sn = norm_serial(row[cs - 1] if len(row) >= cs else "")
                if len(sn) >= 10:
                    mac = str(row[cm - 1] or "").strip().upper().replace("-", ":")
                    if MAC_RE.match(mac):
                        out.setdefault(sn, [mac, i])            # при повторе серийного — первая (верхняя) строка
                    last_used = i
                elif i - last_used > GAP and i > GAP:
                    break
            log(f"Журнал MAC: прочитано {i} строк за {time.time() - t1:.1f} с, выдано {len(out)} MAC")
            return out
        finally:
            wb.close()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def load_index(path, sheet, col_mac, col_sn, cache_file, force=False, log=lambda s: None):
    """-> (индекс, пометка о состоянии). Недоступен файл — берётся последний кэш (пометка «кэш…»).
    Ошибки читаемого файла (нет листа, не Excel) — исключение с понятным текстом."""
    cache = {}
    try:
        with open(cache_file, encoding="utf-8") as f:
            cache = json.load(f)
    except Exception:
        cache = {}
    key = {"sheet": sheet, "col_mac": str(col_mac).upper(), "col_sn": str(col_sn).upper()}
    try:
        stamp = _stamp(path)
    except OSError as e:
        if cache.get("map") and cache.get("path") == os.path.abspath(path) and cache.get("key") == key:
            when = time.strftime("%d.%m.%Y %H:%M", time.localtime(cache.get("loaded", 0)))
            return cache["map"], f"файл недоступен ({e.__class__.__name__}) — использую кэш от {when}"
        raise
    same = cache.get("key") == key and all(cache.get(k) == v for k, v in stamp.items())
    if same and not force and cache.get("map"):
        return cache["map"], "кэш (файл не менялся)"
    index = _read_book(path, sheet, col_mac, col_sn, log)
    try:
        with open(cache_file, "w", encoding="utf-8") as f:
            json.dump(dict(stamp, key=key, loaded=int(time.time()), map=index), f, ensure_ascii=False)
    except OSError:
        pass
    return index, "прочитан заново"


def lookup(index, product_number):
    """Полный номер изделия (16 цифр) -> (MAC, строка) или None."""
    hit = (index or {}).get(norm_serial(product_number))
    return (hit[0], hit[1]) if hit else None
