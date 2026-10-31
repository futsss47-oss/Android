"""
check_logic - последовательность одиночной проверки MIB-211 / MIB-212 на мини-стенде.

Отличия от MIB_Calibrator (auto_logic.Sequencer):
  * каналы переключает блок коммутации стенда, оператору ничего переключать не нужно;
  * питание изделия подаёт стенд, ток потребления измеряется и проверяется;
  * точка «20 мА, 400 Ом» измеряется по-настоящему: стенд включает нагрузку 400 Ом;
  * два режима: «Проверка» (только измерение, коэффициенты не меняются) и
    «Калибровка + проверка» (подбор коэффициента тем же алгоритмом, что в MIB_Calibrator);
  * самоконтроль стенда без изделия;
  * каждая проверка дописывается в журнал Журнал_проверок.csv и в отдельный отчёт .csv.

Объект io (интерфейс): log(text), state(name), timer(remaining, total),
point(ch, label, meas, err, ok), supply(ma, volts), done(path, lines, ok), finished().
"""
import csv
import datetime as dt
import os
import threading
import time
from dataclasses import dataclass

from algorithm import (CELLS, L1_CALIB, L1_DECISION, POINTS, Config, Stopped, calibrate_channel,
                       channels_for, err_pct, pause)
from auto_logic import Devices
from xlsx_patch import patch_xlsx

LOAD_LABEL = "20 (400 Ом)"
LOAD_KEY = "400"          # ключ точки с нагрузкой в словаре измерений
MODE_CHECK, MODE_CAL = "check", "cal"
EPS = 1e-9


@dataclass
class StandConfig(Config):
    i_min: float = 10.0          # допустимый ток потребления изделия, мА (уточнить по ТУ)
    i_max: float = 150.0
    power_settle: float = 3.0    # пауза после подачи питания, с
    self_tol: float = 0.05       # допуск самоконтроля стенда, %
    keepalive: float = 5.0       # период опроса стенда во время проверки, с
    stand_wdt: int = 30          # стенд всё отключит, если ПК молчит дольше, с


class StandDevices(Devices):
    """Шлюз MK-531 + CA71 + блок коммутации. Шлюз нужен только для калибровки и чтения коэффициента."""

    def __init__(self, stand_port="", gate_port="COM3", ca71_port="COM2", simulate=False):
        super().__init__(gate_port, ca71_port, simulate)
        self.stand_port = stand_port
        self.stand = None

    def connect(self, log):
        self.close()
        if self.simulate:
            from simulation import FakeGate
            from stand import FakeCA71Stand, FakeStand
            self.stand = FakeStand()
            self.gate = FakeGate()
            self.ca71 = FakeCA71Stand(self.gate, self.stand)
            log("Режим имитации: оборудование не используется")
            return
        from stand import Stand, find_stand
        port = self.stand_port
        if not port:
            port = find_stand(exclude=(self.gate_port, self.ca71_port), log=log)
        if port:
            try:
                self.stand = Stand(port)
                log(f"Стенд подключён: {port} ({self.stand.ident()})")
                self.stand_port = port
            except Exception as e:
                if self.stand:
                    self.stand.close()
                self.stand = None
                log(f"Стенд {port}: ОШИБКА - {e}")
        else:
            log("Стенд: ОШИБКА - не найден ни на одном COM-порту")
        try:
            from ca71 import CA71
            self.ca71 = CA71(self.ca71_port)
            log(f"CA71 подключён: {self.ca71_port}")
        except Exception as e:
            self.ca71 = None
            log(f"CA71 {self.ca71_port}: ОШИБКА - {e}")
        if self.gate_port:
            try:
                from mk531 import MK531
                self.gate = MK531(self.gate_port)
                log(f"Шлюз MK-531 подключён: {self.gate_port}")
            except Exception as e:
                self.gate = None
                log(f"Шлюз {self.gate_port}: ОШИБКА - {e} (проверка возможна и без шлюза)")
        else:
            log("Шлюз MK-531 не задан: доступна только проверка, без калибровки")

    def close(self):
        if self.stand is not None:
            try:
                self.stand.all_off()
            except Exception:
                pass
            try:
                self.stand.close()
            except Exception:
                pass
            self.stand = None
        super().close()

    def set_channel(self, ch):
        """Вызывается и из calibrate_channel: каналы переключает стенд, а не оператор."""
        try:
            self.ca71.output(False)  # реле не коммутируют ток под нагрузкой
        except Exception:
            pass
        self.stand.route(ch)


