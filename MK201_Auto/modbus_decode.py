"""Расшифровка кадров MK201 (Modbus RTU + расширения «Нефтеавтоматика») в понятный текст.
Используется программой MK201 Auto (журнал обмена) и сниффером mk201_sniffer.py.

Порядок данных, подтверждённый логом с реальной платы:
  * float и 32-битные числа — МЛАДШЕЕ слово идёт первым (регистр N = младшие 16 бит);
  * «Информация о ПО» (функция 0x51) — длина и CRC в little-endian.
"""
import struct

FC_NAMES = {
    0x03: "Чтение holding", 0x04: "Чтение input", 0x06: "Запись 1 рег.", 0x10: "Запись holding",
    0x2B: "Идентификация", 0x50: "ПО: блок", 0x51: "ПО: инфо", 0x52: "ПО: активация",
    0x53: "ПО: старт", 0x54: "Файл", 0x55: "Команда", 100: "ЗАПИСЬ Settings", 101: "ЧТЕНИЕ Settings",
}

# Известные области регистров (адрес, количество, имя, тип расшифровки)
REGIONS = [
    (1, 16, "AI, мА", "float8"),
    (17, 8, "AI, коды АЦП", "u16"),
    (503, 2, "AO0, мА", "float"),
    (505, 2, "AO1, мА", "float"),
    (101, 32, "Настройки AI (по 4 слова на канал)", "u16"),
    (801, 32, "Калибровка AI: коды 4/16/18/20 мА", "aical"),
    (851, 8, "Калибровка AO: измерено при 4/19 мА (AO0, AO1)", "float"),
    (1010, 26, "Системные: TCP + порты 1–3", "sys"),
    (1036, 6, "Дата/время (чтение)", "dt"),
    (1042, 6, "Дата/время (запись)", "dt"),
    (25, 2, "DI, маска входов", "bits"),
    (600, 2, "DO, маска выходов", "bits"),
    (201, 80, "Настройки DI (5 слов на канал: режим, вкл, инв, дребезг, —)", "cfg5"),
    (602, 80, "Настройки DO (5 слов на канал: режим, вкл, инв, use_err, err_val)", "cfg5"),
    (51, 8, "CI, частота, Гц", "float"),
    (67, 8, "CI, счётчики", "u32x"),
    (401, 4, "CI, режим (0 откл, 1 счётчик, 2 частотомер)", "u16"),
    (409, 8, "CI, сброс счётчиков", "u16"),
    (417, 4, "CI, период измерения частоты", "u16"),
    (425, 4, "CI, антидребезг", "u16"),
    (507, 4, "Настройки AO", "u16"),
    (1100, 2, "Ключ разблокировки", "u32"),
    (1102, 2, "Серийный номер", "u32"),
    (1104, 3, "MAC", "mac"),
]


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def crc_ok(frame: bytes) -> bool:
    return len(frame) >= 4 and crc16(frame[:-2]) == struct.unpack("<H", frame[-2:])[0]


def hx(b, limit=None):
    b = bytes(b)
    if limit and len(b) > limit:
        return " ".join(f"{x:02X}" for x in b[:limit]) + f" … (+{len(b) - limit} байт)"
    return " ".join(f"{x:02X}" for x in b)


def u32_lw(r0, r1):  return (r1 << 16) | r0          # младшее слово первым (так в MK201)
def u32_hw(r0, r1):  return (r0 << 16) | r1          # старшее слово первым


def f_lw(r0, r1):
    return struct.unpack(">f", struct.pack(">I", u32_lw(r0, r1)))[0]


def fmt_f(v):
    if v != v or abs(v) > 1e7 or (v != 0 and abs(v) < 1e-6):
        return "мусор"
    return f"{v:.4f}"


def region_of(addr):
    for a, n, name, kind in REGIONS:
        if a <= addr < a + n:
            return a, n, name, kind
    return None


