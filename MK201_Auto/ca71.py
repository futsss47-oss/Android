"""Yokogawa CA71 по RS-232 (кабель 91017). Команды — по IM CA71-E, гл. 8:
9600 бод, 8 бит, без чётности, 2 стоп-бита, разделитель CrLf. Режим связи на калибраторе: Normal."""
import re, time, serial
from mk201_stats import robust_mean


class CA71Error(Exception):
    pass


class CA71:
    SF_20MA, SF_4_20MA = 6, 7          # функции источника «20 mA» и «4 to 20 mA»
    MF_CURRENT = 7                     # функция измерения «Current»

    def __init__(self, port, timeout=2.0, log=None):
        self.ser = serial.Serial(port, 9600, bytesize=8, parity="N", stopbits=2, timeout=timeout)
        self.log = log

    def close(self):
        try:
            self.source_off()
        finally:
            self.ser.close()

    # ---- обмен ----
    def _send(self, cmd):
        self.ser.reset_input_buffer()
        self.ser.write((cmd + "\r\n").encode("ascii"))

    def query(self, cmd):
        self._send(cmd)
        line = self.ser.readline().decode("ascii", "replace").strip()
        if not line:
            raise CA71Error(f"CA71 не ответил на '{cmd}' (кабель, режим связи Normal, 9600 8N2?)")
        return line

    def command(self, cmd):
        self._send(cmd)
        time.sleep(0.15)
        err = self.query("OE")
        if err.strip() != "ERR00":
            raise CA71Error(f"CA71: команда '{cmd}' -> {err}")

    @staticmethod
    def _int_after(prefix, line):
        m = re.search(prefix + r"\s*(-?\d+)", line)
        if not m:
            raise CA71Error(f"Непонятный ответ CA71: '{line}'")
        return int(m.group(1))

    # ---- настройка ----
    def init(self):
        self.command("H1")                       # заголовок в ответе OD
        self.command("MO1")                      # измерение включено
        mf = self._int_after("MF", self.query("MF?"))
        if mf != self.MF_CURRENT:
            raise CA71Error("Селектор MEASURE калибратора должен стоять в положении mA")
        self.command("MR0")                      # диапазон 20 мА
        sf = self._int_after("SF", self.query("SF?"))
        if sf not in (self.SF_20MA, self.SF_4_20MA):
            raise CA71Error("Селектор SOURCE калибратора должен стоять на 20mA (для 0 мА) или 4-20mA")
        self.sf = sf

    # ---- источник ----
    def set_source(self, ma, settle=2.0):
        if abs(ma) < 0.05:
            # 0 мА: просто выключаем выход — в разомкнутой цепи тока нет при любом положении SOURCE
            # (на 4-20mA калибратор 0 мА задать не может).
            self.source_off()
            time.sleep(settle)
            return
        if ma < 3.0 and self.sf == self.SF_4_20MA:
            raise CA71Error(f"Ток {ma:g} мА на SOURCE 4-20mA не задать — переведите SOURCE на 20mA")
        self.command(f"SD{ma:+.4f}")
        self.command("SO1")
        time.sleep(settle)

    def source_off(self):
        self.command("SO0")

    # ---- измерение ----
    def measure_ma(self, n=3, interval=1.1):
        """Среднее n отсчётов; индикатор калибратора обновляется примерно раз в секунду."""
        vals = []
        for _ in range(n):
            line = self.query("OD")
            vals.append(self.parse_od(line))
            time.sleep(interval)
        return robust_mean(vals, floor=0.002, log=self.log, what="CA71")

    @staticmethod
    def parse_od(line):
        m = re.search(r"([+-]?\d+(?:\.\d+)?)\s*E\s*([+-]\d+)", line)
        if not m:
            raise CA71Error(f"Не разобран ответ OD: '{line}'")
        mant, exp = float(m.group(1)), int(m.group(2))
        if abs(mant) >= 99999:
            raise CA71Error("CA71: перегрузка/нет данных на измерительном входе")
        return mant * 10 ** exp * 1000.0         # А -> мА