# ------------------------------------------------------------------ измерения ----
def measure(dev, cfg, setpoint, check):
    pause(cfg.settle, check)
    m = dev.ca71.read_current_avg(cfg.n_avg, cfg.read_delay)
    check()
    if abs(m - setpoint) > cfg.max_dev:
        raise RuntimeError(f"измеренный ток {m:.3f} мА слишком далёк от заданного {setpoint} мА "
                           f"(проверьте подключение изделия и режим CA71)")
    return m


def measure_load(ch, dev, cfg, log, check, on_point):
    """Точка 20 мА с нагрузкой 400 Ом (стенд включает резистор последовательно с измерителем)."""
    ca71, stand = dev.ca71, dev.stand
    ca71.set_current(20.0)
    ca71.output(True)
    stand.load(True)
    try:
        m = measure(dev, cfg, 20.0, check)
    finally:
        stand.load(False)
    e = err_pct(m, 20.0, cfg.span)
    log(f"  канал {ch}: 20 мА на нагрузке 400 Ом -> {m:.4f} мА ({e:+.4f} %)")
    on_point(ch, LOAD_LABEL, m, e, abs(e) <= cfg.tol + EPS)
    return m


def verify_channel(ch, dev, cfg, log, check, on_point):
    """Проверка без изменения коэффициента. -> {точка: мА, '400': мА}."""
    dev.set_channel(ch)
    log(f"=== КАНАЛ {ch}: проверка ===")
    ca71 = dev.ca71
    meas = {}
    try:
        ca71.output(True)
        for p in POINTS:
            check()
            ca71.set_current(float(p))
            meas[p] = measure(dev, cfg, float(p), check)
            e = err_pct(meas[p], p, cfg.span)
            on_point(ch, f"{p}", meas[p], e, abs(e) <= cfg.tol + EPS)
        meas[LOAD_KEY] = measure_load(ch, dev, cfg, log, check, on_point)
    finally:
        try:
            ca71.output(False)
        except Exception:
            pass
    return meas


def read_code(dev, ch, log):
    if dev.gate is None:
        return None
    try:
        return dev.gate.read_wiper(ch, rom=True)
    except Exception as e:
        log(f"  не удалось прочитать коэффициент канала {ch}: {e}")
        return None


def channel_result(meas: dict, cfg) -> dict:
    """Погрешности по точкам (по округлённым, как в протоколе) и решения."""
    errs = [err_pct(round(meas[p], 4), p, cfg.span) for p in POINTS]
    e400 = err_pct(round(meas[LOAD_KEY], 4), 20, cfg.span)
    emax = max(abs(x) for x in errs)
    r = {"e20": errs[-1], "emax": emax, "e400": e400,
         "ok20": abs(errs[-1]) <= cfg.tol + EPS, "okmax": emax <= cfg.tol + EPS,
         "ok400": abs(e400) <= cfg.tol + EPS}
    r["ok"] = r["ok20"] and r["okmax"] and r["ok400"]
    return r


def evaluate(results: dict, supply_ok: bool, supply_ma: float, cfg):
    lines, ok_all = [], supply_ok
    lines.append(f"Ток потребления {supply_ma:.1f} мА ({cfg.i_min:g}..{cfg.i_max:g}) -> "
                 f"{'PASS' if supply_ok else 'FAIL'}")
    for ch, (code, meas) in sorted(results.items()):
        r = channel_result(meas, cfg)
        ok_all &= r["ok"]
        lines.append(f"Канал {ch}: коэф. {code if code is not None else '-'}, 20 мА {r['e20']:+.4f} %, "
                     f"400 Ом {r['e400']:+.4f} %, макс. {r['emax']:.4f} % -> {'PASS' if r['ok'] else 'FAIL'}")
    return lines, ok_all


# ------------------------------------------------------------------ документы ----
def _safe(s):
    return "".join(c if c.isalnum() or c in "-_" else "_" for c in s)


