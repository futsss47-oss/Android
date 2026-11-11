"""Протокол испытаний MK201 (Excel). С версии 1.34 собирается самой программой — шаблон не нужен.

Все результаты хранятся в модели (словарь, кладётся в .json рядом с протоколом), а файл .xlsx рисуется из неё заново
при каждом сохранении. Повторный прогон того же номера в тот же день берёт модель из прошлого .json и дополняет её.
Оформление — как в прежнем шаблоне (таблицы 1–9, те же названия), но Arial 9 вместо 14 при масштабе 42 %, формат A4
книжный, 2 страницы: 1-я — разделы 1–3 (питание, AI, AO), 2-я — разделы 4–9 и подписи. Погрешности и «годен»
в таблицах — формулы, поэтому после правки измеренного значения вручную пересчитываются сами.
"""
import copy
from openpyxl import Workbook
from openpyxl.comments import Comment
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter as CL
from openpyxl.worksheet.pagebreak import Break
from openpyxl.worksheet.properties import PageSetupProperties

RED_FILL = PatternFill("solid", start_color="FFFF9999", end_color="FFFF9999")
RED_FONT = Font(name="Arial", size=9, color="FFC00000", bold=True)
HEAD_FILL = PatternFill("solid", start_color="FFEEF1F6", end_color="FFEEF1F6")

SP_DEFAULT = [0, 4, 8, 12, 16, 20]                           # точки AI / AO, мА
CI_POINTS = [(10, 300), (15000, 450000), (30000, 900000)]    # частота, Гц × число импульсов (ТУ, табл. 6)
# Погрешность частотомера — ОТНОСИТЕЛЬНАЯ, % от заданной частоты (паспорт: «основная относительная погрешность ±0,01 %»).
IFACES = ("Ethernet 10/100 Base-T", "RS-485_1", "RS-485_2", "RS-485_3", "CAN", "Service USB")

NCOL = 13
WIDTHS = [4.6, 7.8, 7.8, 7.8, 7.8, 7.8, 7.8, 4.6, 7.8, 7.8, 7.8, 7.8, 7.8]      # A..M
THIN = Side(style="thin", color="FF000000")
BOX = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
ROW = 12.0                                                   # высота строки данных, pt


def _px(width):
    return int(width * 7 + 5)                                # ширина колонки Excel (знаков) -> пиксели


