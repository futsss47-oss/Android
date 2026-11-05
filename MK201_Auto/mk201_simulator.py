"""Имитация оборудования (режим «Симулятор»): плата MK201, блок реле, CA71, блок питания и генератор в памяти.
Нужна, чтобы проверить сценарий и окно без железа. Поведение близко к реальному:
при переключении канала цепь рвётся и калибратор CA71 уходит в 0 и OFF; DI горит только при ≥ 8 В на CH2."""
import random, time
from mk201_proto import AI_STD_CODES_HV31, crc16, MK201, ModbusError, l2regs, regs2l, f2regs


class World:
    def __init__(self):
        self.ma, self.cal_on = 0.0, False
        self.ai_ch = self.ao_ch = None
        self.gain = [1 + random.uniform(-0.004, 0.004) for _ in range(8)]
        self.off = [random.uniform(-6, 6) for _ in range(8)]
        self.ai_cal = [[c + random.randint(-10, 10) for c in AI_STD_CODES_HV31] for _ in range(8)]
        self.ao_cmd = [4.0, 4.0]
        self.ao_cal = [(4.0, 19.0), (4.0, 19.0)]
        self.ao_g = [1 + random.uniform(-.003, .003) for _ in range(2)]
        self.ao_o = [random.uniform(-.01, .01) for _ in range(2)]
        self.regs = {}                       # прочие регистры Settings
        ports = [0x00C0 | (0xA8 << 8), 0x0101, 0xFFFF, 0x00FF, 0x00C0 | (0xA8 << 8), 0x0101, 0, 0]
        for p in range(3):
            ports += [1, 0, 0, 11, 0, 0]
        self._put(1010, ports)
        self._put(201, [0, 1, 0, 0, 0] * 16)
        self._put(602, [0, 1, 0, 0, 0] * 16)
        self._put(101, [1, 0, 0, 100] * 8)
        self._put(507, [1, 0, 1, 0])
        self._put(401, [2, 2, 2, 2])
        self.serial, self.mac = 0, "00:00:00:00:00:00"
        self.app = (370884, 0x0D70)
        self.segs = {}
        self.boot = False
        self.do_mask = 0
        self.psu_v, self.psu_on = 0.0, False
        self.ci = [0, 0, 0, 0]
        self.ci_freq = [0.0] * 4

    def _put(self, a, vals):
        for i, v in enumerate(vals):
            self.regs[a + i] = v

    def lit(self):
        """Какие DI горят: петля DOn -> DIn, вход срабатывает от ~8 В."""
        if not self.psu_on or self.psu_v < 8.0:
            return 0
        return self.do_mask & 0xFFFF


