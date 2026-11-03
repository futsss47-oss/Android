"""Приборы стенда по USB (VISA): блок питания АКИП-1168 и генератор АКИП-3409/2.

Проверено в тестере команд 27.10.2026:
  * АКИП-1168 (клон Siglent SPD3303X-E), ресурс USB0::0xF4EC::0x1430::…::INSTR,
    *IDN? -> «AKIP-1168,SPD3EGGD7R2196,1.02.01.02.02R1,V2.0».
    Команды: CHn:CURRent x, CHn:VOLTage x, CHn:VOLTage?, CHn:CURRent?, OUTPut CHn,ON|OFF,
    MEASure:VOLTage? CHn, MEASure:CURRent? CHn. Между командами пауза 200 мс,
    сначала задавать ток, потом напряжение.
    CH1 — питание 24 В самой платы MK201: программа его НЕ трогает.
    CH2 — напряжение для проверки DO -> DI (5 / 11 / 24 / 30 В).
  * АКИП-3409/2 (формат Siglent SDG), ресурс USB0::0xF4ED::0xEE3A::…::INSTR.
    Проверены: C1:BSWV …; C1:BSWV?; C1:OUTP ON/OFF; C1:OUTP?. Режим на приборе: синус 20 Vpp, Hi-Z,
    Burst · N Cycle · Source Manual; программа меняет только FRQ и TIME. Пакет (C1:BTWV TIME / MTRIG) — проверить.

Нужны: pip install pyvisa и установленный NI-VISA (уже стоит на ПК стенда).
"""
import time

PSU_IDN = ("1168", "SPD3", "DPS3", "SPD")        # 02.11.2026: второй АКИП-1168 отвечает «DPS3EKGX900711,1.02.01.02.03,V2.0»
PSU_USB = ("0XF4EC::0X1430",)                    # USB VID:PID блоков питания Siglent SPD3303 / АКИП-1168
MANUAL_TRIG = "вручную (кнопка Trig)"
GEN_IDN = ("3409", "SDG")
GEN_USB = ("0XF4ED::0XEE3A",)                    # USB VID:PID генераторов Siglent SDG / АКИП-3409


def kind_of(resource, idn=""):
    """'psu' | 'gen' | None — по ответу *IDN? или по USB VID:PID в имени ресурса."""
    r, i = (resource or "").upper(), (idn or "").upper()
    if any(k in i for k in PSU_IDN) or any(k in r for k in PSU_USB):
        return "psu"
    if any(k in i for k in GEN_IDN) or any(k in r for k in GEN_USB):
        return "gen"
    return None


def _rm():
    try:
        import warnings, pyvisa
        # NI-VISA без файла настроек (visaconf.ini) пишет VI_WARN_CONFIG_NLOADED — это не ошибка, берутся умолчания
        warnings.filterwarnings("ignore", message=".*VI_WARN_CONFIG_NLOADED.*")
    except ImportError:
        raise RuntimeError("Не установлен pyvisa. Команда: py -m pip install pyvisa")
    try:
        return pyvisa.ResourceManager()
    except Exception as e:
        raise RuntimeError(f"VISA не запускается ({e}). Установлен ли NI-VISA?")


def list_resources():
    """Список USB-приборов VISA: [(ресурс, «ресурс — *IDN?»)]. Без VISA — пустой список."""
    try:
        rm = _rm()
        res = [r for r in rm.list_resources() if r.upper().startswith("USB")]
    except Exception:
        return []
    out = []
    for r in res:
        name = ""
        i = None
        try:
            i = rm.open_resource(r, open_timeout=1500)
            i.timeout = 1500; i.write_termination = "\n"; i.read_termination = "\n"
            name = i.query("*IDN?").strip()
        except Exception:
            pass
        finally:                                  # 02.11.2026: сеанс закрывается и при таймауте *IDN?
            try: i is not None and i.close()
            except Exception: pass
        out.append((r, f"{r} — {name}" if name else r))
    return out


def resource_of(display):
    s = (display or "").strip()
    if not s or s.startswith("—"):
        return ""
    return s.split(" —")[0].strip()