def save_xlsx(template, path, serial, operator, product, results, cfg):
    """Протокол в копию шаблона MIB_Calibrator (те же ячейки), точка 400 Ом - измеренная."""
    cells, l1 = {}, {}
    for ch, (coef_cell, row0, row400) in CELLS.items():
        if ch in results:
            code, meas = results[ch]
            if code is not None:
                cells[coef_cell] = int(code)
            for i, p in enumerate(POINTS):
                cells[f"B{row0 + i}"] = round(meas[p], 4)
            cells[f"B{row400}"] = round(meas[LOAD_KEY], 4)
            r = channel_result(meas, cfg)
            d20, dmax, d400 = L1_DECISION[ch]
            l1[d20] = "PASS" if r["ok20"] else "FAIL"
            l1[dmax] = "PASS" if r["okmax"] else "FAIL"
            l1[d400] = "PASS" if r["ok400"] else "FAIL"
        else:
            cells[coef_cell] = None
            for row in range(row0, row400 + 1):
                cells[f"B{row}"] = None
            for ref in L1_CALIB[ch]:
                l1[ref] = "-"
    now = dt.datetime.now()
    l1.update({"D7": serial, "H7": operator, "I7": (now - dt.datetime(1899, 12, 30)).total_seconds() / 86400.0})
    if product:
        l1["D5"] = product
    patch_xlsx(template, path, {"Данные": cells, "Лист1": l1})


def save_report(path, info: dict, results: dict, cfg):
    """Подробный отчёт одной проверки (открывается в Excel)."""
    with open(path, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh, delimiter=";")
        for k, v in info.items():
            w.writerow([k, v])
        w.writerow([])
        w.writerow(["Канал", "Точка, мА", "Измерено, мА", "Погрешность, %", "Допуск, %", "Результат"])
        for ch, (code, meas) in sorted(results.items()):
            for p in POINTS:
                e = err_pct(round(meas[p], 4), p, cfg.span)
                w.writerow([ch, p, f"{meas[p]:.4f}", f"{e:+.4f}", cfg.tol, "OK" if abs(e) <= cfg.tol + EPS else "ВНЕ"])
            e = err_pct(round(meas[LOAD_KEY], 4), 20, cfg.span)
            w.writerow([ch, LOAD_LABEL, f"{meas[LOAD_KEY]:.4f}", f"{e:+.4f}", cfg.tol,
                        "OK" if abs(e) <= cfg.tol + EPS else "ВНЕ"])
            w.writerow([ch, "коэффициент", code if code is not None else "-"])


def append_journal(outdir, row: dict):
    path = os.path.join(outdir, "Журнал_проверок.csv")
    new = not os.path.exists(path)
    with open(path, "a", newline="", encoding="utf-8-sig") as fh:
        w = csv.DictWriter(fh, fieldnames=list(row), delimiter=";")
        if new:
            w.writeheader()
        w.writerow(row)