class SimBoard(MK201):
    base_timeout = 1.0
    trace = None

    def __init__(self, w):
        self.w, self.unit = w, 1

    def close(self): pass
    def set_timeout(self, t): pass

    # ---- общий доступ к регистрам ----
    def read_set(self, addr, count):
        w = self.w
        if w.boot:
            raise ModbusError("Нет ответа (плата в загрузчике)")
        if 417 <= addr <= 428:                   # как у реальной MK201 (27.10.2026): периода/дребезга CI нет
            raise ModbusError("Исключение Modbus 2 (fc=101)", code=2)
        dyn = {}
        for i, v in enumerate(l2regs(w.lit())): dyn[25 + i] = v
        for i, v in enumerate(l2regs(w.do_mask)): dyn[600 + i] = v
        for c in range(4):
            for i, v in enumerate(l2regs(w.ci[c])): dyn[67 + 2 * c + i] = v
            for i, v in enumerate(f2regs(w.ci_freq[c])): dyn[51 + 2 * c + i] = v
        for i, v in enumerate(l2regs(w.serial)): dyn[1102 + i] = v
        for i, v in enumerate(MK201.mac_to_regs(w.mac)): dyn[1104 + i] = v
        t = time.localtime()
        for i, v in enumerate([t.tm_hour, t.tm_min, t.tm_sec, t.tm_year - 2000, t.tm_mon, t.tm_mday]): dyn[1036 + i] = v
        return [dyn.get(addr + i, w.regs.get(addr + i, 0)) for i in range(count)]

    def write_set(self, addr, vals):
        w = self.w
        if addr == 600:
            w.do_mask = regs2l(vals[:2]); return
        if addr == 409:
            if len(vals) > 4:                    # как у реальной MK201: 409 ×8 -> исключение 2
                raise ModbusError("Исключение Modbus 2 (fc=100)", code=2)
            for c in range(4):
                if vals[c]: w.ci[c] = 0
            return
        if addr == 1102:
            w.serial = regs2l(vals); return
        if addr == 1104:
            w.mac = MK201.regs_to_mac(vals); return
        w._put(addr, vals)

    def raw(self, payload, wait=0.3):
        """Команда вручную в симуляторе: чтение 3/101 и запись 16/100 понимаем, остальное — исключение 1."""
        import struct
        from mk201_proto import crc16
        p = bytes(payload)
        try:
            if p[1] in (3, 101):
                a, n = struct.unpack(">HH", p[2:6])
                regs = self.read_set(a, n)
                body = bytes([p[0], p[1], 2 * n]) + b"".join(struct.pack(">H", r & 0xFFFF) for r in regs)
            elif p[1] in (16, 100):
                a, n = struct.unpack(">HH", p[2:6])
                self.write_set(a, [struct.unpack(">H", p[7 + 2 * i:9 + 2 * i])[0] for i in range(n)])
                body = p[:6]
            else:
                body = bytes([p[0], p[1] | 0x80, 1])
        except ModbusError as e:
            body = bytes([p[0], p[1] | 0x80, e.code or 4])
        except Exception:
            body = bytes([p[0] if p else 1, (p[1] if len(p) > 1 else 0) | 0x80, 3])
        return body + struct.pack("<H", crc16(body))

    def identify(self):
        return {0: "Nefteavtomatika", 1: f"MK201 (s/n:{self.w.serial})", 2: "Rev3.00 BL2.6.4", 3: "RT3.0.2.9.6",
                5: "MK201 (Rev3.00 BL2.6.4) — симулятор", 6: "empty_project"}

    # ---- аналоговые входы ----
    def _src(self, ch):
        w = self.w
        return w.ma if (w.ai_ch == ch and w.cal_on) else 0.0

    def ai_adc(self):
        w = self.w
        return [int(self._src(c) * 655.36 * w.gain[c] + w.off[c] + random.uniform(-1, 1)) if self._src(c) else 0
                for c in range(8)]

    def ai_ma(self):
        w, out, adc = self.w, [], self.ai_adc()
        for c in range(8):
            if not self._src(c):
                out.append(random.uniform(-0.002, 0.002)); continue
            p1, p4 = w.ai_cal[c][0], w.ai_cal[c][3]
            out.append(4 + (adc[c] - p1) * 16 / (p4 - p1) if p4 != p1 else 0.0)
        return out

    def ai_cal_read(self, ch): return list(self.w.ai_cal[ch])
    def ai_cal_write(self, ch, codes): self.w.ai_cal[ch] = [int(c) for c in codes]
    def ai_cal_read_all(self): return [list(r) for r in self.w.ai_cal]
    def ai_cal_write_all(self, t): self.w.ai_cal = [[int(v) for v in r] for r in t]

    # ---- аналоговые выходы ----
    def ao_set(self, ch, ma):
        if random.random() < getattr(self.w, "ao_stuck", 0.2) and ma in (4.0, 19.0):
            return                              # как у реальной платы: иногда значение «не встаёт»
        self.w.ao_cmd[ch] = ma
    def ao_get(self, ch): return self.w.ao_cmd[ch]
    def ao_cal_read(self): return list(self.w.ao_cal)
    def ao_cal_write(self, pts): self.w.ao_cal = [tuple(p) for p in pts]

    def ao_actual(self, ch):
        w = self.w
        p1, p2 = w.ao_cal[ch]
        c = 4 + (w.ao_cmd[ch] - p1) * 15 / (p2 - p1)
        return c * w.ao_g[ch] + w.ao_o[ch]

    # ---- системные ----
    def datetime_write(self, t=None): pass
    def datetime_read(self):
        t = time.localtime(); return (t.tm_year, t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec)
    def serial_write(self, n): self.w.serial = int(n)
    def mac_write(self, mac): self.w.mac = mac.upper().replace("-", ":")

    # ---- загрузчик ----
    def app_info(self): return self.w.app
    def app_activate(self, flag=1): self.w.boot = True; self.w.segs = {}; return 0
    def app_write_segment(self, n, data): self.w.segs[n] = bytes(data); return 0
    def app_start(self, length, crc):
        img = b"".join(self.w.segs[k] for k in sorted(self.w.segs))[:length]
        if crc16(img) != crc:
            return 0xFF
        self.w.app, self.w.boot = (length, crc), False
        return 1


