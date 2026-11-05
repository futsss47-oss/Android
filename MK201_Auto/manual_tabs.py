"""Ручной режим (как вкладки конфигуратора MKLogic200, без сохранения настроек в файл) и панель «Приборы».

Все обращения к плате идут в фоновом потоке через одно соединение (self.mdev) под замком self.mlock.
Пока идёт автоматический прогон или запись ПО, ручной режим недоступен; соединение закрывается.
Все вкладки проверены на плате (03.11.2026)."""
import statistics, threading, time, tkinter as tk
from tkinter import ttk, messagebox

from mk201_proto import PORT_MODES, BAUDS, PARITIES, STOPBITS, CI_MODES, MK201
from mk201_simulator import make_sim, make_sim_instruments
from mk201_relay import NoRelay
from mk201_workflow import Runner
from mk201_instruments import Generator, MANUAL_TRIG

C_BG, C_PANEL, C_MUTED, C_LINE = "#f4f6f9", "#ffffff", "#6b7280", "#d0d7de"
OK, ERR, WARN, ACC = "#16a34a", "#dc2626", "#d97706", "#1d4ed8"
LAMP_OFF, DI_ON, DO_ON = "#d5dae3", "#16a34a", "#f59e0b"
MASTERS = ["Master 0", "Master 1", "Master 2"]


def ip_of(regs, i):
    return ".".join(str(x) for x in (regs[i] & 255, regs[i] >> 8, regs[i + 1] & 255, regs[i + 1] >> 8))


def ip_regs(text):
    b = [int(x) for x in text.strip().split(".")]
    if len(b) != 4 or any(x < 0 or x > 255 for x in b):
        raise ValueError(f"Неверный IP-адрес: {text}")
    return [b[0] | b[1] << 8, b[2] | b[3] << 8]