class Protocol:
    IFACES = IFACES
    PAGE_W_PT, PAGE_H_PT = 595.0, 842.0                      # A4
    MARGIN_LR, MARGIN_TB = 0.3, 0.4                         # дюймы

    # ------------------------------------------------------------------ модель
    @staticmethod
    def blank():
        return {"number": "", "serial": "", "mac": "", "mac_src": "", "date": "", "operator": "", "op_date": "",
                "power": None,
                "ai": {str(c): {"sp": list(SP_DEFAULT), "m": [None] * 6} for c in range(8)},
                "ao": {str(c): {"sp": list(SP_DEFAULT), "m": [None] * 6} for c in range(2)},
                "di": {}, "do": {}, "ci": {}, "cif": {}, "ifaces": {}, "flags": {}, "bad": {}, "notes": {},
                "mem": {"Объем энергонезависимого ЗУ": "годен", "ОЗУ модуля": "годен"},
                "rtc": {"Встроенные часы реального времени": "±1 сек на 24 часа", "Индикация": "годен"},
                "data_rows": []}

    def __init__(self, data=None, tol=0.08, ci_tol=1, freq_tol=0.01):
        self.tol, self.ci_tol, self.freq_tol = float(tol), int(ci_tol), float(freq_tol)
        self.d = self.blank()
        if data:
            for k, v in data.items():
                if k in self.d:
                    self.d[k] = copy.deepcopy(v)
        if not self.d["date"]:
            import time
            self.d["date"] = time.strftime("%d.%m.%Y")

    def export(self):
        """Модель для .json (повторный прогон в тот же день дополняет её)."""
        return copy.deepcopy(self.d)

    # ------------------------------------------------------------------ запись результатов
    def setpoints_ai(self, ch=0):
        return [float(v) for v in self.d["ai"][str(ch)]["sp"]]

    def setpoints_ao(self, ch=0):
        return [float(v) for v in self.d["ao"][str(ch)]["sp"]]

    def ci_points(self, ch=0):
        return list(CI_POINTS)

    def set_title(self, number):
        self.d["serial"] = str(number)

    def set_mac(self, mac, src=""):
        if str(mac or "").strip():
            self.d["mac"] = str(mac).strip().upper().replace("-", ":")
            if src:
                self.d["mac_src"] = src

    def set_operator(self, fio, date_text):
        self.d["operator"], self.d["op_date"] = fio, date_text

    def set_current_consumption(self, ma, good=True):
        self.d["power"] = {"mA": round(float(ma), 1), "ok": bool(good)}

    def _clear_marks(self, kind, ch):
        self.d["flags"].pop(f"{kind}{ch}", None)
        self.d["bad"].pop(f"{kind}{ch}", None)

    def put_ai(self, ch, values):
        self.d["ai"][str(ch)]["m"] = [round(v, 3) for v in values]
        self._clear_marks("ai", ch)                          # исправленное снимает красное

    def put_ao(self, ch, values):
        self.d["ao"][str(ch)]["m"] = [round(v, 3) for v in values]
        self._clear_marks("ao", ch)

    def flag(self, kind, ch, note):
        """Замечание по каналу: красные измеренные ячейки и примечание с причиной."""
        self.d["flags"][f"{kind}{ch}"] = note

    def mark_bad_points(self, kind, ch, idx_list):
        self.d["bad"][f"{kind}{ch}"] = list(idx_list)

    def put_di(self, ch, cells):
        """cells: {'B': ('нет', True), ...} -> значение и признак «годен»."""
        row = self.d["di"].setdefault(str(ch), {})
        for col, (val, good) in cells.items():
            row[col] = [val, bool(good)]

    def put_do(self, ch, good):
        self.d["do"][str(ch)] = bool(good)

    def put_ci(self, ch, i, measured, error, good):
        self.d["ci"][f"{ch}:{i}"] = [measured, error, bool(good)]

    def put_cifreq(self, ch, i, f, meas, err_pct, good, wave=""):
        """Частотомер: вход ch (0–3), точка i (0–2)."""
        self.d["cif"][f"{ch}:{i}"] = {"f": f, "meas": round(float(meas), 4), "err_pct": round(float(err_pct), 5),
                                      "ok": bool(good), "wave": wave}

    def put_iface(self, name, state):
        self.d["ifaces"][name] = state

    def note(self, cell, text):
        """Примечание к таблице. Адреса ячеек прежнего шаблона: C70 — таблица 4 (DI)."""
        key = "di" if str(cell).upper().startswith(("C70", "B70")) else "other"
        self.d["notes"][key] = text

    def data_sheet(self, rows):
        self.d["data_rows"] = [[k, v] for k, v in rows]

    # ------------------------------------------------------------------ рисование
    def _cell(self, ws, r, c, v=None, bold=False, size=9, h="center", wrap=True, fmt=None, fill=None, box=True,
              italic=False, red=False, valign="center"):
        x = ws.cell(r, c)
        if v is not None:
            x.value = v
        x.font = RED_FONT if red else Font(name="Arial", size=size, bold=bold, italic=italic)
        x.alignment = Alignment(horizontal=h, vertical=valign, wrap_text=wrap)
        if fmt:
            x.number_format = fmt
        if fill is not None:
            x.fill = fill
        if box:
            x.border = BOX
        return x

    def _merge(self, ws, r1, c1, r2, c2, v=None, box=True, **kw):
        for r in range(r1, r2 + 1):
            for c in range(c1, c2 + 1):
                self._cell(ws, r, c, v if (r, c) == (r1, c1) else None, box=box, **kw)
        if (r1, c1) != (r2, c2):
            ws.merge_cells(start_row=r1, start_column=c1, end_row=r2, end_column=c2)

    def _hdr(self, ws, r1, c1, r2, c2, text, size=8):
        self._merge(ws, r1, c1, r2, c2, text, bold=True, size=size, fill=HEAD_FILL)

    def _height(self, ws, r, h):
        """Высота строки; строка общая для таблиц слева и справа — берётся бо́льшая."""
        h = max(h, self._h.get(r, 0))
        ws.row_dimensions[r].height = h
        self._h[r] = h

    def _title(self, ws, r, c1, c2, text, h=13.0):
        self._merge(ws, r, c1, r, c2, text, box=False, h="left", size=9)
        self._height(ws, r, h)

    def _section(self, ws, r, c, text):
        self._merge(ws, r, c, r, NCOL if c == 1 else c + 5, text, box=False, h="left", bold=True, size=10, wrap=False)
        self._height(ws, r, 16.0)

    def _comment(self, cell, text):
        cell.comment = Comment(f"MK201 Auto: {text}", "MK201 Auto", width=360, height=110)

    # --- таблица AI / AO: 6 колонок (№, заданное, измеренное, погрешность, допуск, результат)
    def _tbl_analog(self, ws, r, c0, kind, ch, title):
        info = self.d[kind][str(ch)]
        flagged = self.d["flags"].get(f"{kind}{ch}")
        bad = set(self.d["bad"].get(f"{kind}{ch}", []))
        self._title(ws, r, c0, c0 + 5, title)
        self._hdr(ws, r + 1, c0, r + 2, c0, "№")
        self._hdr(ws, r + 1, c0 + 1, r + 1, c0 + 2, "Значение тока, мА")
        self._hdr(ws, r + 1, c0 + 3, r + 2, c0 + 3, "Погр.,\n%")
        self._hdr(ws, r + 1, c0 + 4, r + 2, c0 + 4, "Допуск,\n±%")
        self._hdr(ws, r + 1, c0 + 5, r + 2, c0 + 5, "Итог")
        self._hdr(ws, r + 2, c0 + 1, r + 2, c0 + 1, "Задано")
        self._hdr(ws, r + 2, c0 + 2, r + 2, c0 + 2, "Измерено")
        self._height(ws, r + 1, 12.5); self._height(ws, r + 2, 12.5)
        col = lambda k: CL(c0 + k)
        for i in range(6):
            rr = r + 3 + i
            self._height(ws, rr, ROW)
            m = info["m"][i]
            self._cell(ws, rr, c0, i + 1)
            self._cell(ws, rr, c0 + 1, info["sp"][i], fmt="0")
            red = bool(flagged) or i in bad
            x = self._cell(ws, rr, c0 + 2, m, fmt="0.000", fill=RED_FILL if flagged else None, red=red)
            if flagged and i == 0:
                self._comment(x, flagged)
            sp, me = f"{col(1)}{rr}", f"{col(2)}{rr}"
            self._cell(ws, rr, c0 + 3, f'=IF({me}="","",({me}-{sp})*100/16)', fmt="0.000")
            self._cell(ws, rr, c0 + 4, self.tol, fmt="0.00")
            er = f"{col(3)}{rr}"
            self._cell(ws, rr, c0 + 5, f'=IF({me}="","",IF(ABS({er})<={col(4)}{rr},"годен","не годен"))', size=8)
        return r + 9

    # --- таблица CI: 6 колонок (№, частота, задано, измерено, погрешность, результат)
    def _tbl_ci(self, ws, r, c0, ch):
        self._title(ws, r, c0, c0 + 5, f"Таблица 6.{ch + 1} – Проверка счетного входа {ch + 1}")
        for k, t in enumerate(("№", "Частота,\nГц", "Задано,\nимп.", "Измерено\nимп.", "Погр.,\nимп.", "Итог")):
            self._hdr(ws, r + 1, c0 + k, r + 1, c0 + k, t)
        self._height(ws, r + 1, 24.0)
        for i, (f, n) in enumerate(CI_POINTS):
            rr = r + 2 + i
            self._height(ws, rr, ROW)
            got = self.d["ci"].get(f"{ch}:{i}")
            self._cell(ws, rr, c0, i + 1)
            self._cell(ws, rr, c0 + 1, f, fmt="0")
            self._cell(ws, rr, c0 + 2, n, fmt="0")
            red = bool(got) and not got[2]
            x = self._cell(ws, rr, c0 + 3, got[0] if got else None, fmt="0", fill=RED_FILL if red else None, red=red)
            self._cell(ws, rr, c0 + 4, got[1] if got else None, fmt="0", fill=RED_FILL if red else None, red=red)
            dc, cc = CL(c0 + 3), CL(c0 + 2)
            self._cell(ws, rr, c0 + 5, f'=IF({dc}{rr}="","",IF(ABS({dc}{rr}-{cc}{rr})<={self.ci_tol},"годен","не годен"))', size=8)
        return r + 5

    # --- таблица 6.5: частотомер, все четыре входа в одной таблице
    def _tbl_freq(self, ws, r):
        tol = self.freq_tol
        self._title(ws, r, 1, NCOL, f"Таблица 6.5 – Проверка частотомера счетных входов (допуск ±{tol:g} % от заданной частоты)")
        self._hdr(ws, r + 1, 1, r + 2, 1, "№")
        self._hdr(ws, r + 1, 2, r + 2, 3, "Задано,\nГц")             # тип сигнала в протоколе не показываем
        groups = ((4, 5, 5), (6, 7, 7), (8, 10, 9), (11, 12, 12))       # колонки: измерено, погрешность (CI3: H:I + J)
        for ch, (cm, ce, _) in enumerate(groups):
            self._hdr(ws, r + 1, cm, r + 1, ce, f"Вход CI{ch + 1}")
        self._hdr(ws, r + 1, 13, r + 2, 13, "Итог")
        spans = ((4, 4, 5), (6, 6, 7), (8, 9, 10), (11, 11, 12))
        for ch, (c1, c2, ce) in enumerate(spans):
            self._hdr(ws, r + 2, c1, r + 2, c2, "Измерено\nГц")
            self._hdr(ws, r + 2, ce, r + 2, ce, "Погр.,\n%")
        self._height(ws, r + 1, 12.5); self._height(ws, r + 2, 24.0)
        for i, (f, _) in enumerate(CI_POINTS):
            rr = r + 3 + i
            self._height(ws, rr, ROW)
            ents = [self.d["cif"].get(f"{ch}:{i}") for ch in range(4)]
            self._cell(ws, rr, 1, i + 1)
            self._merge(ws, rr, 2, rr, 3, f, fmt="0")
            errs = []
            for ch, (c1, c2, ce) in enumerate(spans):
                e = ents[ch]
                red = bool(e) and not e["ok"]
                fill = RED_FILL if red else None
                self._merge(ws, rr, c1, rr, c2, e["meas"] if e else None, fmt="0.0000", fill=fill, red=red, size=7)
                self._cell(ws, rr, ce, f'=IF({CL(c1)}{rr}="","",ABS({CL(c1)}{rr}-$B{rr})'
                                       f'/$B{rr}*100)', fmt="0.0000", fill=fill, red=red)
                errs.append(f"{CL(ce)}{rr}")
            self._cell(ws, rr, 13, f'=IF(COUNT({",".join(errs)})=0,"",IF(MAX({",".join(errs)})<={tol:g},"годен","не годен"))', size=8)
        return r + 6

    def _tbl_state(self, ws, r, c0, title, rows, title_h=13.0):
        """Таблица «Наименование | Состояние»: наименование — 4 колонки (c0..c0+3), состояние — 2 (c0+4..c0+5)."""
        self._title(ws, r, c0, c0 + 5, title, h=title_h)
        self._hdr(ws, r + 1, c0, r + 1, c0 + 3, "Наименование"); self._hdr(ws, r + 1, c0 + 4, r + 1, c0 + 5, "Состояние")
        self._height(ws, r + 1, 12.5)
        for i, (name, state) in enumerate(rows):
            rr = r + 2 + i
            lines = 2 if len(name) > 36 else 1
            self._height(ws, rr, ROW * lines)
            self._merge(ws, rr, c0, rr, c0 + 3, name, h="left", size=8)
            good = str(state or "") not in ("не годен",)
            self._merge(ws, rr, c0 + 4, rr, c0 + 5, state, red=not good, fill=RED_FILL if not good else None, size=8)
        return r + 2 + len(rows)

    # ------------------------------------------------------------------ сборка книги
    def build(self):
        wb = Workbook()
        ws = wb.active
        ws.title = "Протокол"
        self._h = {}
        for c, w in enumerate(WIDTHS, 1):
            ws.column_dimensions[CL(c)].width = w
        d = self.d
        r = 1
        self._merge(ws, r, 1, r, NCOL, f"Протокол испытаний №__________ от {d['date']}", box=False, bold=True, size=13)
        self._height(ws, r, 19.0); r += 1
        self._merge(ws, r, 1, r, NCOL, f"Контроллера промышленного MK201A № {d['serial']}", box=False, bold=True, size=11)
        self._height(ws, r, 16.0); r += 1
        self._merge(ws, r, 1, r, NCOL, f"MAC-адрес: {d['mac'] or 'не указан'}", box=False, bold=True, size=10)
        self._height(ws, r, 15.0); r += 1
        self._height(ws, r, 6.0); r += 1

        # 1. Потребление
        self._section(ws, r, 1, "1. Проверка потребления тока и мощности"); r += 1
        self._title(ws, r, 1, 7, "Таблица 1 - Проверка потребления тока и мощности"); r += 1
        self._hdr(ws, r, 1, r, 4, "Потребляемый ток, мА"); self._hdr(ws, r, 5, r, 7, "Потребляемая мощность, Вт")
        self._height(ws, r, 12.5); r += 1
        self._hdr(ws, r, 1, r, 2, "Норма"); self._hdr(ws, r, 3, r, 3, "Измерено"); self._hdr(ws, r, 4, r, 4, "Итог")
        self._hdr(ws, r, 5, r, 5, "Норма"); self._hdr(ws, r, 6, r, 6, "Измерено"); self._hdr(ws, r, 7, r, 7, "Итог")
        self._height(ws, r, 12.5); r += 1
        p = d["power"]
        red = bool(p) and not p["ok"]
        self._merge(ws, r, 1, r, 2, 400, fmt="0")
        self._cell(ws, r, 3, p["mA"] if p else None, fmt="0.0", fill=RED_FILL if red else None, red=red)
        self._cell(ws, r, 4, f'=IF(C{r}="","",IF(C{r}<=A{r},"годен","не годен"))', size=8)
        self._cell(ws, r, 5, 6, fmt="0")
        self._cell(ws, r, 6, f'=IF(C{r}="","",24*C{r}/1000)', fmt="0.00", fill=RED_FILL if red else None, red=red)
        self._cell(ws, r, 7, f'=IF(F{r}="","",IF(F{r}<=E{r},"годен","не годен"))', size=8)
        self._height(ws, r, ROW); r += 1
        self._height(ws, r, 6.0); r += 1

        # 2. Аналоговые входы
        self._section(ws, r, 1, "2. Проверка аналоговых входов"); r += 1
        for k in range(4):
            end = self._tbl_analog(ws, r, 1, "ai", 2 * k, f"Таблица 2.{2 * k + 1} – Проверка аналогового входа {2 * k + 1}")
            self._tbl_analog(ws, r, 8, "ai", 2 * k + 1, f"Таблица 2.{2 * k + 2} – Проверка аналогового входа {2 * k + 2}")
            r = end; self._height(ws, r, 4.0); r += 1

        # 3. Аналоговые выходы
        self._section(ws, r, 1, "3. Проверка аналоговых выходов"); r += 1
        end = self._tbl_analog(ws, r, 1, "ao", 0, "Таблица 3.1 – Проверка аналогового выхода 1")
        self._tbl_analog(ws, r, 8, "ao", 1, "Таблица 3.2 – Проверка аналогового выхода 2")
        r = end; self._height(ws, r, 4.0); r += 1
        page1_end = r - 1

        # 4–5. Дискретные входы и выходы рядом
        self._merge(ws, r, 1, r, 7, "4. Проверка дискретных входов", box=False, h="left", bold=True, size=10, wrap=False)
        self._merge(ws, r, 10, r, 13, "5. Проверка дискретных выходов", box=False, h="left", bold=True, size=10, wrap=False)
        self._height(ws, r, 16.0); r += 1
        self._merge(ws, r, 1, r, 7, "Таблица 4 – Срабатывание дискретных входов при подаче логической единицы на вход "
                                     "(наблюдение в окне ПО «MKTest»)", box=False, h="left")
        self._merge(ws, r, 10, r, 13, "Таблица 5 – Срабатывание дискретных выходов при их активации с помощью ПО «MKTest»",
                    box=False, h="left")
        self._height(ws, r, 37.0)
        if d["notes"].get("di"):
            self._comment(ws.cell(r, 1), d["notes"]["di"])
        r += 1
        top = r
        self._hdr(ws, r, 1, r + 1, 1, "№")
        self._hdr(ws, r, 2, r, 7, "Срабатывание дискретного входа при величине входного сигнала:")
        for k, t in enumerate(("5 В", "ток менее 30 мА", "11 В", "ток менее 6 мА", "30 В", "ток менее 30 мА")):
            self._hdr(ws, r + 1, 2 + k, r + 1, 2 + k, t)
        self._hdr(ws, r, 10, r + 1, 10, "№")
        self._hdr(ws, r, 11, r + 1, 11, "Коммута-\nция тока\n0,25 А", size=7)
        self._hdr(ws, r, 12, r + 1, 12, "Падение\nнапряже-\nния менее\n1,5 В", size=7)
        self._hdr(ws, r, 13, r + 1, 13, "Ток утечки\nменее\n10 мкА", size=7)
        self._height(ws, r, 24.0); self._height(ws, r + 1, 36.0)
        r += 2
        cols = ("B", "C", "D", "E", "F", "G")
        for ch in range(16):
            rr = r + ch
            self._height(ws, rr, ROW)
            self._cell(ws, rr, 1, ch + 1)
            row = d["di"].get(str(ch), {})
            for k, col in enumerate(cols):
                v = row.get(col)
                bad = bool(v) and not v[1]
                self._cell(ws, rr, 2 + k, v[0] if v else None, fill=RED_FILL if bad else None, red=bad, size=8)
            okd = d["do"].get(str(ch))
            self._cell(ws, rr, 10, ch + 1)
            for k in range(3):
                self._cell(ws, rr, 11 + k, None if okd is None else ("да" if okd else "нет"),
                           fill=RED_FILL if okd is False else None, red=okd is False, size=8)
        r += 16
        self._height(ws, r, 4.0); r += 1

        # 6. Счётные входы
        self._section(ws, r, 1, "6. Проверка счетных входов"); r += 1
        for pair in ((0, 1), (2, 3)):
            end = self._tbl_ci(ws, r, 1, pair[0])
            self._tbl_ci(ws, r, 8, pair[1])
            r = end; self._height(ws, r, 4.0); r += 1
        r = self._tbl_freq(ws, r)
        self._height(ws, r, 4.0); r += 1

        # 7–9. Интерфейсы, память, индикация и часы
        self._merge(ws, r, 1, r, 6, "7. Проверка интерфейсов связи", box=False, h="left", bold=True, size=10, wrap=False)
        self._merge(ws, r, 8, r, 13, "8. Проверка памяти", box=False, h="left", bold=True, size=10, wrap=False)
        self._height(ws, r, 16.0); r += 1
        # Ethernet, RS-485_1–3 и Service USB ставит проверка (нет проверки — пусто, а не «годен»); CAN — вручную
        rows7 = [(n, d["ifaces"].get(n, "годен" if n == "CAN" else "")) for n in IFACES]
        end7 = self._tbl_state(ws, r, 1, "Таблица 7 – Проверка интерфейсов связи", rows7)
        e8 = self._tbl_state(ws, r, 8, "Таблица 8 – Проверка памяти", list(d["mem"].items()))
        self._merge(ws, e8, 8, e8, 13, "9. Проверка индикации и часов реального времени", box=False, h="left", bold=True,
                    size=9, wrap=False)
        self._height(ws, e8, 16.0)
        e9 = self._tbl_state(ws, e8 + 1, 8, "Таблица 9 – Индикация и часы реального времени", list(d["rtc"].items()))
        r = max(end7, e9)
        self._height(ws, r, 8.0); r += 1

        # подписи
        self._merge(ws, r, 1, r, 7, "Измерения провели:", box=False, h="left", bold=True, size=10, wrap=False)
        self._height(ws, r, 16.0); r += 1
        # 1.43: четыре отдельные линии с промежутками — должность (A:C), подпись (E:F), дата (H:I), ФИО (K:M);
        # под каждой своя подпись. D, G, J — промежутки без линии.
        fields = ((1, 3, "должность"), (5, 6, "подпись"), (8, 9, "дата"), (11, 13, "ФИО"))
        for pos, fio, dt in (("Оператор", d["operator"], d["op_date"]), (None, None, None)):
            vals = (pos if (fio or dt) else None, None, dt or None, fio or None)
            for (c1, c2, _), v in zip(fields, vals):
                self._merge(ws, r, c1, r, c2, v, box=False, size=9)
                for c in range(c1, c2 + 1):
                    ws.cell(r, c).border = Border(bottom=THIN)
            self._height(ws, r, 20.0); r += 1
            for c1, c2, t in fields:
                self._merge(ws, r, c1, r, c2, t, box=False, size=7, italic=True)
            self._height(ws, r, 10.0); r += 1
            self._height(ws, r, 8.0); r += 1
        last = r - 1

        self._page_setup(ws, page1_end, last)
        self._data_sheet(wb)
        return wb

    def _page_setup(self, ws, page1_end, last):
        """A4 книжная, разрыв после раздела 3, масштаб — чтобы обе страницы влезли по ширине и высоте."""
        h1 = sum(self._h.get(r, ROW) for r in range(1, page1_end + 1))
        h2 = sum(self._h.get(r, ROW) for r in range(page1_end + 1, last + 1))
        width_pt = sum(_px(w) for w in WIDTHS) * 0.75
        avail_w = self.PAGE_W_PT - 2 * self.MARGIN_LR * 72
        avail_h = self.PAGE_H_PT - 2 * self.MARGIN_TB * 72 - 8        # запас на сноску
        scale = int(min(100, 100 * avail_w / width_pt, 100 * avail_h / max(h1, h2)))
        self.page_info = {"scale": scale, "page1_pt": h1, "page2_pt": h2, "width_pt": width_pt}
        ws.page_setup.orientation = "portrait"
        ws.page_setup.paperSize = 9
        ws.page_setup.scale = scale
        ws.page_margins.left = ws.page_margins.right = self.MARGIN_LR
        ws.page_margins.top = ws.page_margins.bottom = self.MARGIN_TB
        ws.page_margins.header = ws.page_margins.footer = 0.15
        ws.print_options.horizontalCentered = True
        ws.print_area = f"A1:M{last}"
        ws.row_breaks.append(Break(id=page1_end))
        ws.oddFooter.center.text = "Лист &P из 2"
        ws.oddFooter.center.size = 7
        ws.sheet_view.showGridLines = False

    def _data_sheet(self, wb):
        """Лист «Данные» (как в МИБ): заключение, ошибки, коды калибровки, замечания."""
        ws = wb.create_sheet("Данные")
        ws.column_dimensions["A"].width = 38
        ws.column_dimensions["B"].width = 100
        ws.page_setup.orientation = "landscape"
        ws.page_setup.paperSize = 9
        ws.page_setup.fitToWidth, ws.page_setup.fitToHeight = 1, 0
        ws.sheet_properties.pageSetUpPr = PageSetupProperties(fitToPage=True)
        bold = Font(bold=True)
        rows = list(self.d["data_rows"])
        if self.d["mac"]:
            rows.insert(0, ("MAC-адрес", self.d["mac"] + (f" (источник: {self.d['mac_src']})" if self.d["mac_src"] else "")))
        for r, (k, v) in enumerate(rows, 1):
            ws.cell(r, 1, k).font = bold
            c = ws.cell(r, 2, v)
            c.alignment = Alignment(wrap_text=True, vertical="top")
            ws.cell(r, 1).alignment = Alignment(vertical="top")
            if isinstance(v, str) and (v.startswith("НЕ ГОДЕН") or v.startswith("!!!")):
                c.font = Font(color="FFC00000", bold=True)

    def save(self, path):
        import os
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        self.build().save(path)


def serial_from_name(path):
    import os, re
    m = re.search(r"(\d{10,})", os.path.basename(path))
    return m.group(1) if m else ""