def interpret(addr, regs):
    """Человеческий смысл значений регистров начиная с addr."""
    reg = region_of(addr)
    if not reg or not regs:
        return generic(regs)
    a, n, name, kind = reg
    off = addr - a
    out = name
    try:
        if kind in ("float", "float8") and off % 2 == 0 and len(regs) >= 2:
            vals = [fmt_f(f_lw(regs[i], regs[i + 1])) for i in range(0, len(regs) - 1, 2)]
            if kind == "float8":
                first = off // 2
                vals = [f"AI{first + i}={v}" for i, v in enumerate(vals)]
            out += ": " + ", ".join(vals)
        elif kind == "aical" and off % 4 == 0:
            first = off // 4
            out += ": " + "; ".join(f"AI{first + i}=[{', '.join(map(str, regs[4*i:4*i+4]))}]"
                                    for i in range(len(regs) // 4))
        elif kind == "bits" and off == 0 and len(regs) >= 2:
            m = u32_lw(regs[0], regs[1])
            on = [str(i) for i in range(32) if m >> i & 1]
            out += f": 0x{m:04X} (включены: {', '.join(on) if on else 'нет'})"
        elif kind == "u32x" and off % 2 == 0:
            out += ": " + ", ".join(str(u32_lw(regs[i], regs[i + 1])) for i in range(0, len(regs) - 1, 2))
        elif kind == "cfg5" and off % 5 == 0:
            first = off // 5
            rows = [regs[5 * i:5 * i + 5] for i in range(len(regs) // 5)]
            ch = [f"{first + i}:{'/'.join(map(str, r))}" for i, r in enumerate(rows) if list(r) != [0, 1, 0, 0, 0]]
            out += ": " + ("все по умолчанию (0/1/0/0/0)" if not ch else "отличаются " + ", ".join(ch))
        elif kind == "u16":
            out += ": " + ", ".join(str(r) for r in regs)
        elif kind == "u32" and len(regs) >= 2:
            out += f": {u32_lw(regs[0], regs[1])} (если старшее слово первым: {u32_hw(regs[0], regs[1])})"
        elif kind == "dt" and len(regs) >= 6:
            h, m, s, y, mo, d = [r & 0xFF for r in regs[:6]]
            out += f": {h:02d}:{m:02d}:{s:02d} {d:02d}.{mo:02d}.20{y:02d}"
        elif kind == "mac" and len(regs) >= 3:
            lo_hi = ":".join(f"{x:02X}" for r in regs[:3] for x in (r & 255, r >> 8))
            hi_lo = ":".join(f"{x:02X}" for r in regs[:3] for x in (r >> 8, r & 255))
            out += f": {lo_hi} (или {hi_lo})"
        elif kind == "sys" and off == 0 and len(regs) >= 26:
            ip = lambda i: ".".join(str(x) for x in (regs[i] & 255, regs[i] >> 8, regs[i + 1] & 255, regs[i + 1] >> 8))
            out += f": IP {ip(0)}, маска {ip(2)}, шлюз {ip(4)}, DNS {ip(6)}"
            for p in range(3):
                b = 8 + 6 * p
                q = [regs[b + k] for k in range(6)]
                out += (f" | Порт{p + 1}: адрес={q[0] & 255} режим={q[1]} мастер№={q[2]} "
                        f"скорость={q[3]} чётность={q[4]} стоп={q[5]}")
        else:
            out += ": " + generic(regs)
    except Exception as e:
        out += f": (ошибка расшифровки {e})"
    return out


def generic(regs):
    s = " ".join(f"{r}" for r in regs[:40])
    if len(regs) > 40:
        s += f" … (+{len(regs) - 40})"
    return "[" + s + "]"


def describe_request(f: bytes) -> str:
    if len(f) < 4:
        return "короткий кадр"
    fc = f[1]
    name = FC_NAMES.get(fc, f"функция {fc} (0x{fc:02X})")
    bad = "" if crc_ok(f) else "  !! ОШИБКА CRC"
    try:
        if fc in (3, 4, 101):
            a, n = struct.unpack(">HH", f[2:6])
            r = region_of(a)
            return f"{name}: адрес {a} ×{n}" + (f" ({r[2]})" if r else "") + bad
        if fc == 6:
            a, v = struct.unpack(">HH", f[2:6])
            return f"{name}: адрес {a} = {v}" + bad
        if fc in (16, 100):
            a, n, bc = struct.unpack(">HHB", f[2:7])
            regs = list(struct.unpack(f">{n}H", f[7:7 + 2 * n]))
            return f"{name}: адрес {a} ×{n} -> {interpret(a, regs)}" + bad
        if fc == 0x50:
            return f"{name} №{struct.unpack('>H', f[2:4])[0]} ({len(f) - 6} байт данных)" + bad
        if fc == 0x52:
            return f"{name}: флаг {f[2]}" + bad
        if fc == 0x53:
            ln, c = struct.unpack(">LH", f[2:8])
            return f"{name}: длина {ln}, CRC {c:04X} (как отправлено, big-endian)" + bad
        if fc == 0x54:
            return f"{name}: подфункция {f[2]} [{hx(f[3:-2], 24)}]" + bad
        if fc == 0x55:
            code, key, par = struct.unpack(">HLL", f[2:12])
            return f"{name}: код {code}, ключ {key}, параметр {par}" + bad
    except Exception as e:
        return f"{name}: не разобран ({e})" + bad
    return f"{name}: [{hx(f[2:-2], 32)}]" + bad


def describe_response(f: bytes, req: bytes = None) -> str:
    if len(f) < 4:
        return "короткий ответ"
    fc = f[1]
    bad = "" if crc_ok(f) else "  !! ОШИБКА CRC"
    if fc & 0x80:
        codes = {1: "функция не поддерживается", 2: "неверный адрес", 3: "неверное значение", 4: "сбой устройства"}
        return f"ИСКЛЮЧЕНИЕ {f[2]} ({codes.get(f[2], '?')}) на функцию {fc & 0x7F}" + bad
    try:
        if fc in (3, 4, 101):
            n = f[2] // 2
            regs = list(struct.unpack(f">{n}H", f[3:3 + 2 * n]))
            a = struct.unpack(">H", req[2:4])[0] if req and len(req) >= 6 and req[1] == fc else None
            return ("ответ: " + (interpret(a, regs) if a is not None else generic(regs))) + bad
        if fc in (6, 16, 100):
            a, n = struct.unpack(">HH", f[2:6])
            return f"ответ: запись принята (адрес {a} ×{n})" + bad
        if fc == 0x51:
            b = f[2:8]
            ln, c = struct.unpack("<LH", b)
            return f"ответ: в плате ПО длиной {ln} байт, CRC {c:04X}" + bad
        if fc in (0x50, 0x52, 0x53):
            return f"ответ: код {f[2]}" + bad
        if fc == 0x55:
            code, ret = struct.unpack(">HL", f[2:8])
            return f"ответ: команда {code}, результат {ret}" + bad
    except Exception as e:
        return f"ответ не разобран ({e})" + bad
    return f"ответ: [{hx(f[2:-2], 32)}]" + bad
