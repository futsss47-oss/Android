"""Заполнение протокола испытаний (Excel). Формулы погрешности остаются в шаблоне."""
import openpyxl, re, os
from copy import copy
from openpyxl.styles import PatternFill, Font
from openpyxl.comments import Comment

RED_FILL = PatternFill('solid', start_color='FFFF9999', end_color='FFFF9999')
RED_FONT = Font(color='FFC00000', bold=True)


def ai_cells(ch):
    """канал 0..7 -> (колонка заданного, колонка измеренного, первая строка)"""
    base = 17 + 10 * (ch // 2)
    return ("B", "C", base) if ch % 2 == 0 else ("G", "H", base)


def ao_cells(ch):
    return ("B", "C", 59) if ch == 0 else ("G", "H", 59)


class Protocol:
    def __init__(self, path, template=None):
        """path — шаблон или протокол, в который дописываем; template — чистый шаблон, из него берётся
        исходное оформление ячеек, когда повторный прогон исправил то, что раньше было красным."""
        self.wb = openpyxl.load_workbook(path)
        self.ws = self.wb["Лист1"] if "Лист1" in self.wb.sheetnames else self.wb.worksheets[0]
        self.tpl = None
        if template and os.path.abspath(template) != os.path.abspath(path):
            try:
                wb = openpyxl.load_workbook(template)
                self.tpl = wb["Лист1"] if "Лист1" in wb.sheetnames else wb.worksheets[0]
            except Exception:
                self.tpl = None

    def _put(self, ref, value, good=True):
        """Записать значение; годное — с исходным оформлением шаблона (снять красное от прошлого прогона)."""
        c = self.ws[ref]
        c.value = value
        if good:
            if self.tpl is not None:
                t = self.tpl[ref]
                c.font = copy(t.font); c.fill = copy(t.fill)
            elif c.fill is not None and c.fill.start_color is not None and c.fill.start_color.rgb == RED_FILL.start_color.rgb:
                c.fill = PatternFill(fill_type=None); c.font = Font()
            if c.comment is not None and str(c.comment.text).startswith("MK201 Auto"):
                c.comment = None
        else:
            c.fill = RED_FILL; c.font = RED_FONT
        return c

    def setpoints_ai(self, ch=0):
        s, _, r = ai_cells(ch)
        return [float(self.ws[f"{s}{r + i}"].value) for i in range(6)]

    def setpoints_ao(self, ch=0):
        s, _, r = ao_cells(ch)
        return [float(self.ws[f"{s}{r + i}"].value) for i in range(6)]

    def set_title(self, number):
        self.ws["C3"] = f"Контроллера промышленного MK201A № {number}"

    def set_current_consumption(self, ma, good=True):
        self._put("B10", round(ma, 1), good)   # D10 пересчитается формулой (=24*B10/1000)

    def data_sheet(self, rows):
        """Лист «Данные» (как в МИБ): заключение, ошибки, коды калибровки, замечания. rows: [(заголовок, значение)]."""
        name = "Данные"
        if name in self.wb.sheetnames:
            del self.wb[name]
        ws = self.wb.create_sheet(name)
        ws.column_dimensions["A"].width = 38
        ws.column_dimensions["B"].width = 110
        bold = Font(bold=True)
        for r, (k, v) in enumerate(rows, 1):
            ws.cell(r, 1, k).font = bold
            c = ws.cell(r, 2, v)
            if isinstance(v, str) and (v.startswith("НЕ ГОДЕН") or v.startswith("!!!")):
                c.font = RED_FONT

    def put_ai(self, ch, values):
        _, m, r = ai_cells(ch)
        for i, v in enumerate(values):
            self._put(f"{m}{r + i}", round(v, 3))

    def put_ao(self, ch, values):
        _, m, r = ao_cells(ch)
        for i, v in enumerate(values):
            self._put(f"{m}{r + i}", round(v, 3))

    def flag(self, kind, ch, note):
        """Выделить красным измеренные ячейки канала (6 точек) и добавить примечание с причиной."""
        _, m, r = (ai_cells if kind == "ai" else ao_cells)(ch)
        for i in range(6):
            c = self.ws[f"{m}{r + i}"]
            c.fill = RED_FILL; c.font = RED_FONT
        self.ws[f"{m}{r}"].comment = Comment(f"MK201 Auto: {note}", "MK201 Auto", width=360, height=110)

    def mark_bad_points(self, kind, ch, idx_list):
        """Красным шрифтом точки, вышедшие за допуск."""
        _, m, r = (ai_cells if kind == "ai" else ao_cells)(ch)
        for i in idx_list:
            self.ws[f"{m}{r + i}"].font = RED_FONT

    # ---- таблица 4: DI (строки 71–86), колонки B..G: 5 В, ток, 11 В, ток, 30 В, ток ----
    def put_di(self, ch, cells):
        """cells: {'B': ('нет', True), ...} -> значение и признак «годен»."""
        r = 71 + ch
        for col, (val, good) in cells.items():
            self._put(f"{col}{r}", val, good)

    def note(self, cell, text):
        self.ws[cell].comment = Comment(f"MK201 Auto: {text}", "MK201 Auto", width=320, height=90)

    # ---- таблица 5: DO (строки 92–107), колонки B, C, D ----
    def put_do(self, ch, good):
        r = 92 + ch
        for col in "BCD":
            self._put(f"{col}{r}", "да" if good else "нет", good)

    # ---- таблица 6: CI. Канал 0..3 -> (колонки частота/кол-во/измерено/погрешность, первая строка) ----
    @staticmethod
    def ci_cells(ch):
        row = 113 if ch < 2 else 119
        return ("B", "C", "D", "E", row) if ch % 2 == 0 else ("H", "I", "J", "K", row)

    def ci_points(self, ch):
        f, n, _, _, r = self.ci_cells(ch)
        out = []
        for i in range(3):
            try:
                out.append((float(self.ws[f"{f}{r + i}"].value), int(float(self.ws[f"{n}{r + i}"].value))))
            except (TypeError, ValueError):
                pass
        return out

    def put_ci(self, ch, i, measured, error, good):
        _, _, m, e, r = self.ci_cells(ch)
        for col, val in ((m, measured), (e, error)):
            self._put(f"{col}{r + i}", val, good)

    # ---- таблица 7: интерфейсы (строки 127–132) ----
    IFACES = ("Ethernet 10/100 Base-T", "RS-485_1", "RS-485_2", "RS-485_3", "CAN", "Service USB")

    def put_iface(self, name, state):
        r = 127 + self.IFACES.index(name)
        self._put(f"B{r}", state, state == "годен")

    # ---- подписи: «Измерения провели» ----
    def set_operator(self, fio, date_text):
        self.ws["H151"] = fio
        self.ws["F151"] = f"/ {date_text}"

    def save(self, path):
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        self.wb.save(path)


def serial_from_name(path):
    m = re.search(r"(\d{10,})", os.path.basename(path))
    return m.group(1) if m else ""