class ManualMixin:
    # ================= инфраструктура =================
    def manual_init(self):
        self.mdev = None
        self.mlock = threading.Lock()
        self.ilock = threading.Lock()
        self._mbusy = False
        self.mbtns = []
        self.auto = {}
        self.sys_snap = None
        self.do_mask = 0
        self.di_rows = self.do_rows = None
        self._sim_instr = None
        self.dio_psu_on = False          # CH2 включён ручным режимом (вкладка DI / DO)
        self._cur_sub = None

    def mbtn(self, parent, text, cmd, **kw):
        b = ttk.Button(parent, text=text, command=cmd, **kw)
        self.mbtns.append(b)
        return b

    def manual_enable(self, on):
        for b in getattr(self, "mbtns", []):
            try: b.configure(state="normal" if on else "disabled")
            except tk.TclError: pass

    def manual_close(self):
        if self.mlock.acquire(timeout=3):
            try:
                if self.mdev is not None:
                    try: self.mdev.close()
                    except Exception: pass
                self.mdev = None
            finally:
                self.mlock.release()

    def mboard(self, fn, done=None, quiet=False):
        """Выполнить fn(плата) в фоне; done(результат) — в окне."""
        if self.running or self.flashing or self.connecting:
            if not quiet: self.add_log("Идёт прогон, подключение или запись ПО — ручной режим недоступен", "warn")
            return
        if self.locked:
            if not quiet: self.add_log("Плата заблокирована после загрузки ПО — сначала «Проверить плату»", "warn")
            return
        if quiet and self._mbusy:
            return
        def job():
            with self.mlock:
                # 02.11.2026: пока поток ждал mlock, мог начаться прогон/подключение — тогда порт платы не открываем
                if self.running or self.flashing or self.connecting:
                    return
                self._mbusy = True
                try:
                    if self.mdev is None:
                        self.mdev = make_sim()[0] if self.sim.get() else self._board()
                    d = self.mdev
                    old = getattr(d, "trace", None)
                    if quiet: d.trace = None
                    try:
                        r = fn(d)
                    finally:
                        if quiet: d.trace = old
                    if done: self.root.after(0, done, r)
                except Exception as e:
                    self.ui.log(f"Ручной режим: {type(e).__name__}: {e}", "err")
                    if quiet:
                        self.root.after(0, lambda: [v.set(False) for v in self.auto.values()])
                        self.ui.log("Автообновление выключено из-за ошибки связи", "warn")
                    try:
                        if self.mdev is not None and not self.sim.get(): self.mdev.close()
                    except Exception: pass
                    self.mdev = None
                finally:
                    self._mbusy = False
        threading.Thread(target=job, daemon=True).start()

    def instr(self, name):
        """Подключённый прибор или прибор симулятора."""
        if self.sim.get():
            if self._sim_instr is None:
                _, r, c = make_sim(); p, g = make_sim_instruments()
                self._sim_instr = dict(relay=r, cal=c, psu=p, gen=g)
            return self._sim_instr[name]
        return getattr(self, name)

    def ibg(self, name, fn, done=None):
        """Действие с прибором в фоне."""
        dev = self.instr(name)
        if dev is None:
            messagebox.showinfo("Приборы", "Прибор не подключён. Выберите его на вкладке «Подключение» и нажмите «Подключить».")
            return
        if self.running or self.connecting:
            self.add_log("Идёт прогон или подключение — подождите", "warn"); return
        def job():
            with self.ilock:
                try:
                    r = fn(dev)
                    if done: self.root.after(0, done, r)
                except Exception as e:
                    self.ui.log(f"{name}: {e}", "err")
        threading.Thread(target=job, daemon=True).start()

    def poll(self):
        """Автообновление видимой вкладки ручного режима раз в секунду."""
        try:
            if self.nb.select() == str(self.tabs["manual"]) and not (self.running or self.flashing or self.locked or self.connecting):
                cur = self.mnb.select()
                for key, (frame, fn) in self.mpoll.items():
                    if cur == str(frame) and self.auto.get(key) and self.auto[key].get():
                        fn(quiet=True)
        except Exception:
            pass
        try:
            self.connect_watchdog()
        except Exception:
            pass
        self.root.after(1000, self.poll)

    def note(self, parent, text):
        ttk.Label(parent, text=text, style="Note.TLabel", wraplength=900, justify="left").pack(anchor="w", pady=(0, 6))

    # ================= разметка =================
    def _tab_manual(self, f):
        self.note(f, "Ручной режим — как вкладки конфигуратора. Подключение и журнал общие. "
                     "Пока идёт автоматический прогон или запись ПО, кнопки здесь недоступны.")
        nb = ttk.Notebook(f); nb.pack(fill="both", expand=True)
        self.mnb = nb
        self.mpoll = {}
        self.msub = {}
        for key, title, builder in (("info", "Информация", self._m_info), ("sys", "Системные настройки", self._m_sys),
                                    ("ai", "AI", self._m_ai), ("ao", "AO", self._m_ao), ("dio", "DI / DO", self._m_dio),
                                    ("ci", "Счётные входы", self._m_ci), ("instr", "Приборы", self._m_instr),
                                    ("cmd", "Команда вручную", self._m_cmd)):
            fr = ttk.Frame(nb, padding=6)
            nb.add(fr, text=title)
            self.msub[key] = fr
            builder(fr, self._scrollable(fr))
        nb.bind("<<NotebookTabChanged>>", self.m_sub_changed)

    # ---- 29.10.2026: вход во вкладку — сразу читаем всё; DI / DO — 24 В на CH2 ----
    def m_readers(self):
        return {"info": self.m_info_read, "sys": self.m_sys_read, "ai": self.m_ai_all,
                "ao": lambda: (self.m_ao_read(), self.m_ao_cfg_read()),
                "dio": lambda: (self.m_dio_values(), self.m_dio_cfg_read()),
                "ci": lambda: (self.m_ci_values(), self.m_ci_cfg_read())}

    def m_sub_key(self):
        cur = self.mnb.select()
        for k, fr in self.msub.items():
            if cur == str(fr):
                return k
        return None

    def m_manual_visible(self):
        return self.nb.select() == str(self.tabs["manual"])

    def m_busy(self):
        return self.running or self.flashing or self.locked or getattr(self, "connecting", False)

    def m_enter(self):
        """Открыли «Ручной режим»: прочитать из платы всё (информацию, настройки, AI, AO, DI/DO, CI)."""
        if not self.m_manual_visible():
            return
        if getattr(self, "connecting", False):
            self.root.after(1500, self.m_enter)            # идёт подключение приборов — прочитаем чуть позже
            return
        if self.m_busy():
            return
        self._cur_sub = self.m_sub_key()
        def ok(_):
            self.add_log("Ручной режим: читаю из платы информацию, настройки, AI, AO, DI / DO, счётные входы", "dim")
            for fn in self.m_readers().values():
                fn()
        self.mboard(lambda d: d.ai_ma(), ok)             # сначала одна проверка связи — без кучи одинаковых ошибок
        if self._cur_sub == "dio":
            self.m_dio_power(True)

    def m_leave(self):
        self.m_dio_power(False)
        self._cur_sub = None

    def m_sub_changed(self, e=None):
        try:
            if not self.m_manual_visible():
                return
        except Exception:
            return
        new, old = self.m_sub_key(), self._cur_sub
        self._cur_sub = new
        if old == "dio" and new != "dio":
            self.m_dio_power(False)
        if new == "dio":
            self.m_dio_power(True)
        if new in self.m_readers() and new != old and not self.m_busy():
            self.m_readers()[new]()

    def m_dio_power(self, on, then=None):
        """CH2 блока питания для вкладки DI / DO: открыли вкладку (или нажали лампочку DO) — 24 В, ушли — выкл.
        Без блока питания ничего не делаем (питание DO/DI — вручную)."""
        psu = self.instr("psu")
        if psu is None or (on and (self.running or self.flashing)) or bool(on) == self.dio_psu_on:
            if then: then()
            return
        self.dio_psu_on = bool(on)
        v, a = float(self.S["manual_dio_v"]), float(self.S["psu_amps"])
        def job():
            try:
                with self.ilock:
                    if on:
                        psu.set(v, a)
                        time.sleep(float(self.S["psu_settle"]) if not self.sim.get() else 0)
                    else:
                        psu.off()
                self.ui.log(f"Блок питания: CH2 = {v:g} В (вкладка DI / DO)" if on else
                            "Блок питания: CH2 выключен (ушли с вкладки DI / DO)", "dim")
            except Exception as e:
                self.dio_psu_on = False
                self.ui.log(f"Блок питания: CH2 не {'включился' if on else 'выключился'} — {e}", "warn")
            if then:
                self.root.after(0, then)
        threading.Thread(target=job, daemon=True).start()

    # ---------------- Информация ----------------
    def _m_info(self, tab, f):
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(0, 6))
        self.mbtn(bar, "Прочитать", self.m_info_read, style="Accent.TButton").pack(side="left")
        self.info_txt = tk.Text(f, height=9, font=("Consolas", 10), bg=C_PANEL, relief="solid", bd=1, wrap="word")
        self.info_txt.pack(fill="x", pady=(0, 8))
        lf = ttk.LabelFrame(f, text="Заводские данные", padding=10); lf.pack(fill="x", pady=(0, 8))
        self.m_sn = tk.StringVar(); self.m_mac = tk.StringVar()
        ttk.Label(lf, text="Серийный номер (в плату):").grid(row=0, column=0, sticky="w")
        ttk.Entry(lf, textvariable=self.m_sn, width=16).grid(row=0, column=1, sticky="w", padx=6)
        self.mbtn(lf, "Записать", self.m_sn_write).grid(row=0, column=2, sticky="w")
        ttk.Label(lf, text="MAC-адрес:").grid(row=1, column=0, sticky="w", pady=4)
        ttk.Entry(lf, textvariable=self.m_mac, width=20).grid(row=1, column=1, sticky="w", padx=6)
        self.mbtn(lf, "Записать", self.m_mac_write).grid(row=1, column=2, sticky="w")
        ttk.Button(lf, text="Взять из вкладки «Проверка»", command=lambda: (self.m_sn.set(self.v["serial"].get()),
                   self.m_mac.set(self.v["mac"].get()))).grid(row=0, column=3, rowspan=2, padx=12)
        lf = ttk.LabelFrame(f, text="Дата и время", padding=10); lf.pack(fill="x")
        self.m_dt = ttk.Label(lf, text="в плате: —"); self.m_dt.grid(row=0, column=0, sticky="w", padx=(0, 12))
        self.mbtn(lf, "Прочитать", self.m_dt_read).grid(row=0, column=1)
        self.mbtn(lf, "Записать время ПК", self.m_dt_write).grid(row=0, column=2, padx=6)

    def m_info_read(self):
        def fn(d):
            out = []
            try:
                ids = d.identify()
                out += [f"{k}: {v}" for k, v in sorted(ids.items())]
            except Exception as e:
                out.append(f"Идентификация (0x2B): не прочитана — {e}")
            try:
                ln, crc = d.app_info(); out.append(f"ПО в плате: {ln} байт, CRC16 {crc:04X}")
            except Exception as e:
                out.append(f"Информация о ПО: {e}")
            sn, mac = d.serial_read(), d.mac_read()
            out += [f"Серийный номер: {sn}", f"MAC: {mac}"]
            return out, sn, mac
        def done(r):
            out, sn, mac = r
            self.info_txt.delete("1.0", "end"); self.info_txt.insert("end", "\n".join(out))
            self.m_sn.set(str(sn)); self.m_mac.set(mac)
        self.mboard(fn, done)
        self.m_dt_read()

    def m_sn_write(self):
        try: sn = int(self.m_sn.get())
        except ValueError: messagebox.showwarning("Серийный номер", "Введите число"); return
        if not messagebox.askyesno("Серийный номер", f"Записать в плату серийный номер {sn}?"): return
        def fn(d):
            d.serial_write(sn); return d.serial_read()
        self.mboard(fn, lambda back: self.add_log(f"Серийный номер записан, в плате {back}" + (" — OK" if back == sn else " — НЕ СОВПАЛО")))

    def m_mac_write(self):
        mac = self.m_mac.get().strip()
        try: MK201.mac_to_regs(mac)
        except Exception: messagebox.showwarning("MAC", "Формат: 00:11:22:33:44:55"); return
        if not messagebox.askyesno("MAC", f"Записать в плату MAC {mac}?"): return
        def fn(d):
            d.mac_write(mac); return d.mac_read()
        self.mboard(fn, lambda back: self.add_log(f"MAC записан, в плате {back}" +
                                                  (" — OK" if back.upper() == mac.upper().replace('-', ':') else " — НЕ СОВПАЛО")))

    def m_dt_read(self):
        def done(t):
            y, mo, d, h, mi, s = t
            diff = abs(time.mktime(time.localtime()) - time.mktime((y, mo, d, h, mi, s, 0, 0, -1)))
            self.m_dt.configure(text=f"в плате: {d:02d}.{mo:02d}.{y} {h:02d}:{mi:02d}:{s:02d}  (расхождение с ПК {diff:.0f} с)",
                                foreground=OK if diff <= 1 else ERR)
        self.mboard(lambda d: d.datetime_read(), done)

    def m_dt_write(self):
        def done(r):
            ok, _, text = r
            self.add_log(f"Время ПК записано в плату — {text}", "ok" if ok else "err")
            self.m_dt_read()
        self.mboard(lambda d: d.datetime_sync(), done)

    # ---------------- Системные настройки ----------------
    def _m_sys(self, tab, f):
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(0, 6))
        self.mbtn(bar, "Прочитать", self.m_sys_read, style="Accent.TButton").pack(side="left")
        self.mbtn(bar, "Записать в плату", self.m_sys_write).pack(side="left", padx=6)
        self.sys_star = ttk.Label(bar, text="", foreground=WARN); self.sys_star.pack(side="left", padx=10)
        self.note(f, "Изменённые поля помечаются «*». Порты 1–3 = MbSlave, Master 0–2 — как в автоматическом шаге.")
        lf = ttk.LabelFrame(f, text="Настройка TCP", padding=10); lf.pack(fill="x", pady=(0, 8))
        self.sys_v = {}
        for i, (k, t) in enumerate((("ip", "IP-адрес"), ("mask", "Маска"), ("gw", "Шлюз"), ("dns", "DNS"))):
            ttk.Label(lf, text=t + ":").grid(row=0, column=2 * i, sticky="w", padx=(0, 4))
            self.sys_v[k] = tk.StringVar()
            ttk.Entry(lf, textvariable=self.sys_v[k], width=15).grid(row=0, column=2 * i + 1, sticky="w", padx=(0, 14))
        lf = ttk.LabelFrame(f, text="Последовательные порты (RS-485)", padding=10); lf.pack(fill="x")
        for c, t in enumerate(("", "Режим", "Мастер №", "Адрес", "Скорость", "Чётность", "Стоп-бит")):
            ttk.Label(lf, text=t, style="Muted.TLabel").grid(row=0, column=c, sticky="w", padx=4)
        for p in range(3):
            row = {}
            ttk.Label(lf, text=f"Порт {p + 1}").grid(row=p + 1, column=0, sticky="w", padx=4, pady=2)
            for c, (k, vals, w) in enumerate((("mode", list(PORT_MODES), 10), ("master", MASTERS, 9), ("addr", None, 6),
                                              ("baud", [str(b) for b in BAUDS.values()], 8),
                                              ("par", list(PARITIES.values()), 6), ("stop", list(STOPBITS.values()), 5)), 1):
                var = tk.StringVar(); row[k] = var; self.sys_v[f"p{p}{k}"] = var
                (ttk.Combobox(lf, textvariable=var, values=vals, width=w, state="readonly") if vals
                 else ttk.Entry(lf, textvariable=var, width=w)).grid(row=p + 1, column=c, sticky="w", padx=4, pady=2)
        for var in self.sys_v.values():
            var.trace_add("write", lambda *a: self.sys_mark())

    def sys_mark(self):
        if self.sys_snap is None: return
        changed = [k for k, v in self.sys_v.items() if v.get() != self.sys_snap.get(k)]
        self.sys_star.configure(text=f"* изменено полей: {len(changed)} — нажмите «Записать в плату»" if changed else "")

    def m_sys_read(self):
        def done(regs):
            self.sys_regs = list(regs)
            for k, i in (("ip", 0), ("mask", 2), ("gw", 4), ("dns", 6)):
                self.sys_v[k].set(ip_of(regs, i))
            for p in range(3):
                q = regs[8 + 6 * p: 14 + 6 * p]
                g = lambda k: self.sys_v[f"p{p}{k}"]
                g("addr").set(str(q[0] & 255))
                g("mode").set(PORT_MODES[q[1]] if q[1] < len(PORT_MODES) else str(q[1]))
                g("master").set(MASTERS[q[2]] if q[2] < 3 else str(q[2]))
                g("baud").set(str(BAUDS.get(q[3], q[3])))
                g("par").set(PARITIES.get(q[4], str(q[4])))
                g("stop").set(STOPBITS.get(q[5], str(q[5])))
            self.sys_snap = {k: v.get() for k, v in self.sys_v.items()}
            self.sys_mark()
        self.mboard(lambda d: d.ports_read(), done)

    def m_sys_write(self):
        if self.sys_snap is None:
            messagebox.showinfo("Системные настройки", "Сначала нажмите «Прочитать»."); return
        try:
            regs = list(self.sys_regs)
            for k, i in (("ip", 0), ("mask", 2), ("gw", 4), ("dns", 6)):
                regs[i:i + 2] = ip_regs(self.sys_v[k].get())
            inv = lambda d, s: {str(v): k for k, v in d.items()}[s]
            for p in range(3):
                g = lambda k: self.sys_v[f"p{p}{k}"].get()
                b = 8 + 6 * p
                addr = int(g("addr"))
                if not 1 <= addr <= 247: raise ValueError(f"Порт {p + 1}: адрес 1…247")
                regs[b] = (regs[b] & 0xFF00) | addr
                regs[b + 1] = PORT_MODES.index(g("mode"))
                regs[b + 2] = MASTERS.index(g("master"))
                regs[b + 3] = inv(BAUDS, g("baud"))
                regs[b + 4] = inv(PARITIES, g("par"))
                regs[b + 5] = inv(STOPBITS, g("stop"))
        except Exception as e:
            messagebox.showwarning("Системные настройки", str(e)); return
        def fn(d):
            d.ports_write(regs); time.sleep(0.3); return d.ports_read()
        def done(back):
            diff = [1010 + k for k in range(26) if back[k] != regs[k]]
            self.add_log("Системные настройки записаны — OK" if not diff else f"!!! Системные настройки: не совпали регистры {diff}")
            self.m_sys_read()
        self.mboard(fn, done)

    # ---------------- AI ----------------
    def _m_ai(self, tab, f):
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(0, 6))
        self.auto["ai"] = tk.BooleanVar(value=self.c.get("auto_ai", True))
        ttk.Checkbutton(bar, text="Автообновление значений", variable=self.auto["ai"]).pack(side="left")
        self.mbtn(bar, "Прочитать всё", self.m_ai_all).pack(side="left", padx=8)
        self.mpoll["ai"] = (tab, self.m_ai_values)
        g = ttk.Frame(f); g.pack(fill="x")
        heads = ("Канал", "I, мА", "Код АЦП", "Enable", "РФ 50 Гц", "ФНЧ", "T(ФНЧ), мс", "Калибровка: коды 4 / 16 / 18 / 20 мА")
        for c, t in enumerate(heads):
            ttk.Label(g, text=t, style="Muted.TLabel").grid(row=0, column=c, sticky="w", padx=6)
        self.ai_w = []
        for ch in range(8):
            w = dict(ma=ttk.Label(g, text="—", width=9), adc=ttk.Label(g, text="—", width=7),
                     en=tk.BooleanVar(), rf=tk.BooleanVar(), lpf=tk.BooleanVar(), t=tk.StringVar(),
                     cal=ttk.Label(g, text="—", width=30))
            ttk.Label(g, text=f"AI{ch}", font=("Segoe UI", 10, "bold")).grid(row=ch + 1, column=0, sticky="w", padx=6, pady=1)
            w["ma"].grid(row=ch + 1, column=1, sticky="w", padx=6); w["adc"].grid(row=ch + 1, column=2, sticky="w", padx=6)
            for c, k in ((3, "en"), (4, "rf"), (5, "lpf")):
                ttk.Checkbutton(g, variable=w[k]).grid(row=ch + 1, column=c, padx=6)
            ttk.Entry(g, textvariable=w["t"], width=7).grid(row=ch + 1, column=6, sticky="w", padx=6)
            w["cal"].grid(row=ch + 1, column=7, sticky="w", padx=6)
            self.ai_w.append(w)
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(8, 2))
        self.mbtn(bar, "Прочитать настройки", self.m_ai_cfg_read).pack(side="left")
        self.mbtn(bar, "Записать настройки", self.m_ai_cfg_write).pack(side="left", padx=6)
        lf = ttk.LabelFrame(f, text="Калибровка AI (таблица 801)", padding=10); lf.pack(fill="x", pady=(8, 0))
        self.ai_ch = tk.StringVar(value="AI0")
        ttk.Combobox(lf, textvariable=self.ai_ch, values=[f"AI{i}" for i in range(8)], width=6, state="readonly").pack(side="left")
        self.mbtn(lf, "Калибровать канал (4 и 20 мА)", self.m_ai_cal).pack(side="left", padx=6)
        self.mbtn(lf, "Сбросить канал", lambda: self.m_ai_reset(int(self.ai_ch.get()[2:]))).pack(side="left", padx=6)
        self.mbtn(lf, "Сбросить все", lambda: self.m_ai_reset(None)).pack(side="left")
        self.mbtn(lf, "Прочитать калибровку", self.m_ai_cal_read).pack(side="left", padx=6)
        self.mbtn(lf, "Восстановить из протокола…", lambda: self.m_cal_restore("ai")).pack(side="left", padx=6)
        self.note(f, "Калибровка канала: реле подключает калибратор к выбранному входу, CA71 подаёт 4 и 20 мА "
                     "(без CA71 программа попросит выставить ток), коды АЦП пишутся в таблицу, коды 16/18 мА — заводские.")

    def m_ai_all(self):
        self.m_ai_values(); self.m_ai_cfg_read(); self.m_ai_cal_read()

    def m_ai_values(self, quiet=False):
        def done(r):
            ma, adc = r
            for ch, w in enumerate(self.ai_w):
                w["ma"].configure(text=f"{ma[ch]:.3f}"); w["adc"].configure(text=str(adc[ch]))
        self.mboard(lambda d: (d.ai_ma(), d.ai_adc()), done, quiet=quiet)

    def m_ai_cfg_read(self):
        def done(rows):
            self.ai_cfg_raw = rows
            for w, r in zip(self.ai_w, rows):
                w["en"].set(bool(r[0])); w["rf"].set(bool(r[1])); w["lpf"].set(bool(r[2])); w["t"].set(str(r[3]))
        self.mboard(lambda d: d.ai_cfg_read(), done)

    def m_ai_cfg_write(self):
        if not getattr(self, "ai_cfg_raw", None):
            messagebox.showinfo("AI", "Сначала прочитайте настройки."); return
        try:
            rows = [[int(w["en"].get()), int(w["rf"].get()), int(w["lpf"].get()), int(w["t"].get())] for w in self.ai_w]
        except ValueError:
            messagebox.showwarning("AI", "T(ФНЧ) — целое число, мс"); return
        if not messagebox.askyesno("AI", "Записать настройки AI?"):
            return
        self.mboard(lambda d: (d.ai_cfg_write(rows), d.ai_cfg_read())[1],
                    lambda back: self.add_log("Настройки AI записаны — OK" if back == rows else f"!!! Настройки AI: плата вернула {back}"))

    def m_ai_cal_read(self):
        def done(t):
            for w, r in zip(self.ai_w, t):
                w["cal"].configure(text=" / ".join(map(str, r)))
        self.mboard(lambda d: d.ai_cal_read_all(), done)

    def m_ai_reset(self, ch):
        what = "всех каналов AI" if ch is None else f"AI{ch}"
        if not messagebox.askyesno("AI", f"Сбросить калибровку {what} до заводской?"): return
        hv = self.v["hv"].get()
        self.mboard(lambda d: d.ai_cal_reset(None if ch is None else [ch], hv),
                    lambda back: (self.add_log(f"Калибровка {what} сброшена до заводской — OK"), self.m_ai_cal_read()))

    def _runner(self, d):
        sim = self.sim.get()
        relay = self.instr("relay") or NoRelay(lambda p: self.ui.confirm(p))
        cal = self.instr("cal")
        cfg = dict(self.S)
        cfg.update(tol_percent=self.S["tol"], samples=2 if sim else self.S["samples"], hv=self.v["hv"].get(), sim=sim)
        if sim: cfg.update(ai_settle=0, ao_settle=0, reassert_settle=0, cal_settle=0)
        self.ui.stopped = False
        return Runner(d, relay, self.ui, cfg, cal)

    def m_ai_cal(self):
        ch = int(self.ai_ch.get()[2:])
        if not messagebox.askyesno("Калибровка AI", f"Калибровать AI{ch}?\n\nНа вход будут поданы 4 и 20 мА, "
                                   "коды АЦП запишутся в таблицу калибровки платы."): return
        def fn(d):
            r = self._runner(d)
            fac = d.ai_cal_factory(self.v["hv"].get())
            codes = {}
            for ma in (4.0, 20.0):
                r.set_current(ma); r.sw_ai(ch); r.reassert()
                time.sleep(float(r.cfg.get("ai_settle", 1.0)))
                vals = []
                for _ in range(r.samples):
                    vals.append(d.ai_adc()[ch]); time.sleep(0.05)
                codes[ma] = round(statistics.mean(vals))
                self.ui.log(f"[AI-кал вручную · AI{ch}] {ma:g} мА → код АЦП {codes[ma]}")
            r.relay.off()
            if r.cal: r.cal.source_off()
            table = d.ai_cal_read_all()
            table[ch] = [codes[4.0], fac[1], fac[2], codes[20.0]]
            d.ai_cal_write_all(table)
            return table[ch], d.ai_cal_read_all()[ch]
        def done(r):
            new, back = r
            self.add_log(f"AI{ch}: калибровка записана {new}" + (" — OK" if list(back) == list(new) else f" — !!! плата вернула {back}"))
            self.m_ai_cal_read()
        self.mboard(fn, done)

    # ---------------- AO ----------------
    def _m_ao(self, tab, f):
        g = ttk.Frame(f); g.pack(fill="x")
        for c, t in enumerate(("Выход", "Задать, мА", "", "В плате, мА", "CA71 (MEASURE), мА", "", "Калибровка: точка 1 / точка 2")):
            ttk.Label(g, text=t, style="Muted.TLabel").grid(row=0, column=c, sticky="w", padx=6)
        self.ao_w = []
        for ch in range(2):
            w = dict(set=tk.StringVar(value="4"), rd=ttk.Label(g, text="—", width=10), cal=ttk.Label(g, text="—", width=10),
                     pts=ttk.Label(g, text="—", width=22))
            ttk.Label(g, text=f"AO{ch}", font=("Segoe UI", 10, "bold")).grid(row=ch + 1, column=0, sticky="w", padx=6, pady=3)
            ttk.Entry(g, textvariable=w["set"], width=8).grid(row=ch + 1, column=1, sticky="w", padx=6)
            self.mbtn(g, "Записать", lambda ch=ch: self.m_ao_set(ch)).grid(row=ch + 1, column=2, padx=6)
            w["rd"].grid(row=ch + 1, column=3, sticky="w", padx=6)
            w["cal"].grid(row=ch + 1, column=4, sticky="w", padx=6)
            ttk.Button(g, text="Измерить", command=lambda ch=ch: self.m_ao_measure(ch)).grid(row=ch + 1, column=5, padx=6)
            w["pts"].grid(row=ch + 1, column=6, sticky="w", padx=6)
            self.ao_w.append(w)
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(10, 0))
        self.mbtn(bar, "Прочитать", self.m_ao_read, style="Accent.TButton").pack(side="left")
        self.mbtn(bar, "Калибровать AO0", lambda: self.m_ao_cal(0)).pack(side="left", padx=6)
        self.mbtn(bar, "Калибровать AO1", lambda: self.m_ao_cal(1)).pack(side="left")
        self.mbtn(bar, "Сбросить калибровку (4 / 19)", self.m_ao_reset).pack(side="left", padx=6)
        self.mbtn(bar, "Восстановить из протокола…", lambda: self.m_cal_restore("ao")).pack(side="left")
        self.note(f, "Калибровка: в выход пишется 4 и 19 мА, CA71 меряет, измеренное записывается в таблицу 851 "
                     "Если значение «не встало», программа задаёт другую точку и снова нужную.")
        lf = ttk.LabelFrame(f, text="Настройки AO (рег. 507, сырые слова)", padding=10); lf.pack(fill="x", pady=(8, 0))
        self.ao_cfg = [tk.StringVar() for _ in range(4)]
        for i, var in enumerate(self.ao_cfg):
            ttk.Label(lf, text=f"слово {i}:").pack(side="left")
            ttk.Entry(lf, textvariable=var, width=6).pack(side="left", padx=(2, 10))
        self.mbtn(lf, "Прочитать", self.m_ao_cfg_read).pack(side="left", padx=6)
        self.mbtn(lf, "Записать", self.m_ao_cfg_write).pack(side="left")

    def m_ao_cfg_read(self):
        self.mboard(lambda d: d.ao_cfg_read(), lambda r: [v.set(str(x)) for v, x in zip(self.ao_cfg, r)])

    def m_ao_read(self):
        def done(r):
            vals, pts = r
            for ch, w in enumerate(self.ao_w):
                w["rd"].configure(text=f"{vals[ch]:.4f}"); w["pts"].configure(text=f"{pts[ch][0]:.4f} / {pts[ch][1]:.4f}")
        self.mboard(lambda d: ([d.ao_get(0), d.ao_get(1)], d.ao_cal_read()), done)

    def m_ao_set(self, ch):
        try: ma = float(self.ao_w[ch]["set"].get().replace(",", "."))
        except ValueError: messagebox.showwarning("AO", "Введите ток, мА"); return
        def fn(d):
            d.ao_set(ch, ma); time.sleep(0.3); return d.ao_get(ch)
        self.mboard(fn, lambda v: (self.ao_w[ch]["rd"].configure(text=f"{v:.4f}"), self.add_log(f"AO{ch}: задано {ma:g} мА, в плате {v:.4f}")))

    def m_ao_measure(self, ch):
        def fn(cal):
            relay = self.instr("relay")
            if relay: relay.ao(ch)
            return cal.measure_ma(n=2)
        self.ibg("cal", fn, lambda v: self.ao_w[ch]["cal"].configure(text=f"{v:.4f}"))

    def m_ao_cal(self, ch):
        if not messagebox.askyesno("Калибровка AO", f"Калибровать AO{ch}? Калибратор CA71 должен быть в режиме MEASURE mA."): return
        def fn(d):
            r = self._runner(d)
            pts = d.ao_cal_read()
            pts[ch] = (4.0, 19.0); d.ao_cal_write(pts); time.sleep(0.5)
            m4 = r.measure_ao(ch, 4.0); m19 = r.measure_ao(ch, 19.0)
            self.ui.log(f"[AO-кал вручную · AO{ch}] задано 4 → {m4:.4f} мА, задано 19 → {m19:.4f} мА")
            pts[ch] = (round(m4, 4), round(m19, 4)); d.ao_cal_write(pts)
            d.ao_set(ch, 4.0); r.relay.off()
            return pts[ch], d.ao_cal_read()[ch]
        def done(res):
            new, back = res
            good = all(abs(a - b) < 1e-3 for a, b in zip(new, back))
            self.add_log(f"AO{ch}: калибровка записана {new}" + (" — OK" if good else f" — !!! плата вернула {back}"))
            self.m_ao_read()
        self.mboard(fn, done)

    def m_ao_reset(self):
        if not messagebox.askyesno("AO", "Записать заводские точки 4 / 19 мА для обоих выходов?"): return
        self.mboard(lambda d: d.ao_cal_write([(4.0, 19.0), (4.0, 19.0)]), lambda r: (self.add_log("Калибровка AO сброшена (4 / 19)"), self.m_ao_read()))

    def m_cal_restore(self, kind):
        """Записать в плату коды калибровки из прошлого протокола этого номера (.json рядом с протоколом),
        например после замены ПО или случайного сброса."""
        from mk201_labels import parse_label, LabelError
        code = self.v["protocol_number"].get().strip()
        try:
            num = parse_label(code, self.S["proto_prefix"])[0] if code else ""
        except LabelError:
            num = ""
        if not num:
            messagebox.showinfo("Калибровка", "На вкладке «Проверка» укажите номер изделия — по нему ищется протокол."); return
        path, data = self.latest_json(num, f"{kind}_cal")
        if not data:
            messagebox.showinfo("Калибровка", f"Для № {num} не найден протокол с калибровкой {kind.upper()} "
                                "(в папке протоколов этого номера)."); return
        import os
        if kind == "ai":
            table = [[int(x) for x in row] for row in data]
            text = "\n".join(f"AI{c}: {row}" for c, row in enumerate(table))
        else:
            table = [(float(p[0]), float(p[1])) for p in data]
            text = "\n".join(f"AO{c}: {p[0]:.4f} / {p[1]:.4f}" for c, p in enumerate(table))
        if not messagebox.askyesno("Восстановить калибровку", f"Записать в плату калибровку {kind.upper()} из\n"
                                   f"{os.path.basename(path)}?\n\n{text}"):
            return
        def fn(d):
            if kind == "ai":
                d.ai_cal_write_all(table); return d.ai_cal_read_all()
            d.ao_cal_write(table); return d.ao_cal_read()
        def done(back):
            if kind == "ai":
                good = [list(map(int, r)) for r in back] == table
            else:
                good = all(abs(a - b) < 1e-3 for p, q in zip(table, back) for a, b in zip(p, q))
            self.add_log(f"Калибровка {kind.upper()} восстановлена из {os.path.basename(path)}"
                         + (" — OK" if good else f" — !!! плата вернула {back}"))
            (self.m_ai_cal_read if kind == "ai" else self.m_ao_read)()
        self.mboard(fn, done)

    def m_ao_cfg_write(self):
        try: vals = [int(v.get()) for v in self.ao_cfg]
        except ValueError: messagebox.showwarning("AO", "Сначала прочитайте настройки (целые числа)"); return
        if not messagebox.askyesno("AO", "Записать настройки AO (рег. 507)?"): return
        self.mboard(lambda d: (d.ao_cfg_write(vals), d.ao_cfg_read())[1],
                    lambda back: self.add_log("Настройки AO записаны — OK" if list(back) == vals else f"!!! Настройки AO: плата вернула {back}"))

    # ---------------- DI / DO ----------------
    def _m_dio(self, tab, f):
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(0, 6))
        self.auto["dio"] = tk.BooleanVar(value=self.c.get("auto_dio", True))
        ttk.Checkbutton(bar, text="Автообновление", variable=self.auto["dio"]).pack(side="left")
        self.mbtn(bar, "Все DO вкл", lambda: self.m_do_write(0xFFFF)).pack(side="left", padx=(12, 4))
        self.mbtn(bar, "Все DO выкл", lambda: self.m_do_write(0)).pack(side="left")
        self.mbtn(bar, "Обновить", lambda: self.m_dio_values()).pack(side="left", padx=6)
        self.mpoll["dio"] = (tab, self.m_dio_values)
        lf = ttk.LabelFrame(f, text="Значения", padding=10); lf.pack(fill="x")
        self.lamps = {}
        cv = tk.Canvas(lf, width=16 * 46 + 70, height=120, bg=C_BG, highlightthickness=0); cv.pack(anchor="w")
        cv.create_text(4, 30, text="DI", anchor="w", font=("Segoe UI", 11, "bold"))
        cv.create_text(4, 88, text="DO", anchor="w", font=("Segoe UI", 11, "bold"))
        for i in range(16):
            x = 60 + i * 46
            self.lamps[("di", i)] = cv.create_oval(x, 18, x + 26, 44, fill=LAMP_OFF, outline="#9aa3b1")
            cv.create_text(x + 13, 54, text=str(i + 1), font=("Segoe UI", 8))
            o = cv.create_oval(x, 76, x + 26, 102, fill=LAMP_OFF, outline="#9aa3b1")
            self.lamps[("do", i)] = o
            cv.create_text(x + 13, 112, text=str(i + 1), font=("Segoe UI", 8))
            cv.tag_bind(o, "<Button-1>", lambda e, i=i: self.m_do_toggle(i))
            cv.tag_bind(o, "<Enter>", lambda e: cv.configure(cursor="hand2"))
            cv.tag_bind(o, "<Leave>", lambda e: cv.configure(cursor=""))
        self.lamp_cv = cv
        self.note(lf, "DI — зелёная = вход включён. DO — нажмите на лампочку, чтобы включить/выключить выход "
                      "(оранжевая = выход включён, по чтению из платы).")
        cfg = ttk.Frame(f); cfg.pack(fill="x", pady=(8, 0))
        bar = ttk.Frame(cfg); bar.pack(fill="x")
        self.mbtn(bar, "Прочитать настройки", self.m_dio_cfg_read).pack(side="left")
        self.mbtn(bar, "Записать настройки", self.m_dio_cfg_write).pack(side="left", padx=6)
        g = ttk.Frame(cfg); g.pack(fill="x", pady=(4, 0))
        for c, t in enumerate(("Канал", "DI Enable", "DI Инв.", "DI T(дреб)", "", "DO Enable", "DO Инв.")):
            ttk.Label(g, text=t, style="Muted.TLabel").grid(row=0, column=c, sticky="w", padx=6)
        self.dio_w = []
        for i in range(16):
            w = dict(di_en=tk.BooleanVar(), di_inv=tk.BooleanVar(), di_t=tk.StringVar(), do_en=tk.BooleanVar(), do_inv=tk.BooleanVar())
            ttk.Label(g, text=str(i + 1)).grid(row=i + 1, column=0, sticky="w", padx=6)
            ttk.Checkbutton(g, variable=w["di_en"]).grid(row=i + 1, column=1, padx=6)
            ttk.Checkbutton(g, variable=w["di_inv"]).grid(row=i + 1, column=2, padx=6)
            ttk.Entry(g, textvariable=w["di_t"], width=6).grid(row=i + 1, column=3, sticky="w", padx=6)
            ttk.Checkbutton(g, variable=w["do_en"]).grid(row=i + 1, column=5, padx=6)
            ttk.Checkbutton(g, variable=w["do_inv"]).grid(row=i + 1, column=6, padx=6)
            self.dio_w.append(w)

    def lamps_show(self, di, do):
        self.do_mask = do
        for i in range(16):
            self.lamp_cv.itemconfigure(self.lamps[("di", i)], fill=DI_ON if di >> i & 1 else LAMP_OFF)
            self.lamp_cv.itemconfigure(self.lamps[("do", i)], fill=DO_ON if do >> i & 1 else LAMP_OFF)

    def m_dio_values(self, quiet=False):
        self.mboard(lambda d: (d.di_read(), d.do_read()), lambda r: self.lamps_show(*r), quiet=quiet)

    def m_do_toggle(self, i):
        mask = self.do_mask ^ (1 << i)
        if not self.dio_psu_on and self.instr("psu") is not None:
            self.m_dio_power(True, then=lambda: self.m_do_write(mask))     # сначала 24 В на CH2, потом выход
        else:
            self.m_do_write(mask)

    def m_do_write(self, mask):
        def fn(d):
            d.do_write(mask); time.sleep(0.2); return d.di_read(), d.do_read()
        self.mboard(fn, lambda r: self.lamps_show(*r))

    def m_dio_cfg_read(self):
        def done(r):
            self.di_rows, self.do_rows = r
            for w, a, b in zip(self.dio_w, *r):
                w["di_en"].set(bool(a[1])); w["di_inv"].set(bool(a[2])); w["di_t"].set(str(a[3]))
                w["do_en"].set(bool(b[1])); w["do_inv"].set(bool(b[2]))
        self.mboard(lambda d: (d.di_cfg_read(), d.do_cfg_read()), done)

    def m_dio_cfg_write(self):
        if self.di_rows is None:
            messagebox.showinfo("DI / DO", "Сначала прочитайте настройки."); return
        try:
            di = [[a[0], int(w["di_en"].get()), int(w["di_inv"].get()), int(w["di_t"].get()), a[4]]
                  for w, a in zip(self.dio_w, self.di_rows)]
            do = [[b[0], int(w["do_en"].get()), int(w["do_inv"].get()), b[3], b[4]] for w, b in zip(self.dio_w, self.do_rows)]
        except ValueError:
            messagebox.showwarning("DI / DO", "T(дреб) — целое число"); return
        def fn(d):
            if di != self.di_rows: d.di_cfg_write(di)
            if do != self.do_rows: d.do_cfg_write(do)
            return d.di_cfg_read(), d.do_cfg_read()
        def done(r):
            ok = r[0] == di and r[1] == do
            self.add_log("Настройки DI/DO записаны — OK" if ok else "!!! Настройки DI/DO: плата вернула не то, что записано")
            self.di_rows, self.do_rows = r
        self.mboard(fn, done)

    # ---------------- Счётные входы ----------------
    def _m_ci(self, tab, f):
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(0, 6))
        self.auto["ci"] = tk.BooleanVar(value=self.c.get("auto_ci", True))
        ttk.Checkbutton(bar, text="Автообновление", variable=self.auto["ci"]).pack(side="left")
        self.mbtn(bar, "Сбросить все", lambda: self.m_ci_reset(None)).pack(side="left", padx=12)
        self.mpoll["ci"] = (tab, self.m_ci_values)
        self.note(f, "Режимы входа: 0 — Отключено, 1 — Счётчик, 2 — Частотомер.")
        g = ttk.Frame(f); g.pack(fill="x")
        for c, t in enumerate(("Вход", "Частота, Гц", "Счётчик", "", "Режим", "Период изм. частоты", "Антидребезг")):
            ttk.Label(g, text=t, style="Muted.TLabel").grid(row=0, column=c, sticky="w", padx=6)
        self.ci_w = []
        for ch in range(4):
            w = dict(f=ttk.Label(g, text="—", width=12), n=ttk.Label(g, text="—", width=12),
                     mode=tk.StringVar(), per=tk.StringVar(), bnc=tk.StringVar())
            ttk.Label(g, text=f"CI{ch + 1}", font=("Segoe UI", 10, "bold")).grid(row=ch + 1, column=0, sticky="w", padx=6, pady=3)
            w["f"].grid(row=ch + 1, column=1, sticky="w", padx=6); w["n"].grid(row=ch + 1, column=2, sticky="w", padx=6)
            self.mbtn(g, "Сбросить", lambda ch=ch: self.m_ci_reset([ch])).grid(row=ch + 1, column=3, padx=6)
            ttk.Combobox(g, textvariable=w["mode"], values=list(CI_MODES.values()), width=12, state="readonly").grid(row=ch + 1, column=4, padx=6)
            ttk.Entry(g, textvariable=w["per"], width=8).grid(row=ch + 1, column=5, sticky="w", padx=6)
            ttk.Entry(g, textvariable=w["bnc"], width=8).grid(row=ch + 1, column=6, sticky="w", padx=6)
            self.ci_w.append(w)
        bar = ttk.Frame(f); bar.pack(fill="x", pady=(8, 0))
        self.mbtn(bar, "Прочитать настройки", self.m_ci_cfg_read).pack(side="left")
        self.mbtn(bar, "Записать настройки", self.m_ci_cfg_write).pack(side="left", padx=6)

    def m_ci_values(self, quiet=False):
        def done(r):
            fr, cnt = r
            for w, a, b in zip(self.ci_w, fr, cnt):
                w["f"].configure(text=f"{a:.4f}"); w["n"].configure(text=str(b))
        self.mboard(lambda d: (d.ci_freq(), d.ci_counts()), done, quiet=quiet)

    def m_ci_reset(self, chs):
        def fn(d):
            try:
                d.ci_reset(chs)
            except Exception as e:
                if getattr(e, "code", None) == 2:
                    raise RuntimeError("плата не приняла сброс счётчиков (рег. 409 ×4, исключение 2)")
                raise
        self.mboard(fn, lambda r: (self.add_log("Счётчики сброшены" + ("" if chs is None else f": CI{chs[0] + 1}")),
                                                         self.m_ci_values()))

    def m_ci_cfg_read(self):
        def done(r):
            modes, per, bnc = r
            self.ci_has = (per is not None, bnc is not None)
            for i, w in enumerate(self.ci_w):
                w["mode"].set(CI_MODES.get(modes[i], str(modes[i])))
                w["per"].set(str(per[i]) if per is not None else "нет в МК201")
                w["bnc"].set(str(bnc[i]) if bnc is not None else "нет в МК201")
        self.mboard(lambda d: d.ci_cfg_read(), done)

    def m_ci_cfg_write(self):
        inv = {v: k for k, v in CI_MODES.items()}
        try:
            modes = [inv[w["mode"].get()] for w in self.ci_w]
            has_p, has_b = getattr(self, "ci_has", (False, False))
            per = [int(w["per"].get()) for w in self.ci_w] if has_p else None
            bnc = [int(w["bnc"].get()) for w in self.ci_w] if has_b else None
        except (KeyError, ValueError):
            messagebox.showwarning("Счётные входы", "Сначала прочитайте настройки; период и антидребезг — целые числа"); return
        if not messagebox.askyesno("Счётные входы", "Записать настройки счётных входов?"): return
        def fn(d):
            d.ci_cfg_write(modes=modes, bounce=bnc, period=per); return d.ci_cfg_read()
        def done(r):
            ok = list(r[0]) == modes and (per is None or list(r[1]) == per) and (bnc is None or list(r[2]) == bnc)
            self.add_log("Настройки счётных входов записаны — OK" if ok else f"!!! Счётные входы: плата вернула {r}")
        self.mboard(fn, done)

    # ---------------- Приборы ----------------
    def _m_instr(self, tab, f):
        self.note(f, "Ручное управление приборами стенда. Приборы подключаются на вкладке «Подключение» "
                     "(при запуске программы — сами). В режиме «Симулятор» работают имитации.")
        lf = ttk.LabelFrame(f, text="Калибратор Yokogawa CA71", padding=10); lf.pack(fill="x", pady=(0, 8))
        self.i_ma = tk.StringVar(value="4")
        ttk.Label(lf, text="SOURCE, мА:").pack(side="left")
        ttk.Entry(lf, textvariable=self.i_ma, width=8).pack(side="left", padx=4)
        ttk.Button(lf, text="Подать", command=self.i_cal_set).pack(side="left", padx=4)
        ttk.Button(lf, text="Выключить выход", command=lambda: self.ibg("cal", lambda c: c.source_off(),
                   lambda r: self.add_log("CA71: выход выключен"))).pack(side="left", padx=4)
        ttk.Button(lf, text="Измерить (MEASURE)", command=lambda: self.ibg("cal", lambda c: c.measure_ma(n=2),
                   lambda v: self.i_cal_lbl.configure(text=f"{v:.4f} мА"))).pack(side="left", padx=(16, 4))
        self.i_cal_lbl = ttk.Label(lf, text="—", font=("Segoe UI", 11, "bold")); self.i_cal_lbl.pack(side="left", padx=6)

        lf = ttk.LabelFrame(f, text="Блок реле (ESP32): куда подключён калибратор", padding=10); lf.pack(fill="x", pady=(0, 8))
        for ch in range(8):
            ttk.Button(lf, text=f"AI{ch}", width=5, command=lambda ch=ch: self.ibg("relay", lambda r: r.ai(ch),
                       lambda x: self.add_log(f"Реле: калибратор на AI{ch}"))).pack(side="left", padx=2)
        for ch in range(2):
            ttk.Button(lf, text=f"AO{ch}", width=5, command=lambda ch=ch: self.ibg("relay", lambda r: r.ao(ch),
                       lambda x: self.add_log(f"Реле: калибратор на AO{ch}"))).pack(side="left", padx=2)
        ttk.Button(lf, text="Все выкл", command=lambda: self.ibg("relay", lambda r: r.off(),
                   lambda x: self.add_log("Реле: все разомкнуты"))).pack(side="left", padx=(10, 2))

        lf = ttk.LabelFrame(f, text="Блок питания АКИП-1168", padding=10); lf.pack(fill="x", pady=(0, 8))
        r1 = ttk.Frame(lf); r1.pack(fill="x")
        ttk.Label(r1, text="CH1 — питание платы:", width=22).pack(side="left")
        ttk.Button(r1, text="Включить", command=lambda: self.board_power_cmd(True)).pack(side="left", padx=4)
        ttk.Button(r1, text="Выключить", command=lambda: self.board_power_cmd(False)).pack(side="left", padx=4)
        ttk.Button(r1, text="Измерить", command=lambda: self.ibg("psu", lambda p: p.measure_board(),
                   lambda r: self.i_ch1_lbl.configure(text=f"{r[0]:.3f} В, {r[1] * 1000:.1f} мА, {r[0] * r[1]:.2f} Вт"))
                   ).pack(side="left", padx=(16, 4))
        self.i_ch1_lbl = ttk.Label(r1, text="—", font=("Segoe UI", 11, "bold")); self.i_ch1_lbl.pack(side="left", padx=6)
        r2 = ttk.Frame(lf); r2.pack(fill="x", pady=(6, 0))
        self.i_v, self.i_a = tk.StringVar(value="24"), tk.StringVar(value=f"{self.S['psu_amps']:g}")
        ttk.Label(r2, text="CH2 — DO → DI:", width=22).pack(side="left")
        ttk.Label(r2, text="В:").pack(side="left")
        ttk.Entry(r2, textvariable=self.i_v, width=7).pack(side="left", padx=4)
        ttk.Label(r2, text="А:").pack(side="left", padx=(6, 0))
        ttk.Entry(r2, textvariable=self.i_a, width=6).pack(side="left", padx=4)
        ttk.Button(r2, text="Включить", command=self.i_psu_on).pack(side="left", padx=4)
        ttk.Button(r2, text="Выключить", command=lambda: self.ibg("psu", lambda p: p.off(),
                   lambda r: self.add_log("Блок питания: CH2 выключен"))).pack(side="left", padx=4)
        ttk.Button(r2, text="Измерить", command=lambda: self.ibg("psu", lambda p: p.measure(),
                   lambda r: self.i_psu_lbl.configure(text="—" if r[0] is None else f"{r[0]:.3f} В, {r[1] * 1000:.1f} мА"))).pack(side="left", padx=(16, 4))
        self.i_psu_lbl = ttk.Label(r2, text="—", font=("Segoe UI", 11, "bold")); self.i_psu_lbl.pack(side="left", padx=6)

        lf = ttk.LabelFrame(f, text="Генератор АКИП-3409/2, канал C1 (на счётные входы напрямую)", padding=10); lf.pack(fill="x")
        r1 = ttk.Frame(lf); r1.pack(fill="x")
        self.i_f, self.i_n = tk.StringVar(value="10"), tk.StringVar(value="300")
        self.i_wave = tk.StringVar(value="прямоугольник")         # 1.33: форма сигнала
        ttk.Label(r1, text="Форма:").pack(side="left")
        ttk.Combobox(r1, textvariable=self.i_wave, values=["прямоугольник", "синус"], width=14,
                     state="readonly").pack(side="left", padx=(4, 10))
        ttk.Label(r1, text="Частота, Гц:").pack(side="left")
        ttk.Entry(r1, textvariable=self.i_f, width=9).pack(side="left", padx=4)
        ttk.Label(r1, text="Импульсов:").pack(side="left", padx=(10, 0))
        ttk.Entry(r1, textvariable=self.i_n, width=9).pack(side="left", padx=4)
        ttk.Button(r1, text="Выдать пакет", command=self.i_gen_burst).pack(side="left", padx=4)
        ttk.Button(r1, text="Непрерывно (частотомер)", command=self.i_gen_cont).pack(side="left", padx=4)
        ttk.Button(r1, text="Стоп (снова Burst)", command=lambda: self.ibg("gen", lambda g: g.stop_continuous(),
                   lambda r: self.add_log("Генератор: непрерывный сигнал выключен, снова Burst"))).pack(side="left", padx=4)
        r2 = ttk.Frame(lf); r2.pack(fill="x", pady=(6, 0))
        ttk.Button(r2, text="Настроить Burst", command=self.i_gen_setup).pack(side="left")
        ttk.Label(r2, text="Запуск пакета:").pack(side="left", padx=(12, 0))
        ttk.Combobox(r2, textvariable=self.v["gen_trig"], values=list(Generator.TRIGGERS), width=24,
                     state="readonly").pack(side="left", padx=4)
        ttk.Button(r2, text="Отправить только запуск", command=self.i_gen_trig).pack(side="left", padx=4)
        ttk.Button(r2, text="Спросить настройки", command=lambda: self.ibg("gen", lambda g: (g.query("C1:BSWV?"), g.query("C1:BTWV?"),
                   g.query("C1:OUTP?")), lambda r: self.add_log("Генератор: " + " | ".join(r)))).pack(side="left", padx=4)
        self.note(f, "Для проверки: на вкладке «Счётные входы» поставьте режим «Счётчик», выдайте пакет и сравните прирост "
                     "с числом импульсов. Больше, чем «Импульсов в пакете, не более» (Настройки), программа выдаёт несколькими пакетами.")

    def _num(self, var, name):
        try: return float(var.get().replace(",", "."))
        except ValueError: messagebox.showwarning("Приборы", f"Неверное значение: {name}"); return None

    def i_cal_set(self):
        ma = self._num(self.i_ma, "ток")
        if ma is None: return
        self.ibg("cal", lambda c: c.set_source(ma, settle=0.5), lambda r: self.add_log(f"CA71: подано {ma:g} мА"))

    def i_psu_on(self):
        v, a = self._num(self.i_v, "напряжение"), self._num(self.i_a, "ток")
        if v is None or a is None: return
        if v > 32:
            messagebox.showwarning("Блок питания", "Не больше 32 В"); return
        self.ibg("psu", lambda p: p.set(v, a), lambda r: self.add_log(f"Блок питания: CH2 = {v:g} В, ограничение {a:g} А, выход включён"))

    def i_gen_setup(self):
        amp, wave = float(self.S["gen_amp"]), self.i_wave.get()
        self.add_log(f"Генератор: настройка — {wave} {amp:g} Vpp, смещение 0, Hi-Z, Burst · N Cycle · Manual, выход вкл")
        self.ibg("gen", lambda g: g.setup(amp, wave), lambda r: self.add_log("Генератор: настройка отправлена — проверьте экран прибора"))

    def i_gen_cont(self):
        fr = self._num(self.i_f, "частота")
        if fr is None: return
        wave = self.i_wave.get()
        self.ibg("gen", lambda g: (g.set_wave(wave), g.continuous(fr)),
                 lambda r: self.add_log(f"Генератор: непрерывно {wave} {fr:g} Гц (для режима «Частотомер»)"))

    def i_gen_trig(self):
        cmd = self.v["gen_trig"].get()
        if cmd == MANUAL_TRIG:
            messagebox.showinfo("Генератор", "Выбран ручной запуск — нажмите Trig на приборе."); return
        self.save()
        self.ibg("gen", lambda g: g.trigger(cmd), lambda r: self.add_log(f"Генератор: отправлено «{cmd}» — смотрите, пошёл ли пакет"))

    def i_gen_burst(self):
        fr, n = self._num(self.i_f, "частота"), self._num(self.i_n, "количество")
        if None in (fr, n): return
        method, mx = self.v["gen_method"].get() or "burst", int(self.S["gen_burst_max"])
        trig = self.v["gen_trig"].get() or "C1:BTWV MTRIG"
        wave = self.i_wave.get()
        self.add_log(f"Генератор: пакет {int(n)} импульсов {fr:g} Гц, {wave} (≈ {n / fr:.1f} с), способ {method}, запуск «{trig}»")
        self.ibg("gen", lambda g: g.set_wave(wave) or g.pulses(fr, int(n), burst_max=mx, method=method, trig=trig,
                                          ask=lambda t: self.ui.confirm(t) or (_ for _ in ()).throw(RuntimeError("отменено"))),
                 lambda r: self.add_log("Генератор: пакет выдан"))

    # ---------------- Команда вручную ----------------
    TARGETS = ("Плата (Modbus RTU, hex — CRC добавится)", "Блок питания (SCPI)", "Генератор (SCPI)")

    def _m_cmd(self, tab, f):
        self.note(f, "Отправить одну команду вручную — для проверки и отладки. Плата: байты в hex без CRC, "
                     "например «01 65 00 19 00 02» — чтение DI (функция 101, рег. 25, 2 слова). "
                     "Приборы: команда SCPI; если в ней есть «?», ждём ответ. Всё видно в журнале.")
        row = ttk.Frame(f); row.pack(fill="x", pady=(0, 6))
        self.cmd_target = tk.StringVar(value=self.TARGETS[0])
        ttk.Combobox(row, textvariable=self.cmd_target, values=self.TARGETS, width=40, state="readonly").pack(side="left")
        self.cmd_hist = list(self.c.get("manual_cmd", []))
        self.cmd_text = tk.StringVar(value=self.cmd_hist[-1] if self.cmd_hist else "01 65 00 19 00 02")
        self.cmd_cb = ttk.Combobox(row, textvariable=self.cmd_text, values=list(reversed(self.cmd_hist)), width=48)
        self.cmd_cb.pack(side="left", padx=6)
        self.cmd_cb.bind("<Return>", lambda e: self.m_cmd_send())
        ttk.Button(row, text="Отправить", style="Accent.TButton", command=self.m_cmd_send).pack(side="left")
        self.cmd_res = tk.Text(f, height=8, font=("Consolas", 10), wrap="word", relief="flat",
                               highlightthickness=1, highlightbackground=C_LINE)
        self.cmd_res.pack(fill="both", expand=True)

    def _cmd_out(self, text):
        try:
            self.cmd_res.insert("end", time.strftime("%H:%M:%S  ") + text + "\n"); self.cmd_res.see("end")
        except Exception:
            pass
        self.add_log("Команда вручную: " + text)

    def m_cmd_send(self):
        text = self.cmd_text.get().strip()
        if not text: return
        if text in self.cmd_hist: self.cmd_hist.remove(text)
        self.cmd_hist.append(text); self.cmd_hist = self.cmd_hist[-30:]
        self.cmd_cb.configure(values=list(reversed(self.cmd_hist))); self.save()
        t = self.TARGETS.index(self.cmd_target.get()) if self.cmd_target.get() in self.TARGETS else 0
        if t == 0:
            try:
                data = bytes(int(x, 16) for x in text.replace(",", " ").replace("0x", "").split())
            except ValueError:
                messagebox.showwarning("Команда вручную", "Байты в hex через пробел, например: 01 65 00 19 00 02"); return
            if len(data) < 2:
                messagebox.showwarning("Команда вручную", "Нужно минимум 2 байта: адрес и функция"); return
            self._cmd_out("→ " + " ".join(f"{b:02X}" for b in data) + " (+CRC)")
            self.mboard(lambda d: d.raw(data), lambda r: self._cmd_out("← " + (" ".join(f"{b:02X}" for b in r) if r else "нет ответа")))
        else:
            name = "psu" if t == 1 else "gen"
            q = "?" in text
            self._cmd_out(f"→ {text}")
            self.ibg(name, lambda dev: dev.query(text) if q else (dev.write(text), "отправлено")[1],
                     lambda r: self._cmd_out(f"← {r}"))
