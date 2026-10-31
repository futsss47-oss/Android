#!/usr/bin/env python3
"""
stand_gui - окно мини-стенда одиночной проверки MIB-211 / MIB-212 (Tkinter).

Запуск:  python stand_gui.py
Рядом должны лежать модули MIB_Calibrator (algorithm, auto_logic, ca71, mk531, simulation,
xlsx_patch) и модули стенда (stand, check_logic). Шаблон протокола (ШАБЛОН*.xlsx) - по желанию:
без него сохраняется отчёт .csv.
"""
import configparser
import glob
import os
import queue
import sys
import threading
import time
import traceback
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from algorithm import channels_for
from check_logic import LOAD_LABEL, MODE_CAL, MODE_CHECK, StandConfig, StandDevices, StandSequencer
from xlsx_patch import read_list

try:
    import winsound
except ImportError:  # не Windows
    winsound = None

BASE = (os.path.dirname(sys.executable) if getattr(sys, "frozen", False)
        else os.path.dirname(os.path.abspath(__file__)))
INI = os.path.join(BASE, "stand.ini")
OPERATORS = ["Мухаметдинов Р.Н.", "Насибуллин А.И."]
PRODUCTS = ["MIB-211", "MIB-212"]
ROW_LABELS = ["0", "4", "8", "12", "16", "20", LOAD_LABEL]

BG, PANEL, FIELD = "#e6eaf0", "#f6f8fa", "#ffffff"
FG, DIM = "#1f2328", "#6e7781"
ACC, OK, BAD, WARN = "#0969da", "#1a7f37", "#cf222e", "#9a6700"
BTN, BTN_ACTIVE, BTN_OFF = "#e1e5ea", "#d0d7de", "#eef1f4"

STATES = {
    "idle": ("Установите изделие и нажмите СТАРТ", DIM),
    "precheck": ("Проверка приборов...", ACC),
    "warmup1": ("Канал 1: прогрев под током 20 мА", WARN),
    "warmup2": ("Канал 2: прогрев под током 20 мА", WARN),
    "cal1": ("Калибровка КАНАЛА 1", ACC),
    "cal2": ("Калибровка КАНАЛА 2", ACC),
    "chk1": ("Проверка КАНАЛА 1", ACC),
    "chk2": ("Проверка КАНАЛА 2", ACC),
    "selftest": ("Самоконтроль стенда...", ACC),
    "self_ok": ("Самоконтроль стенда: PASS", OK),
    "self_fail": ("Самоконтроль стенда: FAIL", BAD),
    "done_ok": ("ГОДЕН (PASS) - снимите изделие", OK),
    "done_fail": ("НЕ ГОДЕН (FAIL)", BAD),
    "stopped": ("Остановлено, изделие обесточено", WARN),
    "error": ("ОШИБКА, изделие обесточено", BAD),
}


def log_error(text: str):
    try:
        with open(os.path.join(BASE, "error.log"), "a", encoding="utf-8") as fh:
            fh.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")
    except OSError:
        pass