class SimRelay:
    def __init__(self, w): self.w = w
    def ai(self, ch): self.w.ai_ch, self.w.ao_ch, self.w.cal_on = ch, None, False   # цепь рвётся -> CA71 в 0/OFF
    def ao(self, ch): self.w.ao_ch, self.w.ai_ch = ch, None
    def off(self): self.w.ai_ch = self.w.ao_ch = None
    def ping(self): return "OK"
    def close(self): pass


class SimCal:
    def __init__(self, w, board): self.w, self.b = w, board
    def init(self): pass
    def set_source(self, ma, settle=0): self.w.ma, self.w.cal_on = ma, abs(ma) >= 0.05
    def source_off(self): self.w.cal_on = False
    def close(self): pass
    def measure_ma(self, n=3, interval=0):
        w = self.w
        if w.ao_ch is None:
            return 0.0
        return self.b.ao_actual(w.ao_ch) + random.uniform(-0.0005, 0.0005)


class SimPSU:
    measured = True
    idn = "AKIP-1168 (симулятор)"
    def __init__(self, w): self.w = w
    def set(self, volts, amps=0.2): self.w.psu_v, self.w.psu_on = float(volts), True
    def off(self): self.w.psu_on = False
    def measure(self):
        w = self.w
        if not w.psu_on:
            return 0.0, 0.0
        n = bin(w.do_mask & 0xFFFF).count("1")
        return w.psu_v, n * w.psu_v / 4400.0 + random.uniform(0, 0.0004)
    def board_on(self, volts=24.0, amps=1.0): self.w.board_on = True
    def board_off(self): self.w.board_on = False
    def measure_board(self): return 24.0 + random.uniform(-0.01, 0.01), 0.122 + random.uniform(-0.001, 0.001)
    def close(self): self.off()


class SimGen:
    """Генератор в симуляторе. sim_wire — «провода»: какие входы CI сейчас подключены к генератору."""
    idn = "AKIP-3409-2 (симулятор)"
    trig_ok = None
    def __init__(self, w):
        self.w = w
        if not hasattr(w, "ci_conn"):
            w.ci_conn = {0, 1, 2, 3}
            w.cont_f = None
    def output(self, on): pass
    def freq(self, f): pass
    def query(self, cmd): return {"C1:BSWV?": "C1:BSWV WVTP,SINE,FRQ,10HZ,AMP,20V", "C1:BTWV?": "C1:BTWV STATE,ON,GATE_NCYC,NCYC,TRSR,MAN,TIME,300", "C1:OUTP?": "C1:OUTP ON,LOAD,HZ"}.get(cmd, "")
    def trigger(self, cmd="", ask=None, text=""): pass
    def setup(self, amp=20.0, wave="SQUARE"): self.wave = wave
    def set_wave(self, wave): self.wave = wave
    def sim_wire(self, want):
        w = self.w
        if want == "all":
            w.ci_conn = {0, 1, 2, 3}
        elif want == "pair":
            w.ci_conn = {2, 3}                       # «оператор» выбрал вторую клемму первой
        else:
            w.ci_conn = {0, 1} if w.ci_conn == {2, 3} else {2, 3}
    def continuous(self, f):
        w = self.w
        w.cont_f = float(f)
        for c in range(4):
            w.ci_freq[c] = float(f) * (1 + random.uniform(-2e-6, 2e-6)) if c in w.ci_conn else 0.0
    def stop_continuous(self):
        self.w.cont_f = None
    def pulses(self, freq, count, burst_max=50000, method="burst", stop=None, progress=None, trig="", ask=None,
               before=None, probe=None):
        if before: before()
        w = self.w
        for c in range(4):
            if c in w.ci_conn and w.regs.get(401 + c, 0) == 1:
                w.ci[c] = (w.ci[c] + int(count)) % (1 << 32)
        if progress: progress(1.0)
        time.sleep(0.05)
    def close(self): pass


_WORLD = None


def make_sim(new=False):
    """-> (плата, реле, CA71). Мир общий для ручного и автоматического режимов."""
    global _WORLD
    if _WORLD is None or new:
        _WORLD = World()
    b = SimBoard(_WORLD)
    return b, SimRelay(_WORLD), SimCal(_WORLD, b)


def make_sim_instruments():
    if _WORLD is None:
        make_sim()
    return SimPSU(_WORLD), SimGen(_WORLD)
