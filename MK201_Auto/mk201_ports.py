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


FTDI_VID = 0x0403                                 # Service Port платы — FT232 (USB-COM)
MOXA_VID = 0x110A                                 # переходники MOXA UPort (RS-485) — плату на них не ищем


def is_virtual(p):
    """Виртуальные порты com0com (CNCA/CNCB, «serial port emulator»): опрос может зависнуть — не трогаем.
    (03.11.2026: автопоиск застрял на COM21 «com0com - serial port emulator CNCB1».)"""
    d = f"{p.description or ''} {getattr(p, 'hwid', '') or ''} {getattr(p, 'manufacturer', '') or ''}".upper()
    return "COM0COM" in d or "SERIAL PORT EMULATOR" in d or "CNCA" in d or "CNCB" in d


def is_bluetooth(p):
    """Порты Bluetooth («Стандартный последовательный порт по соединению Bluetooth»): открытие такого COM в Windows
    может висеть десятки секунд — их не опрашиваем никогда."""
    d = f"{p.description or ''} {getattr(p, 'hwid', '') or ''}".upper()
    return "BLUETOOTH" in d or "BTHENUM" in d


def find_board(exclude=(), prefer="", log=lambda s: None, baud=115200, stop=lambda: False):
    """02.11.2026: у каждой новой платы Service Port получает новый COM (свой серийник FT232).
    Ищем плату: сначала prefer (порт из списка), потом порты FTDI, потом остальные; пропускаем exclude
    (порты других приборов) и переходники MOXA (через RS-485 плата тоже ответит — но это не Service Port).
    -> порт или None."""
    ex = {e.upper() for e in exclude if e}
    ports = [p for p in lp.comports() if p.device.upper() not in ex and p.vid != MOXA_VID
             and "MOXA" not in (p.description or "").upper() and not (p.vid == SCANNER_VID and p.pid == SCANNER_PID)
             and not is_bluetooth(p) and not is_virtual(p)]
    ports.sort(key=lambda p: (p.device != prefer, p.vid != FTDI_VID))
    for p in ports:
        if stop():
            log("Время на подключение вышло — поиск платы остановлен")
            break
        if p.device != prefer:
            log(f"Ищу плату: {p.device} ({p.description})…")
        if probe_board(p.device, baud):
            return p.device
    return None


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


def is_moxa(port):
    for p in lp.comports():
        if p.device == port:
            return p.vid == MOXA_VID or "MOXA" in (p.description or "").upper()
    return False


def quick_check(saved, log=lambda s: None):
    """Сначала проверяем порты, сохранённые с прошлого раза: {'board': 'COM9', ...} -> что ответило."""
    probes = {"board": probe_board, "relay": probe_relay, "cal": probe_ca71}
    have = {p.device for p in lp.comports()}
    out = {}
    for name, port in saved.items():
        if port and port in have and name in probes and not (name == "board" and is_moxa(port)) and probes[name](port):
            out[name] = port
            log(f"{port}: {dict(board='плата MK201', relay='блок реле', cal='калибратор CA71')[name]} (как в прошлый раз)")
    return out


def autodetect(log=lambda s: None, busy=(), known=None, stop=lambda: False, board=True):
    """-> {'board': порт|None, 'relay': ..., 'cal': ..., 'scanner': ...}. busy — открытые нами порты (не трогать),
    known — уже найденное (эти порты и роли не проверяются повторно). board=False — плату по COM-портам не искать
    (05.11.2026, 1.37: связь с платой выбрана по Modbus RTU или TCP — Service Port не нужен)."""
    found = {"board": None, "relay": None, "cal": None, "scanner": None, "rs485": None}
    roles = ("board", "relay", "cal") if board else ("relay", "cal")
    found.update({k: v for k, v in (known or {}).items() if k in found and v})
    busy = set(busy) | {v for v in found.values() if v}
    rest, moxa = [], []
    for p in lp.comports():
        if p.vid == SCANNER_VID and p.pid == SCANNER_PID:
            found["scanner"] = p.device; log(f"Сканер: {p.device}")
        elif p.vid == MOXA_VID or "MOXA" in (p.description or "").upper():
            # 02.11.2026: на MOXA могут быть и калибратор CA71 (RS-232), и переходник RS-485 к плате.
            # Плату на MOXA не ищем (через RS-485 она тоже ответит), CA71 — ищем; оставшийся порт MOXA — RS-485.
            if p.device not in busy:
                moxa.append(p.device)
        elif p.device in busy or all(found[k] for k in roles) or is_bluetooth(p) or is_virtual(p):
            continue
        else:
            rest.append(p)
    for port in moxa:
        if stop():
            break
        if not found["cal"]:
            log(f"Проверяю {port} (MOXA) — калибратор CA71?…")
            if probe_ca71(port):
                found["cal"] = port
                log(f"  {port}: калибратор CA71 (через MOXA)")
    left = [m for m in moxa if m != found["cal"]]
    if not found["rs485"] and len(left) == 1:
        found["rs485"] = left[0]
        log(f"Переходник RS-485 (MOXA): {left[0]}")
    elif len(left) > 1:
        log(f"Портов MOXA несколько ({', '.join(left)}) — переходник RS-485 выберите вручную")
    for p in rest:
        if all(found[k] for k in roles):
            break
        if stop():
            log("Время на подключение вышло — остальные порты не проверяю (приборы выберите вручную или «Автопоиск»)")
            break
        d = (p.description or "").upper()
        order = [("relay", probe_relay), ("board", probe_board), ("cal", probe_ca71)] if "CH34" in d or "CP210" in d \
            else [("board", probe_board), ("cal", probe_ca71), ("relay", probe_relay)]
        log(f"Проверяю {p.device} ({p.description})…")
        for name, fn in order:
            if found[name] or name not in roles:
                continue
            if stop():
                break
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