class GuiIO:
    """Мост рабочий поток -> окно: всё через очередь, виджеты трогает только главный поток."""

    def __init__(self):
        self.q = queue.Queue()

    def log(self, text):
        self.q.put(("log", text))

    def state(self, name):
        self.q.put(("state", name))

    def timer(self, remaining, total):
        self.q.put(("timer", remaining, total))

    def point(self, ch, label, meas, err, ok):
        self.q.put(("point", ch, label, meas, err, ok))

    def supply(self, ma, volts):
        self.q.put(("supply", ma, volts))

    def done(self, path, lines, ok):
        self.q.put(("done", path, lines, ok))

    def finished(self):
        self.q.put(("end",))


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Мини-стенд MIB-211 / MIB-212 - одиночная проверка")
        self.geometry("1100x900")
        self.minsize(980, 780)
        self.configure(bg=BG)
        self.io = GuiIO()
        self.dev = StandDevices()
        self.seq = None
        self.protocol_path = ""
        self.running = False
        self._style()
        self._vars()
        self._build()
        self._load_ini()
        self._reload_lists()
        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.after(100, self._poll)
        self.after(300, self._poll_stand)

    def report_callback_exception(self, exc, val, tb):
        log_error("".join(traceback.format_exception(exc, val, tb)))
        try:
            self.write(f"ОШИБКА ПРОГРАММЫ: {val} (подробности в error.log)")
        except Exception:
            pass

    # ------------------------------ оформление ------------------------------
    def _style(self):
        s = ttk.Style(self)
        s.theme_use("clam")
        s.configure(".", background=PANEL, foreground=FG, fieldbackground=FIELD,
                    bordercolor="#c9d1d9", lightcolor=PANEL, darkcolor=PANEL, font=("Segoe UI", 10))
        s.configure("TFrame", background=PANEL)
        s.configure("TLabel", background=PANEL, foreground=FG)
        s.configure("TLabelframe", background=PANEL, bordercolor="#c9d1d9")
        s.configure("TLabelframe.Label", background=PANEL, foreground=ACC, font=("Segoe UI", 10, "bold"))
        s.configure("TButton", background=BTN, foreground=FG, padding=6, borderwidth=1, bordercolor="#c9d1d9")
        s.map("TButton", background=[("active", BTN_ACTIVE), ("disabled", BTN_OFF)],
              foreground=[("disabled", "#8c959f")])
        s.configure("Accent.TButton", background=ACC, foreground="white", font=("Segoe UI", 11, "bold"), padding=8)
        s.map("Accent.TButton", background=[("active", "#218bff"), ("disabled", BTN_OFF)],
              foreground=[("disabled", "#8c959f")])
        s.configure("TEntry", fieldbackground=FIELD, foreground=FG, insertcolor=FG)
        s.configure("TCombobox", fieldbackground=FIELD, background=BTN, foreground=FG, arrowcolor=FG)
        s.map("TCombobox", fieldbackground=[("readonly", FIELD)], foreground=[("readonly", FG)])
        s.configure("TSpinbox", fieldbackground=FIELD, foreground=FG, arrowcolor=FG, insertcolor=FG)
        s.configure("TCheckbutton", background=PANEL, foreground=FG)
        s.configure("TRadiobutton", background=PANEL, foreground=FG)
        s.configure("Treeview", background=FIELD, fieldbackground=FIELD, foreground=FG, rowheight=24, borderwidth=0)
        s.configure("Treeview.Heading", background="#dde3ea", foreground=FG, font=("Segoe UI", 10, "bold"))
        s.configure("Horizontal.TProgressbar", troughcolor="#d8dee4", background=ACC, borderwidth=0)

    def _vars(self):
        self.v_stand = tk.StringVar(value="")
        self.v_gate = tk.StringVar(value="COM3")
        self.v_ca71 = tk.StringVar(value="COM2")
        self.v_sim = tk.BooleanVar(value=False)
        self.v_serial = tk.StringVar()
        self.v_oper = tk.StringVar(value=OPERATORS[0])
        self.v_prod = tk.StringVar(value=PRODUCTS[1])
        self.v_mode = tk.StringVar(value=MODE_CHECK)
        tpl = sorted(glob.glob(os.path.join(BASE, "ШАБЛОН*.xlsx")))
        self.v_tpl = tk.StringVar(value=tpl[0] if tpl else "")
        self.v_out = tk.StringVar(value=os.path.join(BASE, "Протоколы"))
        self.v_min = tk.StringVar(value="1")
        self.v_tol = tk.StringVar(value="0.08")
        self.v_imin = tk.StringVar(value="10")
        self.v_imax = tk.StringVar(value="150")
        # ручное управление стендом
        self.v_pwr = tk.BooleanVar(value=False)
        self.v_in = tk.StringVar(value="0")
        self.v_outch = tk.StringVar(value="1")
        self.v_load = tk.BooleanVar(value=False)

    # -------------------------------- построение --------------------------------
    def _build(self):
        root = ttk.Frame(self, padding=10)
        root.pack(fill="both", expand=True)
        root.columnconfigure(0, weight=1)
        root.rowconfigure(4, weight=1)

        f = ttk.LabelFrame(root, text="Подключение", padding=8)
        f.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        col = 0
        self.leds = {}
        for title, var, key, hint in (("Стенд:", self.v_stand, "stand", "пусто - найти"),
                                      ("CA71:", self.v_ca71, "ca71", ""),
                                      ("MK-531:", self.v_gate, "gate", "пусто - без шлюза")):
            ttk.Label(f, text=title).grid(row=0, column=col, sticky="w", padx=(12 if col else 0, 0))
            ttk.Entry(f, textvariable=var, width=8).grid(row=0, column=col + 1, padx=4)
            self.leds[key] = ttk.Label(f, text="●", foreground=BAD)
            self.leds[key].grid(row=0, column=col + 2)
            if hint:
                ttk.Label(f, text=hint, foreground=DIM, font=("Segoe UI", 8)).grid(row=1, column=col + 1, sticky="w")
            col += 3
        self.b_conn = ttk.Button(f, text="Подключить", command=self.connect)
        self.b_conn.grid(row=0, column=col, padx=(16, 4))
        self.b_disc = ttk.Button(f, text="Отключить", command=self.disconnect)
        self.b_disc.grid(row=0, column=col + 1)
        ttk.Checkbutton(f, text="Имитация", variable=self.v_sim).grid(row=0, column=col + 2, padx=12)

        f = ttk.LabelFrame(root, text="Изделие и режим", padding=8)
        f.grid(row=1, column=0, sticky="ew", pady=(0, 8))
        f.columnconfigure(6, weight=1)
        ttk.Label(f, text="Изделие:").grid(row=0, column=0, sticky="w")
        self.cb_prod = ttk.Combobox(f, textvariable=self.v_prod, values=PRODUCTS, width=12, state="readonly")
        self.cb_prod.grid(row=0, column=1, padx=6, sticky="w")
        self.cb_prod.bind("<<ComboboxSelected>>", lambda e: self._on_product())
        ttk.Label(f, text="Серийный №:").grid(row=0, column=2, sticky="e", padx=(10, 0))
        self.e_serial = ttk.Entry(f, textvariable=self.v_serial, width=22, font=("Consolas", 12))
        self.e_serial.grid(row=0, column=3, padx=6, sticky="w")
        self.e_serial.bind("<Return>", lambda e: self.start())  # сканер штрихкода присылает Enter
        ttk.Label(f, text="Оператор:").grid(row=0, column=4, sticky="e", padx=(10, 0))
        self.cb_oper = ttk.Combobox(f, textvariable=self.v_oper, values=OPERATORS, width=22)
        self.cb_oper.grid(row=0, column=5, padx=6, sticky="w")

        mf = ttk.Frame(f)
        mf.grid(row=1, column=0, columnspan=6, sticky="w", pady=(6, 0))
        ttk.Label(mf, text="Режим:").pack(side="left")
        ttk.Radiobutton(mf, text="Проверка (коэффициенты не меняются)", value=MODE_CHECK,
                        variable=self.v_mode).pack(side="left", padx=8)
        ttk.Radiobutton(mf, text="Калибровка + проверка (нужен MK-531)", value=MODE_CAL,
                        variable=self.v_mode).pack(side="left", padx=8)

        pf = ttk.Frame(f)
        pf.grid(row=2, column=0, columnspan=8, sticky="w", pady=(6, 0))
        for text, var, w in (("Прогрев, мин:", self.v_min, 5), ("Допуск, %:", self.v_tol, 6),
                             ("Ток потребл. от, мА:", self.v_imin, 6), ("до:", self.v_imax, 6)):
            ttk.Label(pf, text=text).pack(side="left", padx=(0, 4))
            ttk.Entry(pf, textvariable=var, width=w).pack(side="left", padx=(0, 14))

        ttk.Label(f, text="Шаблон:").grid(row=3, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(f, textvariable=self.v_tpl).grid(row=3, column=1, columnspan=6, sticky="ew", padx=6, pady=(6, 2))
        ttk.Button(f, text="Обзор", command=self._pick_tpl).grid(row=3, column=7, pady=(6, 2))
        ttk.Label(f, text="Протоколы:").grid(row=4, column=0, sticky="w")
        ttk.Entry(f, textvariable=self.v_out).grid(row=4, column=1, columnspan=6, sticky="ew", padx=6, pady=2)
        ttk.Button(f, text="Обзор", command=self._pick_out).grid(row=4, column=7)

        f = ttk.LabelFrame(root, text="Процесс", padding=8)
        f.grid(row=2, column=0, sticky="ew", pady=(0, 8))
        f.columnconfigure(1, weight=1)
        self.l_state = tk.Label(f, text=STATES["idle"][0], fg=DIM, bg=PANEL, font=("Segoe UI", 16, "bold"), anchor="w")
        self.l_state.grid(row=0, column=0, columnspan=3, sticky="ew")
        self.l_timer = tk.Label(f, text="--:--", fg=FG, bg=PANEL, font=("Consolas", 40, "bold"))
        self.l_timer.grid(row=1, column=0, rowspan=2, padx=(4, 24))
        self.pb = ttk.Progressbar(f, maximum=1, value=0)
        self.pb.grid(row=1, column=1, sticky="ew", pady=(10, 4))
        self.l_supply = tk.Label(f, text="Питание: -", fg=DIM, bg=PANEL, font=("Consolas", 12))
        self.l_supply.grid(row=1, column=2, padx=(16, 4))
        bf = ttk.Frame(f)
        bf.grid(row=2, column=1, columnspan=2, sticky="w")
        self.b_start = ttk.Button(bf, text="СТАРТ", style="Accent.TButton", command=self.start)
        self.b_start.pack(side="left", padx=(0, 8))
        self.b_skip = ttk.Button(bf, text="Пропустить таймер", command=lambda: self.seq and self.seq.skip_timer())
        self.b_skip.pack(side="left", padx=(0, 8))
        self.b_stop = ttk.Button(bf, text="СТОП", command=lambda: self.seq and self.seq.stop())
        self.b_stop.pack(side="left")
        self.b_self = ttk.Button(bf, text="Самоконтроль стенда", command=self.selftest)
        self.b_self.pack(side="left", padx=(16, 0))
        self.b_open = ttk.Button(bf, text="Открыть протокол", command=self._open_protocol)
        self.b_open.pack(side="left", padx=(8, 0))

        f = ttk.LabelFrame(root, text="Результаты", padding=8)
        f.grid(row=3, column=0, sticky="w", pady=(0, 8))
        self.trees = {}
        for i, ch in enumerate((1, 2)):
            tf = ttk.Frame(f)
            tf.grid(row=0, column=i, padx=(0, 16), sticky="n")
            ttk.Label(tf, text=f"{ch} канал", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(0, 2))
            tr = ttk.Treeview(tf, columns=("pt", "meas", "err", "tol"), show="headings",
                              height=len(ROW_LABELS), selectmode="none")
            for c, t, w in (("pt", "мА", 105), ("meas", "измер., мА", 95), ("err", "%", 85), ("tol", "доп.", 60)):
                tr.heading(c, text=t)
                tr.column(c, width=w, anchor="center", stretch=False)
            tr.tag_configure("ok", foreground=OK)
            tr.tag_configure("bad", foreground=BAD)
            tr.tag_configure("off", foreground="#8c959f")
            tr.pack()
            self.trees[ch] = tr
        self.l_sum = tk.Label(f, text="", bg=PANEL, fg=FG, justify="left", anchor="nw",
                              font=("Consolas", 10), wraplength=320)
        self.l_sum.grid(row=0, column=2, sticky="nw")

        bottom = ttk.Frame(root)
        bottom.grid(row=4, column=0, sticky="nsew")
        bottom.columnconfigure(1, weight=1)
        bottom.rowconfigure(0, weight=1)

        f = ttk.LabelFrame(bottom, text="Ручное управление (наладка)", padding=8)
        f.grid(row=0, column=0, sticky="ns", padx=(0, 8))
        self.manual_widgets = [ttk.Checkbutton(f, text="Питание +24 В", variable=self.v_pwr, command=self._manual)]
        self.manual_widgets[-1].grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(f, text="Вход:").grid(row=1, column=0, sticky="w", pady=(6, 0))
        for i, (val, text) in enumerate((("0", "нет"), ("1", "К1"), ("2", "К2"))):
            rb = ttk.Radiobutton(f, text=text, value=val, variable=self.v_in, command=self._manual)
            rb.grid(row=1, column=1 + i, sticky="w", pady=(6, 0))
            self.manual_widgets.append(rb)
        ttk.Label(f, text="Выход:").grid(row=2, column=0, sticky="w")
        for i, val in enumerate(("1", "2")):
            rb = ttk.Radiobutton(f, text=f"К{val}", value=val, variable=self.v_outch, command=self._manual)
            rb.grid(row=2, column=1 + i, sticky="w")
            self.manual_widgets.append(rb)
        cb = ttk.Checkbutton(f, text="Нагрузка 400 Ом", variable=self.v_load, command=self._manual)
        cb.grid(row=3, column=0, columnspan=3, sticky="w", pady=(4, 0))
        self.manual_widgets.append(cb)
        b = ttk.Button(f, text="Всё выключить", command=self._manual_off)
        b.grid(row=4, column=0, columnspan=3, sticky="ew", pady=(8, 0))
        self.manual_widgets.append(b)

        f = ttk.LabelFrame(bottom, text="Журнал", padding=6)
        f.grid(row=0, column=1, sticky="nsew")
        f.rowconfigure(0, weight=1)
        f.columnconfigure(0, weight=1)
        self.log = tk.Text(f, bg=FIELD, fg=FG, font=("Consolas", 10), wrap="word", relief="solid", bd=1,
                           height=10, selectbackground="#b6d7ff", selectforeground=FG)
        sb = ttk.Scrollbar(f, command=self.log.yview)
        self.log.configure(yscrollcommand=sb.set)
        self.log.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.log.tag_configure("err", foreground=BAD)
        self.log.tag_configure("warn", foreground=WARN)
        self.log.tag_configure("good", foreground=OK)
        self.log.bind("<Key>", lambda e: None if (e.state & 0x4) else "break")

        self._set_running(False)

    def write(self, text):
        low = text.lower()
        tag = "err" if ("ошибка" in low or "fail" in low) else "warn" if ("внимание" in low or "вне" in low) else \
            "good" if "pass" in low else None
        self.log.insert("end", f"[{time.strftime('%H:%M:%S')}] {text}\n", tag)
        self.log.see("end")

    # ------------------------------ настройки ------------------------------
    _INI_KEYS = (("stand", "v_stand"), ("ca71", "v_ca71"), ("gate", "v_gate"), ("operator", "v_oper"),
                 ("template", "v_tpl"), ("outdir", "v_out"), ("warmup_min", "v_min"), ("tol", "v_tol"),
                 ("product", "v_prod"), ("mode", "v_mode"), ("i_min", "v_imin"), ("i_max", "v_imax"))

    def _load_ini(self):
        cp = configparser.ConfigParser()
        cp.read(INI, encoding="utf-8")
        g = cp["stand"] if cp.has_section("stand") else {}
        for key, attr in self._INI_KEYS:
            if key in g:
                getattr(self, attr).set(g[key])

    def _save_ini(self):
        cp = configparser.ConfigParser()
        cp["stand"] = {key: getattr(self, attr).get() for key, attr in self._INI_KEYS}
        try:
            with open(INI, "w", encoding="utf-8") as fh:
                cp.write(fh)
        except OSError:
            pass

    def _reload_lists(self):
        """Операторы берутся из листа «Ресурсы» шаблона, если он задан."""
        tpl = self.v_tpl.get().strip()
        if os.path.isfile(tpl):
            try:
                self.cb_oper.configure(values=read_list(tpl, "Ресурсы", "A") or OPERATORS)
            except Exception as e:
                self.write(f"Не удалось прочитать список операторов из шаблона ({e})")
        self._on_product()

    def _on_product(self):
        if not self.running:
            self._reset_tables(channels_for(self.v_prod.get()))

    def _reset_tables(self, chans=(1, 2)):
        for ch, tr in self.trees.items():
            tr.delete(*tr.get_children())
            for i, lab in enumerate(ROW_LABELS):
                if ch in chans:
                    tr.insert("", "end", iid=f"{ch}_{i}", values=(lab, "", "", ""))
                else:
                    tr.insert("", "end", iid=f"{ch}_{i}", values=(lab, "-", "-", "-"), tags=("off",))
        self.l_sum.configure(text="")

    def _pick_tpl(self):
        p = filedialog.askopenfilename(title="Шаблон протокола", filetypes=[("Excel", "*.xlsx")])
        if p:
            self.v_tpl.set(p)
            self._reload_lists()

    def _pick_out(self):
        p = filedialog.askdirectory(title="Папка протоколов")
        if p:
            self.v_out.set(p)

    # ------------------------------- приборы -------------------------------
    def connect(self):
        self.dev.stand_port = self.v_stand.get().strip()
        self.dev.gate_port = self.v_gate.get().strip()
        self.dev.ca71_port = self.v_ca71.get().strip()
        self.dev.simulate = self.v_sim.get()
        self.dev.connect(self.write)
        if self.dev.stand is not None and not self.dev.simulate:
            self.v_stand.set(self.dev.stand_port)
        self._refresh_leds()
        self._save_ini()

    def disconnect(self):
        self.dev.close()
        self._refresh_leds()
        self.write("Приборы отключены, изделие обесточено")

    def _refresh_leds(self):
        for key in ("stand", "ca71", "gate"):
            self.leds[key].configure(foreground=OK if getattr(self.dev, key) else BAD)

    def _poll_stand(self):
        """Кнопки на стенде, пока проверка не идёт (во время проверки события читает рабочий поток)."""
        if not self.running and self.dev.stand is not None:
            try:
                for ev in self.dev.stand.poll_events():
                    if ev == "START":
                        self.write("Нажата кнопка СТАРТ на стенде")
                        self.start()
                    elif ev == "STOP":
                        self._sync_manual(off=True)
                    else:
                        self.write(f"Стенд: {ev}")
            except Exception as e:
                self.write(f"ОШИБКА связи со стендом: {e}")
                self.dev.close()
                self._refresh_leds()
        self.after(300, self._poll_stand)

    # ---------------------------- ручное управление ----------------------------
    def _manual(self):
        st = self.dev.stand
        if st is None:
            self.write("ОШИБКА: стенд не подключён")
            self._sync_manual(off=True)
            return
        try:
            st.power(self.v_pwr.get())
            st.input(int(self.v_in.get()))
            st.output(int(self.v_outch.get()))
            st.load(self.v_load.get())
            if self.v_pwr.get():
                ma, v = st.supply()
                self._show_supply(ma, v)
            self.write(f"Стенд: {st.state()}")
        except Exception as e:
            self.write(f"ОШИБКА стенда: {e}")

    def _manual_off(self):
        if self.dev.stand is not None:
            try:
                self.dev.stand.all_off()
            except Exception as e:
                self.write(f"ОШИБКА стенда: {e}")
        self._sync_manual(off=True)
        self.write("Стенд: всё выключено")

    def _sync_manual(self, off=False):
        if off:
            self.v_pwr.set(False)
            self.v_in.set("0")
            self.v_outch.set("1")
            self.v_load.set(False)

    def _show_supply(self, ma, v):
        self.l_supply.configure(text=f"Питание: {ma:5.1f} мА  {v:5.2f} В", fg=FG if ma > 0.5 else DIM)

    # ------------------------------ запуск ------------------------------
    def _config(self):
        try:
            cfg = StandConfig(tol=float(self.v_tol.get().replace(",", ".")),
                              i_min=float(self.v_imin.get().replace(",", ".")),
                              i_max=float(self.v_imax.get().replace(",", ".")))
            warm = float(self.v_min.get().replace(",", ".")) * 60
        except ValueError:
            messagebox.showwarning("Параметры", "Прогрев, допуск и пределы тока должны быть числами")
            return None, None
        if self.dev.simulate:
            cfg.settle = cfg.read_delay = cfg.power_settle = 0.0
            cfg.keepalive = 0.5
            warm = min(warm, 5)
        return cfg, warm

    def _ensure_connected(self):
        if self.dev.stand is None or self.dev.ca71 is None:
            self.connect()
        if self.dev.stand is None or self.dev.ca71 is None:
            messagebox.showerror("Подключение", "Не удалось подключить стенд и CA71, см. журнал")
            return False
        return True

    def start(self):
        if self.running:
            return
        serial = self.v_serial.get().strip()
        if not serial:
            messagebox.showwarning("Серийный номер", "Введите (отсканируйте) серийный номер изделия")
            self.e_serial.focus_set()
            return
        cfg, warm = self._config()
        if cfg is None or not self._ensure_connected():
            return
        mode = self.v_mode.get()
        if mode == MODE_CAL and self.dev.gate is None:
            messagebox.showerror("Калибровка", "Для калибровки нужен шлюз MK-531 (порт и подключение)")
            return
        product = self.v_prod.get().strip()
        self._reset_tables(channels_for(product))
        self._sync_manual(off=True)
        self.protocol_path = ""
        self._save_ini()
        self.seq = StandSequencer(self.dev, cfg, self.io, mode, warm, serial, self.v_oper.get().strip(),
                                  self.v_tpl.get().strip(), self.v_out.get().strip(), product)
        self.write(f"Старт: {product}, №{serial}, режим «{'калибровка + проверка' if mode == MODE_CAL else 'проверка'}»")
        self._set_running(True)
        threading.Thread(target=self.seq.run, daemon=True).start()

    def selftest(self):
        if self.running:
            return
        if not messagebox.askyesno("Самоконтроль стенда",
                                   "Изделие должно быть СНЯТО со стенда, источник CA71 - в режиме 4-20mA.\n"
                                   "Начать самоконтроль?"):
            return
        cfg, _ = self._config()
        if cfg is None or not self._ensure_connected():
            return
        self._sync_manual(off=True)
        self.seq = StandSequencer(self.dev, cfg, self.io, MODE_CHECK, 0, "", "", "", "")
        self._set_running(True)
        threading.Thread(target=self.seq.run_selftest, daemon=True).start()

    def _open_protocol(self):
        if self.protocol_path and os.path.exists(self.protocol_path):
            try:
                os.startfile(self.protocol_path)  # Windows
            except AttributeError:
                self.write(f"Протокол: {self.protocol_path}")

    def _set_running(self, running):
        self.running = running
        st = "disabled" if running else "normal"
        for w in [self.b_start, self.b_conn, self.b_disc, self.b_self] + self.manual_widgets:
            w.configure(state=st)
        self.b_skip.configure(state="normal" if running else "disabled")
        self.b_stop.configure(state="normal" if running else "disabled")
        self.b_open.configure(state="normal" if (not running and self.protocol_path) else "disabled")

    def _beep(self, freq=1000, n=1):
        if winsound:
            try:
                for _ in range(n):
                    winsound.Beep(freq, 250)
            except Exception:
                pass
        else:
            self.bell()

    # --------------------------- события от рабочего потока ---------------------------
    def _poll(self):
        try:
            while True:
                self._handle(self.io.q.get_nowait())
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _handle(self, ev):
        kind = ev[0]
        if kind == "log":
            self.write(ev[1])
        elif kind == "state":
            text, color = STATES.get(ev[1], (ev[1], FG))
            self.l_state.configure(text=text, fg=color)
        elif kind == "timer":
            rem, total = ev[1], ev[2]
            self.l_timer.configure(text=f"{int(rem // 60):02d}:{int(rem % 60):02d}", fg=WARN if rem > 0 else OK)
            self.pb.configure(maximum=max(total, 1), value=total - rem)
        elif kind == "point":
            _, ch, label, meas, err, ok = ev
            if label in ROW_LABELS:
                self.trees[ch].item(f"{ch}_{ROW_LABELS.index(label)}",
                                    values=(label, f"{meas:.4f}", f"{err:+.4f}", "OK" if ok else "ВНЕ"),
                                    tags=("ok" if ok else "bad",))
        elif kind == "supply":
            self._show_supply(ev[1], ev[2])
        elif kind == "done":
            _, path, lines, ok = ev
            self.protocol_path = path
            self._clear_serial = ok  # после FAIL номер остаётся: можно откалибровать и проверить снова
            self.l_sum.configure(text="\n".join(lines), fg=OK if ok else BAD)
            self.write(f"Протокол сохранён: {path}")
            self.write("Итог: PASS" if ok else "Итог: FAIL")
            self._beep(1400 if ok else 500, 2)
        elif kind == "end":
            self._set_running(False)
            self.l_timer.configure(text="--:--", fg=FG)
            self.pb.configure(value=0)
            self._show_supply(0.0, 0.0)
            if getattr(self, "_clear_serial", False):
                self.v_serial.set("")
                self._clear_serial = False
            self.e_serial.focus_set()

    def _on_close(self):
        if self.seq:
            self.seq.stop()
        self._save_ini()
        self.dev.close()
        self.destroy()


if __name__ == "__main__":
    try:
        App().mainloop()
    except Exception:
        log_error(traceback.format_exc())
        try:
            messagebox.showerror("Ошибка запуска", "Программа не запустилась, подробности в error.log")
        except Exception:
            pass
        raise
