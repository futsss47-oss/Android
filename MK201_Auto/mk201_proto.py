"""Протокол MK201 (Modbus RTU + расширения «Нефтеавтоматика»).
Карта регистров и коды функций получены из exe конфигуратора configurator_MKLogic200_3.4.4f.

ВАЖНО: у MK201 ВСЕ регистры (AI, AO, калибровка, порты, время, серийник) читаются и пишутся
«Settings»-функциями: ЗАПИСЬ = 100, ЧТЕНИЕ = 101 (не наоборот!). Service Port — это просто
USB-COM (FT232), по нему идут те же Modbus RTU-кадры.

Подтверждено логом с реальной платы (15.10.2026):
  * float и 32-битные числа — МЛАДШЕЕ слово первым (серийник в плате читался [A1C0, 77E9] = 2011800000);
  * «Информация о ПО» (0x51) приходит в little-endian (C4 A8 05 00 70 0D = 370884 байт, CRC 0D70).
"""
import socket, struct, time, serial
from modbus_decode import describe_request, describe_response

FC_READ_HOLD, FC_WRITE_MULT = 0x03, 0x10
FC_WRITE_SET, FC_READ_SET = 100, 101
FC_APP_WRITE_SEG, FC_APP_INFO, FC_APP_ACTIVATE, FC_APP_START = 0x50, 0x51, 0x52, 0x53
FC_EXEC = 0x55

ADDR_AI_FLOAT, ADDR_AI_ADC = 1, 17        # 8 float (16 рег) / 8 слов
ADDR_AO = (503, 505)                      # float на канал
ADDR_AO_CAL = 851                         # 4 float: AO0.P1,P2 AO1.P1,P2
ADDR_AI_CAL = 801                         # 8 каналов × 4 слова: коды АЦП для 4, 16, 18, 20 мА (сниффер 16.10)
ADDR_MK201_BUS = 1010                     # 26 рег: IP,mask,gw,dns,COM1..3
ADDR_DATETIME_READ, ADDR_DATETIME_SET = 1036, 1042
ADDR_UNBLOCK, ADDR_SERIAL, ADDR_MAC = 1100, 1102, 1104
UNBLOCK_KEY = 2902319943
AI_STD_CODES_HV31 = (2621, 10485, 11796, 13106)   # 4, 16, 18, 20 мА
AI_STD_CODES_HV20 = (2640, 10560, 11880, 13200)
PORT_MODES = ("Off", "MbMaster", "MbSlave", "Logger")

# ---- дискретные входы/выходы (сниффер 24.10.2026: подтверждено) ----
ADDR_DI = 25                              # Long (2 рег.), бит n = DIn
ADDR_DO = 600                             # Long (2 рег.), бит n = DOn — запись и чтение
ADDR_DI_CFG = 201                         # 16 каналов × 5 слов: режим, enable, инверсия, дребезг, —
ADDR_DO_CFG = 602                         # 16 каналов × 5 слов: режим, enable, инверсия, use_error, error_value
# ---- счётные входы (из exe конфигуратора; сниффером НЕ проверено) ----
ADDR_CI_FREQ, ADDR_CI_CNT = 51, 67        # 4 float / 4 Long
ADDR_CI_MODE, ADDR_CI_RESET = 401, 409    # 4 слова / 8 слов (1 = сбросить)
ADDR_CI_PERIOD, ADDR_CI_BOUNCE = 417, 425 # 4 слова / 4 слова
CI_MODES = {0: "Отключено", 1: "Счётчик", 2: "Частотомер"}
# ---- настройки AI/AO (из exe; сниффером НЕ проверено) ----
ADDR_AI_CFG, ADDR_AO_CFG = 101, 507       # 8 × 4 слова / 4 слова
# ---- коды скорости портов (из exe: словарь 2..12) ----
BAUDS = {2: 1200, 3: 2400, 4: 4800, 5: 9600, 6: 14400, 7: 19200, 8: 38400, 9: 56000, 10: 57600, 11: 115200, 12: 128000}
PARITIES = {0: "None", 1: "Odd", 2: "Even"}
STOPBITS = {0: "1", 1: "1.5", 2: "2"}


class ModbusError(Exception):
    def __init__(self, msg, code=None):
        super().__init__(msg)
        self.code = code


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