# ------------------------------------------------------------------ последовательность ----
class StandSequencer:
    def __init__(self, dev: StandDevices, cfg: StandConfig, io, mode, warmup_s, serial, operator,
                 template, outdir, product=""):
        self.dev, self.cfg, self.io, self.mode = dev, cfg, io, mode
        self.warmup_s, self.serial, self.operator = warmup_s, serial, operator
        self.template, self.outdir, self.product = template, outdir, product
        self.channels = channels_for(product)
        self._stop, self._skip = threading.Event(), threading.Event()
        self._alive = threading.Event()

    # --- управление из интерфейса ---
    def stop(self):
        self._stop.set()

    def skip_timer(self):
        self._skip.set()

    def check(self):
        if self._stop.is_set():
            raise Stopped()

    # --- поддержание связи со стендом и показ тока потребления ---
    def _keepalive(self):
        while self._alive.is_set():
            try:
                ma, v = self.dev.stand.supply()
                self.io.supply(ma, v)
            except Exception:
                pass
            for ev in self.dev.stand.poll_events():
                self._on_event(ev)
            for _ in range(int(self.cfg.keepalive * 10)):
                if not self._alive.is_set():
                    return
                time.sleep(0.1)

    def _on_event(self, ev):
        if ev == "STOP":
            self.io.log("Нажата кнопка СТОП на стенде")
            self.stop()
        elif ev in ("WDT", "OVERCURRENT"):
            self.io.log(f"ОШИБКА: стенд отключил изделие ({'перегрузка по току' if ev == 'OVERCURRENT' else 'потеря связи с ПК'})")
            self.stop()

    # --- этапы ---
    def _precheck(self):
        d = self.dev
        if d.stand is None or d.ca71 is None:
            raise RuntimeError("подключите стенд и CA71 (кнопка «Подключить»)")
        if self.mode == MODE_CAL:
            if d.gate is None:
                raise RuntimeError("для калибровки нужен шлюз MK-531")
            d.gate.find_interface()
        d.stand.all_off()
        d.ca71.measure_setup()
        d.ca71.output(False)
        sf = d.ca71.source_function()
        self.io.log(f"Источник CA71: {sf}")
        if sf not in ("4-20mA", "20mA", "20mA SINK"):
            raise RuntimeError(f"на CA71 выбрана функция источника {sf}, нужна 4-20mA (или 20mA SINK)")
        d.stand.watchdog(self.cfg.stand_wdt)

    def _power_on(self):
        st = self.dev.stand
        st.power(True)
        self.io.log("Питание изделия +24 В подано")
        pause(self.cfg.power_settle, self.check)
        ma, v = st.supply()
        self.io.supply(ma, v)
        ok = self.cfg.i_min <= ma <= self.cfg.i_max
        self.io.log(f"Ток потребления {ma:.1f} мА при {v:.2f} В -> {'норма' if ok else 'ВНЕ НОРМЫ'}")
        if v < 20.0:
            raise RuntimeError(f"напряжение питания изделия {v:.2f} В, проверьте блок питания стенда")
        return ok, ma

    def _warmup(self, ch):
        total = float(self.warmup_s)
        self._skip.clear()
        if total <= 0:
            return
        self.io.state(f"warmup{ch}")
        self.io.log(f"Канал {ch}: прогрев {int(total // 60)} мин {int(total % 60)} с под током 20 мА")
        end = time.monotonic() + total
        while not self._skip.is_set():
            self.check()
            left = end - time.monotonic()
            if left <= 0:
                break
            self.io.timer(left, total)
            time.sleep(min(0.2, left))
        self.io.timer(0, total)
        if self._skip.is_set():
            self.io.log("Таймер пропущен оператором")

    def _channel(self, ch):
        d = self.dev
        d.set_channel(ch)
        d.ca71.set_current(20.0)
        d.ca71.output(True)
        self.io.log(f"Канал {ch}: вход и выход подключены стендом, CA71 выдаёт 20 мА")
        self._warmup(ch)
        self.io.state(f"{'cal' if self.mode == MODE_CAL else 'chk'}{ch}")
        if self.mode == MODE_CAL:
            code, meas = calibrate_channel(ch, d, self.cfg, self.io.log, self.check, self.io.point)
            meas[LOAD_KEY] = measure_load(ch, d, self.cfg, self.io.log, self.check, self.io.point)
            d.ca71.output(False)
        else:
            meas = verify_channel(ch, d, self.cfg, self.io.log, self.check, self.io.point)
            code = read_code(d, ch, self.io.log)
            if code is not None:
                self.io.log(f"  коэффициент канала {ch} (ROM): {code}")
        return code, meas

    def _finish(self, results, supply_ok, supply_ma):
        lines, ok = evaluate(results, supply_ok, supply_ma, self.cfg)
        os.makedirs(self.outdir, exist_ok=True)
        now = dt.datetime.now()
        base = os.path.join(self.outdir, f"Проверка_{_safe(self.serial)}_{now:%Y%m%d_%H%M%S}")
        mode_text = "калибровка + проверка" if self.mode == MODE_CAL else "проверка"
        info = {"Изделие": self.product, "Серийный номер": self.serial, "Оператор": self.operator,
                "Дата": f"{now:%d.%m.%Y %H:%M}", "Режим": mode_text,
                "Ток потребления, мА": f"{supply_ma:.1f}", "Итог": "PASS" if ok else "FAIL"}
        save_report(base + ".csv", info, results, self.cfg)
        path = base + ".csv"
        if self.template and os.path.isfile(self.template):
            try:
                save_xlsx(self.template, base + ".xlsx", self.serial, self.operator, self.product, results, self.cfg)
                path = base + ".xlsx"
            except Exception as e:
                self.io.log(f"ВНИМАНИЕ: протокол по шаблону не записан ({e}), сохранён отчёт .csv")
        row = {"Дата": info["Дата"], "Изделие": self.product, "Серийный номер": self.serial,
               "Оператор": self.operator, "Режим": mode_text, "Ток потребления, мА": info["Ток потребления, мА"]}
        for ch in (1, 2):
            if ch in results:
                code, meas = results[ch]
                r = channel_result(meas, self.cfg)
                row.update({f"К{ch} коэф.": code if code is not None else "-",
                            f"К{ch} 20 мА, %": f"{r['e20']:+.4f}", f"К{ch} 400 Ом, %": f"{r['e400']:+.4f}",
                            f"К{ch} макс., %": f"{r['emax']:.4f}", f"К{ch}": "PASS" if r["ok"] else "FAIL"})
            else:
                row.update({f"К{ch} коэф.": "-", f"К{ch} 20 мА, %": "-", f"К{ch} 400 Ом, %": "-",
                            f"К{ch} макс., %": "-", f"К{ch}": "-"})
        row.update({"Итог": info["Итог"], "Файл": os.path.basename(path)})
        append_journal(self.outdir, row)
        return path, lines, ok

    def _start_keepalive(self):
        self._alive.set()
        threading.Thread(target=self._keepalive, daemon=True).start()

    def run(self):
        results = {}
        d = self.dev
        try:
            self.io.state("precheck")
            self._precheck()
            self._start_keepalive()
            d.stand.led("BUSY")
            supply_ok, supply_ma = self._power_on()
            if not supply_ok:
                raise RuntimeError(f"ток потребления {supply_ma:.1f} мА вне нормы "
                                   f"{self.cfg.i_min:g}..{self.cfg.i_max:g} мА, проверка прекращена")
            if len(self.channels) == 1:
                self.io.log(f"Изделие {self.product}: один канал, проверяется только канал 1")
            for ch in self.channels:
                results[ch] = self._channel(ch)
            d.stand.all_off()
            self.io.log("Изделие обесточено")
            path, lines, ok = self._finish(results, supply_ok, supply_ma)
            d.stand.led("PASS" if ok else "FAIL")
            d.stand.beep(300 if ok else 1200)
            self.io.state("done_ok" if ok else "done_fail")
            self.io.done(path, lines, ok)
        except Stopped:
            self.io.state("stopped")
            self.io.log("Остановлено оператором")
        except Exception as e:
            self.io.state("error")
            self.io.log(f"ОШИБКА: {e}")
            try:
                d.stand.led("FAIL")
            except Exception:
                pass
        finally:
            self._alive.clear()
            for off in (lambda: d.ca71.output(False), lambda: d.stand.all_off(), lambda: d.stand.watchdog(0)):
                try:
                    off()
                except Exception:
                    pass
            self.io.finished()

    def run_selftest(self):
        """Самоконтроль стенда БЕЗ изделия: источник CA71 -> реле стенда -> измеритель CA71."""
        d = self.dev
        ok_all = True
        try:
            self.io.state("selftest")
            if d.stand is None or d.ca71 is None:
                raise RuntimeError("подключите стенд и CA71")
            d.stand.all_off()
            d.ca71.measure_setup()
            if d.ca71.source_function() == "20mA SINK":
                raise RuntimeError("для самоконтроля переключите источник CA71 в режим 4-20mA или 20mA")
            d.stand.selftest(True)
            d.ca71.output(True)
            for sp, load in ((4.0, False), (12.0, False), (20.0, False), (20.0, True)):
                self.check()
                d.ca71.set_current(sp)
                d.stand.load(load)
                m = measure(d, self.cfg, sp, self.check)
                e = err_pct(m, sp, self.cfg.span)
                ok = abs(e) <= self.cfg.self_tol
                ok_all &= ok
                self.io.log(f"Самоконтроль {sp:g} мА{' + 400 Ом' if load else ''}: {m:.4f} мА, {e:+.4f} % "
                            f"-> {'OK' if ok else 'ВНЕ ДОПУСКА'} (допуск {self.cfg.self_tol} %)")
            self.io.log("Самоконтроль стенда: " + ("PASS" if ok_all else "FAIL - проверьте контакты реле, "
                                                    "резистор 400 Ом и CA71"))
            self.io.state("self_ok" if ok_all else "self_fail")
        except Stopped:
            self.io.state("stopped")
            self.io.log("Остановлено оператором")
        except Exception as e:
            self.io.state("error")
            self.io.log(f"ОШИБКА самоконтроля: {e}")
            ok_all = False
        finally:
            for off in (lambda: d.ca71.output(False), lambda: d.stand.all_off()):
                try:
                    off()
                except Exception:
                    pass
            self.io.finished()
        return ok_all
