#!/usr/bin/env python3
"""
Управление Yokogawa CA71 HANDY CAL по RS-232 (кабель 91017, у вас через USB как COM2).
Команды взяты из руководства IM CA71-E, глава 8 (Communication Function).

Параметры порта (раздел 8.1): 9600 бод, 8 бит, без чётности, 2 стоп-бита,
без управления потоком, разделитель команд CrLf.

ВАЖНО на самом приборе:
  * режим связи должен быть Normal (раздел 8.2 руководства);
  * переключатель функции источника (SOURCE) и измерения (MEASURE) по связи
    не переключается - выставляется вручную:
        SOURCE:  20mA  (или 4-20mA / 20mA SINK, см. ниже)
        MEASURE: mA, диапазон 20 mA (диапазон можно выставить командой MR0);
  * источник (SOURCE) и измерение (MEASURE) работают независимо и одновременно.

Команды, которые использует модуль (по руководству):
  SDm   - задать уставку источника (например SD+12.000), SD? - прочитать
  SOm   - выход источника 0/1, SO? - прочитать
  SF?   - функция источника (6 = 20mA, 7 = 4-20mA, 8 = 20mA SINK)
  MF?   - функция измерения (7 = ток)
  MOm   - измерение вкл/выкл
  MRm   - диапазон измерения тока (0 = 20 mA, 1 = 100 mA)
  OD    - выдать измеренное значение: ODabcde, мантисса + порядок (E-3/E+0/E+3)
  OE    - код ошибки, ответ ERRmm (00 - нет ошибки)
  OS    - выдать настройки (8 строк)

Примеры:
    python ca71.py --port COM2 info
    python ca71.py --port COM2 set 12      # уставка 12 мА (выход не включает)
    python ca71.py --port COM2 on
    python ca71.py --port COM2 read        # измеренный ток, мА
    python ca71.py --port COM2 off
"""
import argparse
import re
import time

import serial

SF_NAMES = {0: "30V", 1: "10V", 2: "1V", 3: "100mV", 4: "Resistance",
            5: "Pulse", 6: "20mA", 7: "4-20mA", 8: "20mA SINK"}

_NUM_RE = re.compile(r"([+-]?\d+(?:\.\d*)?)\s*E\s*([+-]\d+)", re.I)


class CA71Error(RuntimeError):
    pass


def parse_od(resp: str) -> float:
    """
    Разбор ответа на OD. Возвращает ток в мА.
    Формат по руководству: OD + [заголовок a b c] + мантисса (7 цифр) + порядок
    (E-3 / E+0 / E+3). Базовая единица тока - ампер, поэтому мА = мантисса * 10**(порядок+3).
    Перегрузка/нет данных: заголовок c = 'O'/'E' либо значение 99999.E+3.
    """
    m = _NUM_RE.search(resp)
    if not m:
        raise CA71Error(f"не удалось разобрать ответ на OD: {resp!r}")
    mant, exp = float(m.group(1)), int(m.group(2))
    if abs(mant) >= 99999 and exp == 3:
        raise CA71Error("перегрузка или нет данных на измерительном входе")
    return mant * 10 ** (exp + 3)


class CA71:
    def __init__(self, port: str = "COM2", timeout: float = 1.0):
        self.ser = serial.Serial(port, baudrate=9600, bytesize=8, parity="N",
                                 stopbits=2, timeout=timeout, xonxoff=False,
                                 rtscts=False)

    def close(self):
        self.ser.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # --- низкий уровень ---
    def send(self, cmd: str):
        self.ser.reset_input_buffer()
        self.ser.write(cmd.encode("ascii") + b"\r\n")

    def readline(self) -> str:
        line = self.ser.readline().decode("ascii", errors="replace").strip()
        if not line:
            raise CA71Error("нет ответа от CA71 (проверьте порт, кабель и режим Normal)")
        return line

    def query(self, cmd: str) -> str:
        self.send(cmd)
        return self.readline()

    def check_error(self):
        resp = self.query("OE")
        code = re.sub(r"\D", "", resp)
        if code and int(code) != 0:
            raise CA71Error(f"CA71 сообщил об ошибке: {resp}")

    # --- прибор ---
    def source_function(self) -> str:
        resp = self.query("SF?")
        n = int(re.sub(r"\D", "", resp))
        return SF_NAMES.get(n, str(n))

    def measure_setup(self):
        """Включить измерение и диапазон тока 20 мА (переключатель MEASURE - mA)."""
        self.send("MO1")
        time.sleep(0.1)
        self.send("MR0")
        time.sleep(0.1)
        self.check_error()

    def set_current(self, ma: float, verify: bool = True):
        """Задать уставку источника, мА (выход не включается)."""
        self.send(f"SD{ma:+.3f}")
        time.sleep(0.1)
        self.check_error()
        if verify:
            back = float(re.search(r"[+-]?\d+(?:\.\d*)?", self.query("SD?")[2:]).group())
            if abs(back - ma) > 0.0015:
                raise CA71Error(f"уставка не принята: задано {ma}, прибор вернул {back}")

    def output(self, on: bool):
        self.send(f"SO{1 if on else 0}")
        time.sleep(0.1)
        self.check_error()

    def read_current(self) -> float:
        """Измеренный ток, мА."""
        return parse_od(self.query("OD"))

    def read_current_avg(self, n: int = 5, delay: float = 1.1) -> float:
        """Среднее по n отсчётам (прибор обновляет показания примерно раз в секунду)."""
        vals = []
        for _ in range(n):
            time.sleep(delay)
            vals.append(self.read_current())
        return sum(vals) / len(vals)

    def info(self):
        self.send("OS")
        lines = []
        for _ in range(8):
            line = self.ser.readline().decode("ascii", errors="replace").strip()
            if not line:
                break
            lines.append(line)
        return lines


def main():
    ap = argparse.ArgumentParser(description="Yokogawa CA71 по RS-232")
    ap.add_argument("--port", default="COM2")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("info")
    s = sub.add_parser("set")
    s.add_argument("ma", type=float)
    sub.add_parser("on")
    sub.add_parser("off")
    sub.add_parser("read")
    a = ap.parse_args()

    with CA71(a.port) as c:
        if a.cmd == "info":
            print("функция источника:", c.source_function())
            for line in c.info():
                print(line)
        elif a.cmd == "set":
            c.set_current(a.ma)
            print(f"уставка {a.ma} мА")
        elif a.cmd == "on":
            c.output(True)
            print("выход включён")
        elif a.cmd == "off":
            c.output(False)
            print("выход выключен")
        else:
            c.measure_setup()
            time.sleep(1.2)
            print(f"{c.read_current():.4f} мА")


if __name__ == "__main__":
    main()
