#!/usr/bin/env python3
"""
stand - блок коммутации мини-стенда (ESP32, прошивка firmware/src/main.cpp) по USB-COM.

Протокол текстовый, 115200 8N1, строка заканчивается \\n. Ответ "OK ..." или "ERR ...".
Строки "EV ..." приходят без запроса (кнопки СТАРТ/СТОП, сторожевой таймер, перегрузка),
они складываются в очередь и забираются poll_events().

Примеры:
    python stand.py find                   # найти стенд среди COM-портов
    python stand.py --port COM5 state
    python stand.py --port COM5 route 2    # вход и выход канала 2 на CA71
    python stand.py --port COM5 pwr 1
    python stand.py --port COM5 supply     # ток потребления изделия, мА
    python stand.py --port COM5 off
"""
import argparse
import collections
import random
import threading
import time

import serial
from serial.tools import list_ports

from simulation import FakeCA71

BAUD = 115200
ID_PREFIX = "MIB-STAND"


class StandError(RuntimeError):
    pass


class Stand:
    def __init__(self, port: str, timeout: float = 1.0):
        self.port = port
        self.ser = serial.Serial(port, baudrate=BAUD, timeout=timeout)
        self._lock = threading.Lock()
        self._events = collections.deque()
        time.sleep(0.1)
        self.ser.reset_input_buffer()

    def close(self):
        try:
            self.ser.close()
        except Exception:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # --- обмен ---
    def _readline(self) -> str:
        return self.ser.readline().decode("utf-8", errors="replace").strip()

    def query(self, cmd: str) -> str:
        """Отправить команду, вернуть текст после OK. ERR -> StandError."""
        with self._lock:
            self.ser.write(cmd.encode("ascii") + b"\n")
            deadline = time.monotonic() + 3.0
            while time.monotonic() < deadline:
                line = self._readline()
                if not line:
                    continue
                if line.startswith("EV "):
                    self._events.append(line[3:])
                elif line == "OK" or line.startswith("OK "):
                    return line[3:]
                elif line.startswith("ERR"):
                    raise StandError(f"стенд: {line[4:]} (команда {cmd})")
            raise StandError(f"стенд не ответил на {cmd} (порт {self.port})")

    def poll_events(self) -> list:
        """События, пришедшие без запроса (START, STOP, WDT, OVERCURRENT)."""
        with self._lock:
            while self.ser.in_waiting:
                line = self._readline()
                if line.startswith("EV "):
                    self._events.append(line[3:])
            out = list(self._events)
            self._events.clear()
        return out

    # --- команды ---
    def ident(self) -> str:
        resp = self.query("ID?")
        if not resp.startswith(ID_PREFIX):
            raise StandError(f"на порту {self.port} не стенд: {resp}")
        return resp

    def state(self) -> dict:
        return {k: int(v) for k, v in (p.split("=") for p in self.query("STATE?").split())}

    def power(self, on: bool):
        self.query(f"PWR {int(on)}")

    def input(self, ch: int):
        self.query(f"IN {ch}")

    def output(self, ch: int):
        self.query(f"OUT {ch}")

    def load(self, on: bool):
        self.query(f"LOAD {int(on)}")

    def route(self, ch: int):
        """Вход и выход канала ch изделия на CA71, нагрузка 400 Ом выключена."""
        self.query(f"ROUTE {ch}")

    def selftest(self, on: bool):
        self.query(f"SELF {int(on)}")

    def supply(self):
        """-> (ток потребления изделия, мА; напряжение питания, В)."""
        ma, v = self.query("I?").split()
        return float(ma), float(v)

    def led(self, mode: str):
        self.query(f"LED {mode}")

    def beep(self, ms: int = 200):
        self.query(f"BEEP {int(ms)}")

    def watchdog(self, seconds: int):
        self.query(f"WDT {int(seconds)}")

    def all_off(self):
        self.query("ALLOFF")