class _Visa:
    pause = 0.2

    def __init__(self, resource, log=None):
        self.log = log or (lambda s: None)
        self.resource = resource
        self._open()
        try:
            self.idn = self.query("*IDN?")
        except Exception as e:                   # прибор выбран оператором — работаем и без ответа на *IDN?
            self.idn = ""
            self.log(f"*IDN? без ответа ({e}) — всё равно пробую команды")

    def _open(self):
        self.inst = _rm().open_resource(self.resource, open_timeout=3000)
        self.inst.timeout = 5000
        self.inst.write_termination = "\n"
        self.inst.read_termination = "\n"

    def reopen(self):
        """После переподключения USB (кнопка «Повторить»): новый сеанс VISA к тому же прибору."""
        try: self.inst.close()
        except Exception: pass
        time.sleep(0.5)
        self._open()

    def alive(self):
        """Прибор отвечает? Не ответил — один раз переоткрыть сеанс и спросить снова."""
        for n in range(2):
            try:
                self.inst.query("*IDN?"); return True
            except Exception:
                if n == 0:
                    try: self.reopen()
                    except Exception: return False
        return False

    def write(self, cmd):
        self.log(f"→ {cmd}")
        self.inst.write(cmd)
        time.sleep(self.pause)

    def query(self, cmd):
        ans = self.inst.query(cmd).strip()
        self.log(f"→ {cmd}   ← {ans}")
        time.sleep(self.pause)
        return ans

    def close(self):
        try: self.inst.close()
        except Exception: pass


class PSU(_Visa):
    """АКИП-1168. Работаем только с одним каналом (по умолчанию CH2)."""
    measured = True

    def __init__(self, resource, channel=2, log=None, pause=0.2, strict=False):
        self.pause = float(pause)
        super().__init__(resource, log)
        self.recognized = kind_of(resource, self.idn) == "psu"
        if not self.recognized:
            if strict:
                _Visa.close(self)
                raise RuntimeError(f"На {resource} не АКИП-1168 (ответ «{self.idn}»)")
            self.log(f"ответ «{self.idn}» не похож на АКИП-1168 — прибор выбран вручную, команды всё равно отправляю")
        if int(channel) == 1:
            _Visa.close(self)
            raise RuntimeError("CH1 — питание платы; для проверок используется CH2")
        self.ch = f"CH{int(channel)}"

    # ---- CH1: питание самой платы (28.10.2026: включается при запуске программы, чтобы появился порт платы) ----
    def board_on(self, volts=24.0, amps=1.0):
        self.write(f"CH1:CURRent {amps:.3f}")
        self.write(f"CH1:VOLTage {volts:.3f}")
        self.write("OUTPut CH1,ON")

    def board_off(self):
        self.write("OUTPut CH1,OFF")

    def measure_board(self):
        """-> (вольт, ампер) на CH1 — ток и мощность потребления платы (табл. 1 протокола)."""
        v = float(self.query("MEASure:VOLTage? CH1"))
        a = float(self.query("MEASure:CURRent? CH1"))
        return v, a

    def set(self, volts, amps=0.2):
        """Сначала ток, потом напряжение, потом включить выход."""
        self.write(f"{self.ch}:CURRent {amps:.3f}")
        self.write(f"{self.ch}:VOLTage {volts:.3f}")
        self.write(f"OUTPut {self.ch},ON")

    def off(self):
        self.write(f"OUTPut {self.ch},OFF")

    def measure(self):
        """-> (вольт, ампер) на выходе канала."""
        v = float(self.query(f"MEASure:VOLTage? {self.ch}"))
        a = float(self.query(f"MEASure:CURRent? {self.ch}"))
        return v, a

    def close(self):
        try: self.off()
        except Exception: pass
        super().close()


