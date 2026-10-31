#!/usr/bin/env python3
"""
Управление потенциометром MCP4161 платы MIB-212 через шлюз MK-531 (COM3).

Формат кадров восстановлен по перехвату обмена MIB_HAND.exe <-> MK-531.

ПРОВЕРЕНО по логам:
  * кадр запроса: 4 вложенных 16-битных длины (15, 13, 11, 9, младший байт первым)
    и 7 байт полезной части:
        15 | op | value_lo | value_hi | reg | channel | 00
    op: 02 - чтение, 03 - запись;  reg: 00 - RAM wiper, 02 - ROM wiper;
    channel: 0 - канал 1, 1 - канал 2;  value: 0..256
  * ответ на чтение RAM (канал 1): 0D 00 | 00 00 | 09 00 | 15 02 00 00 00 B2 00
    -> последние 2 байта полезной части = значение (0xB2 = 178)

НЕ ПРОВЕРЕНО (нет в логах): ответы на запись, ответ на чтение канала 2 и чтение ROM,
ответ на Find interface. Код принимает ответ, если он пришёл, и проверяет запись
обратным чтением.

Примеры:
    python mk531.py --port COM3 find
    python mk531.py --port COM3 read 1
    python mk531.py --port COM3 write 1 132            # в RAM
    python mk531.py --port COM3 write 1 132 --rom      # в ROM (энергонезависимо)
"""
import argparse
import struct
import time

import serial

OP_READ = 0x02
OP_WRITE = 0x03
REG_RAM = 0x00
REG_ROM = 0x02
MAX_CODE = 256

# Кадр Find interface как в перехвате (параметры не расшифрованы)
FIND_INTERFACE = bytes.fromhex("12000A000E00060011000101038000000100")


def build_cmd(op: int, value: int, reg: int, channel: int) -> bytes:
    """channel: 1 или 2 (в кадр уходит 0 или 1)."""
    if not 0 <= value <= MAX_CODE:
        raise ValueError(f"код должен быть 0..{MAX_CODE}")
    if channel not in (1, 2):
        raise ValueError("канал должен быть 1 или 2")
    frame = bytes([0x15, op]) + struct.pack("<H", value) + bytes([reg, channel - 1, 0x00])
    for _ in range(4):
        frame = struct.pack("<H", len(frame) + 2) + frame
    return frame


class MK531:
    def __init__(self, port: str = "COM3", baud: int = 115200, timeout: float = 0.5):
        self.ser = serial.Serial(port, baudrate=baud, bytesize=8, parity="N",
                                 stopbits=1, timeout=timeout)

    def close(self):
        self.ser.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # --- низкий уровень ---
    def _read_frame(self):
        head = self.ser.read(2)
        if len(head) < 2:
            return None
        total = struct.unpack("<H", head)[0]
        rest = self.ser.read(total - 2)
        if len(rest) < total - 2:
            raise IOError("неполный ответ шлюза")
        return head + rest

    def _transact(self, frame: bytes):
        self.ser.reset_input_buffer()
        self.ser.write(frame)
        resp = self._read_frame()
        if resp is not None and len(resp) >= 4:
            err = struct.unpack("<H", resp[2:4])[0]
            if err != 0:
                raise IOError(f"шлюз вернул код ошибки {err}, ответ: {resp.hex(' ')}")
        return resp

    # --- команды ---
    def find_interface(self):
        return self._transact(FIND_INTERFACE)

    def read_wiper(self, channel: int, rom: bool = False) -> int:
        resp = self._transact(build_cmd(OP_READ, 0, REG_ROM if rom else REG_RAM, channel))
        if resp is None or len(resp) < 13:
            raise IOError("нет ответа на чтение")
        return struct.unpack("<H", resp[-2:])[0]

    def write_wiper(self, channel: int, value: int, rom: bool = False, check: bool = True):
        self._transact(build_cmd(OP_WRITE, value, REG_ROM if rom else REG_RAM, channel))
        if rom:
            time.sleep(0.02)  # запись в EEPROM занимает несколько мс
        if check:
            back = self.read_wiper(channel, rom=rom)
            if back != value:
                raise IOError(f"проверка не прошла: записано {value}, прочитано {back}")


def find_best_code(set_and_measure_error, lo: int = 0, hi: int = MAX_CODE):
    """
    Бинарный поиск кода, при котором погрешность (в %) ближе всего к нулю.

    set_and_measure_error(code) -> float: записать код в RAM, подождать, измерить
    выходной ток и вернуть погрешность в % (положительная - ток выше заданного).
    Предполагается монотонная зависимость. Возвращает (код, погрешность).
    """
    e_lo, e_hi = set_and_measure_error(lo), set_and_measure_error(hi)
    if e_lo == e_hi:
        raise ValueError("погрешность не зависит от кода, проверьте подключение")
    rising = e_hi > e_lo
    cache = {lo: e_lo, hi: e_hi}
    while hi - lo > 1:
        mid = (lo + hi) // 2
        cache[mid] = set_and_measure_error(mid)
        if (cache[mid] < 0) == rising:
            lo = mid
        else:
            hi = mid
    best = min((lo, hi), key=lambda c: abs(cache[c]))
    return best, cache[best]


def main():
    ap = argparse.ArgumentParser(description="MK-531: потенциометр MIB-212")
    ap.add_argument("--port", default="COM3")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("find")
    r = sub.add_parser("read")
    r.add_argument("channel", type=int, choices=[1, 2])
    r.add_argument("--rom", action="store_true")
    w = sub.add_parser("write")
    w.add_argument("channel", type=int, choices=[1, 2])
    w.add_argument("value", type=int)
    w.add_argument("--rom", action="store_true")
    a = ap.parse_args()

    with MK531(a.port) as g:
        if a.cmd == "find":
            resp = g.find_interface()
            print("ответ:", resp.hex(" ") if resp else "нет ответа")
        elif a.cmd == "read":
            print(g.read_wiper(a.channel, rom=a.rom))
        else:
            g.write_wiper(a.channel, a.value, rom=a.rom)
            print("OK")


if __name__ == "__main__":
    main()
