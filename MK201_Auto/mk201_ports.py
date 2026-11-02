"""Список COM-портов с описаниями, автопоиск устройств и сканер штрихкодов."""
import struct, threading, time
import serial
import serial.tools.list_ports as lp

SCANNER_VID, SCANNER_PID = 0x27DD, 0x0002        # как в мультифлешере


def list_ports():
    """[(порт, «COM63 — USB Serial Port (FTDI)»), ...]"""
    out = []
    for p in sorted(lp.comports(), key=lambda x: (len(x.device), x.device)):
        desc = (p.description or "").replace(f"({p.device})", "").strip()
        out.append((p.device, f"{p.device} — {desc}" if desc else p.device))
    return out


def port_of(display):
    """«COM63 — USB Serial Port» -> «COM63»; «— вручную —» -> ''"""
    s = (display or "").strip()
    if not s or s.startswith("—"):
        return ""
    return s.split(" —")[0].strip()


def _crc(data):
    c = 0xFFFF
    for b in data:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def probe_board(port, baud=115200):
    try:
        with serial.Serial(port, baud, timeout=0.4) as s:
            for f in (bytes([1, 101, 0, 1, 0, 16]), bytes([1, 0x51])):   # чтение AI / инфо загрузчика
                f += struct.pack("<H", _crc(f))
                s.reset_input_buffer(); s.write(f)
                r = s.read(5)
                if len(r) >= 2 and r[0] == 1 and (r[1] & 0x7F) in (101, 0x51):
                    return True
    except Exception:
        pass
    return False


def probe_ca71(port):
    try:
        with serial.Serial(port, 9600, bytesize=8, parity="N", stopbits=2, timeout=0.6) as s:
            s.reset_input_buffer(); s.write(b"OE\r\n")
            return s.readline().decode(errors="replace").strip().startswith("ERR")
    except Exception:
        return False


def probe_relay(port):
    try:
        with serial.Serial(port, 115200, timeout=0.6) as s:
            time.sleep(1.6)
            for _ in range(3):                   # ESP32 после сброса может стартовать дольше
                s.reset_input_buffer(); s.write(b"ID?\n")
                if s.readline().decode(errors="replace").strip() == "MK201-RELAY":
                    return True
                time.sleep(0.4)
            return False
    except Exception:
        return False


def quick_check(saved, log=lambda s: None):
    """Сначала проверяем порты, сохранённые с прошлого раза: {'board': 'COM9', ...} -> что ответило."""
    probes = {"board": probe_board, "relay": probe_relay, "cal": probe_ca71}
    have = {p.device for p in lp.comports()}
    out = {}
    for name, port in saved.items():
        if port and port in have and name in probes and probes[name](port):
            out[name] = port
            log(f"{port}: {dict(board='плата MK201', relay='блок реле', cal='калибратор CA71')[name]} (как в прошлый раз)")
    return out


def autodetect(log=lambda s: None, busy=(), known=None):
    """-> {'board': порт|None, 'relay': ..., 'cal': ..., 'scanner': ...}. busy — открытые нами порты (не трогать),
    known — уже найденное (эти порты и роли не проверяются повторно)."""
    found = {"board": None, "relay": None, "cal": None, "scanner": None}
    found.update({k: v for k, v in (known or {}).items() if k in found and v})
    busy = set(busy) | {v for v in found.values() if v}
    rest = []
    for p in lp.comports():
        if p.vid == SCANNER_VID and p.pid == SCANNER_PID:
            found["scanner"] = p.device; log(f"Сканер: {p.device}")
        elif p.device in busy or all(found[k] for k in ("board", "relay", "cal")):
            continue
        else:
            rest.append(p)
    for p in rest:
        if all(found[k] for k in ("board", "relay", "cal")):
            break
        d = (p.description or "").upper()
        order = [("relay", probe_relay), ("board", probe_board), ("cal", probe_ca71)] if "CH34" in d or "CP210" in d \
            else [("board", probe_board), ("cal", probe_ca71), ("relay", probe_relay)]
        log(f"Проверяю {p.device} ({p.description})…")
        for name, fn in order:
            if found[name]:
                continue
            if fn(p.device):
                found[name] = p.device
                log(f"  {p.device}: {dict(board='плата MK201', relay='блок реле', cal='калибратор CA71')[name]}")
                break
    return found


class Scanner:
    """Читает строки со сканера (виртуальный COM) и отдаёт их в callback."""
    def __init__(self, port, on_code, baud=9600):
        self.ser = serial.Serial(port, baud, timeout=0.2)
        self.on_code, self.alive, self.buf = on_code, True, b""
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.alive:
            try:
                data = self.ser.read(64)
            except Exception:
                break
            if data:
                self.buf += data
                while b"\r" in self.buf or b"\n" in self.buf:
                    i = min(x for x in (self.buf.find(b"\r"), self.buf.find(b"\n")) if x >= 0)
                    line, self.buf = self.buf[:i], self.buf[i + 1:]
                    code = line.decode(errors="replace").strip()
                    if code:
                        self.on_code(code)
            elif self.buf:                          # сканер без CR/LF: отдаём по паузе
                code, self.buf = self.buf.decode(errors="replace").strip(), b""
                if code:
                    self.on_code(code)

    def close(self):
        self.alive = False
        try: self.ser.close()
        except Exception: pass