class Generator(_Visa):
    """АКИП-3409/2 (формат Siglent SDG), канал C1, по руководству PG02-E05C, п. 3.7.
    28.10.2026: синус напрямую на счётные входы (без ключа), 20 Vpp, смещение 0, Hi-Z,
    Burst · N Cycle · ручной запуск, выход включён. В режиме пакета частота — «C1:BTWV CARR,FRQ,f»
    (C1:BSWV FRQ выключает Burst). Для режима частотомера — непрерывный сигнал (Burst выкл)."""
    pause = 0.6
    margin = 0.5
    AMP_MAX = 20.0
    # 03.11.2026 (1.33): форма сигнала на счётные входы — по умолчанию прямоугольник (выбирается на «Подключении»
    # и в ручном режиме). У прямоугольника на 30 кГц скважность 60 %: импульс по ТУ не короче 17 мкс.
    WAVES = {"прямоугольник": "SQUARE", "синус": "SINE"}
    wave = "SQUARE"

    @classmethod
    def wave_code(cls, name):
        n = str(name or "").strip().lower()
        return cls.WAVES.get(n, "SINE" if n.upper() == "SINE" else "SQUARE")

    def set_wave(self, wave):
        """Форма для следующих пакетов и непрерывного сигнала (команды уходят в prepare / continuous)."""
        self.wave = self.wave_code(wave)

    def duty_for(self, freq):
        """Скважность прямоугольника, %: 60 на частотах выше 25 кГц (импульс ≥ 17 мкс), иначе 50. Синус — None."""
        if self.wave != "SQUARE":
            return None
        return 60 if float(freq) > 25000 else 50

    def __init__(self, resource, log=None, channel=1, pause=0.6, margin=0.5, strict=False):
        self.pause, self.margin = float(pause), float(margin)
        super().__init__(resource, log)
        self.recognized = kind_of(resource, self.idn) == "gen"
        if not self.recognized:
            if strict:
                _Visa.close(self)
                raise RuntimeError(f"На {resource} не АКИП-3409 (ответ «{self.idn}»)")
            self.log(f"ответ «{self.idn}» не похож на АКИП-3409 — прибор выбран вручную, команды всё равно отправляю")
        self.c = f"C{int(channel)}"

    def setup(self, amp=20.0, wave="SQUARE"):
        """Прямоугольник или синус amp Vpp, смещение 0, Hi-Z, Burst · N Cycle · ручной запуск, фаза 0 (между пакетами 0 В), выход вкл.
        После включения питания прибор всё это забывает, поэтому настройка перед каждым шагом.
        Сначала параметры формы (BSWV), потом Burst. Команды по одной, после каждой — контрольный запрос."""
        amp = float(amp)
        if not 1.0 <= amp <= self.AMP_MAX:
            raise RuntimeError(f"амплитуда {amp:g} Vpp вне 1…{self.AMP_MAX:g}")
        c = self.c
        self.wave = self.wave_code(wave)
        shape = [(f"{c}:BSWV WVTP,{self.wave}", f"{c}:BSWV?")]
        if self.wave == "SQUARE":
            shape.append((f"{c}:BSWV DUTY,50", f"{c}:BSWV?"))
        for cmd, chk in tuple(shape) + ((f"{c}:BSWV AMP,{amp:g}", f"{c}:BSWV?"),
                         (f"{c}:BSWV OFST,0", f"{c}:BSWV?"), (f"{c}:OUTP LOAD,HZ", f"{c}:OUTP?"),
                         (f"{c}:BTWV STATE,ON", f"{c}:BTWV?"), (f"{c}:BTWV TRSR,MAN", f"{c}:BTWV?"),
                         (f"{c}:BTWV GATE_NCYC,NCYC", f"{c}:BTWV?"), (f"{c}:BTWV STPS,0", f"{c}:BTWV?"),
                         (f"{c}:OUTP ON", f"{c}:OUTP?")):
            self.write(cmd)
            time.sleep(0.4)
            self.query(chk)

    def continuous(self, freq):
        """Непрерывный сигнал freq (для режима частотомера): Burst выкл, частота обычной командой."""
        self.write(f"{self.c}:BTWV STATE,OFF")
        self.write(f"{self.c}:BSWV WVTP,{self.wave}")
        self.write(f"{self.c}:BSWV FRQ,{freq:g}")
        duty = self.duty_for(freq)
        if duty is not None:
            self.write(f"{self.c}:BSWV DUTY,{duty:g}")
        return self.query(f"{self.c}:BSWV?")

    def stop_continuous(self):
        """Обратно в пакет с ручным запуском — выход молчит до запуска."""
        self.write(f"{self.c}:BTWV STATE,ON")
        st = self.burst_state()
        if st.get("STATE") != "ON":
            raise RuntimeError(f"генератор не вернулся в режим пакета: {st}")

    def freq(self, f):
        self.write(f"{self.c}:BSWV FRQ,{f:g}")
        return self.query(f"{self.c}:BSWV?")        # заодно ждём, пока прибор применит

    def output(self, on):
        self.write(f"{self.c}:OUTP {'ON' if on else 'OFF'}")

    @staticmethod
    def parse(ans):
        """«C1:BTWV STATE,ON,TRSR,MAN,TIME,300,…» -> {'STATE': 'ON', 'TRSR': 'MAN', 'TIME': '300', …}"""
        body = ans.split(" ", 1)[1] if " " in ans else ans
        t = [x.strip() for x in body.split(",") if x.strip()]
        out, k = {}, 0
        while k < len(t) - 1:
            if t[k].upper() == "CARR":              # «CARR,WVTP,SINE,FRQ,10HZ,…» — параметры несущей идут парами дальше
                k += 1; continue
            out[t[k].upper()] = t[k + 1]; k += 2
        return out

    @staticmethod
    def _num_eq(text, val):
        try:
            return abs(float(str(text).upper().rstrip("%").replace("HZ", "")) - val) < 1e-6
        except (TypeError, ValueError):
            return False

    @staticmethod
    def _frq_ok(st, freq):
        frq = st.get("FRQ", "").upper().replace("HZ", "")
        try:
            return abs(float(frq) - freq) <= 1e-6 * max(freq, 1)
        except ValueError:
            return False

    def burst_state(self):
        return self.parse(self.query(f"{self.c}:BTWV?"))

    def burst_count(self, count):
        self.write(f"{self.c}:BTWV TIME,{int(count)}")
        return self.query(f"{self.c}:BTWV?")

    def prepare(self, freq, count, duty=None):
        """Частота + пакет из count импульсов, ручной запуск. 27.10.2026: команда C1:BSWV FRQ выключает
        режим Burst (C1:BTWV? -> STATE,OFF, выход начинает выдавать непрерывный синус), поэтому после
        частоты режим пакета включается заново и проверяется по ответу прибора. Без подтверждения — ошибка,
        запускать нельзя (иначе счётчики насчитают лишнее)."""
        # По руководству Siglent SDG (PG02-E05C, п. 3.7): STATE,ON ставится первым; частота в режиме пакета —
        # параметром несущей «C1:BTWV CARR,FRQ,f» (C1:BSWV FRQ выключает Burst — видно в логе 27.10.2026);
        # MTRIG работает только при TRSR,MAN.
        want = {"TRSR": "MAN", "GATE_NCYC": "NCYC", "TIME": str(int(count))}
        for attempt in range(2):
            st = self.burst_state()
            if st.get("STATE") != "ON":
                self.write(f"{self.c}:BTWV STATE,ON")
                st = self.burst_state()
            if str(st.get("WVTP", "")).upper() != self.wave:          # форма несущей пакета (1.33)
                self.write(f"{self.c}:BTWV CARR,WVTP,{self.wave}")
                st = self.burst_state()
            if not self._frq_ok(st, freq):
                self.write(f"{self.c}:BTWV CARR,FRQ,{freq:g}")
            if duty is not None and st.get("WVTP", "SQUARE").upper() == "SQUARE" and not self._num_eq(st.get("DUTY"), duty):
                self.write(f"{self.c}:BTWV CARR,DUTY,{duty:g}")
            # шлём только то, что отличается: лишние команды Burst на этом приборе могут запустить пакет сами
            for k, v in want.items():
                if st.get(k) != v:
                    self.write(f"{self.c}:BTWV {k},{v}")
            st = self.burst_state()
            if (st.get("STATE") == "ON" and st.get("TRSR") == "MAN" and st.get("TIME") == str(int(count))
                    and self._frq_ok(st, freq) and str(st.get("WVTP", self.wave)).upper() == self.wave):
                return st
            self.log(f"!! генератор не подтвердил пакет (попытка {attempt + 1}): {st}")
        raise RuntimeError(f"генератор не перешёл в режим пакета ({int(count)} имп., ручной запуск): {st}")

    TRIGGERS = ("C1:BTWV MTRIG", "*TRG", "C1:TRIG", "TRIG", MANUAL_TRIG)

    def trigger(self, cmd="C1:BTWV MTRIG", ask=None, text=""):
        """Запуск одного пакета: командой по USB или (cmd = MANUAL_TRIG) кнопкой Trig на приборе по просьбе программы."""
        if cmd == MANUAL_TRIG:
            if not ask:
                raise RuntimeError("Ручной запуск пакета возможен только с окном оператора")
            ask(f"Нажмите на генераторе экранную кнопку Trig{text}, затем «Готово»")
        else:
            self.write(cmd.replace("C1:", f"{self.c}:"))

    def pulses(self, freq, count, burst_max=50000, method="burst", stop=None, progress=None,
               trig="C1:BTWV MTRIG", ask=None, before=None, probe=None):
        """Выдать ровно count импульсов частотой freq пакетами по ≤ burst_max (у прибора максимум 50000).
        before() вызывается, когда генератор уже настроен и молчит, — тут сбрасывают счётчики платы.
        probe() -> True, если после первого пакета счётчики выросли; если нет — пробуются другие команды запуска.
        method='timer' (запасной, приблизительно): пакетный режим выключается, выход включается на count/freq с."""
        stop = stop or (lambda: None)
        if method == "timer":
            self.freq(freq)
            self.write(f"{self.c}:BTWV STATE,OFF")
            self.output(False)
            if before: before()
            try:
                self.output(True)
                t_end = time.time() + count / freq
                while time.time() < t_end:
                    stop(); time.sleep(0.05)
                    if progress: progress(1 - (t_end - time.time()) * freq / count)
            finally:
                self.output(False)
            return
        count = int(count)
        m = -(-count // int(burst_max))                 # число пакетов
        while count % m:                                # все пакеты одинаковые: TIME в процессе не меняем
            m += 1                                      # (смена TIME может сама запустить пакет)
        size = count // m
        # ТУ: импульс не короче 17 мкс; на 30 кГц период 33 мкс -> скважность 60 % (20 мкс)
        self.prepare(freq, size, duty=self.duty_for(freq))
        if before: before()
        # порядок попыток запуска: выбранная команда, затем остальные, в конце — кнопка Trig вручную
        order = [trig] + [t for t in self.TRIGGERS if t not in (trig, MANUAL_TRIG)]
        if ask and trig != MANUAL_TRIG:
            order.append(MANUAL_TRIG)
        for k in range(1, m + 1):
            stop()
            for cmd in (order if (k == 1 and probe) else [trig]):
                t0 = time.time()
                self.trigger(cmd, ask, f" (пакет {k} из {m}: {size} имп., ≈ {size / freq:.1f} с)")
                # при ручном запуске пакет начинается с нажатия — ждём его длительность от «Готово»
                time.sleep(max(0.0, size / freq + self.margin - (0 if cmd == MANUAL_TRIG else time.time() - t0)))
                if k > 1 or not probe:
                    break
                if probe():                             # счётчики платы выросли — пакет пошёл
                    if cmd != trig:
                        self.log(f"запуск пакета работает командой «{cmd}» (а не «{trig}») — дальше ею")
                    trig = cmd
                    self.trig_ok = cmd
                    break
                self.log(f"!! команда «{cmd}» пакет не запустила (счётчики не изменились)")
            else:
                raise RuntimeError("ни одна команда не запустила пакет генератора: " + ", ".join(order))
            if progress: progress(k / m)

    def close(self):
        super().close()                              # выход не трогаем — им управляет оператор


class ManualPSU:
    """Блок питания не подключён: оператор выставляет напряжение сам по подсказке."""
    measured = False

    def __init__(self, ask):
        self.ask = ask

    def set(self, volts, amps=0.2):
        if not self.ask(f"Выставьте на канале CH2 блока питания {volts:g} В, включите выход и нажмите ОК"):
            raise RuntimeError("Остановлено оператором")

    def off(self):
        pass

    def measure(self):
        return None, None

    def close(self):
        pass


class ManualGen:
    """Генератор не подключён: оператор выставляет всё на приборе сам по подсказкам программы.
    Подсчёт импульсов и оценка — как с подключённым генератором."""
    measured = False

    def __init__(self, ask, log=None, margin=0.5):
        self.ask, self.log, self.margin = ask, log or (lambda s: None), float(margin)
        self.trig_ok = None
        self.wave = "SQUARE"

    def set_wave(self, wave):
        self.wave = Generator.wave_code(wave)

    def _form(self, freq):
        if self.wave == "SINE":
            return "синус"
        return f"прямоугольник, скважность {60 if float(freq) > 25000 else 50} %"

    def setup(self, amp=20.0, wave="SQUARE"):
        form = "синус" if Generator.wave_code(wave) == "SINE" else "прямоугольник (скважность 50 %, на 30 кГц — 60 %)"
        self.ask(f"Генератор не подключён к ПК — настройте вручную: {form}, {amp:g} Vpp, смещение 0, нагрузка Hi-Z, "
                 "Burst · N Cycle · Source Manual, выход CH1 включён.")

    def pulses(self, freq, count, burst_max=50000, method="burst", stop=None, progress=None,
               trig=None, ask=None, before=None, probe=None):
        count = int(count)
        m = -(-count // int(burst_max))
        while count % m:
            m += 1
        size = count // m
        self.ask(f"Выставьте на генераторе: {self._form(freq)}, частота {freq:g} Гц, N Cycle = {size} импульсов. "
                 "Ничего не запускайте.")
        if before:
            before()
        for k in range(1, m + 1):
            if stop: stop()
            self.ask(f"Нажмите Trig на генераторе (пакет {k} из {m}: {size} имп., ≈ {size / freq:.1f} с), затем «Готово».")
            time.sleep(size / freq + self.margin)
            if progress: progress(k / m)

    def continuous(self, freq):
        self.ask(f"Режим частотомера: на генераторе выключите Burst (непрерывный сигнал), {self._form(freq)}, "
                 f"частота {freq:g} Гц.")

    def stop_continuous(self):
        self.ask("Включите на генераторе снова Burst (N Cycle, Source Manual) — выход должен замолчать.")

    def output(self, on):
        pass

    def close(self):
        pass
