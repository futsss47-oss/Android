"""MK201 Сниффер — «прослушка» обмена оригинального конфигуратора с платой через com0com.

Схема:  Конфигуратор -> COM20 ==(com0com)== COM21 <- этот сниффер -> COM63 (Service Port платы)
Сниффер пересылает байты в обе стороны без задержки, собирает кадры Modbus, расшифровывает их
и пишет в журнал вместе с вашими метками («нажал Загрузить в устройство» и т.п.).

Файлы журнала (рядом с программой):
  sniff_ДАТА_ВРЕМЯ.log      — то, что видно на экране + метки (без повторов периодического опроса)
  sniff_ДАТА_ВРЕМЯ_raw.log  — абсолютно все кадры целиком
"""
import os, queue, sys, threading, time, tkinter as tk
from tkinter import ttk, messagebox

try:
    import serial
    import serial.tools.list_ports as lp
    serial.Serial
except Exception as e:
    r = tk.Tk(); r.withdraw()
    messagebox.showerror("MK201 Сниффер", f"Не загружен pyserial ({e}).\nКоманда:\n\"{sys.executable}\" -m pip install pyserial")
    sys.exit(1)

from modbus_decode import describe_request, describe_response, hx, crc_ok

APPDIR = os.path.dirname(os.path.abspath(sys.argv[0]))
MARKS = ["Подключился", "Обновление ПО: Загрузить", "Сист. настройки: Загрузить в устройство",
         "Сист. настройки: Считать", "Время: Из системы", "Время: Записать", "Время: Сравнить с системным",
         "Серийный: Записать", "MAC: Записать", "Серийный/MAC: Считать",
         "AI: подал 4 мА", "AI: кнопка 4 мА", "AI: подал 20 мА", "AI: кнопка 20 мА", "AI: Записать калибровку",
         "AO: кнопка Все", "AO: Записать калибровку", "AO: задал значение, Записать", "AO: ввёл точки, Записать"]
POLL_FC = (3, 4, 101, 0x51)
BG, PANEL, BORDER, TITLE = "#F0F2F5", "#F7F9FB", "#D3D9E1", "#1565C0"


def req_len(b):
    """Ожидаемая длина запроса по первым байтам (None — неизвестно, ждём CRC + паузу)."""
    if len(b) < 2: return None
    fc = b[1]
    if fc in (3, 4, 6, 101): return 8
    if fc in (16, 100): return 9 + b[6] if len(b) >= 7 else None
    return {0x50: 256, 0x51: 4, 0x52: 5, 0x53: 10, 0x55: 14}.get(fc)


def resp_len(b):
    if len(b) < 2: return None
    fc = b[1]
    if fc & 0x80: return 5
    if fc in (3, 4, 101): return 5 + b[2] if len(b) >= 3 else None
    if fc in (6, 16, 100): return 8
    return {0x50: 5, 0x52: 5, 0x53: 5, 0x51: 10, 0x55: 10}.get(fc)


class Framer:
    """Собирает поток байт одного направления в кадры."""
    def __init__(self, lenfn, out, tag):
        self.buf, self.last, self.lenfn, self.out, self.tag = bytearray(), 0.0, lenfn, out, tag

    def feed(self, data, t):
        self.buf += data; self.last = t
        while True:
            n = self.lenfn(self.buf)
            if n and len(self.buf) >= n:
                self.out(self.tag, bytes(self.buf[:n]), t); del self.buf[:n]
            else:
                break

    def tick(self, now):
        if self.buf and now - self.last > 0.06:          # пауза — отдаём, что накопилось
            self.out(self.tag, bytes(self.buf), self.last); self.buf.clear()


