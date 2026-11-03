"""MK201 Auto — заливка ПО, настройка, калибровка AI/AO, проверка DO/DI и CI, протокол Excel, ручной режим."""
import csv, json, os, re, subprocess, sys, threading, time, tkinter as tk
from tkinter import ttk, filedialog, messagebox


def _check_deps():
    """Понятное сообщение, если библиотеки стоят не в том Python, которым запущена программа."""
    bad = []
    try:
        import serial
        serial.Serial          # пакет «serial» вместо «pyserial» не имеет этого класса
    except Exception as e:
        bad.append(f"pyserial ({e})")
    try:
        import openpyxl
    except Exception as e:
        bad.append(f"openpyxl ({e})")
    if bad:
        cmd = f'"{sys.executable}" -m pip install --force-reinstall pyserial openpyxl pyvisa'
        text = ("Не удаётся загрузить: " + "; ".join(bad) + "\n\nЭта программа запущена через:\n"
                + sys.executable + "\n\nУстановите библиотеки именно в этот Python, команда:\n" + cmd)
        r = tk.Tk(); r.withdraw(); messagebox.showerror("MK201 Auto", text); r.destroy()
        print(text); sys.exit(1)


_check_deps()

from mk201_proto import MK201
from mk201_relay import Relay, NoRelay
from ca71 import CA71
from mk201_workflow import Runner
from mk201_simulator import make_sim, make_sim_instruments
from modbus_decode import hx
import mk201_firmware as firmware
import mk201_ports as ports
import mk201_instruments as instruments
import mk201_settings as SET
from mk201_labels import parse_label, LabelError
from protocol_xlsx import Protocol
from manual_tabs import ManualMixin
from mk201_fallback import SafeDev, ManualCal

APPDIR = os.path.dirname(os.path.abspath(sys.argv[0]))
CFG = os.path.join(APPDIR, "mk201_auto.json")
LOGDIR = os.path.join(APPDIR, "logs")
TRACE = os.path.join(LOGDIR, time.strftime("mk201_%Y-%m-%d_%H%M%S.log"))     # свой файл на каждый запуск программы
from mk201_version import VERSION, NUM as VERSION_NUM   # номер версии меняется только в mk201_version.py
LOCKFILE = os.path.join(APPDIR, "mk201_fw_lock.json")   # есть файл = плата после загрузки ПО не подтвердила запуск
STEPS = [("power", "Ток и мощность потребления (CH1)"),
         ("ports", "Порты 1–3 (MbSlave, Master 0–2)"), ("time", "Дата и время с ПК"),
         ("serial", "Серийный № и MAC"), ("ai_cal", "Калибровка AI (прогрев, 4 и 20 мА)"),
         ("ai_check", "Проверка AI (0…20 мА)"), ("ao_cal", "Калибровка AO (4 и 19 мА)"),
         ("ao_check", "Проверка AO (0…20 мА)"), ("dio", "Проверка DO → DI (5 / 11 / 24 / 30 В)"),
         ("ci", "Счётные входы CI (генератор, синус)"), ("eth", "Ethernet: серийный, MAC, время по Modbus TCP"),
         ("rs485", "RS-485 1–3: то же по Modbus RTU (3 клеммы)")]
STEP_NAMES = dict(STEPS)
STEP_DEFAULT_OFF = {"ci", "eth", "rs485"}
DEF_SP = ["0", "4", "8", "12", "16", "20"]
IFACES = Protocol.IFACES

# ---- палитра как в МИБ252 (02.11.2026: тёмная шапка, карточки, цветные СТАРТ/Стоп) ----
C_BG, C_PANEL, C_TEXT, C_MUTED = "#f4f6f9", "#ffffff", "#1f2328", "#6b7280"
C_ACCENT, C_ACCENT_DARK, C_LINE = "#2563eb", "#1d4ed8", "#d0d7de"
C_HEAD, C_HEAD_2, C_HEAD_TEXT, C_HEAD_MUTED = "#1e293b", "#273449", "#ffffff", "#a8b3c7"
C_GO, C_GO_DARK = "#16a34a", "#15803d"
OK, ERR, WARN = "#16a34a", "#dc2626", "#d97706"
FONT, FONT_B, FONT_S = ("Segoe UI", 10), ("Segoe UI", 10, "bold"), ("Segoe UI", 9)
MONO = ("Consolas", 10)
STATE = {"wait": ("ожидает", C_MUTED), "run": ("идёт…", C_ACCENT), "ok": ("ОК", OK),
         "warn": ("замечания", WARN), "fail": ("ошибка", ERR), "skip": ("—", "#b8bfcc"),
         "manual": ("вручную", WARN)}


def _prepare_logs():
    """Папка logs: по файлу на запуск, храним последние 200."""
    try:
        os.makedirs(LOGDIR, exist_ok=True)
        old = sorted(f for f in os.listdir(LOGDIR) if f.startswith("mk201_") and f.endswith(".log"))
        for f in old[:-200]:
            try: os.remove(os.path.join(LOGDIR, f))
            except Exception: pass
    except Exception:
        pass


def apply_theme(root):
    st = ttk.Style(root)
    st.theme_use("clam")
    root.configure(bg=C_BG)
    root.option_add("*Font", FONT)
    st.configure(".", background=C_BG, foreground=C_TEXT, font=FONT, bordercolor=C_LINE,
                 lightcolor=C_PANEL, darkcolor=C_LINE, troughcolor="#e5e9f0", focuscolor=C_ACCENT)
    st.configure("TFrame", background=C_BG)
    st.configure("TLabel", background=C_BG, foreground=C_TEXT)
    st.configure("Muted.TLabel", foreground=C_MUTED)
    st.configure("Note.TLabel", foreground=C_MUTED, font=("Segoe UI", 8))
    st.configure("Big.TLabel", font=("Segoe UI", 13, "bold"), foreground=C_ACCENT_DARK)
    st.configure("TLabelframe", background=C_BG, bordercolor="#c5cfdc", relief="solid", borderwidth=1)
    st.configure("TLabelframe.Label", background=C_BG, foreground=C_ACCENT_DARK, font=("Segoe UI", 10, "bold"))
    st.configure("TNotebook", background=C_BG, borderwidth=0, tabmargins=(6, 4, 6, 0))
    st.configure("TNotebook.Tab", padding=(18, 7), background="#e2e7ef", foreground="#374151", font=FONT_B,
                 bordercolor=C_LINE)
    st.map("TNotebook.Tab", background=[("selected", C_ACCENT), ("active", "#d5deec")],
           foreground=[("selected", "#ffffff")], expand=[("selected", (1, 1, 1, 0))])
    st.configure("TButton", padding=(10, 4), background=C_PANEL)
    st.map("TButton", background=[("active", "#eaf1ff"), ("disabled", "#eef0f3")], foreground=[("disabled", "#a5acb8")])
    st.configure("Accent.TButton", background=C_ACCENT, foreground="#ffffff", font=("Segoe UI", 11, "bold"), padding=(18, 8))
    st.map("Accent.TButton", background=[("active", C_ACCENT_DARK), ("disabled", "#9db7f0")], foreground=[("disabled", "white")])
    st.configure("Start.TButton", background=C_GO, foreground="#ffffff", font=("Segoe UI", 12, "bold"), padding=(26, 9))
    st.map("Start.TButton", background=[("active", C_GO_DARK), ("disabled", "#a7d8b8")], foreground=[("disabled", "white")])
    st.configure("Stop.TButton", background=ERR, foreground="#ffffff", font=FONT_B, padding=(14, 4))
    st.map("Stop.TButton", background=[("active", "#b91c1c"), ("disabled", "#4b5a72")],
           foreground=[("disabled", "#8f9bb0")])
    st.configure("Head.TButton", background=C_HEAD_2, foreground=C_HEAD_TEXT, bordercolor="#3b4a63", padding=(12, 4))
    st.map("Head.TButton", background=[("active", "#34445e")])
    st.configure("Head.Horizontal.TProgressbar", background="#3b82f6", troughcolor=C_HEAD_2, bordercolor=C_HEAD_2,
                 lightcolor="#3b82f6", darkcolor="#3b82f6", thickness=10)
    st.configure("TCheckbutton", background=C_BG)
    st.map("TCheckbutton", background=[("active", C_BG)])
    st.configure("TRadiobutton", background=C_BG)
    st.configure("TEntry", fieldbackground=C_PANEL)
    st.configure("TCombobox", fieldbackground=C_PANEL)
    st.map("TCombobox", fieldbackground=[("readonly", C_PANEL)])
    st.configure("TSpinbox", fieldbackground=C_PANEL)
    st.configure("Treeview", background=C_PANEL, fieldbackground=C_PANEL, rowheight=24)
    st.configure("Compact.Treeview", background=C_PANEL, fieldbackground=C_PANEL, rowheight=20, font=FONT_S)
    st.configure("Treeview.Heading", background="#dbe4f3", foreground="#1e3a8a", font=("Segoe UI", 9, "bold"),
                 bordercolor="#c5cfdc")
    st.map("Treeview.Heading", background=[("active", "#cbd8ee")])
    st.map("Treeview", background=[("selected", "#dce8fb")], foreground=[("selected", C_TEXT)])
    st.configure("Horizontal.TProgressbar", background=C_ACCENT, thickness=12)


def center_on(win, parent, size=None):
    """Окно — посередине окна программы (02.11.2026: раньше открывалось сверху слева). Окно программы
    свёрнуто или ещё не показано — посередине экрана."""
    win.update_idletasks()
    w, h = size or (win.winfo_reqwidth(), win.winfo_reqheight())
    try:
        visible = parent.winfo_viewable() and parent.state() != "iconic"
    except tk.TclError:
        visible = False
    if visible:
        px, py, pw, ph = parent.winfo_rootx(), parent.winfo_rooty(), parent.winfo_width(), parent.winfo_height()
    else:
        px, py, pw, ph = 0, 0, win.winfo_screenwidth(), win.winfo_screenheight()
    x = px + (pw - w) // 2
    y = py + (ph - h) // 2
    x = min(max(x, 0), max(win.winfo_screenwidth() - w, 0))
    y = min(max(y, 0), max(win.winfo_screenheight() - h - 40, 0))
    win.geometry(f"{w}x{h}+{x}+{y}" if size else f"+{x}+{y}")


def _messageboxes_on(root):
    """Стандартные сообщения (messagebox) — с окном программы как родителем: Windows ставит их по центру программы."""
    for name in ("showinfo", "showwarning", "showerror", "askyesno", "askokcancel", "askyesnocancel", "askretrycancel"):
        f = getattr(messagebox, name)
        def wrap(*a, _f=f, **k):
            if "parent" not in k:
                try:
                    w = root.focus_get()
                    k["parent"] = w.winfo_toplevel() if w is not None else root
                except Exception:
                    k["parent"] = root
            return _f(*a, **k)
        setattr(messagebox, name, wrap)