def find_stand(exclude=(), log=print):
    """Перебрать COM-порты и вернуть порт, на котором отвечает стенд (или None)."""
    for p in list_ports.comports():
        if p.device in exclude:
            continue
        try:
            with Stand(p.device, timeout=0.3) as s:
                ident = s.ident()
            log(f"Стенд найден: {p.device} ({ident})")
            return p.device
        except Exception:
            continue
    return None


# ------------------------------------------------------------------ имитация ----
class FakeStand:
    """Имитация блока коммутации. Состояние реле влияет на показания FakeCA71Stand."""

    port = "SIM"

    def __init__(self):
        self.pwr, self.inp, self.out, self.ld, self.self_ = False, 0, 1, False, False
        self._events = collections.deque()

    def close(self):
        pass

    def ident(self):
        return "MIB-STAND,SIM"

    def state(self):
        return {"PWR": int(self.pwr), "IN": self.inp, "OUT": self.out, "LOAD": int(self.ld), "SELF": int(self.self_)}

    def power(self, on):
        if on and self.self_:
            raise StandError("стенд: выключите самоконтроль")
        self.pwr = bool(on)

    def input(self, ch):
        if ch and self.self_:
            raise StandError("стенд: выключите самоконтроль")
        self.inp = ch

    def output(self, ch):
        self.out = ch

    def load(self, on):
        self.ld = bool(on)

    def route(self, ch):
        self.input(ch)
        self.out, self.ld = ch, False

    def selftest(self, on):
        if on and (self.pwr or self.inp):
            raise StandError("стенд: самоконтроль только при PWR=0 и IN=0")
        self.self_ = bool(on)

    def supply(self):
        if not self.pwr:
            return 0.0, 24.0
        return 62.0 + random.gauss(0, 0.3), 23.9

    def led(self, mode):
        pass

    def beep(self, ms=200):
        pass

    def watchdog(self, seconds):
        pass

    def all_off(self):
        self.pwr, self.inp, self.out, self.ld, self.self_ = False, 0, 1, False, False

    def poll_events(self):
        out = list(self._events)
        self._events.clear()
        return out


class FakeCA71Stand(FakeCA71):
    """CA71, показания которого зависят от коммутации стенда (ловит ошибки последовательности)."""

    def __init__(self, gate, stand: FakeStand):
        super().__init__(gate)
        self.stand = stand

    def read_current_avg(self, n=3, delay=0):
        s = self.stand
        if not self.on:
            return random.gauss(0, 0.0003)
        if s.self_:  # источник замкнут на измеритель через тракт стенда
            return self.sp + random.gauss(0, 0.0003)
        if not s.pwr or s.inp != s.out or s.inp == 0:
            return random.gauss(0, 0.0003)  # на выбранном выходе нет входного сигнала
        self.ch = s.out
        i = super().read_current_avg(n, delay)
        return i - (0.0004 if s.ld else 0.0)  # небольшое влияние нагрузки 400 Ом


def main():
    ap = argparse.ArgumentParser(description="Блок коммутации мини-стенда MIB-211/212")
    ap.add_argument("--port")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("find")
    sub.add_parser("state")
    sub.add_parser("supply")
    sub.add_parser("off")
    for name, choices in (("pwr", [0, 1]), ("in", [0, 1, 2]), ("out", [1, 2]), ("load", [0, 1]),
                          ("route", [1, 2]), ("self", [0, 1])):
        sub.add_parser(name).add_argument("value", type=int, choices=choices)
    a = ap.parse_args()

    if a.cmd == "find":
        if not find_stand():
            print("стенд не найден")
        return
    if not a.port:
        ap.error("укажите --port")
    with Stand(a.port) as s:
        if a.cmd == "state":
            print(s.state())
        elif a.cmd == "supply":
            ma, v = s.supply()
            print(f"{ma:.1f} мА, {v:.2f} В")
        elif a.cmd == "off":
            s.all_off()
        else:
            {"pwr": s.power, "in": s.input, "out": s.output, "load": s.load,
             "route": s.route, "self": s.selftest}[a.cmd](a.value)
        print("OK")


if __name__ == "__main__":
    main()