class Sniffer:
    def __init__(self):
        self.root = tk.Tk(); self.root.title("MK201 Сниффер — запись обмена конфигуратора с платой")
        self.root.geometry("1150x800"); self.root.configure(bg=BG)
        st = ttk.Style(self.root); st.theme_use("clam")
        st.configure(".", font=("Segoe UI", 10), background=PANEL)
        st.configure("TButton", background="#E3E7ED", borderwidth=0, padding=(10, 5))
        st.map("TButton", background=[("active", "#D5DBE3")])
        st.configure("Accent.TButton", background="#0B63D8", foreground="white", font=("Segoe UI Semibold", 10))
        st.map("Accent.TButton", background=[("active", "#0A52B3")])
        st.configure("Mark.TButton", padding=(6, 3), font=("Segoe UI", 9))
        self.q = queue.Queue(); self.running = False; self.ports = []
        self.pending = None; self.seen = {}; self.hidden = 0; self.nframes = 0
        self.v_cfg = tk.StringVar(value="COM21"); self.v_dev = tk.StringVar(value="COM63")
        self.v_baud = tk.StringVar(value="115200"); self.v_show_poll = tk.BooleanVar(value=False)
        self.v_mark = tk.StringVar()
        self.build()
        self.root.after(50, self.pump)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

    # ---------- окно ----------
    def sec(self, title):
        tk.Label(self.root, text=title, bg=BG, fg=TITLE, font=("Segoe UI Semibold", 10)).pack(anchor="w", padx=14, pady=(8, 2))
        p = tk.Frame(self.root, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        p.pack(fill="x", padx=14); return p

    def build(self):
        p = self.sec("Подключение (мост через com0com)")
        r = tk.Frame(p, bg=PANEL); r.pack(fill="x", padx=10, pady=8)
        for lbl, var, w in (("Порт для конфигуратора (вторая половина пары com0com):", self.v_cfg, 8),
                            ("Порт платы (Service Port):", self.v_dev, 8), ("Скорость:", self.v_baud, 8)):
            tk.Label(r, text=lbl, bg=PANEL).pack(side="left", padx=(0, 4))
            ttk.Entry(r, textvariable=var, width=w).pack(side="left", padx=(0, 12))
        self.btn = ttk.Button(r, text="Старт моста", style="Accent.TButton", command=self.toggle); self.btn.pack(side="left")
        ports = ", ".join(f"{x.device} ({x.description})" for x in lp.comports()) or "не найдено"
        tk.Label(p, text="Порты в системе: " + ports, bg=PANEL, fg="#6B7280", font=("Segoe UI", 9),
                 wraplength=1080, justify="left").pack(anchor="w", padx=12, pady=(0, 8))

        p = self.sec("Метки (что вы сейчас нажимаете в конфигураторе)")
        r = tk.Frame(p, bg=PANEL); r.pack(fill="x", padx=10, pady=(8, 4))
        e = ttk.Entry(r, textvariable=self.v_mark, width=80); e.pack(side="left", padx=(0, 8)); e.bind("<Return>", lambda ev: self.mark())
        ttk.Button(r, text="Добавить метку (Enter)", command=self.mark).pack(side="left")
        g = tk.Frame(p, bg=PANEL); g.pack(fill="x", padx=10, pady=(0, 8))
        for i, m in enumerate(MARKS):
            ttk.Button(g, text=m, style="Mark.TButton", command=lambda m=m: self.mark(m)).grid(row=i // 5, column=i % 5, sticky="ew", padx=2, pady=2)
        for c in range(5): g.columnconfigure(c, weight=1)

        hr = tk.Frame(self.root, bg=BG); hr.pack(fill="x", padx=14, pady=(8, 2))
        tk.Label(hr, text="Обмен  (→ конфигуратор пишет в плату,  ← плата отвечает)", bg=BG, fg=TITLE,
                 font=("Segoe UI Semibold", 10)).pack(side="left")
        ttk.Checkbutton(hr, text="Показывать периодический опрос", variable=self.v_show_poll).pack(side="right")
        box = tk.Frame(self.root, bg="#FFFFFF", highlightbackground=BORDER, highlightthickness=1)
        box.pack(fill="both", expand=True, padx=14)
        self.txt = tk.Text(box, font=("Consolas", 9), wrap="word", relief="flat", padx=8, pady=6, bg="#FFFFFF")
        sb = ttk.Scrollbar(box, command=self.txt.yview); self.txt.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y"); self.txt.pack(fill="both", expand=True)
        for tag, col in (("tx", "#0B63D8"), ("rx", "#1E7A46"), ("mark", "#C25E00"), ("err", "#D32F2F"), ("dim", "#8A93A2")):
            self.txt.tag_configure(tag, foreground=col)
        self.txt.tag_configure("mark", font=("Consolas", 10, "bold"))
        self.status = tk.Label(self.root, text="Мост не запущен", bg=BG, fg="#6B7280", anchor="w")
        self.status.pack(fill="x", padx=14, pady=(4, 10))

    # ---------- журнал ----------
    def write(self, line, tag=None, raw_only=False):
        ts = time.strftime("%H:%M:%S.") + f"{int(time.time() * 1000) % 1000:03d}"
        full = f"{ts} {line}"
        try:
            if self.f_raw: self.f_raw.write(full + "\n")
            if not raw_only and self.f_log: self.f_log.write(full + "\n")
        except Exception:
            pass
        if not raw_only:
            self.txt.insert("end", full + "\n", tag or ())
            if int(self.txt.index("end").split(".")[0]) > 6000: self.txt.delete("1.0", "1000.0")
            self.txt.see("end")

    def mark(self, text=None):
        t = (text or self.v_mark.get()).strip()
        if not t: return
        if text and self.v_mark.get().strip(): t += " — " + self.v_mark.get().strip()
        self.v_mark.set("")
        self.write(f"######## МЕТКА: {t} ########", "mark")
        for f in (self.f_log, self.f_raw):
            try: f and f.flush()
            except Exception: pass

    # ---------- мост ----------
    def toggle(self):
        if self.running: self.stop(); return
        try:
            baud = int(self.v_baud.get())
            a = serial.Serial(self.v_cfg.get().strip(), baud, timeout=0)
            try: b = serial.Serial(self.v_dev.get().strip(), baud, timeout=0)
            except Exception: a.close(); raise
        except Exception as e:
            messagebox.showerror("MK201 Сниффер", f"Не удалось открыть порт:\n{e}"); return
        self.ports = [a, b]; self.running = True
        stamp = time.strftime("%Y%m%d_%H%M%S")
        self.path = os.path.join(APPDIR, f"sniff_{stamp}.log")
        self.f_log = open(self.path, "w", encoding="utf-8", buffering=1)
        self.f_raw = open(os.path.join(APPDIR, f"sniff_{stamp}_raw.log"), "w", encoding="utf-8", buffering=1)
        self.write(f"Мост запущен: конфигуратор<->{a.port}  |  {b.port}<->плата, {baud} бод")
        self.fr = {"tx": Framer(req_len, self.on_frame, "tx"), "rx": Framer(resp_len, self.on_frame, "rx")}
        threading.Thread(target=self.fwd, args=(a, b, "tx"), daemon=True).start()
        threading.Thread(target=self.fwd, args=(b, a, "rx"), daemon=True).start()
        self.btn.configure(text="Стоп моста")

    def fwd(self, src, dst, tag):
        while self.running:
            try:
                n = src.in_waiting
                data = src.read(n or 1)
                if data:
                    dst.write(data)                     # пересылаем сразу, до любой обработки
                    self.q.put((tag, data, time.time()))
                else:
                    time.sleep(0.0005)
            except Exception as e:
                self.q.put(("err", str(e).encode(), time.time())); break

    def stop(self):
        self.running = False; time.sleep(0.05)
        for p in self.ports:
            try: p.close()
            except Exception: pass
        self.write("Мост остановлен. Журнал: " + self.path)
        for f in (self.f_log, self.f_raw):
            try: f.close()
            except Exception: pass
        self.f_log = self.f_raw = None
        self.btn.configure(text="Старт моста")

    f_log = f_raw = None

    def pump(self):
        try:
            for _ in range(2000):
                tag, data, t = self.q.get_nowait()
                if tag == "err": self.write("ОШИБКА ПОРТА: " + data.decode(errors="replace"), "err"); self.stop(); break
                self.fr[tag].feed(data, t)
        except queue.Empty:
            pass
        if self.running:
            now = time.time()
            for f in self.fr.values(): f.tick(now)
            if self.pending and now - self.pending[1] > 1.5:
                self.show_pair(self.pending[0], None); self.pending = None
        self.status.configure(text=(f"Кадров: {self.nframes}   скрыто повторов опроса: {self.hidden}   журнал: {self.path}"
                                    if self.running else "Мост не запущен"))
        self.root.after(40, self.pump)

    # ---------- кадры ----------
    def on_frame(self, tag, frame, t):
        self.nframes += 1
        if tag == "tx":
            if self.pending: self.show_pair(self.pending[0], None)
            self.pending = (frame, t)
        else:
            req = self.pending[0] if self.pending else None
            self.pending = None
            self.show_pair(req, frame)

    def show_pair(self, req, resp):
        rq = describe_request(req) if req else "(ответ без запроса)"
        rs = describe_response(resp, req) if resp else "НЕТ ОТВЕТА"
        poll = req is not None and len(req) > 1 and req[1] in POLL_FC
        lim = 40 if req and len(req) > 1 and req[1] == 0x50 else None
        raw = []
        if req: raw.append(f"→ {hx(req)}\n      {rq}")
        if resp: raw.append(f"← {hx(resp)}\n      {rs}")
        else: raw.append("← НЕТ ОТВЕТА")
        self.write("\n".join(raw), raw_only=True)
        if poll and not self.v_show_poll.get() and resp is not None:
            key = bytes(req)
            if self.seen.get(key) == bytes(resp):
                self.hidden += 1; return
            self.seen[key] = bytes(resp)
        bad = (resp is None) or (resp and (resp[1] & 0x80)) or ("ОШИБКА CRC" in rs)
        if req: self.write(f"→ {hx(req, lim)}   | {rq}", "tx")
        self.write(f"← {hx(resp) if resp else ''}   | {rs}", "err" if bad else "rx")

    def close(self):
        if self.running: self.stop()
        self.root.destroy()


if __name__ == "__main__":
    Sniffer().root.mainloop()