class Dialog(tk.Toplevel):
    """Модальное окно запроса оператору (крупный текст, при необходимости поле ввода)."""
    def __init__(self, parent, text, entry=False, ok_text="Готово", cancel_text="Остановить", unit="мА"):
        super().__init__(parent, bg=C_PANEL)
        self.withdraw()                              # показать сразу на своём месте, без прыжка из угла
        self.result, self.entry = None, entry
        self.title("MK201 Auto")
        self.transient(parent); self.resizable(False, False)
        body = tk.Frame(self, bg=C_PANEL, padx=26, pady=22); body.pack(fill="both", expand=True)
        lines = sum(1 + len(x) // 60 for x in str(text).split("\n"))
        if lines > 16:                 # длинный текст — в поле с прокруткой (колесо мыши работает)
            self.resizable(True, True)
            fr = tk.Frame(body, bg=C_PANEL); fr.pack(fill="both", expand=True)
            t = tk.Text(fr, wrap="word", font=("Segoe UI", 12), width=58, height=16, bg=C_PANEL, fg=C_TEXT,
                        relief="flat", highlightthickness=0)
            sb = ttk.Scrollbar(fr, orient="vertical", command=t.yview); t.configure(yscrollcommand=sb.set)
            t.insert("1.0", text); t.configure(state="disabled")
            sb.pack(side="right", fill="y"); t.pack(side="left", fill="both", expand=True)
        else:
            tk.Label(body, text=text, bg=C_PANEL, fg=C_TEXT, font=("Segoe UI", 12), justify="left", wraplength=480).pack(anchor="w")
        self.var = tk.StringVar()
        if entry:
            e = ttk.Entry(body, textvariable=self.var, font=("Segoe UI", 13), width=14)
            e.pack(anchor="w", pady=(14, 0)); e.focus_set()
            tk.Label(body, text=f"значение в {unit}", bg=C_PANEL, fg=C_MUTED, font=FONT_S).pack(anchor="w")
        bar = tk.Frame(self, bg=C_BG, padx=20, pady=12); bar.pack(fill="x")
        ttk.Button(bar, text=ok_text, style="Accent.TButton", command=self.ok).pack(side="right")
        ttk.Button(bar, text=cancel_text, command=self.cancel).pack(side="right", padx=8)
        self.bind("<Return>", lambda e: self.ok()); self.bind("<Escape>", lambda e: self.cancel())
        self.protocol("WM_DELETE_WINDOW", self.cancel)
        center_on(self, parent)
        self.deiconify()
        self.attributes("-topmost", True)
        try:
            self.wait_visibility(); self.grab_set()
        except tk.TclError:
            pass

    def ok(self):
        if self.entry:
            try: self.result = float(self.var.get().replace(",", "."))
            except ValueError: return
        else:
            self.result = True
        self.destroy()

    def cancel(self):
        self.result = None; self.destroy()


class UI:
    """Мост между потоком сценария и окном."""
    def __init__(self, app): self.app = app; self.stopped = False; self.skip = False
    def _a(self, f, *a): self.app.root.after(0, f, *a)
    def log(self, s, tag=None):
        if tag not in ("tx", "rx"):          # сообщения программы — тоже в mk201_debug.log
            try:
                with open(TRACE, "a", encoding="utf-8") as f:
                    f.write(f"{time.strftime('%H:%M:%S')} ## {s}\n")
            except Exception: pass
        self._a(self.app.add_log, s, tag)
    def step(self, key, state): self._a(self.app.set_step, key, state)
    def phase(self, text): self._a(self.app.set_phase, text)
    def detail(self, text): self._a(self.app.set_detail, text)
    def progress(self, done, total): self._a(self.app.set_progress, done, total)
    def sub_progress(self, p): self._a(self.app.set_sub_progress, p)
    def countdown(self, sec): self._a(self.app.set_timer, sec)
    def fw_lock(self, state):
        """Вызывается из потока сценария. Файл пишем сразу — блокировка переживёт закрытие программы."""
        self.app.write_lock(state)
        self._a(self.app.apply_lock)
    def point(self, kind, ch, i, sp, v, good): self._a(self.app.set_point, kind, ch, i, sp, v, good)
    def dio(self, mode, n, good, mask): self._a(self.app.set_dio, mode, n, good, mask)
    def ci(self, ch, i, f, n, got, good): self._a(self.app.set_ci, ch, i, f, n, got, good)
    def gen_trig(self, cmd):
        """Сценарий нашёл рабочую команду запуска пакета — запоминаем её в настройках."""
        self._a(lambda: (self.app.v["gen_trig"].set(cmd), self.app.save()))
    def iface(self, name, state):
        """Ethernet / RS-485 проверены автоматически — обновить выбор в таблице 7 на экране."""
        if name in self.app.iface:
            self._a(self.app.iface[name].set, state)
    def _ask(self, fn):
        box, ev = [], threading.Event()
        def go(): box.append(fn()); ev.set()
        self.app.root.after(0, go); ev.wait(); return box[0]
    def confirm(self, p): return bool(self._ask(lambda: self._dlg(p, False)))
    def ask(self, text, yes, no):
        def f():
            d = Dialog(self.app.root, text, ok_text=yes, cancel_text=no)
            self.app.root.wait_window(d); return d.result
        return bool(self._ask(f))
    def ask_float(self, p): return self._ask(lambda: self._dlg(p, True))
    def _dlg(self, text, entry):
        d = Dialog(self.app.root, text, entry=entry, ok_text="Записать" if entry else "Готово")
        self.app.root.wait_window(d); return d.result


class SettingsDialog(tk.Toplevel):
    """«Настройки…»: задержки приборов и операций, начало номера протокола, допуски. Окно прокручивается колесом."""
    def __init__(self, app):
        super().__init__(app.root, bg=C_BG)
        self.app = app
        self.title("MK201 Auto — настройки")
        self.transient(app.root)
        self.withdraw()
        self.geometry("820x640"); self.minsize(640, 360)
        bar = tk.Frame(self, bg=C_BG, padx=14, pady=10); bar.pack(side="bottom", fill="x")
        ttk.Button(bar, text="Сохранить", style="Accent.TButton", command=self.save).pack(side="right")
        ttk.Button(bar, text="Отмена", command=self.destroy).pack(side="right", padx=8)
        ttk.Button(bar, text="Всё по умолчанию", command=self.reset_all).pack(side="left")
        self.msg = ttk.Label(bar, text="", foreground=ERR); self.msg.pack(side="left", padx=12)
        holder = ttk.Frame(self, padding=(10, 10, 4, 0)); holder.pack(fill="both", expand=True)
        inner = app._scrollable(holder)
        self.bind("<Destroy>", lambda e: e.widget is self and app._forget_scroll(inner))
        self.vars, self.ent = {}, {}
        for title, items in SET.GROUPS:
            lf = ttk.LabelFrame(inner, text=title, padding=(10, 6)); lf.pack(fill="x", pady=(0, 8), padx=(0, 8))
            lf.columnconfigure(0, weight=1)
            for r, (key, label, default, typ, lo, hi) in enumerate(items):
                ttk.Label(lf, text=label).grid(row=r, column=0, sticky="w", pady=2)
                v = tk.StringVar(value=self.fmt(app.S.get(key, default)))
                e = ttk.Entry(lf, textvariable=v, width=14); e.grid(row=r, column=1, sticky="w", padx=8, pady=2)
                ttk.Label(lf, text=f"по умолч.: {self.fmt(default) or '—'}", style="Muted.TLabel").grid(row=r, column=2, sticky="w")
                ttk.Button(lf, text="↺", width=3, command=lambda v=v, d=default: v.set(self.fmt(d))).grid(row=r, column=3, padx=(6, 0))
                self.vars[key], self.ent[key] = v, e
        self.bind("<Escape>", lambda e: self.destroy())
        center_on(self, app.root, (820, 640)); self.deiconify()

    @staticmethod
    def fmt(x):
        if isinstance(x, float):
            return f"{x:g}".replace(".", ",")
        return str(x)

    def reset_all(self):
        for k, v in self.vars.items():
            v.set(self.fmt(SET.DEFAULTS[k]))

    def save(self):
        new, bad = {}, []
        names = {k: label for _, items in SET.GROUPS for k, label, *_ in items}
        for k, v in self.vars.items():
            try:
                new[k] = SET.convert(k, v.get())
            except ValueError as e:
                bad.append(f"«{names[k]}»: {e or 'неверное число'}")
        if bad:
            self.msg.configure(text="Не сохранено: " + bad[0] + (f" (и ещё {len(bad) - 1})" if len(bad) > 1 else ""))
            return
        self.app.apply_settings(new)
        self.destroy()


class App(ManualMixin):
    def __init__(self):
        try:
            import ctypes; ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
        _prepare_logs()
        self.root = tk.Tk(); self.root.title(f"MK201 Auto {VERSION} — прошивка, калибровка, проверка, протокол")
        self.root.geometry("1120x740"); self.root.minsize(960, 600)
        apply_theme(self.root)
        _messageboxes_on(self.root)               # сообщения — по центру окна программы
        try:
            c = json.load(open(CFG, encoding="utf-8")) if os.path.exists(CFG) else {}
        except Exception:
            c = {}
        self.c = c
        raw = dict(c.get("settings") or {})
        for old, new in (("tol", "tol"), ("warm", "warm"), ("gen_burst_max", "gen_burst_max")):   # из прошлых версий
            if new not in raw and str(c.get(old, "")).strip():
                raw[new] = c[old]
        self.S = SET.clean(raw)
        self.v = {k: tk.StringVar(value=str(c.get(k, d))) for k, d in dict(
            port="COM63", baud="115200", unit="1", relay_port="", cal_port="", serial="", mac="", hex_path="",
            template="", outdir="", protocol_number="", hv="3.3", scan_port="", rs485_port="",
            psu_res="", gen_res="", operator="", gen_method="burst", gen_trig="C1:BTWV MTRIG",
            # 02.11.2026: связь с платой — Service Port / Modbus RTU (RS-485) / Modbus TCP
            conn_mode="Service Port", rtu_baud="19200", rtu_parity="N", rtu_stop="1", rtu_unit="1",
            tcp_ip="10.155.26.140", tcp_port="502", tcp_unit="1").items()}
        self.steps = {k: tk.BooleanVar(value=c.get("step_" + k, k not in STEP_DEFAULT_OFF)) for k, _ in STEPS}
        self.force = tk.BooleanVar(value=c.get("force", False))
        self.sim = tk.BooleanVar(value=c.get("sim", False))
        self.verbose = tk.BooleanVar(value=c.get("verbose", True))
        self.operators = list(c.get("operators", []))
        self.iface = {n: tk.StringVar(value="годен") for n in IFACES}
        self.ui = UI(self); self.last_protocol = None
        self.relay = self.cal = self.scanner = self.psu = self.gen = None
        self.board_power = False                 # CH1 блока питания включён программой
        self.flashing = False; self.running = False; self.connecting = False
        self.locked = os.path.exists(LOCKFILE)
        self.bad = {"ai": [False] * 8, "ao": [False] * 2}; self.pt_bad = {"ai": {}, "ao": {}}; self.phase_text = "Ожидание запуска"
        self.err_count = 0
        self._scrollables = []
        self._save_job = None
        self.manual_init()
        self.build()
        self._install_wheel(); self._install_clipboard()
        self._install_autosave()
        self.apply_lock()
        try:
            with open(TRACE, "a", encoding="utf-8") as f:
                f.write(f"===== MK201 Auto {VERSION} · запуск {time.strftime('%d.%m.%Y %H:%M:%S')} =====\n")
        except Exception:
            pass
        if self.locked:
            self.add_log("ВНИМАНИЕ: прошлая загрузка ПО не подтверждена. Нажмите «Проверить плату».", "err")
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(1000, self.poll)
        self.root.after(300, self.startup)

    # ================= окно =================
    def build(self):
        r = self.root
        # --- верхняя полоса: всегда видна ---
        top = tk.Frame(r, bg=C_HEAD, padx=12, pady=8); top.pack(fill="x")
        tk.Label(top, text="MK201 Auto", bg=C_HEAD, fg=C_HEAD_TEXT, font=("Segoe UI", 14, "bold")).pack(side="left")
        tk.Label(top, text=f"v{VERSION_NUM}", bg=C_HEAD, fg=C_HEAD_MUTED, font=("Segoe UI", 10, "bold")).pack(side="left", padx=(6, 0), pady=(3, 0))
        tk.Frame(top, bg=C_HEAD, width=14).pack(side="left")
        self.chip = tk.Label(top, text="Готов к работе", bg=C_ACCENT, fg="#ffffff", font=("Segoe UI", 10, "bold"), padx=12, pady=3)
        self.chip.pack(side="left")
        self.stop_btn = ttk.Button(top, text="■ Стоп", style="Stop.TButton", command=self.stop, state="disabled")
        self.stop_btn.pack(side="right")
        ttk.Button(top, text="⚙ Настройки…", style="Head.TButton", command=self.open_settings).pack(side="right", padx=(0, 8))
        self.timer = tk.Label(top, text="", bg=C_HEAD, fg="#fbbf24", font=("Consolas", 15, "bold")); self.timer.pack(side="right", padx=10)
        self.bar = ttk.Progressbar(top, length=200, maximum=100, style="Head.Horizontal.TProgressbar"); self.bar.pack(side="right", padx=8)
        self.status = tk.Label(top, text="Ожидание запуска", bg=C_HEAD, fg=C_HEAD_MUTED, font=FONT, anchor="e"); self.status.pack(side="right", padx=8, fill="x", expand=True)
        line2 = tk.Frame(r, bg=C_PANEL, padx=12, pady=5, highlightbackground=C_LINE, highlightthickness=1); line2.pack(fill="x")
        self.detail_lbl = tk.Label(line2, text="", bg=C_PANEL, fg=C_ACCENT_DARK, font=FONT_B, anchor="w"); self.detail_lbl.pack(fill="x")
        self.last_lbl = tk.Label(line2, text="", bg=C_PANEL, fg=C_MUTED, font=FONT_S, anchor="w"); self.last_lbl.pack(fill="x")
        self.lock_bar = tk.Frame(r, bg="#fdecea", highlightbackground="#f5b5ae", highlightthickness=1)
        tk.Label(self.lock_bar, text="⛔  Плата после загрузки ПО ещё не подтвердила запуск основной программы.\n"
                 "Все действия с платой заблокированы. Питание не отключать!", bg="#fdecea", fg=ERR,
                 font=FONT_B, justify="left").pack(side="left", padx=12, pady=8)
        ttk.Button(self.lock_bar, text="Снять блокировку…", command=self.force_unlock).pack(side="right", padx=(4, 10))
        ttk.Button(self.lock_bar, text="Перезагрузить плату", command=self.reboot_board).pack(side="right", padx=4)
        ttk.Button(self.lock_bar, text="Проверить плату", command=self.check_board).pack(side="right", padx=4)
        self.lock_anchor = line2

        nb = ttk.Notebook(r); nb.pack(fill="both", expand=True, padx=6, pady=(6, 6))
        self.nb = nb
        self.tabs = {}
        for key, title, builder, scroll in (("conn", "Подключение", self._tab_connect, True),
                                            ("check", "Проверка", self._tab_check, False),
                                            ("log", "Журнал", self._tab_log, False),
                                            ("manual", "Ручной режим", self._tab_manual, False)):
            f = ttk.Frame(nb, padding=8)
            nb.add(f, text=title)
            self.tabs[key] = f
            builder(self._scrollable(f) if scroll else f)
        nb.bind("<<NotebookTabChanged>>", self._tab_changed)
        nb.select(self.tabs["conn"])                       # 28.10.2026: при запуске — вкладка «Подключение»

    def _scrollable(self, parent, fit=False):
        """Рамка с вертикальной прокруткой; колесо мыши работает, когда курсор над ней.
        fit=True — ширина по содержимому (левая колонка «Проверки»)."""
        canvas = tk.Canvas(parent, bg=C_BG, highlightthickness=0)
        sb = ttk.Scrollbar(parent, orient="vertical", command=canvas.yview)
        inner = ttk.Frame(canvas)
        win = canvas.create_window((0, 0), window=inner, anchor="nw")
        if fit:
            inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all"),
                                                                 width=inner.winfo_reqwidth()))
        else:
            inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
            canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=e.width))
        canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y"); canvas.pack(side="left", fill="both", expand=True)
        canvas._inner = inner
        self._scrollables.append(canvas)
        return inner

    def _forget_scroll(self, inner):
        self._scrollables = [c for c in self._scrollables if getattr(c, "_inner", None) is not inner]

    def _install_wheel(self):
        def wheel(e, delta=None):
            d = delta if delta is not None else e.delta
            try: w = self.root.winfo_containing(e.x_root, e.y_root)
            except Exception: w = None
            while w is not None:
                try: cls = w.winfo_class()
                except Exception: return
                if cls in ("Text", "Treeview", "Listbox"):
                    return                                   # у них своя прокрутка
                if w in self._scrollables:
                    if tuple(w.yview()) != (0.0, 1.0):
                        w.yview_scroll(-1 if d > 0 else 1, "units") if abs(d) < 120 else w.yview_scroll(int(-d / 120), "units")
                    return
                w = getattr(w, "master", None)
        self.root.bind_all("<MouseWheel>", wheel)
        self.root.bind_all("<Button-4>", lambda e: wheel(e, 120))
        self.root.bind_all("<Button-5>", lambda e: wheel(e, -120))

    def _install_clipboard(self):
        """Ctrl+C/V/X/A и при русской раскладке, меню по правой кнопке."""
        def select_all(w):
            try:
                if w.winfo_class() == "Text": w.tag_add("sel", "1.0", "end")
                else: w.select_range(0, "end"); w.icursor("end")
            except Exception: pass
        def ctrl(e):
            if e.keysym.lower() in ("c", "v", "x", "a"):
                return None
            ev = {67: "<<Copy>>", 86: "<<Paste>>", 88: "<<Cut>>"}.get(e.keycode)
            if ev: e.widget.event_generate(ev); return "break"
            if e.keycode == 65: select_all(e.widget); return "break"
            return None
        def menu(e):
            w = e.widget; m = tk.Menu(self.root, tearoff=0)
            for label, ev in (("Вырезать", "<<Cut>>"), ("Копировать", "<<Copy>>"), ("Вставить", "<<Paste>>")):
                m.add_command(label=label, command=lambda ev=ev: w.event_generate(ev))
            m.add_separator(); m.add_command(label="Выделить всё", command=lambda: select_all(w))
            m.tk_popup(e.x_root, e.y_root)
        for cls in ("TEntry", "Entry", "Text", "TCombobox", "TSpinbox"):
            self.root.bind_class(cls, "<Control-KeyPress>", ctrl, add="+")
            self.root.bind_class(cls, "<Button-3>", menu, add="+")

    def _install_autosave(self):
        """Галочки и выборы сохраняются сразу (как в МИБ) — переживут перезапуск программы."""
        vs = list(self.steps.values()) + [self.force, self.sim, self.verbose] + list(self.auto.values())
        vs += [self.v[k] for k in ("hv", "gen_method", "gen_trig", "operator", "template", "outdir", "hex_path")]
        for var in vs:
            var.trace_add("write", lambda *a: self.save_soon())

    def save_soon(self):
        if self._save_job:
            try: self.root.after_cancel(self._save_job)
            except Exception: pass
        self._save_job = self.root.after(700, self.save)

    # ---------------- вкладка «Подключение» ----------------
    def _tab_connect(self, f):
        lf = ttk.LabelFrame(f, text="Устройства", padding=10); lf.pack(fill="x", pady=(0, 8))
        self.dev_rows = [("board", "Плата MK201 (Service Port)", "port", "com"),
                         ("relay", "Блок реле (ESP32)", "relay_port", "com"),
                         ("cal", "Калибратор Yokogawa CA71", "cal_port", "com"),
                         ("scan", "Сканер штрихкодов", "scan_port", "com"),
                         ("rs485", "Переходник RS-485 (MOXA, Modbus RTU)", "rs485_port", "com"),
                         ("psu", "Блок питания АКИП-1168 (CH1 плата, CH2 DO/DI)", "psu_res", "visa"),
                         ("gen", "Генератор АКИП-3409/2 (→ CI)", "gen_res", "visa")]
        self.combo, self.st = {}, {}
        for i, (name, label, key, kind) in enumerate(self.dev_rows):
            ttk.Label(lf, text=label).grid(row=i, column=0, sticky="w", padx=(0, 10), pady=3)
            cb = ttk.Combobox(lf, textvariable=self.v[key], width=50, state="readonly")
            cb.grid(row=i, column=1, sticky="w", pady=3); self.combo[name] = cb
            self.st[name] = tk.Label(lf, text="○ не проверено", bg=C_BG, fg="#9aa3b1", font=FONT_B, anchor="w", width=32)
            self.st[name].grid(row=i, column=2, sticky="w", padx=(12, 0))
        ttk.Label(lf, text="Скорость:", style="Muted.TLabel").grid(row=0, column=3, sticky="e", padx=(10, 4))
        ttk.Entry(lf, textvariable=self.v["baud"], width=8).grid(row=0, column=4, sticky="w")
        row = ttk.Frame(lf); row.grid(row=len(self.dev_rows), column=0, columnspan=5, sticky="w", pady=(8, 0))
        self.btn_refresh = ttk.Button(row, text="⟳ Обновить список", command=self.refresh_ports); self.btn_refresh.pack(side="left", padx=(0, 6))
        self.btn_auto = ttk.Button(row, text="Автопоиск и подключение", command=self.autodetect); self.btn_auto.pack(side="left", padx=(0, 6))
        self.btn_conn = ttk.Button(row, text="Подключить", style="Accent.TButton", command=self.connect); self.btn_conn.pack(side="left", padx=(0, 6))
        self.btn_disc = ttk.Button(row, text="Отключить", command=self.disconnect); self.btn_disc.pack(side="left", padx=(0, 14))
        ttk.Checkbutton(row, text="Симулятор (без железа)", variable=self.sim).pack(side="left")
        row2 = ttk.Frame(lf); row2.grid(row=len(self.dev_rows) + 1, column=0, columnspan=5, sticky="w", pady=(6, 0))
        ttk.Label(row2, text="Питание платы (CH1):").pack(side="left", padx=(0, 6))
        ttk.Button(row2, text="Включить", command=lambda: self.board_power_cmd(True)).pack(side="left", padx=(0, 4))
        ttk.Button(row2, text="Выключить", command=lambda: self.board_power_cmd(False)).pack(side="left", padx=(0, 10))
        self.pwr_lbl = ttk.Label(row2, text="", style="Muted.TLabel"); self.pwr_lbl.pack(side="left")
        self.refresh_ports(visa=False)
        self._conn_box(f)

        lf = ttk.LabelFrame(f, text="Прошивка (обновление ПО платы)", padding=10); lf.pack(fill="x", pady=(0, 8))
        lf.columnconfigure(1, weight=1)
        self.path_row(lf, 0, "Файл ПО (.hex):", "hex_path", "hex")
        self.btn_flash = ttk.Button(lf, text="Записать", style="Accent.TButton", command=self.flash)
        self.btn_flash.grid(row=0, column=3, padx=(6, 0))
        ttk.Checkbutton(lf, text="перепрошить, даже если в плате то же ПО", variable=self.force).grid(row=1, column=1, sticky="w")

        lf = ttk.LabelFrame(f, text="Протокол", padding=10); lf.pack(fill="x", pady=(0, 8))
        lf.columnconfigure(1, weight=1)
        self.path_row(lf, 0, "Шаблон протокола (.xlsx):", "template", "xlsx")
        self.path_row(lf, 1, "Папка протоколов:", "outdir", "dir")
        ttk.Button(lf, text="Открыть папку", command=self.open_outdir).grid(row=1, column=3, padx=(6, 0))

        lf = ttk.LabelFrame(f, text="Параметры", padding=10); lf.pack(fill="x", pady=(0, 8))
        def fld(c_, label, key, w, values):
            ttk.Label(lf, text=label).grid(row=0, column=c_, sticky="w", padx=(0, 6), pady=3)
            ttk.Combobox(lf, textvariable=self.v[key], values=values, width=w, state="readonly").grid(
                row=0, column=c_ + 1, sticky="w", padx=(0, 20))
        fld(0, "Ревизия платы:", "hv", 6, ["3.3", "3.1", "2.0"])
        fld(2, "Импульсы CI:", "gen_method", 8, ["burst", "timer"])
        fld(4, "Запуск пакета:", "gen_trig", 22, list(instruments.Generator.TRIGGERS))
        ttk.Button(lf, text="⚙ Настройки… (задержки, номер протокола, допуски)", command=self.open_settings).grid(
            row=1, column=0, columnspan=6, sticky="w", pady=(8, 0))

    CONN_MODES = ("Service Port", "Modbus RTU", "Modbus TCP")

    def _conn_box(self, f):
        """02.11.2026: как в конфигураторе — плата по Service Port, по RS-485 (Modbus RTU через переходник
        USB-RS485) или по Ethernet (Modbus TCP, порт 502). Выбранный способ — для ручного режима и прогона;
        шаги «Ethernet» и «RS-485 1–3» берут отсюда настройки RTU и TCP."""
        lf = ttk.LabelFrame(f, text="Связь с платой (как в конфигураторе)", padding=10); lf.pack(fill="x", pady=(0, 8))
        r0 = ttk.Frame(lf); r0.grid(row=0, column=0, columnspan=12, sticky="w", pady=(0, 6))
        ttk.Label(r0, text="Подключаться к плате через:").pack(side="left", padx=(0, 8))
        for m in self.CONN_MODES:
            ttk.Radiobutton(r0, text=m, value=m, variable=self.v["conn_mode"]).pack(side="left", padx=(0, 12))
        ttk.Label(r0, text="(загрузка ПО — только через Service Port)", style="Muted.TLabel").pack(side="left")

        def cell(r, c, text, key, w, values=None):
            ttk.Label(lf, text=text).grid(row=r, column=c, sticky="e", padx=(0 if c == 0 else 10, 4), pady=2)
            if values:
                ttk.Combobox(lf, textvariable=self.v[key], values=values, width=w, state="readonly").grid(row=r, column=c + 1, sticky="w")
            else:
                ttk.Entry(lf, textvariable=self.v[key], width=w).grid(row=r, column=c + 1, sticky="w")
        ttk.Label(lf, text="Modbus RTU (RS-485):", font=FONT_B).grid(row=1, column=0, sticky="w")
        cell(1, 1, "скорость", "rtu_baud", 8,
             [str(b) for b in (1200, 2400, 4800, 9600, 14400, 19200, 38400, 56000, 57600, 115200, 128000)])
        cell(1, 3, "чётность", "rtu_parity", 3, ["N", "E", "O"])
        cell(1, 5, "стоп", "rtu_stop", 3, ["1", "2"])
        cell(1, 7, "адрес", "rtu_unit", 5)
        ttk.Button(lf, text="Проверить связь", command=lambda: self.test_link("Modbus RTU")).grid(row=1, column=9, padx=(14, 0))
        ttk.Button(lf, text="По умолч. модуля", command=lambda: [self.v[k].set(x) for k, x in
                   (("rtu_baud", "19200"), ("rtu_parity", "N"), ("rtu_stop", "1"), ("rtu_unit", "1"))]).grid(row=1, column=10, padx=(6, 0))
        ttk.Label(lf, text="Modbus TCP (Ethernet):", font=FONT_B).grid(row=2, column=0, sticky="w", pady=(4, 0))
        cell(2, 1, "IP платы", "tcp_ip", 15)
        cell(2, 3, "порт", "tcp_port", 6)
        cell(2, 5, "адрес", "tcp_unit", 3)
        ttk.Button(lf, text="Проверить связь", command=lambda: self.test_link("Modbus TCP")).grid(row=2, column=9, padx=(14, 0))
        ttk.Label(lf, text="RTU — через COM-порт переходника RS-485 (MOXA), он выбирается в строке «Переходник RS-485» выше. Шаги «Ethernet» и «RS-485 1–3» (после счётных входов): программа отключается от Service Port, "
                           "по TCP и по RTU на каждой клемме читает серийный номер, MAC, дату и время и сверяет с Service Port. "
                           "Сеть ПК должна быть в подсети платы (например, IP ПК 10.155.26.100, маска 255.255.255.0).",
                  style="Note.TLabel", wraplength=900, justify="left").grid(row=3, column=0, columnspan=12, sticky="w", pady=(6, 0))
        self.v["conn_mode"].trace_add("write", lambda *a: (self.manual_close(), self.apply_lock()))

    def board_link(self, mode=None):
        """Параметры подключения к плате для выбранного способа -> (kwargs для MK201, текст)."""
        mode = mode or self.v["conn_mode"].get()
        if mode == "Modbus TCP":
            ip = self.v["tcp_ip"].get().strip()
            if not ip:
                raise RuntimeError("Не задан IP платы (вкладка «Подключение», Modbus TCP)")
            kw = dict(host=ip, tcp_port=int(self.v["tcp_port"].get() or 502), unit=int(self.v["tcp_unit"].get() or 1),
                      timeout=float(self.S.get("eth_timeout", 2.0)))
            return kw, f"Modbus TCP {ip}:{kw['tcp_port']}, адрес {kw['unit']}"
        if mode == "Modbus RTU":
            p = self.port("rs485_port")
            if not p:
                raise RuntimeError("Не выбран «Переходник RS-485» на вкладке «Подключение»")
            kw = dict(port=p, baud=int(self.v["rtu_baud"].get() or 19200), unit=int(self.v["rtu_unit"].get() or 1),
                      parity=(self.v["rtu_parity"].get() or "N")[:1], stopbits=int(self.v["rtu_stop"].get() or 1))
            return kw, f"Modbus RTU {p}, {kw['baud']} бод, 8{kw['parity']}{kw['stopbits']}, адрес {kw['unit']}"
        if not self.port("port"):
            raise RuntimeError("Не выбран порт платы")
        kw = dict(port=self.port("port"), baud=int(self.v["baud"].get()), unit=int(self.v["unit"].get()))
        return kw, f"Service Port {kw['port']}"

    def link_cfg(self):
        """Настройки RTU/TCP для сценария (шаги «Ethernet» и «RS-485 1–3»)."""
        return dict(conn_mode=self.v["conn_mode"].get(), tcp_ip=self.v["tcp_ip"].get().strip(),
                    tcp_port=self.v["tcp_port"].get(), tcp_unit=self.v["tcp_unit"].get(), rtu_baud=self.v["rtu_baud"].get(),
                    rtu_parity=self.v["rtu_parity"].get(), rtu_stop=self.v["rtu_stop"].get(), rtu_unit=self.v["rtu_unit"].get())

    def test_link(self, mode):
        """«Проверить связь»: подключиться выбранным способом, прочитать серийный, MAC, дату и время."""
        if self.running or self.flashing or self.connecting:
            return
        try:
            kw, text = self.board_link(mode)
        except Exception as e:
            messagebox.showwarning("Связь с платой", str(e)); return
        if mode == "Modbus RTU" or self.v["conn_mode"].get() == mode:
            self.manual_close()                    # тот же порт / то же подключение может держать ручной режим
        def job():
            log = self.ui.log
            log(f"Проверка связи: {text}…")
            try:
                d = MK201(trace=self.trace, **kw)
                try:
                    sn, mac, t = d.serial_read(), d.mac_read(), d.datetime_read()
                finally:
                    d.close()
                y, mo, dd, h, mi, s_ = t
                log(f"{text}: на связи — серийный {sn}, MAC {mac}, время {dd:02d}.{mo:02d}.{y} {h:02d}:{mi:02d}:{s_:02d}", "ok")
            except Exception as e:
                log(f"{text}: нет связи — {type(e).__name__}: {e}", "err")
        threading.Thread(target=job, daemon=True).start()

    def path_row(self, parent, row, label, key, kind):
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=(0, 8), pady=4)
        ttk.Entry(parent, textvariable=self.v[key]).grid(row=row, column=1, sticky="ew", pady=4)
        def pick():
            if kind == "dir": p = filedialog.askdirectory()
            elif kind == "hex": p = filedialog.askopenfilename(filetypes=[("Intel HEX", "*.hex"), ("Все файлы", "*.*")])
            else: p = filedialog.askopenfilename(filetypes=[("Excel", "*.xlsx")])
            if p: self.v[key].set(p)
        ttk.Button(parent, text="Обзор", command=pick).grid(row=row, column=2, padx=(6, 0))

    # ---------------- настройки ----------------
    def open_settings(self):
        w = getattr(self, "_settings_win", None)
        try:
            if w is not None and w.winfo_exists():
                w.lift(); return
        except Exception:
            pass
        self._settings_win = SettingsDialog(self)

    def apply_settings(self, new):
        changed = [k for k in new if new[k] != self.S.get(k)]
        self.S = SET.clean(new)
        self.save()
        if self.psu is not None: self.psu.pause = float(self.S["psu_pause"])
        if self.gen is not None:
            self.gen.pause = float(self.S["gen_pause"]); self.gen.margin = float(self.S["gen_margin"])
        if self.relay is not None: self.relay.settle = float(self.S["relay_settle"])
        self.update_sn()
        self.add_log("Настройки сохранены" + (": изменено — " + ", ".join(changed) if changed else " (без изменений)"), "ok")

    # ---------------- вкладка «Проверка» ----------------
    def _tab_check(self, f):
        left = ttk.Frame(f); left.pack(side="left", fill="y", padx=(0, 8))
        right = ttk.Frame(f); right.pack(side="left", fill="both", expand=True)

        # кнопки внизу — видны всегда, остальное колонки прокручивается колесом
        btns = ttk.Frame(left); btns.pack(side="bottom", fill="x", pady=(6, 0))
        b1 = ttk.Frame(btns); b1.pack(fill="x")
        self.btn_start = ttk.Button(b1, text="▶  СТАРТ", style="Start.TButton", command=self.start); self.btn_start.pack(side="left")
        self.btn_retry = ttk.Button(b1, text="Повторить непройденные", command=self.retry_failed)
        self.btn_retry.pack(side="left", padx=6)
        b2 = ttk.Frame(btns); b2.pack(fill="x", pady=(4, 0))
        self.btn_skip = ttk.Button(b2, text="Пропустить прогрев", command=self.skip_timer, state="disabled")
        self.btn_skip.pack(side="left")
        self.btn_open = ttk.Button(b2, text="Открыть протокол", command=self.open_protocol, state="disabled")
        self.btn_open.pack(side="left", padx=6)
        body = self._scrollable(left, fit=True)

        lf = ttk.LabelFrame(body, text="Изделие", padding=10); lf.pack(fill="x", pady=(0, 8), padx=(0, 4))
        ttk.Label(lf, text="Номер с этикетки:").grid(row=0, column=0, sticky="w")
        e = ttk.Entry(lf, textvariable=self.v["protocol_number"], width=22, font=("Segoe UI", 11))
        e.grid(row=0, column=1, columnspan=3, sticky="w", pady=2)
        self.sn_lbl = ttk.Label(lf, text="", style="Note.TLabel", wraplength=330)
        self.sn_lbl.grid(row=1, column=0, columnspan=4, sticky="w")
        self.v["protocol_number"].trace_add("write", lambda *a: self.update_sn())
        ttk.Label(lf, text="MAC-адрес:").grid(row=2, column=0, sticky="w", pady=(6, 2))
        ttk.Entry(lf, textvariable=self.v["mac"], width=22).grid(row=2, column=1, columnspan=3, sticky="w", pady=(6, 2))
        ttk.Label(lf, text="Оператор:").grid(row=3, column=0, sticky="w", pady=2)
        self.oper_cb = ttk.Combobox(lf, textvariable=self.v["operator"], values=self.operators, width=22)
        self.oper_cb.grid(row=3, column=1, columnspan=3, sticky="w", pady=2)
        ob = ttk.Frame(lf); ob.grid(row=4, column=1, columnspan=3, sticky="w")
        ttk.Button(ob, text="Добавить", command=self.oper_add).pack(side="left")
        ttk.Button(ob, text="Удалить", command=self.oper_del).pack(side="left", padx=4)
        self.update_sn()

        lf = ttk.LabelFrame(body, text="Что выполнять", padding=10); lf.pack(fill="x", pady=(0, 8), padx=(0, 4))
        self.step_lbl = {}
        for i, (k, t) in enumerate(STEPS):
            ttk.Checkbutton(lf, text=t, variable=self.steps[k]).grid(row=i, column=0, sticky="w")
            lb = tk.Label(lf, text="", bg=C_BG, fg=C_MUTED, font=FONT_S, width=10, anchor="w")
            lb.grid(row=i, column=1, sticky="w"); self.step_lbl[k] = lb
        sb = ttk.Frame(lf); sb.grid(row=len(STEPS), column=0, columnspan=2, sticky="w", pady=(4, 0))
        ttk.Button(sb, text="Все", command=lambda: [v.set(True) for v in self.steps.values()]).pack(side="left")
        ttk.Button(sb, text="Ничего", command=lambda: [v.set(False) for v in self.steps.values()]).pack(side="left", padx=4)

        lf = ttk.LabelFrame(body, text="Интерфейсы, табл. 7", padding=(10, 6)); lf.pack(fill="x", pady=(0, 8), padx=(0, 4))
        for i, n in enumerate(IFACES):
            ttk.Label(lf, text=n).grid(row=i // 2, column=(i % 2) * 2, sticky="w", padx=(0, 4), pady=1)
            ttk.Combobox(lf, textvariable=self.iface[n], values=["годен", "не годен"], width=9,
                         state="readonly").grid(row=i // 2, column=(i % 2) * 2 + 1, sticky="w", padx=(0, 10), pady=1)

        # 02.11.2026: результаты — на одной странице, без вкладок (AI/AO, DO → DI, CI видны сразу)
        pg = self._scrollable(right)
        self.t_an = self.section(pg, "Аналоговые входы и выходы, измерено мА",
                                 ["ch"] + [str(i) for i in range(len(DEF_SP))] + ["tol"], 10,
                                 [("ch", "Канал", 58)] + [(str(i), f"{v} мА", 64) for i, v in enumerate(DEF_SP)] + [("tol", "допуск", 56)])
        self.t_dio = self.section(pg, "DO → DI (каналы 1–16)", ["m"] + [str(i) for i in range(16)], 4,
                                  [("m", "", 104)] + [(str(i), str(i + 1), 24) for i in range(16)])
        self.t_ci = self.section(pg, "Счётные входы CI", ["f", "n", "0", "1", "2", "3"], 3,
                                 [("f", "Гц", 64), ("n", "подано", 76)] + [(str(i), f"CI{i + 1}", 84) for i in range(4)])
        self.reset_tables()

    def section(self, parent, title, cols, height, heads):
        """Карточка с заголовком и компактной таблицей результатов."""
        lf = ttk.LabelFrame(parent, text=title, padding=(6, 2, 6, 4)); lf.pack(fill="x", pady=(0, 4), padx=(0, 4))
        t = ttk.Treeview(lf, columns=cols, show="headings", height=height, selectmode="none", style="Compact.Treeview")
        for k, text, w in heads:
            t.heading(k, text=text); t.column(k, width=w, minwidth=w, anchor="w" if k in ("ch", "m") else "center", stretch=False)
        t.tag_configure("bad", foreground=ERR, background="#fdecea"); t.tag_configure("good", foreground=OK)
        t.tag_configure("ao", background="#f3f6fb")
        t.pack(anchor="w")
        return t

    def restore_tables(self, r):
        """Повтор: таблица AI/AO пустая (программу перезапускали) — заполнить замерами прошлого прогона из .json."""
        tol = float(self.S["tol"])
        for kind, n in (("ai", 8), ("ao", 2)):
            data = r.get(kind)
            if isinstance(data, dict):
                data = [data.get(str(c), data.get(c)) for c in range(n)]
            for ch in range(n):
                try:
                    vals = [float(v) for v in data[ch]]
                except (TypeError, ValueError, IndexError, KeyError):
                    continue
                for i, v in enumerate(vals[:len(DEF_SP)]):
                    sp = float(DEF_SP[i])
                    self.set_point(kind, ch, i, sp, v, abs((v - sp) * 100 / 16) <= tol)

    def reset_tables(self):
        self.bad = {"ai": [False] * 8, "ao": [False] * 2}
        self.pt_bad = {"ai": {}, "ao": {}}          # (канал, точка) -> вне допуска по последнему замеру
        t = self.t_an
        for iid in t.get_children(): t.delete(iid)
        for i, v in enumerate(DEF_SP): t.heading(str(i), text=f"{v} мА")
        for kind, n in (("ai", 8), ("ao", 2)):
            for ch in range(n):
                t.insert("", "end", iid=f"{kind}{ch}", values=[f"{kind.upper()}{ch}"] + [""] * (len(DEF_SP) + 1),
                         tags=("ao",) if kind == "ao" else ())
        for iid in self.t_dio.get_children(): self.t_dio.delete(iid)
        S = self.S
        for iid, t in (("single", f"{S['dio_v_work']:g} В по одному"), (str(float(S["dio_v_hi"])), f"{S['dio_v_hi']:g} В, все DO"),
                       (str(float(S["dio_v_mid"])), f"{S['dio_v_mid']:g} В, все DO"), (str(float(S["dio_v_lo"])), f"{S['dio_v_lo']:g} В, все DO")):
            if not self.t_dio.exists(iid):
                self.t_dio.insert("", "end", iid=iid, values=[t] + [""] * 16)
        for iid in self.t_ci.get_children(): self.t_ci.delete(iid)

    # ---------------- вкладка «Журнал» ----------------
    def _tab_log(self, f):
        hr = ttk.Frame(f); hr.pack(fill="x", pady=(0, 4))
        ttk.Label(hr, text="Журнал (можно выделять и копировать)", font=FONT_B, foreground=C_ACCENT_DARK).pack(side="left")
        ttk.Button(hr, text="Очистить", command=self.clear_log).pack(side="right")
        ttk.Button(hr, text="Папка журналов", command=lambda: self.open_path(LOGDIR)).pack(side="right", padx=6)
        ttk.Button(hr, text="Открыть файл журнала", command=lambda: self.open_path(TRACE)).pack(side="right")
        ttk.Checkbutton(hr, text="Показывать обмен (→ отправлено, ← принято)", variable=self.verbose).pack(side="right", padx=10)
        box = tk.Frame(f, bg=C_PANEL, highlightbackground=C_LINE, highlightthickness=1); box.pack(fill="both", expand=True)
        self.logw = tk.Text(box, bg=C_PANEL, fg=C_TEXT, font=MONO, relief="flat", wrap="word", padx=10, pady=6,
                            highlightthickness=0, state="disabled", spacing3=2)
        sb = ttk.Scrollbar(box, orient="vertical", command=self.logw.yview); self.logw.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y"); self.logw.pack(side="left", fill="both", expand=True)
        for tag, col in (("ok", OK), ("err", ERR), ("warn", WARN), ("head", C_ACCENT_DARK), ("time", "#9aa3b1"),
                         ("dim", "#8a93a2"), ("tx", "#1a5fb4"), ("rx", "#26a269")):
            self.logw.tag_configure(tag, foreground=col)
        self.logw.tag_configure("head", font=("Consolas", 10, "bold"))

    def _tab_changed(self, e=None):
        cur = self.nb.select()
        prev = getattr(self, "_main_prev", None); self._main_prev = cur
        manual = str(self.tabs["manual"]) if "manual" in self.tabs else None
        if manual and cur == manual and prev != manual:
            self.m_enter()                       # ручной режим: сразу прочитать всё из платы
        elif manual and prev == manual and cur != manual:
            self.m_leave()                       # ушли — CH2 (24 В для DI / DO) выключить
        if self.nb.select() == str(self.tabs["log"]):
            self.err_count = 0
            self.nb.tab(self.tabs["log"], text="Журнал")

    # ================= обновление окна из потока =================
    def add_log(self, s, tag=None):
        if tag not in ("tx", "rx"):
            self._last_msg = s                       # для сторожа подключения: на чём остановилось
        if tag is None:
            if s.startswith("==="): tag = "head"
            elif s.startswith(("ОШИБКА", "!!!")) or any(m in s for m in ("НЕ СОВПАЛО", "ВНЕ ДОПУСКА", "ЗА ДОПУСК")): tag = "err"
            elif re.search(r"\bOK\b", s) or s.startswith(("ИТОГ: ГОДЕН", "Протокол сохранён")): tag = "ok"
            elif s.startswith("ИТОГ"): tag = "warn"
        self.logw.configure(state="normal")
        self.logw.insert("end", time.strftime("%H:%M:%S  "), "time"); self.logw.insert("end", s + "\n", tag or ())
        n = int(self.logw.index("end-1c").split(".")[0])
        if n > 20000: self.logw.delete("1.0", f"{n - 15000}.0")
        self.logw.see("end"); self.logw.configure(state="disabled")
        if tag not in ("tx", "rx", "dim"):
            self.last_lbl.configure(text=(s[:160] + "…") if len(s) > 160 else s,
                                    foreground={"err": ERR, "ok": OK, "warn": WARN}.get(tag, C_MUTED))
        if tag == "err" and self.nb.select() != str(self.tabs["log"]):
            self.err_count += 1
            self.nb.tab(self.tabs["log"], text=f"Журнал  ● {self.err_count}")

    def clear_log(self):
        self.logw.configure(state="normal"); self.logw.delete("1.0", "end"); self.logw.configure(state="disabled")

    def set_chip(self, text, color): self.chip.configure(text=text, bg=color)
    def set_dot(self, name, st, text=None):
        default = {"ok": "на связи", "err": "нет ответа", "man": "вручную", "off": "не проверено", "none": "не используется"}
        color = {"ok": OK, "err": ERR, "man": WARN, "off": "#9aa3b1", "none": "#9aa3b1"}[st]
        mark = "●" if st in ("ok", "err", "man") else "○"
        self.st[name].configure(text=f"{mark} {text or default[st]}", fg=color)

    def set_power_lbl(self):
        S = self.S
        if self.sim.get(): t = "симулятор"
        elif self.psu is None: t = "блок питания не подключён — питание платы включают вручную"
        else: t = f"CH1 {S['board_v']:g} В / {S['board_a']:g} А — " + ("включён" if self.board_power else "выключен")
        self.pwr_lbl.configure(text=t, foreground=OK if self.board_power else C_MUTED)

    def set_step(self, key, state):
        t, col = STATE.get(state, (state, C_MUTED))
        if key in self.step_lbl:
            self.step_lbl[key].configure(text=t, fg=col)

    def set_phase(self, text): self.phase_text = text; self.status.configure(text=text)
    def set_detail(self, text): self.detail_lbl.configure(text=("Сейчас: " + text) if text else "")
    def set_progress(self, done, total): self.bar["value"] = 100 * done / max(total, 1)
    def set_sub_progress(self, p):
        self.status.configure(text=f"{self.phase_text}… {p}%" if p else self.phase_text)
    def set_timer(self, sec):
        self.timer.configure(text="" if sec is None else f"{sec // 60:02d}:{sec % 60:02d}")
        self.btn_skip.configure(state="normal" if (sec is not None and self.phase_text == "Прогрев") else "disabled")

    def set_point(self, kind, ch, i, sp, v, good):
        """Точка AI/AO: строка — канал, столбец — точка. ✔/✖ и красная строка — по ПОСЛЕДНЕМУ замеру каждой
        точки: перемерили и попали в допуск — красное и крестик снимаются."""
        t, iid = self.t_an, f"{kind}{ch}"
        self.pt_bad[kind][(ch, i)] = not good
        head = t.heading(str(i), "text")
        if kind == "ai" and head != f"{sp:g} мА":
            t.heading(str(i), text=f"{sp:g} мА"); head = f"{sp:g} мА"
        vals = list(t.item(iid, "values"))
        vals[1 + i] = f"{v:.3f}" + ("" if head == f"{sp:g} мА" else f" ({sp:g})") + ("" if good else " ✖")
        done = [j for j in range(len(DEF_SP)) if (ch, j) in self.pt_bad[kind]]
        self.bad[kind][ch] = any(self.pt_bad[kind][(ch, j)] for j in done)
        if len(done) == len(DEF_SP):
            vals[-1] = "✖" if self.bad[kind][ch] else "✔"
        tags = ["ao"] if kind == "ao" else []
        if self.bad[kind][ch]: tags = ["bad"]
        elif len(done) == len(DEF_SP): tags.append("good")
        t.item(iid, values=vals, tags=tuple(tags))

    def set_dio(self, mode, n, good, mask):
        iid = "single" if mode == "single" else str(float(mode))
        if not self.t_dio.exists(iid):
            self.t_dio.insert("", "end", iid=iid, values=[f"{float(mode):g} В, все DO"] + [""] * 16)
        vals = list(self.t_dio.item(iid, "values"))
        if mode == "single":
            vals[1 + n] = "✔" if good else "✖"
        else:
            want = float(mode) >= float(self.S["dio_v_mid"])
            for i in range(16):
                vals[1 + i] = "✔" if bool(mask >> i & 1) == want else "✖"
        bad = any(x == "✖" for x in vals[1:])
        self.t_dio.item(iid, values=vals, tags=("bad",) if bad else ())

    def set_ci(self, ch, i, f, n, got, good):
        iid = f"p{i}"
        if not self.t_ci.exists(iid):
            self.t_ci.insert("", "end", iid=iid, values=[f"{f:g}", n, "", "", "", ""])
        vals = list(self.t_ci.item(iid, "values")); vals[2 + ch] = f"{got} {'✔' if good else '✖'}"
        self.t_ci.item(iid, values=vals, tags=("bad",) if any(str(x).endswith("✖") for x in vals[2:]) else ())

    # ================= порты =================
    NONE = {"board": None, "relay": "— вручную —", "cal": "— вручную —", "scan": "— не используется —",
            "rs485": "— не используется —", "psu": "— вручную —", "gen": "— вручную —"}

    def _key(self, name):
        return dict((n, k) for n, _, k, _ in self.dev_rows)[name]

    def refresh_ports(self, visa=True):
        lst = ports.list_ports()
        disp = [d for _, d in lst]
        for name, _, key, kind in self.dev_rows:
            if kind != "com":
                continue
            vals = ([self.NONE[name]] if self.NONE[name] else []) + disp
            self.combo[name].configure(values=vals)
            cur = ports.port_of(self.v[key].get())
            match = [d for p_, d in lst if p_ == cur]
            if match: self.v[key].set(match[0])
            elif not cur: self.v[key].set(self.NONE[name] or (disp[0] if disp else ""))
            else: self.v[key].set(f"{cur} — не найден")
        for name in ("psu", "gen"):
            key = self._key(name)
            if not self.v[key].get(): self.v[key].set(self.NONE[name])
            self.combo[name].configure(values=[self.NONE[name], self.v[key].get()] if self.v[key].get() != self.NONE[name] else [self.NONE[name]])
        if visa:
            def job():
                res = instruments.list_resources()
                self.root.after(0, self._apply_visa, res)
            threading.Thread(target=job, daemon=True).start()

    def _apply_visa(self, res):
        for name in ("psu", "gen"):
            key = self._key(name)
            self.combo[name].configure(values=[self.NONE[name]] + [d for _, d in res])
            cur = instruments.resource_of(self.v[key].get())
            m = [d for r_, d in res if r_ == cur]
            if m: self.v[key].set(m[0])
        self.add_log(f"VISA: найдено приборов USB — {len(res)}" + ("" if res else
                     " (нет pyvisa/NI-VISA или приборы не подключены)"), "dim")

    def _sync(self, fn, *a):
        """Выполнить fn в окне и дождаться (из фонового потока)."""
        box, ev = [], threading.Event()
        def go():
            try: box.append(fn(*a))
            finally: ev.set()
        self.root.after(0, go); ev.wait(10)
        return box[0] if box else None

    def port(self, key):
        """Порт из списка. «COMx — не найден» (был в прошлый раз, сейчас нет) у приборов = не подключён;
        у платы — пробуем открыть (порт может появиться после включения питания)."""
        disp = self.v[key].get()
        if key != "port" and "не найден" in disp:
            return ""
        return ports.port_of(disp)

    def update_sn(self):
        if not hasattr(self, "sn_lbl"):
            return
        code = self.v["protocol_number"].get().strip()
        if not code:
            self.sn_lbl.configure(text="отсканируйте или введите номер (изделия или платы — берутся последние 7 цифр)",
                                  foreground=C_MUTED)
            self.v["serial"].set(""); return
        try:
            full, model, sn = parse_label(code, self.S["proto_prefix"])
            self.v["serial"].set(str(sn))
            self.sn_lbl.configure(text=f"в плату — {sn} · в протокол — {full}", foreground=OK)
        except LabelError as e:
            self.v["serial"].set("")
            self.sn_lbl.configure(text=str(e), foreground=ERR)

    def on_scan(self, code):
        def apply():
            self.v["protocol_number"].set("".join(ch for ch in code if ch.isdigit()) or code)
            self.add_log(f"Сканер: {code}", "ok" if self.v["serial"].get() else "err")
        self.root.after(0, apply)

    # ---- операторы ----
    def oper_save(self):
        self.oper_cb.configure(values=self.operators); self.save()

    def oper_add(self):
        name = self.v["operator"].get().strip()
        if not name:
            messagebox.showinfo("Оператор", "Впишите ФИО в поле «Оператор» и нажмите «Добавить»."); return
        if name not in self.operators:
            self.operators.append(name); self.oper_save(); self.add_log(f"Оператор добавлен: {name}")

    def oper_del(self):
        name = self.v["operator"].get().strip()
        if name not in self.operators:
            messagebox.showinfo("Оператор", "Выберите в списке оператора, которого нужно удалить."); return
        if messagebox.askyesno("Оператор", f"Удалить «{name}» из списка операторов?"):
            self.operators.remove(name); self.v["operator"].set(""); self.oper_save()

    # ================= запуск программы: приборы, питание платы, порты =================
    def startup(self):
        """28.10.2026: при запуске сами подключаем блок питания и генератор, включаем CH1 (плата получает 24 В
        и появляется её COM-порт), затем ищем COM-порты платы, реле, CA71 и сканера."""
        if self.running or self.flashing or self.connecting:
            return
        self.add_log("Автоподключение: блок питания → питание платы → поиск COM-портов…", "head")
        self.connecting = True; self.apply_lock()
        threading.Thread(target=self._connect, kwargs=dict(auto=True), daemon=True).start()

    def autodetect(self):
        if self.running or self.flashing or self.connecting: return
        self.manual_close()
        self.connecting = True; self.apply_lock()
        threading.Thread(target=self._connect, kwargs=dict(auto=True), daemon=True).start()

    def com_conflict(self):
        """Один COM-порт выбран для двух приборов (номера COM поменялись) -> текст ошибки или ''."""
        seen = {}
        for name, label, key, kind in self.dev_rows:
            p = self.port(key) if kind == "com" else ""
            if p:
                if p.upper() in seen:
                    return f"Порт {p} выбран и для «{seen[p.upper()]}», и для «{label}». Выберите разные порты."
                seen[p.upper()] = label
        return ""

    def connect(self):
        if self.locked or self.running or self.flashing or self.connecting: return
        err = self.com_conflict()
        if err:
            messagebox.showwarning("Подключение", err); return
        self.save()
        self.manual_close()
        self.connecting = True; self.apply_lock()
        threading.Thread(target=self._connect, daemon=True).start()

    def _pick_visa(self, res, name, auto):
        """Выбранный оператором прибор — всегда он (даже если ответ *IDN? незнакомый, 02.11.2026).
        Автопоиск (запуск программы, «Автопоиск и подключение»): если выбранного нет — первый подходящий
        по ответу *IDN? или по USB VID:PID (второй АКИП-1168 отвечает «DPS3…»)."""
        key = self._key(name)
        cur = instruments.resource_of(self.v[key].get())
        if cur and (any(r_ == cur for r_, _ in res) or not res):
            return cur
        if not auto:
            return cur or None
        for r_, d in res:
            if instruments.kind_of(r_, d) == name:
                self.root.after(0, self.v[key].set, d)
                return r_
        return None

    def _open_visa(self, res, auto=False):
        a = self.root.after; log = self.ui.log; S = self.S
        new_psu = False
        self._drop_stale_visa(auto)
        if self.psu is None:
            pr = self._pick_visa(res, "psu", auto)
            if pr:
                try:
                    self.psu = instruments.PSU(pr, channel=2, log=lambda m: self.ui.log("АКИП-1168 " + m, "dim"),
                                               pause=S["psu_pause"])
                    new_psu = True
                    if self.psu.recognized:
                        a(0, self.set_dot, "psu", "ok", "на связи"); log(f"Блок питания: {self.psu.idn} — OK", "ok")
                    else:
                        a(0, self.set_dot, "psu", "man", "выбран вручную — команды шлю")
                        log(f"Блок питания {pr}: ответ «{self.psu.idn}» незнакомый, но прибор выбран — работаю с ним", "warn")
                except Exception as e:
                    self.psu = None; a(0, self.set_dot, "psu", "err", "не отвечает — вручную"); log(f"Блок питания: {e} — работаю без него (напряжения ставит оператор)", "warn")
            else:
                a(0, self.set_dot, "psu", "man", "вручную — напряжения ставит оператор")
        if self.gen is None:
            gr = self._pick_visa(res, "gen", auto)
            if gr:
                try:
                    self.gen = instruments.Generator(gr, log=lambda m: self.ui.log("АКИП-3409 " + m, "dim"),
                                                     pause=S["gen_pause"], margin=S["gen_margin"])
                    if self.gen.recognized:
                        a(0, self.set_dot, "gen", "ok"); log(f"Генератор: {self.gen.idn} — OK", "ok")
                    else:
                        a(0, self.set_dot, "gen", "man", "выбран вручную — команды шлю")
                        log(f"Генератор {gr}: ответ «{self.gen.idn}» незнакомый, но прибор выбран — работаю с ним", "warn")
                except Exception as e:
                    self.gen = None; a(0, self.set_dot, "gen", "err", "не отвечает — вручную"); log(f"Генератор: {e} — работаю без него (по подсказкам)", "warn")
            else:
                a(0, self.set_dot, "gen", "man", "вручную — по подсказкам программы")
        return new_psu

    def _drop_stale_visa(self, auto):
        """02.11.2026: «Подключить» берёт то, что выбрано. Уже открытый блок питания / генератор закрывается,
        если в списке выбран другой прибор (или «— вручную —» при ручном подключении) либо он не отвечает
        даже после переоткрытия сеанса. Блок питания закрывается без выключения выходов (CH1 питает плату)."""
        for name, what in (("psu", "Блок питания"), ("gen", "Генератор")):
            obj = getattr(self, name)
            if obj is None:
                continue
            cur = instruments.resource_of(self.v[self._key(name)].get())
            why = ""
            if cur and cur != obj.resource:
                why = f"в списке выбран другой прибор ({cur})"
            elif not cur and not auto:
                why = "в списке выбрано «— вручную —»"
            else:
                with self.ilock:
                    if not obj.alive():
                        why = "не отвечает"
            if why:
                self.ui.log(f"{what} {obj.resource}: {why} — отключаю", "warn")
                instruments._Visa.close(obj)
                setattr(self, name, None)
                if name == "psu":
                    self.board_power = False
                    self.root.after(0, self.set_power_lbl)

    def power_board(self, on=True, wait=True):
        """CH1 блока питания: питание платы. Включили — ждём, пока появится и ответит порт платы."""
        S, log = self.S, self.ui.log
        if self.psu is None:
            return False
        with self.ilock:
            if not on:
                self.psu.board_off(); self.board_power = False
                log("Блок питания: CH1 (питание платы) выключен", "warn")
                self.root.after(0, self.set_power_lbl); return True
            before = {p for p, _ in ports.list_ports()}
            self.psu.board_on(S["board_v"], S["board_a"]); self.board_power = True
        log(f"Блок питания: CH1 = {S['board_v']:g} В, ограничение {S['board_a']:g} А — питание платы включено", "ok")
        self.root.after(0, self.set_power_lbl)
        if not wait:
            return True
        bp = self.port("port"); baud = int(self.v["baud"].get() or 115200)
        t0 = time.time(); lim = float(S["board_wait"])
        while time.time() - t0 < lim:
            now = {p for p, _ in ports.list_ports()}
            if bp and bp in now and ports.probe_board(bp, baud):
                log(f"Плата ответила на {bp} через {time.time() - t0:.0f} с", "dim"); return True
            if now - before:
                time.sleep(1.5)
                log("Появился порт: " + ", ".join(sorted(now - before)), "dim"); return True
            time.sleep(1.0)
        log(f"За {lim:g} с порт платы не появился — проверьте кабель USB и питание", "warn")
        return True

    def board_power_cmd(self, on):
        if self.sim.get():
            self.add_log("Симулятор: питание платы не нужно"); return
        if self.psu is None:
            messagebox.showinfo("Питание платы", "Блок питания не подключён — включите/выключите питание платы вручную."); return
        if not on and self.running:
            messagebox.showwarning("Питание платы", "Идёт прогон — сначала остановите его."); return
        if not on and not messagebox.askyesno("Питание платы", "Выключить питание платы (CH1)?"): return
        self.manual_close()
        threading.Thread(target=lambda: self._bg(lambda: self.power_board(on, wait=False)), daemon=True).start()

    def _bg(self, fn):
        try: fn()
        except Exception as e: self.ui.log(f"{type(e).__name__}: {e}", "err")

    def _timed(self, fn, limit, what, default):
        """Выполнить fn не дольше limit секунд (в отдельном потоке); не успел — default и предупреждение.
        Пока прошлый такой же поток ещё висит (прибор VISA не ответил), новый не запускается —
        иначе два потока откроют один прибор одновременно."""
        hung = getattr(self, "_hung", {})
        self._hung = hung
        old = hung.get(what)
        if old is not None and old.is_alive():
            self.ui.log(f"{what}: прошлая попытка ещё ждёт ответа прибора — пропускаю (переподключите USB прибора)", "warn")
            return default
        box = []
        def run():
            try: box.append(fn())
            except Exception as e: self.ui.log(f"{what}: {type(e).__name__}: {e}", "warn")
        t = threading.Thread(target=run, daemon=True)
        t.start(); t.join(limit)
        if t.is_alive() or not box:
            if t.is_alive():
                hung[what] = t
                self.ui.log(f"{what}: нет ответа за {limit} с — продолжаю без этого (приборы — вручную)", "warn")
            return default
        return box[0]

    def _check_board_link(self, bp):
        a = self.root.after; log = self.ui.log
        if self.v["conn_mode"].get() != "Service Port":       # плата по RS-485 или Ethernet
            try:
                _, bp = self.board_link()
            except Exception as e:
                a(0, self.set_dot, "board", "err", "не настроено"); log(f"Плата: {e}", "err"); return
        if not bp:
            a(0, self.set_dot, "board", "err", "порт не найден"); log("Плата: порт не найден — выберите его вручную", "err"); return
        try:
            d = self._board()
            try:
                try: ai = d.ai_ma()[0]; log(f"Плата {bp}: связь есть (AI0 = {ai:.3f} мА) — OK", "ok"); a(0, self.set_dot, "board", "ok", f"на связи ({bp})")
                except Exception:   # плата может быть в режиме загрузчика
                    ln, crc = d.app_info(); log(f"Плата {bp}: отвечает только загрузчик (ПО {ln} байт, CRC16 {crc:04X})", "warn")
                    a(0, self.set_dot, "board", "man", "в загрузчике")
            finally: d.close()
        except Exception as e: a(0, self.set_dot, "board", "err"); log(f"Плата: {e}", "err")

    def _connect(self, auto=False):
        a = self.root.after; log = self.ui.log
        try:
            if self.sim.get():
                for n in ("board", "relay", "cal", "psu", "gen"): a(0, self.set_dot, n, "ok", "симулятор")
                a(0, self.set_dot, "scan", "none"); a(0, self.set_dot, "rs485", "none")
                a(0, self.set_power_lbl)
                log("Симулятор: оборудование не требуется", "ok"); return
            # 1) приборы VISA и питание платы
            # (VISA может «зависнуть» на приборе — тогда не ждём его и подключаем плату и COM-порты дальше)
            res = self._timed(instruments.list_resources, 20, "поиск приборов VISA", [])
            a(0, self._apply_visa, res)
            new_psu = self._timed(lambda: self._open_visa(res, auto), 25, "подключение блока питания и генератора", False)
            if self.psu is not None and (new_psu or not self.board_power):
                try:
                    self.power_board(True, wait=True)
                except Exception as e:
                    log(f"Блок питания: не удалось включить CH1 — {e}", "err")
            a(0, self.set_power_lbl)
            # 2) COM-порты
            self.close_com()
            if auto:
                saved = {"board": self.port("port"), "relay": self.port("relay_port"), "cal": self.port("cal_port")}
                known = ports.quick_check(saved, lambda t: log(t, "dim"))
                if not all(known.get(k) for k in ("board", "relay", "cal")):
                    log("Ищу остальные устройства по COM-портам…", "dim")
                found = ports.autodetect(lambda t: log(t, "dim"), busy={self.port("rs485_port")} - {""}, known=known)
                def apply():
                    lst = dict(ports.list_ports())
                    miss = []
                    for name, label, key, kind in self.dev_rows:
                        fk = "scanner" if name == "scan" else name
                        if kind != "com":
                            continue
                        if name == "rs485":          # переходник MOXA: подставляем, только если он ещё не выбран
                            if found.get("rs485") and not self.port("rs485_port"):
                                self.v[key].set(lst.get(found["rs485"], found["rs485"]))
                            continue
                        if found.get(fk):
                            self.v[key].set(lst.get(found[fk], found[fk]))
                        else:
                            miss.append(label)
                            if name != "board": self.v[key].set(self.NONE[name])
                    log("Автопоиск: " + ("найдено всё" if not miss else "не найдено — " + ", ".join(miss)
                                         + " (без них — вручную по подсказкам)"), "ok" if not miss else "warn")
                self._sync(apply)
            rp, cp, sp, bp = self.port("relay_port"), self.port("cal_port"), self.port("scan_port"), self.port("port")
            self._check_board_link(bp)
            S = self.S
            if rp:
                try:
                    self.relay = Relay(rp, settle=S["relay_settle"], log=lambda m: self.ui.log(m, "warn"))
                    if self.relay.recognized:
                        a(0, self.set_dot, "relay", "ok", f"на связи ({rp})"); log(f"Блок реле {rp}: OK", "ok")
                    else:
                        a(0, self.set_dot, "relay", "man", f"{rp}: не ответил на ID? — команды шлю")
                except Exception as e:
                    self.relay = None; a(0, self.set_dot, "relay", "err", "не отвечает — вручную")
                    log(f"Блок реле {rp}: {e} — работаю без него (провода переключает оператор)", "warn")
            else: a(0, self.set_dot, "relay", "man", "вручную — провода переключает оператор")
            if cp:
                try:
                    self.cal = CA71(cp, log=lambda m: self.ui.log(m, "dim"))
                except Exception as e:
                    self.cal = None; a(0, self.set_dot, "cal", "err", "порт не открылся — вручную")
                    log(f"CA71 {cp}: {e} — работаю без него (токи ставит оператор)", "warn")
                else:
                    try:
                        self.cal.init()
                        a(0, self.set_dot, "cal", "ok", f"на связи ({cp})"); log(f"CA71 {cp}: OK", "ok")
                    except Exception as e:   # порт выбран оператором — не закрываем, команды всё равно шлём
                        a(0, self.set_dot, "cal", "man", f"{cp}: не ответил — команды шлю")
                        log(f"CA71 {cp}: {e} — порт выбран, команды всё равно отправляю "
                            "(если не ответит в прогоне — «Повторить / Дальше вручную»)", "warn")
            else: a(0, self.set_dot, "cal", "man", "вручную — токи ставит оператор")
            if sp:
                try: self.scanner = ports.Scanner(sp, self.on_scan); a(0, self.set_dot, "scan", "ok", "готов к скану"); log(f"Сканер {sp}: OK", "ok")
                except Exception as e: a(0, self.set_dot, "scan", "err"); log(f"Сканер {sp}: {e} — номер можно ввести вручную", "warn")
            else: a(0, self.set_dot, "scan", "none")
            r4 = self.port("rs485_port")
            a(0, self.set_dot, "rs485", "ok" if r4 else "man", f"выбран {r4}" if r4 else "нет — RS-485 отметить вручную")
        except Exception as e:
            log(f"Подключение: {type(e).__name__}: {e}", "err")
        finally:
            def done():
                self.connecting = False; self.apply_lock()
            a(0, done)

    # ================= действия =================
    def save(self):
        self._save_job = None
        d = {k: v.get() for k, v in self.v.items()}
        d.update({"step_" + k: v.get() for k, v in self.steps.items()})
        d.update({"auto_" + k: v.get() for k, v in self.auto.items()})
        d.update(force=self.force.get(), sim=self.sim.get(), verbose=self.verbose.get(), operators=self.operators,
                 settings=self.S, manual_cmd=getattr(self, "cmd_hist", [])[-30:])
        try:
            with open(CFG, "w", encoding="utf-8") as f:
                json.dump(d, f, ensure_ascii=False, indent=1)
        except Exception as e:
            self.add_log(f"Настройки не сохранены: {e}", "warn")

    def trace(self, direction, frame, text):
        """Каждый кадр: в файл — целиком, на экран — с расшифровкой (блоки ПО — сокращённо)."""
        arrow = {"tx": "→", "rx": "←"}.get(direction, "!!")
        try:
            with open(TRACE, "a", encoding="utf-8") as f:
                f.write(f"{time.strftime('%H:%M:%S')} {arrow} {hx(frame)}\n           | {text}\n")
        except Exception: pass
        if self.verbose.get():
            short = hx(frame, 24) if len(frame) > 64 else hx(frame)
            self.ui.log(f"{arrow} {short}   | {text}", {"tx": "tx", "rx": "rx"}.get(direction, "err"))

    # ================= блокировка после загрузки ПО =================
    def write_lock(self, state):
        try:
            if state:
                with open(LOCKFILE, "w", encoding="utf-8") as f:
                    json.dump({"с": time.strftime("%d.%m.%Y %H:%M:%S"), "порт": self.port("port")}, f, ensure_ascii=False)
            elif os.path.exists(LOCKFILE):
                os.remove(LOCKFILE)
        except Exception as e:
            self.ui.log(f"Не удалось записать файл блокировки: {e}", "err")
        self.locked = state

    CONNECT_LIMIT = 150                              # с: дольше подключение не идёт — разблокировать кнопки

    def connect_watchdog(self):
        """03.11.2026 (1.29): поток подключения завис (порт не открывается, прибор не отвечает) — раньше кнопки
        «Подключить», «Автопоиск», «Обновить список» оставались неактивными до перезапуска программы."""
        t0 = getattr(self, "_conn_t0", None)
        if self.connecting and t0 and time.time() - t0 > self.CONNECT_LIMIT:
            self.add_log(f"!!! Подключение не закончилось за {self.CONNECT_LIMIT} с — кнопки разблокированы. "
                         f"Последнее действие: «{getattr(self, '_last_msg', '')}». Пришлите файл журнала (папка logs)", "err")
            self.connecting = False
            self.apply_lock()

    def apply_lock(self):
        if self.connecting:
            if not getattr(self, "_conn_t0", None):
                self._conn_t0 = time.time()
        else:
            self._conn_t0 = None
        busy = self.running or self.flashing or self.connecting
        if self.locked:
            self.lock_bar.pack(fill="x", padx=10, pady=(0, 6), after=self.lock_anchor)
            for b in (self.btn_start, self.btn_conn, self.btn_flash, self.btn_retry): b.config(state="disabled")
        else:
            self.lock_bar.pack_forget()
            for b in (self.btn_conn, self.btn_start, self.btn_flash, self.btn_retry, self.btn_auto, self.btn_refresh, self.btn_disc):
                b.config(state="disabled" if busy else "normal")
        self.manual_enable(not (self.locked or busy))

    def _board(self):
        kw, _ = self.board_link()
        try:
            return MK201(trace=self.trace, **kw)
        except Exception:
            # Service Port новой платы — другой COM: старого порта уже нет. Ищем плату и пробуем ещё раз.
            if self.v["conn_mode"].get() != "Service Port" or not self.ensure_board_port():
                raise
            kw, _ = self.board_link()
            return MK201(trace=self.trace, **kw)

    def ensure_board_port(self, check=False):
        """Найти Service Port платы, если выбранного порта нет или (check=True) плата на нём не отвечает.
        Найденный порт ставится в список «Плата MK201». Вызывать из фонового потока. -> порт или None."""
        if self.sim.get() or self.v["conn_mode"].get() != "Service Port":
            return self.port("port") or None
        cur = self.port("port")
        have = {p for p, _ in ports.list_ports()}
        baud = int(self.v["baud"].get() or 115200)
        if cur in have and (not check or ports.probe_board(cur, baud)):
            return cur
        log = self.ui.log
        log(f"Плата: {'порта ' + cur + ' нет' if cur and cur not in have else (cur or 'порт не выбран') + ' — плата не отвечает'}"
            " — ищу Service Port новой платы…", "warn")
        exclude = {self.port(k) for k in ("relay_port", "cal_port", "scan_port", "rs485_port")}
        for o in (self.relay, self.cal):                 # открытые нами порты не трогаем
            exclude.add(getattr(o, "port", "") or "")
        found = ports.find_board(exclude=exclude - {""}, prefer=cur if cur in have else "",
                                 log=lambda t: log(t, "dim"), baud=baud)
        if not found:
            log("Плата не найдена ни на одном COM-порту — проверьте USB-кабель Service Port и питание платы", "err")
            return None
        disp = dict(ports.list_ports()).get(found, found)
        done = threading.Event()
        def apply():
            self.refresh_ports(visa=False); self.v["port"].set(disp); self.save(); done.set()
        self.root.after(0, apply); done.wait(3)
        log(f"Плата найдена: {found}" + (f" (был {cur})" if cur and cur != found else ""), "ok")
        self.root.after(0, self.set_dot, "board", "ok", f"на связи ({found})")
        return found

    def check_board(self):
        self.manual_close()
        def job():
            log = self.ui.log
            if self.sim.get():
                self.write_lock(False); self.root.after(0, self.apply_lock); log("Симулятор: блокировка снята", "ok"); return
            try:
                d = self._board()
            except Exception as e:
                log(f"Порт платы не открылся: {e}", "err"); return
            try:
                if firmware.app_alive(d):
                    ln, crc = d.app_info()
                    log(f"Основная программа платы отвечает (ПО {ln} байт, CRC16 {crc:04X}) — блокировка снята. OK", "ok")
                    self.write_lock(False); self.root.after(0, self.apply_lock)
                else:
                    try:
                        ln, crc = d.app_info()
                        log(f"Плата отвечает только загрузчиком (ПО {ln} байт, CRC16 {crc:04X}): основная программа "
                            "не запущена. Подождите и проверьте ещё раз; если так больше 3 минут — «Перезагрузить плату».", "warn")
                    except Exception:
                        log("Плата не отвечает. Питание не отключать, проверьте через минуту.", "warn")
            finally:
                d.close()
        threading.Thread(target=job, daemon=True).start()

    def reboot_board(self):
        if not messagebox.askyesno("MK201 Auto", "Отправить плате команду перезагрузки (как конфигуратор)?\n\n"
                                   "Делайте это, только если загрузка ПО закончилась больше 3 минут назад."):
            return
        self.manual_close()
        def job():
            log = self.ui.log
            try:
                d = self._board()
            except Exception as e:
                log(f"Порт платы не открылся: {e}", "err"); return
            try:
                firmware.reboot(d, log)
                log("Жду запуска основной программы (до 60 с)…")
                t0 = time.time()
                while time.time() - t0 < 60:
                    time.sleep(2)
                    if firmware.app_alive(d):
                        log("Плата перезапустилась, основная программа отвечает — блокировка снята. OK", "ok")
                        self.write_lock(False); self.root.after(0, self.apply_lock); return
                log("Плата не вышла на связь после перезагрузки.", "warn")
            except Exception as e:
                log(f"Программная перезагрузка не прошла ({e}). Если загрузка ПО закончилась больше 3 минут назад, "
                    "перезапустите плату по питанию и нажмите «Проверить плату».", "warn")
            finally:
                d.close()
        threading.Thread(target=job, daemon=True).start()

    def force_unlock(self):
        if messagebox.askyesno("MK201 Auto", "Снять блокировку вручную?\n\nДелайте это, только если плату уже "
                               "перепрошили (DFU + ПО конфигуратором) или точно знаете, что она работает."):
            self.write_lock(False); self.apply_lock(); self.add_log("Блокировка снята вручную", "warn")

    # ================= обновление ПО (отдельная кнопка, не шаг проверки) =================
    def flash(self):
        if self.locked or self.running or self.flashing: return
        if self.v["conn_mode"].get() != "Service Port":
            messagebox.showwarning("MK201 Auto", "Загрузка ПО — только через Service Port.\n"
                                   "Выберите «Service Port» в «Связь с платой» на вкладке «Подключение»."); return
        path = self.v["hex_path"].get().strip()
        if not os.path.isfile(path):
            messagebox.showwarning("MK201 Auto", "Выберите файл ПО (.hex)"); return
        if not messagebox.askyesno("MK201 Auto", f"Записать ПО в плату?\n\n{os.path.basename(path)}\n\n"
                                   "Во время записи не отключайте питание и USB."):
            return
        self.save(); self.manual_close(); self.flashing = True; self.ui.stopped = False
        self.apply_lock()
        self.set_chip("Запись ПО…", C_ACCENT); self.set_phase("Обновление ПО"); self.set_detail("запись ПО в плату")
        try: open(TRACE, "a", encoding="utf-8").write(f"\n===== запись ПО {time.strftime('%d.%m.%Y %H:%M:%S')} =====\n")
        except Exception: pass
        def job():
            fin = ("Ошибка записи ПО", ERR); dev = None
            try:
                if self.sim.get():
                    dev = make_sim()[0]; self.ui.log("СИМУЛЯТОР: запись ПО", "warn")
                else:
                    dev = self._board()
                res = firmware.upload_app(dev, path, self.ui.log, progress=self.ui.sub_progress,
                                          stop=lambda: None, force=self.force.get(),
                                          countdown=self.ui.countdown, lock=self.ui.fw_lock)
                fin = ("ПО уже было в плате", OK) if res == "same" else ("ПО записано", OK)
            except Exception as e:
                self.ui.log(f"ОШИБКА записи ПО: {e}")
            finally:
                try: dev and dev.close()
                except Exception: pass
                def done():
                    self.flashing = False; self.set_timer(None); self.set_sub_progress(0); self.set_detail("")
                    self.set_chip(*fin); self.status.configure(text=fin[0]); self.apply_lock()
                self.root.after(0, done)
        threading.Thread(target=job, daemon=True).start()

    def close_one(self, name):
        o = getattr(self, name, None)
        try: o and o.close()
        except Exception: pass
        setattr(self, name, None)

    def close_com(self):
        for o in (self.relay, self.cal, self.scanner):
            try: o and o.close()
            except Exception: pass
        self.relay = self.cal = self.scanner = None

    def close_extra(self):
        self.close_com()
        for o in (self.psu, self.gen):
            try: o and o.close()
            except Exception: pass
        self.psu = self.gen = None

    def disconnect(self):
        if self.running or self.connecting or self.flashing: return
        self.manual_close(); self.close_extra()
        for n, _, _, _ in self.dev_rows: self.set_dot(n, "off")
        self.set_power_lbl()
        self.add_log("Отключено (CH1 блока питания не выключался — кнопка «Питание платы: Выключить»)")

    # ================= протоколы: папка на номер, дописывание за день, журнал плат =================
    def proto_root(self):
        t = self.v["template"].get().strip()
        return self.v["outdir"].get().strip() or (os.path.dirname(t) if t else APPDIR)

    @staticmethod
    def safe(name):
        return re.sub(r'[\\/:*?"<>|]+', "_", name).strip() or "без_номера"

    def today_protocol(self, num):
        """Протокол этого номера за сегодня (самый свежий) и его .json -> (xlsx|None, dict)."""
        folder = os.path.join(self.proto_root(), self.safe(num))
        pref = f"Протокол_{self.safe(num)}_{time.strftime('%Y-%m-%d')}_"
        try:
            names = [n for n in os.listdir(folder) if n.startswith(pref) and n.endswith(".xlsx") and not n.startswith("~$")]
        except OSError:
            names = []
        if not names:
            return None, {}
        path = max((os.path.join(folder, n) for n in names), key=os.path.getmtime)
        info = {}
        try:
            with open(os.path.splitext(path)[0] + ".json", encoding="utf-8") as f:
                info = json.load(f)
        except Exception:
            pass
        return path, info

    def latest_json(self, num, need):
        """Последний .json этого номера (любой день), где есть results[need] — для «Восстановить калибровку»."""
        folder = os.path.join(self.proto_root(), self.safe(num))
        try:
            files = sorted((os.path.join(folder, n) for n in os.listdir(folder) if n.endswith(".json")),
                           key=os.path.getmtime, reverse=True)
        except OSError:
            return None, None
        for p in files:
            try:
                with open(p, encoding="utf-8") as f:
                    d = json.load(f)
                if d.get("results", {}).get(need):
                    return p, d["results"][need]
            except Exception:
                continue
        return None, None

    def journal(self, cfg, res, steps):
        """Журнал плат (CSV, открывается в Excel): дата, номер, заключение, что не прошло, протокол."""
        path = os.path.join(self.proto_root(), "журнал_плат.csv")
        allsteps = res.get("steps_all") or res.get("steps", {})
        bad = [STEP_NAMES.get(k, k) for k, v in allsteps.items() if v in ("fail", "warn")]
        row = [time.strftime("%d.%m.%Y"), time.strftime("%H:%M:%S"), cfg.get("protocol_number", ""), cfg.get("serial", ""),
               cfg.get("operator", ""), res.get("conclusion", "остановлено" if res.get("stopped") else ""),
               ", ".join(bad), ", ".join(STEP_NAMES.get(k, k) for k in steps), res.get("protocol", ""), VERSION]
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            new = not os.path.exists(path)
            with open(path, "a", newline="", encoding="utf-8-sig") as f:
                w = csv.writer(f, delimiter=";")
                if new:
                    w.writerow(["Дата", "Время", "Номер изделия", "Серийный в плате", "Оператор", "Заключение",
                                "Не пройдено", "Выполнялись шаги", "Протокол", "Версия программы"])
                w.writerow(row)
        except PermissionError:
            self.ui.log("Журнал плат открыт в Excel — строка не записана (закройте файл)", "warn")
        except Exception as e:
            self.ui.log(f"Журнал плат не записан: {e}", "warn")

    def failed_steps(self):
        code = self.v["protocol_number"].get().strip()
        try:
            num = parse_label(code, self.S["proto_prefix"])[0] if code else "без_номера"
        except LabelError:
            return None, []
        _, info = self.today_protocol(num)
        r = info.get("results", {})
        allsteps = r.get("steps_all") or r.get("steps") or {}
        return num, [k for k, _ in STEPS if allsteps.get(k) in ("fail", "warn")]

    def retry_failed(self):
        if self.running or self.flashing or self.connecting: return
        num, failed = self.failed_steps()
        if num is None:
            messagebox.showwarning("MK201 Auto", "Неверный номер изделия"); return
        if not failed:
            messagebox.showinfo("MK201 Auto", f"Для № {num} за сегодня нет непройденных шагов\n"
                                "(или по этому номеру сегодня ещё не было прогона)."); return
        if messagebox.askyesno("Повторить непройденные",
                               f"№ {num}. Повторить шаги:\n\n" + "\n".join("• " + STEP_NAMES[k] for k in failed)
                               + "\n\nРезультаты допишутся в тот же протокол."
                               + ("\nПроверка AI / AO: перемеряются только каналы вне допуска, годные берутся из прошлого прогона."
                                  if {"ai_check", "ao_check"} & set(failed) else "")):
            self.start(only=failed)

    # ================= прогон =================
    def start(self, only=None):
        if self.locked:
            messagebox.showwarning("MK201 Auto", "Плата после загрузки ПО не подтвердила запуск. Нажмите «Проверить плату»."); return
        if self.flashing or self.running: return
        if self.connecting:
            messagebox.showinfo("MK201 Auto", "Идёт подключение приборов — подождите несколько секунд."); return
        if not self.v["operator"].get().strip():
            self.add_log("ВНИМАНИЕ: не выбран оператор — строка «Измерения провели» в протоколе останется прежней", "warn")
        self.m_dio_power(False)                    # 24 В ручного режима на CH2 — выключить, дальше CH2 у сценария
        self.manual_close()
        self.run_steps = list(only) if only else [k for k, _ in STEPS if self.steps[k].get()]
        self.retry_mode = bool(only)              # «Повторить непройденные»: AI/AO — только каналы вне допуска
        self.running = True; self.apply_lock()
        self.save(); self.ui.stopped = False; self.ui.skip = False
        if only:
            # 02.11.2026: повтор непройденных — прошедшие шаги и галочки в таблицах остаются,
            # меняется только то, что перепроверяется
            num = self.failed_steps()[0] or "без_номера"
            _, info = self.today_protocol(num)
            r = info.get("results", {})
            prev = r.get("steps_all") or r.get("steps") or {}
            for k in self.step_lbl:
                self.set_step(k, "wait" if k in self.run_steps else prev.get(k, "skip"))
            if getattr(self, "table_num", None) != num:     # в таблицах другая плата (или пусто) — из протокола
                self.reset_tables()
                self.restore_tables(r)
            self.table_num = num
        else:
            for k in self.step_lbl: self.set_step(k, "wait" if k in self.run_steps else "skip")
            self.reset_tables()
            try:
                code = self.v["protocol_number"].get().strip()
                self.table_num = parse_label(code, self.S["proto_prefix"])[0] if code else "без_номера"
            except LabelError:
                self.table_num = None
        self.bar["value"] = 0; self.set_timer(None)
        self.stop_btn.config(state="normal"); self.btn_open.config(state="disabled")
        self.set_chip("Выполняется…", C_ACCENT); self.set_phase("Подготовка")
        self.nb.select(self.tabs["check"])
        try: open(TRACE, "a", encoding="utf-8").write(f"\n===== прогон {time.strftime('%d.%m.%Y %H:%M:%S')}  версия {VERSION} =====\n")
        except Exception: pass
        threading.Thread(target=self.work, daemon=True).start()

    def stop(self):
        self.ui.stopped = True; self.stop_btn.config(state="disabled"); self.add_log("Остановка после текущего действия…", "warn")

    def skip_timer(self): self.ui.skip = True

    def open_path(self, p):
        if not p or not os.path.exists(p): return
        if sys.platform.startswith("win"): os.startfile(p)
        else: subprocess.Popen(["xdg-open", p])

    def open_protocol(self): self.open_path(self.last_protocol)

    def open_outdir(self):
        self.open_path(self.proto_root())

    def finish(self, text, color):
        self.running = False
        self.stop_btn.config(state="disabled"); self.set_timer(None); self.set_detail("")
        self.apply_lock()
        if self.last_protocol and os.path.exists(self.last_protocol): self.btn_open.config(state="normal")
        self.set_chip(text, color); self.status.configure(text=text)

    def work(self):
        ui = self.ui; dev = None; fin = ("Ошибка", ERR); S = dict(self.S)
        try:
            v = {k: x.get().strip() for k, x in self.v.items()}
            steps = list(self.run_steps)
            if not steps:
                raise ValueError("Не выбран ни один шаг в «Что выполнять»")
            full = ""
            if v["protocol_number"]:
                full, model, sn_int = parse_label(v["protocol_number"], S["proto_prefix"])
                v["serial"] = str(sn_int)
            if "serial" in steps:
                if not full:
                    raise ValueError("Не указан номер с этикетки — нечего писать в плату")
                MK201.mac_to_regs(v["mac"])
            if not os.path.isfile(v["template"]):
                if {"power", "ai_check", "ao_check", "dio", "ci", "eth", "rs485"} & set(steps):
                    raise ValueError("Не выбран шаблон протокола (.xlsx) на вкладке «Подключение»")
                v["template"] = ""
            sim = self.sim.get()
            if sim:
                dev, relay, cal = make_sim(); psu, gen = make_sim_instruments()
                ui.log("РЕЖИМ СИМУЛЯТОРА — реальное оборудование не используется", "warn")
            else:
                if self.psu is not None and not self.board_power:
                    try:
                        self.power_board(True, wait=True)
                    except Exception as e:
                        ui.log(f"Блок питания: не удалось включить CH1 ({e}) — питание платы проверьте вручную", "warn")
                # 29.10.2026: плата подключается первой и сама по себе — остальные приборы её не блокируют
                err = self.com_conflict()
                if err:
                    raise RuntimeError(err + " (вкладка «Подключение»)")
                self.ensure_board_port(check=True)          # новая плата — новый COM Service Port
                try:
                    _, bp = self.board_link()
                except Exception as e:
                    raise RuntimeError(f"{e} — вкладка «Подключение»") from None
                ui.log(f"Подключаюсь к плате: {bp}…")
                try:
                    dev = self._board()
                except Exception as e:
                    raise RuntimeError(f"Плата ({bp}) не подключилась: {e}") from None
                rp, cp = self.port("relay_port"), self.port("cal_port")
                if self.relay is not None:
                    try:
                        self.relay.ping()     # порт мог «пропасть» (переподключение USB) — переоткроется сам
                    except Exception as e:
                        ui.log(f"Блок реле не отвечает ({e}) — работаю вручную", "warn")
                        self.close_one("relay"); self.root.after(0, self.set_dot, "relay", "err", "не отвечает — вручную")
                elif rp:
                    try:
                        self.relay = Relay(rp, settle=S["relay_settle"], log=lambda m: ui.log(m, "warn"))
                        self.root.after(0, self.set_dot, "relay", "ok" if self.relay.recognized else "man",
                                        f"на связи ({rp})" if self.relay.recognized else f"{rp}: не ответил на ID? — команды шлю")
                    except Exception as e:
                        self.relay = None
                        ui.log(f"Блок реле {rp} не открылся ({e}) — работаю вручную", "warn")
                        self.root.after(0, self.set_dot, "relay", "err", "не отвечает — вручную")
                if self.cal is None and cp:
                    try:
                        self.cal = CA71(cp, log=lambda m: ui.log(m, "dim"))
                    except Exception as e:
                        self.cal = None
                        ui.log(f"Калибратор CA71 {cp}: порт не открылся ({e}) — токи вручную", "warn")
                        self.root.after(0, self.set_dot, "cal", "err", "порт не открылся — вручную")
                    else:
                        try:
                            self.cal.init()
                            self.root.after(0, self.set_dot, "cal", "ok", f"на связи ({cp})")
                        except Exception as e:
                            ui.log(f"Калибратор CA71 {cp} не ответил ({e}) — порт выбран, команды всё равно отправляю", "warn")
                # нет прибора — работаем вручную: провода/токи/напряжения/генератор по подсказкам;
                # прибор пропал во время прогона — окно «Повторить / Дальше вручную» (fallback.SafeDev)
                ask = lambda t: ui.ask(t, "ОК", "Остановить")
                relay = (SafeDev(self.relay, "Блок реле", ui, NoRelay(lambda p: ui.confirm(p))) if self.relay
                         else NoRelay(lambda p: ui.confirm(p)))
                cal = SafeDev(self.cal, "Калибратор CA71", ui, ManualCal(ui), falsy_when_manual=True) if self.cal else None
                psu = (SafeDev(self.psu, "Блок питания", ui, instruments.ManualPSU(ask)) if self.psu
                       else instruments.ManualPSU(ask))
                gen = (SafeDev(self.gen, "Генератор", ui, instruments.ManualGen(lambda t: (ui.confirm(t) or
                       (_ for _ in ()).throw(RuntimeError("Остановлено оператором"))), margin=S["gen_margin"]))
                       if self.gen else None)   # None -> сценарий сам перейдёт на подсказки (ManualGen)
                for name, obj, what in (("relay", self.relay, "блок реле — каналы AI/AO переключает оператор"),
                                        ("cal", self.cal, "калибратор — токи ставит/читает оператор"),
                                        ("psu", self.psu, "блок питания — напряжения ставит оператор"),
                                        ("gen", self.gen, "генератор — настраивает и запускает оператор")):
                    if obj is None:
                        ui.log(f"Не подключён {what}", "warn")
                self.root.after(0, self.set_dot, "board", "ok", f"на связи ({bp})")
            num = full or "без_номера"
            base, prev = self.today_protocol(num) if v["template"] else (None, {})
            folder = os.path.join(self.proto_root(), self.safe(num))
            out = base or os.path.join(folder, f"Протокол_{self.safe(num)}_{time.strftime('%Y-%m-%d_%H%M%S')}.xlsx")
            if base:
                ui.log(f"Сегодня по № {num} уже был прогон — результаты допишутся в {os.path.basename(base)}")
            cfg = dict(S)
            cfg.update(serial=v["serial"], mac=v["mac"], template=v["template"], hv=v["hv"], hex_path=v["hex_path"],
                       tol_percent=S["tol"], fw_force=self.force.get(),
                       warm_min=0 if sim else S["warm"], samples=2 if sim else S["samples"],
                       protocol_number=num, out_path=out, operator=v["operator"],
                       ifaces={n: x.get() for n, x in self.iface.items()},
                       gen_method=v["gen_method"] or "burst", gen_trig=v["gen_trig"] or "C1:BTWV MTRIG",
                       rs485_port="" if sim else self.port("rs485_port"), sim=sim, version=VERSION, **self.link_cfg(),
                       unit=int(v["unit"] or 1), base_protocol=base, retry_bad=bool(getattr(self, "retry_mode", False)),
                       prev_steps=(prev.get("results", {}).get("steps_all") or prev.get("results", {}).get("steps") or {}),
                       prev_results=prev.get("results", {}))
            if sim:
                cfg.update(ai_settle=0, ao_settle=0, reassert_settle=0, psu_settle=0, dio_on=0, dio_volt=0,
                           cal_settle=0, power_settle=0, relay_settle=0, gen_margin=0)
            self.last_protocol = out if v["template"] else None
            if not sim and (self.gen is not None or self.psu is not None) and not self.ilock.acquire(timeout=2):
                raise RuntimeError("Прибор занят командой из ручного режима (ждёт ответа). Подождите или переподключите приборы")
            try:
                res = Runner(dev, relay, ui, cfg, cal, psu=psu, gen=gen).run(steps)
            finally:
                if not sim and (self.gen is not None or self.psu is not None):
                    self.ilock.release()
                if v["template"]:
                    self.last_protocol = cfg["out_path"]       # могло смениться, если файл был открыт в Excel
            if v["template"]:
                self.journal(cfg, res, steps)
            _, failed = self.failed_steps()
            if ui.stopped: fin = ("Остановлено", WARN); ui.log("ИТОГ: остановлено оператором")
            elif res["ok"] and not failed: fin = ("Готово — годен", OK); ui.log("ИТОГ: ГОДЕН")
            else:
                fin = ("Есть замечания", WARN); ui.log("ИТОГ: ЕСТЬ ЗАМЕЧАНИЯ — см. журнал")
                if failed:
                    ui.log("Не пройдено: " + ", ".join(STEP_NAMES[k] for k in failed)
                           + " — после исправления нажмите «Повторить непройденные»", "warn")
        except Exception as e:
            ui.log(f"ОШИБКА: {type(e).__name__}: {e}")
            try:
                import traceback
                with open(TRACE, "a", encoding="utf-8") as f:
                    f.write(traceback.format_exc())
            except Exception: pass
        finally:
            try: dev and dev.close()
            except Exception: pass
            self.root.after(0, self.finish, *fin)

    def close(self):
        if self.running and not messagebox.askyesno("MK201 Auto", "Идёт прогон. Остановить и закрыть программу?\n\n"
                                                    "Блок питания будет выключен (CH1 и CH2)."):
            return
        if self.flashing:
            messagebox.showwarning("MK201 Auto", "Идёт запись ПО — дождитесь окончания, питание не отключать."); return
        self.ui.stopped = True
        self.save(); self.manual_close()
        if self.psu is not None:                 # 28.10.2026: при закрытии выключаем блок питания (CH1 и CH2)
            try:
                self.ilock.acquire(timeout=3)
                self.psu.board_off(); self.psu.off()
            except Exception:
                pass
        self.close_extra(); self.root.destroy()


if __name__ == "__main__":
    App().root.mainloop()