# 32-битные значения: МЛАДШЕЕ слово в первом регистре (порядок MK201)
def f2regs(v):  hi, lo = struct.unpack(">HH", struct.pack(">f", v)); return [lo, hi]
def regs2f(r):  return struct.unpack(">f", struct.pack(">HH", r[1], r[0]))[0]
def l2regs(v):  hi, lo = struct.unpack(">HH", struct.pack(">L", int(v))); return [lo, hi]
def regs2l(r):  return struct.unpack(">L", struct.pack(">HH", r[1], r[0]))[0]


def hx(b):
    return " ".join(f"{x:02X}" for x in b)


class TcpStream:
    """Modbus TCP, как в конфигураторе (порт 502, заголовок MBAP), — под видом последовательного порта.
    write() получает RTU-кадр (адрес + PDU + CRC), отправляет PDU в обёртке MBAP и принимает ответ целиком;
    read() отдаёт ответ снова как RTU-кадр (адрес + PDU + CRC). Поэтому разбор ответов, идентификация,
    «Команда вручную» и журнал обмена одинаковы для Service Port, RS-485 и Ethernet."""

    def __init__(self, host, port=502, timeout=1.0):
        self.host, self.port, self.timeout = host, int(port), timeout
        self.sock, self.buf, self.tid = None, b"", 0
        self.open()

    def open(self):
        self.close()
        self.sock = socket.create_connection((self.host, self.port), timeout=max(self.timeout, 2.0))
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    @property
    def is_open(self):
        return self.sock is not None

    def close(self):
        if self.sock is not None:
            try: self.sock.close()
            except OSError: pass
        self.sock = None

    def reset_input_buffer(self):
        self.buf = b""

    @property
    def in_waiting(self):
        return len(self.buf)

    def _recv(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise ConnectionError(f"{self.host}:{self.port}: соединение закрыто платой")
            out += chunk
        return out

    def write(self, frame):
        unit, pdu = frame[0], bytes(frame[1:-2])
        self.tid = (self.tid + 1) & 0xFFFF
        req = struct.pack(">HHHB", self.tid, 0, len(pdu) + 1, unit) + pdu
        for attempt in (0, 1):                     # соединение оборвалось (плата перезагрузилась) — один раз заново
            try:
                if self.sock is None:
                    self.open()
                self.sock.settimeout(self.timeout)
                self.sock.sendall(req)
                break
            except OSError:
                self.close()
                if attempt:
                    raise
        self.buf = b""
        try:
            while True:
                head = self._recv(7)
                tid, _, length, u = struct.unpack(">HHHB", head)
                body = self._recv(length - 1)
                if tid == self.tid:              # опоздавший ответ на прошлый запрос — пропускаем
                    break
        except socket.timeout:
            return len(frame)                    # нет ответа: read() вернёт пусто — «НЕТ ОТВЕТА (таймаут)»
        except OSError:
            self.close()
            return len(frame)
        rtu = bytes([u]) + body
        self.buf = rtu + struct.pack("<H", crc16(rtu))
        return len(frame)

    def read(self, n):
        out, self.buf = self.buf[:n], self.buf[n:]
        return out


class MK201:
    """Плата MK201. Связь — Service Port (USB-COM) или RS-485 через переходник (Modbus RTU),
    либо Ethernet (Modbus TCP, host=IP). Команды одинаковые, как в конфигураторе."""

    def __init__(self, port=None, baud=115200, unit=1, timeout=1.0, parity="N", stopbits=1, trace=None,
                 host=None, tcp_port=502):
        self.unit, self.trace = unit, trace
        if host:
            self.ser = TcpStream(host, tcp_port, timeout)
            self.link = f"Modbus TCP {host}:{tcp_port}, адрес {unit}"
        else:
            self.ser = serial.Serial(port, baud, timeout=timeout, parity=parity, stopbits=stopbits)
            self.link = f"{port}, {baud} бод, 8{parity}{stopbits}, адрес {unit}"
        self.is_tcp = bool(host)
        self.base_timeout = timeout

    def close(self):
        self.ser.close()

    def reopen(self):
        """Открыть заново то же подключение (после close() или обрыва)."""
        try: self.ser.close()
        except Exception: pass
        self.ser.open()

    def set_timeout(self, t):
        self.ser.timeout = t

    def _t(self, direction, frame, text):
        """direction: 'tx' | 'rx' | 'err'; frame — байты целиком; text — расшифровка."""
        if self.trace:
            self.trace(direction, bytes(frame), text)

    # ---- низкий уровень ----
    def _xfer(self, pdu: bytes) -> bytes:
        frame = bytes([self.unit]) + pdu
        frame += struct.pack("<H", crc16(frame))
        self.ser.reset_input_buffer()
        self._last_req = frame
        self._t("tx", frame, describe_request(frame))
        self.ser.write(frame)
        buf = self._rd(2, b"")
        if buf[0] != self.unit:
            raise ModbusError(f"Ответ от другого адреса: {buf[0]} (ожидался {self.unit}). RX {hx(buf)}")
        func = buf[1]
        if func & 0x80:
            buf += self._rd(3, buf)
            self._chk_crc(buf)
            self._t("rx", buf, describe_response(buf, frame))
            raise ModbusError(f"Исключение Modbus {buf[2]} (fc={func & 0x7F})", code=buf[2])
        if func in (FC_READ_HOLD, FC_READ_SET):
            n = self._rd(1, buf); buf += n + self._rd(n[0] + 2, buf + n)
        elif func in (FC_WRITE_MULT, FC_WRITE_SET):
            buf += self._rd(6, buf)
        elif func in (FC_APP_WRITE_SEG, FC_APP_ACTIVATE, FC_APP_START):
            buf += self._rd(3, buf)
        elif func == FC_APP_INFO:
            buf += self._rd(8, buf)
        elif func == FC_EXEC:
            buf += self._rd(8, buf)
        else:
            raise ModbusError(f"Неожиданный код функции {func}. RX {hx(buf)}")
        self._chk_crc(buf)
        self._t("rx", buf, describe_response(buf, frame))
        return buf[1:-2]                   # func + payload

    def _chk_crc(self, buf):
        if crc16(buf[:-2]) != struct.unpack("<H", buf[-2:])[0]:
            self._t("err", buf, "ОШИБКА CRC в ответе")
            raise ModbusError("Ошибка CRC ответа. RX " + hx(buf))

    def _rd(self, n, have):
        b = self.ser.read(n)
        if len(b) != n:
            self._t("err", have + b, "НЕТ ОТВЕТА (таймаут), получено: " + (hx(have + b) or "ничего"))
            raise ModbusError("Нет ответа от устройства (таймаут). Проверьте COM-порт, скорость и питание. "
                              f"Получено: {hx(have + b) or 'ничего'}")
        return b

    def _read(self, fc, addr, count):
        r = self._xfer(struct.pack(">BHH", fc, addr, count))
        return list(struct.unpack(f">{r[1] // 2}H", r[2:2 + r[1]]))

    def _write(self, fc, addr, vals):
        r = self._xfer(struct.pack(">BHHB", fc, addr, len(vals), 2 * len(vals))
                       + struct.pack(f">{len(vals)}H", *vals))
        a, c = struct.unpack(">HH", r[1:5])
        if a != addr or c != len(vals):
            raise ModbusError("Эхо записи не совпало")

    # ---- Settings-регистры (основной способ для MK201) ----
    def read_set(self, addr, count):   return self._read(FC_READ_SET, addr, count)
    def write_set(self, addr, vals):   self._write(FC_WRITE_SET, addr, vals)

    # ---- общий доступ: только Settings-функции, как в конфигураторе для MK201.
    # (Запасной переход на 0x03/0x10 убран: 15.10 он писал в загрузчик во время перепрошивки.)
    def read_regs(self, addr, count):  return self.read_set(addr, count)
    def write_regs(self, addr, vals):  self.write_set(addr, vals)

    # ---- аналоговые входы ----
    def ai_ma(self):
        r = self.read_regs(ADDR_AI_FLOAT, 16)
        return [regs2f(r[i:i + 2]) for i in range(0, 16, 2)]

    def ai_adc(self):
        return self.read_regs(ADDR_AI_ADC, 8)

    def ai_cal_read(self, ch):
        return self.read_regs(ADDR_AI_CAL + 4 * ch, 4)

    def ai_cal_write(self, ch, codes):
        self.write_regs(ADDR_AI_CAL + 4 * ch, [int(c) & 0xFFFF for c in codes])

    def ai_cal_read_all(self):
        """Таблица калибровки AI целиком: 8 списков по 4 кода (4, 16, 18, 20 мА)."""
        r = self.read_set(ADDR_AI_CAL, 32)
        return [list(r[4 * c:4 * c + 4]) for c in range(8)]

    def ai_cal_write_all(self, table):
        """Как конфигуратор: один кадр 0x64 на адрес 801 ×32 (все 8 каналов сразу)."""
        self.write_set(ADDR_AI_CAL, [int(v) & 0xFFFF for row in table for v in row])

    @staticmethod
    def ai_cal_factory(hv="3.3"):
        """Заводские коды АЦП (4, 16, 18, 20 мА) для ревизии платы."""
        return list(AI_STD_CODES_HV20 if str(hv).startswith("2") else AI_STD_CODES_HV31)

    def ai_cal_reset(self, channels=None, hv="3.3"):
        """Сброс калибровки AI, как в конфигураторе (снято сниффером 16.10.2026): в строку канала
        записываются заводские коды, остальные строки уходят без изменений — одним кадром 801 ×32.
        channels=None — все 8 каналов. Возвращает таблицу, прочитанную обратно."""
        table = self.ai_cal_read_all()
        for ch in (range(8) if channels is None else channels):
            table[ch] = self.ai_cal_factory(hv)
        self.ai_cal_write_all(table)
        return self.ai_cal_read_all()

    # ---- аналоговые выходы ----
    def ao_set(self, ch, ma):
        self.write_regs(ADDR_AO[ch], f2regs(ma))

    def ao_get(self, ch):
        return regs2f(self.read_regs(ADDR_AO[ch], 2))

    def ao_cal_read(self):
        r = self.read_regs(ADDR_AO_CAL, 8)
        f = [regs2f(r[i:i + 2]) for i in range(0, 8, 2)]
        return [(f[0], f[1]), (f[2], f[3])]

    def ao_cal_write(self, pts):
        regs = []
        for p1, p2 in pts:
            regs += f2regs(p1) + f2regs(p2)
        self.write_regs(ADDR_AO_CAL, regs)

    # ---- системные настройки ----
    def ports_read(self):
        return self.read_regs(ADDR_MK201_BUS, 26)

    def ports_write(self, regs):
        self.write_regs(ADDR_MK201_BUS, regs)

    def datetime_read(self):
        h, m, s, y, mo, d = self.read_regs(ADDR_DATETIME_READ, 6)
        return (2000 + (y & 0xFF), mo & 0xFF, d & 0xFF, h & 0xFF, m & 0xFF, s & 0xFF)

    def datetime_write(self, t=None):
        """Запись времени, как в конфигураторе (download_mk201_datetime): 1042 ×6 = час, мин, сек, год−2000, мес, день,
        функция 100, одной записью."""
        t = t or time.localtime()
        self.write_regs(ADDR_DATETIME_SET, [t.tm_hour, t.tm_min, t.tm_sec, t.tm_year - 2000, t.tm_mon, t.tm_mday])

    def datetime_diff(self):
        """(время платы, расхождение с ПК в секундах)."""
        y, mo, d, h, mi, s = self.datetime_read()
        try:
            board = time.mktime((y, mo, d, h, mi, s, 0, 0, -1))
        except (OverflowError, ValueError):
            return (y, mo, d, h, mi, s), float("inf")
        return (y, mo, d, h, mi, s), abs(time.time() - board)

    @staticmethod
    def _fmt(t):
        y, mo, d, h, mi, s = t
        return f"{d:02d}.{mo:02d}.{y} {h:02d}:{mi:02d}:{s:02d}"

    def datetime_compare(self):
        """Сравнение с системным временем, как кнопка конфигуратора (cmp_with_system_datetime):
        даты должны совпасть, время — с точностью до 1 с. -> (годен, время платы, текст)."""
        y, mo, d, h, mi, s = board = self.datetime_read()
        now = time.localtime()
        if (y, mo, d) != (now.tm_year, now.tm_mon, now.tm_mday):
            return False, board, f"Даты не совпадают: в плате {d:02d}.{mo:02d}.{y}, на ПК {time.strftime('%d.%m.%Y', now)}"
        delta = (h * 3600 + mi * 60 + s) - (now.tm_hour * 3600 + now.tm_min * 60 + now.tm_sec)
        if abs(delta) > 1:
            return False, board, (f"Время в устройстве отстаёт от системного на {-delta} с" if delta < 0 else
                                  f"Время в устройстве опережает системное на {delta} с")
        return True, board, "Дата и время совпадают с точностью до 1 с"

    def datetime_sync(self, log=print, settle=1.0):
        """05.11.2026 (1.39): как в оригинальном конфигураторе — без запасных вариантов и повторов: одна запись
        времени ПК, через settle с чтение и сравнение (даты равны, время ±1 с). Запись — в начале секунды ПК,
        чтобы записанные секунды не отставали от ПК почти на 1 с. -> (годен, время платы, текст)."""
        frac = time.time() % 1.0
        if frac > 0.05:
            time.sleep(1.0 - frac + 0.01)
        self.datetime_write()
        time.sleep(settle)
        return self.datetime_compare()

    # ---- заводские данные ----
    def unblock(self):
        self.write_set(ADDR_UNBLOCK, l2regs(UNBLOCK_KEY))

    def serial_read(self):
        return regs2l(self.read_set(ADDR_SERIAL, 2))

    def serial_write(self, n):
        self.unblock()
        self.write_set(ADDR_SERIAL, l2regs(int(n)))

    @staticmethod
    def mac_to_regs(mac: str):
        b = [int(x, 16) for x in mac.replace("-", ":").split(":")]
        if len(b) != 6 or any(x < 0 or x > 255 for x in b):
            raise ValueError("Неверный формат MAC")
        return [(b[1] << 8) | b[0], (b[3] << 8) | b[2], (b[5] << 8) | b[4]]

    @staticmethod
    def regs_to_mac(r):
        b = [r[0] & 255, r[0] >> 8, r[1] & 255, r[1] >> 8, r[2] & 255, r[2] >> 8]
        return ":".join(f"{x:02X}" for x in b)

    def mac_write(self, mac):
        regs = self.mac_to_regs(mac)
        self.unblock()
        self.write_set(ADDR_MAC, regs)

    def mac_read(self):
        return self.regs_to_mac(self.read_set(ADDR_MAC, 3))

    # ---- дискретные входы / выходы ----
    def di_read(self):
        """Маска DI (бит n = DIn)."""
        return regs2l(self.read_set(ADDR_DI, 2))

    def do_write(self, mask):
        self.write_set(ADDR_DO, l2regs(int(mask) & 0xFFFFFFFF))

    def do_read(self):
        return regs2l(self.read_set(ADDR_DO, 2))

    def di_cfg_read(self):
        r = self.read_set(ADDR_DI_CFG, 80)
        return [list(r[5 * i:5 * i + 5]) for i in range(16)]

    def di_cfg_write(self, rows):
        self.write_set(ADDR_DI_CFG, [int(v) & 0xFFFF for row in rows for v in row])

    def do_cfg_read(self):
        r = self.read_set(ADDR_DO_CFG, 80)
        return [list(r[5 * i:5 * i + 5]) for i in range(16)]

    def do_cfg_write(self, rows):
        self.write_set(ADDR_DO_CFG, [int(v) & 0xFFFF for row in rows for v in row])

    # ---- счётные входы ----
    def ci_freq(self):
        r = self.read_set(ADDR_CI_FREQ, 8)
        return [regs2f(r[i:i + 2]) for i in range(0, 8, 2)]

    def ci_counts(self):
        r = self.read_set(ADDR_CI_CNT, 8)
        return [regs2l(r[i:i + 2]) for i in range(0, 8, 2)]

    def _opt(self, addr, count):
        """Чтение области, которой у платы может не быть (исключение 2 «неверный адрес») -> None."""
        try:
            return self.read_set(addr, count)
        except ModbusError as e:
            if e.code == 2:
                return None
            raise

    def ci_cfg_read(self):
        """-> (режимы[4], период[4] | None, дребезг[4] | None).
        27.10.2026: MK201 на чтение 417 (период) отвечает исключением 2 — у платы этих регистров нет."""
        return (self.read_set(ADDR_CI_MODE, 4), self._opt(ADDR_CI_PERIOD, 4), self._opt(ADDR_CI_BOUNCE, 4))

    def ci_cfg_write(self, modes=None, period=None, bounce=None):
        """Как конфигуратор: режимы -> антидребезг -> период."""
        if modes is not None:  self.write_set(ADDR_CI_MODE, [int(v) for v in modes])
        if bounce is not None: self.write_set(ADDR_CI_BOUNCE, [int(v) for v in bounce])
        if period is not None: self.write_set(ADDR_CI_PERIOD, [int(v) for v in period])

    def ci_reset(self, channels=None, n=4):
        """Сброс счётчиков: по слову на вход, 1 = сбросить. У MK201 4 входа -> 4 слова
        (27.10.2026: запись 409 ×8, как для 8-канальных модулей, плата отвергла исключением 2)."""
        vals = [1] * n if channels is None else [1 if i in channels else 0 for i in range(n)]
        self.write_set(ADDR_CI_RESET, vals)

    # ---- настройки AI / AO (сырые слова) ----
    def ai_cfg_read(self):
        r = self.read_set(ADDR_AI_CFG, 32)
        return [list(r[4 * i:4 * i + 4]) for i in range(8)]

    def ai_cfg_write(self, rows):
        self.write_set(ADDR_AI_CFG, [int(v) & 0xFFFF for row in rows for v in row])

    def ao_cfg_read(self):
        return self.read_set(ADDR_AO_CFG, 4)

    def ao_cfg_write(self, vals):
        self.write_set(ADDR_AO_CFG, [int(v) & 0xFFFF for v in vals])

    # ---- «Команда вручную»: произвольный кадр ----
    def raw(self, payload, wait=0.3):
        """payload — байты кадра БЕЗ CRC (адрес, функция, данные); CRC добавляется. -> принятые байты (что пришло за wait с)."""
        frame = bytes(payload)
        frame += struct.pack("<H", crc16(frame))
        self.ser.reset_input_buffer()
        self._t("tx", frame, describe_request(frame))
        self.ser.write(frame)
        old = self.ser.timeout
        buf = b""
        try:
            self.ser.timeout = 0.05
            t0 = time.time()
            while time.time() - t0 < max(wait, 0.05) + 0.7:
                chunk = self.ser.read(256)
                if chunk:
                    buf += chunk; t0 = time.time() - max(wait, 0.05) + 0.1   # после последнего байта ждём ещё 0,1 с
                elif buf:
                    break
        finally:
            self.ser.timeout = old
        if buf:
            self._t("rx", buf, "ответ (вручную)" + ("" if len(buf) < 4 or crc16(buf[:-2]) == struct.unpack("<H", buf[-2:])[0]
                                                  else " — CRC не сходится"))
        return buf

    # ---- идентификация (функция 0x2B / MEI 0x0E), как вкладка «Информация» конфигуратора ----
    def identify(self):
        """-> {номер объекта: строка}. Читает все объекты, пока плата говорит «есть ещё»."""
        out, obj = {}, 0
        for _ in range(8):
            frame = bytes([self.unit, 0x2B, 0x0E, 0x02, obj])
            frame += struct.pack("<H", crc16(frame))
            self.ser.reset_input_buffer()
            self._t("tx", frame, describe_request(frame))
            self.ser.write(frame)
            buf = self._rd(2, b"")
            if buf[1] & 0x80:
                buf += self._rd(3, buf); self._chk_crc(buf)
                raise ModbusError(f"Идентификация: исключение {buf[2]}", code=buf[2])
            buf += self._rd(6, buf)            # MEI, код, conformity, more, next, n
            more, nxt, n = buf[5], buf[6], buf[7]
            for _ in range(n):
                h = self._rd(2, buf); buf += h
                data = self._rd(h[1], buf) if h[1] else b""; buf += data
                out[h[0]] = data.decode("cp1251", "replace")
            buf += self._rd(2, buf)
            self._chk_crc(buf)
            self._t("rx", buf, "ответ: идентификация " + "; ".join(f"{k}: {v}" for k, v in sorted(out.items())))
            if more != 0xFF or nxt == 0 or nxt == obj:
                break
            obj = nxt
        return out

    # ---- загрузка ПО (bootloader/приложение): функции 0x50–0x53 ----
    def app_info(self):
        r = self._xfer(bytes([FC_APP_INFO]))
        return struct.unpack("<LH", r[1:7])            # (длина, CRC16) — little-endian, подтверждено логом

    def app_activate(self, flag=1):
        return self._xfer(bytes([FC_APP_ACTIVATE, flag]))[1]

    def app_write_segment(self, number, data):
        return self._xfer(bytes([FC_APP_WRITE_SEG]) + struct.pack(">H", number) + bytes(data))[1]

    def exec_cmd(self, code, key, param=0):
        """Функция 0x55: [код 2 байта][ключ 4][параметр 4] -> [код 2][результат 4] (порядок — как в
        конфигураторе, big-endian; подтвердить сниффером)."""
        r = self._xfer(bytes([FC_EXEC]) + struct.pack(">HLL", code, key, param))
        return struct.unpack(">HL", r[1:7])

    def app_start(self, length, crc):
        return self._xfer(bytes([FC_APP_START]) + struct.pack(">LH", length, crc))[1]
