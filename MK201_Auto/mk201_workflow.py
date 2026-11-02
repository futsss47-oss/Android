"""Сценарий (обновление ПО — отдельной кнопкой, см. firmware.py): порты, время, серийник/MAC, калибровка AI/AO, проверка, протокол Excel."""
import json, os, socket, struct, time, statistics
import serial
from mk201_stats import robust_mean
from mk201_instruments import ManualGen
from mk201_proto import *
from protocol_xlsx import Protocol
import mk201_firmware as firmware
from modbus_decode import interpret

# Записи, формат которых ещё не подтверждён перехватом конфигуратора (com0com).
# Пока шаг выбран, программа только меряет и пишет в журнал, в плату НИЧЕГО не записывает.
UNCONFIRMED = {}   # AI и AO подтверждены сниффером 16.10

ANALOG = ("ai_cal", "ai_check", "ao_cal", "ao_check")


class Abort(Exception):
    pass


class StepSkip(Exception):
    """Оператор выбрал «Пропустить шаг» — шаг не пройден, прогон идёт дальше."""
    pass


def lost_link(e):
    """Пропала связь с платой — дальше идти бессмысленно."""
    return isinstance(e, (getattr(serial, "SerialException", OSError), OSError)) or (
        isinstance(e, ModbusError) and "Нет ответа" in str(e))


class Runner:
    """ui: log(str), confirm(str)->bool, ask_float(str)->float|None; необязательно: stopped, skip,
    step(key,state), phase(text), progress(done,total), sub_progress(percent), countdown(sec|None), point(...)"""

    def __init__(self, dev, relay, ui, cfg, cal=None, psu=None, gen=None):
        self.dev, self.relay, self.ui, self.cfg, self.cal = dev, relay, ui, cfg, cal
        self.psu, self.gen = psu, gen
        self.pos = (0, 0, "")              # шаг i из n, название — для строки «Сейчас: …»
        self.n_ai = 8
        self.samples = int(cfg.get("samples", 10))
        self.ai_map = list(range(8))          # вход платы -> номер реле (уточняется в contact_check)
        self.tol = float(cfg.get("tol_percent", 0.08))      # % диапазона 16 мА
        self.results = {"ai": {}, "ao": {}, "ok": True, "steps": {}, "notes": [], "ci_freq": [], "ci": {}}
        self.cur_ma = None
        self.contact_ok = False
        self.flags = {}                       # ('ai'|'ao', канал) -> замечание: пойдёт в журнал и красным в протокол

    # ---- вспомогательное ----
    def log(self, s): self.ui.log(s)

    def detail(self, text=""):
        """Строка «Сейчас: Шаг 6 из 11 · Калибровка AI · канал 4 из 8 (AI3) · 20 мА»."""
        i, n, title = self.pos
        if not n:                             # ручной режим: без номера шага
            return
        self.hook("detail", f"Шаг {i} из {n} · {title}" + (f" · {text}" if text else ""))

    def same_channels(self, kind, data, what):
        """Защита от путаницы каналов (как в МИБ): у двух каналов всё совпало один в один —
        похоже на залипшее реле или перепутанный провод."""
        seen = {}
        for ch, v in data.items():
            seen.setdefault(v, []).append(ch)
        for v, chs in seen.items():
            if len(chs) > 1:
                names = ", ".join(f"{kind.upper()}{c}" for c in chs)
                self.note(f"{names}: {what} — проверьте реле и провода (не перепутаны ли каналы)")

    def note(self, text):
        self.results["notes"].append(text)
        self.log(f"!!! Предупреждение: {text}")

    def flag(self, kind, ch, text):
        """Замечание по каналу: красная строка в журнале + красная отметка в протоколе. Прогон продолжается."""
        self.flags.setdefault((kind, ch), []).append(text)
        self.results["ok"] = False
        self.log(f"!!! {kind.upper()}{ch}: {text}")

    def chk(self):
        if getattr(self.ui, "stopped", False):
            raise Abort("Остановлено оператором")

    def hook(self, name, *a):
        f = getattr(self.ui, name, None)
        if f:
            f(*a)

    def need(self, prompt):
        self.chk()
        if not self.ui.confirm(prompt):
            raise Abort("Остановлено оператором")

    # ---- источник тока ----
    def set_current(self, ma):
        """Ток на калибраторе: сам по CA71 или вручную по запросу."""
        self.chk()
        self.cur_ma = ma
        if self.cal:
            self.cal.set_source(ma, settle=float(self.cfg.get("cal_settle", 2.0)))
            self.log("CA71: 0 мА — выход выключен (OFF)" if abs(ma) < 0.05 else f"CA71: источник {ma:g} мА")
        else:
            self.need(f"Установите на калибраторе {ma:g} мА (SOURCE) и нажмите OK")

    def reassert(self):
        """После переключения канала цепь рвётся и CA71 уходит в 0 и OFF — выставляем ток заново."""
        if self.cal and self.cur_ma is not None:
            self.cal.set_source(self.cur_ma, settle=float(self.cfg.get("reassert_settle", 1.0)))

    # ---- шаг 0: обновление ПО ----
    def step_firmware(self):
        path = self.cfg.get("hex_path", "")
        if not path:
            raise ValueError("Не выбран файл ПО (.hex)")
        res = firmware.upload_app(self.dev, path, self.log,
                                  progress=lambda p: self.hook("sub_progress", p),
                                  stop=self.chk, force=bool(self.cfg.get("fw_force")),
                                  countdown=lambda sec: self.hook("countdown", sec),
                                  lock=lambda state: self.hook("fw_lock", state))
        self.hook("sub_progress", 0)
        return True

    # ---- шаг 1: порты ----
    def step_ports(self):
        regs = self.dev.ports_read()
        self.log("Сейчас в плате: " + interpret(1010, regs))
        mode = int(self.cfg.get("port_mode_value", 2))       # значение «MbSlave» — уточнить перехватом
        new = list(regs)
        for i in range(3):                       # Порт1..3 = MbSlave Master0..2
            base = 8 + 6 * i
            new[base + 1] = mode
            new[base + 2] = i
        self.log("Записываю:        " + interpret(1010, new))
        self.dev.ports_write(new)
        time.sleep(0.3)
        back = self.dev.ports_read()
        self.log("Прочитано после:  " + interpret(1010, back))
        diff = [k for k in range(26) if back[k] != new[k]]
        if diff:
            self.log(f"НЕ СОВПАЛО в регистрах {[1010 + k for k in diff]}: записано {[new[k] for k in diff]}, "
                     f"в плате {[back[k] for k in diff]}")
            return False
        self.log("Порты: OK")
        return True

    # ---- шаг 2: время ----
    def step_time(self):
        sync = getattr(self.dev, "datetime_sync", None)
        if sync is None:                                   # симулятор
            self.dev.datetime_write(); time.sleep(0.3)
            (y, mo, d, h, mi, s), dt = self.dev.datetime_read(), 0
            ok = True
        else:
            ok, (y, mo, d, h, mi, s), dt = sync(self.log)
        self.log(f"Время ПК    {time.strftime('%d.%m.%Y %H:%M:%S')}")
        self.log(f"Время платы {d:02d}.{mo:02d}.{y} {h:02d}:{mi:02d}:{s:02d}"
                 + (f", расхождение {dt:.0f} с" if dt != float("inf") else ""))
        if not ok:
            self.note("часы платы не приняли время ни одним способом (плата отвечает «принято», но время не меняется). "
                      "Проверьте в конфигураторе «Дата и время → Записать»; если там записывается — снимите это сниффером "
                      "(mk201_sniffer.py) и пришлите лог")
        return ok

    # ---- шаг 3: серийник и MAC ----
    def step_serial_mac(self):
        sn, mac = int(self.cfg["serial"]), self.cfg["mac"]
        self.log(f"Было в плате: серийный {self.dev.serial_read()}, MAC {self.dev.mac_read()}")
        self.log(f"Записываю серийный {sn} (регистры {l2regs(sn)}, младшее слово первым) и MAC {mac}")
        self.dev.serial_write(sn)
        self.dev.mac_write(mac)
        sn2, mac2 = self.dev.serial_read(), self.dev.mac_read()
        ok = sn2 == sn and mac2.upper() == mac.upper().replace("-", ":")
        self.log(f"Прочитано после: серийный {sn2}, MAC {mac2} -> {'OK' if ok else 'НЕ СОВПАЛО'}")
        return ok

    # ---- прогрев ----
    def warmup(self):
        """Прогрев перед калибровкой AI: 20 мА на AI0, по умолчанию 10 мин. Ток контролируется по показаниям
        платы; если петля оборвалась и CA71 ушёл в 0/OFF — ток выставляется заново."""
        minutes = float(self.cfg.get("warm_min", 10) or 0)
        if minutes <= 0:
            self.log("Прогрев отключён (0 мин)")
            return
        self.log(f"Прогрев {minutes:g} мин: 20 мА на AI0 (можно пропустить кнопкой)")
        self.hook("phase", "Прогрев")
        self.sw_ai(0)
        self.set_current(20.0)
        t_end, next_chk = time.time() + minutes * 60, time.time() + 5
        while True:
            self.chk()
            if getattr(self.ui, "skip", False):
                self.ui.skip = False
                self.log("ПРОГРЕВ ПРОПУЩЕН оператором")
                break
            left = int(t_end - time.time())
            if left <= 0:
                self.log("Прогрев завершён")
                break
            self.hook("countdown", left)
            self.detail(f"прогрев, 20 мА на AI0, осталось {left // 60:02d}:{left % 60:02d}")
            if time.time() >= next_chk:
                next_chk = time.time() + 5
                try:
                    v = self.dev.ai_ma()[0]
                    if v < 19.0:
                        self.log(f"Прогрев: на AI0 {v:.3f} мА вместо 20 — выставляю ток заново")
                        self.reassert()
                except Exception as e:
                    self.log(f"Прогрев: не удалось прочитать AI0 ({e})")
            time.sleep(0.5)
        self.hook("countdown", None)

    # ---- соответствие «реле -> вход платы» ----
    def sw_ai(self, ch):
        """Подключить калибратор к входу платы AI{ch} (с учётом найденной перестановки реле)."""
        self.relay.ai(self.ai_map[ch])

    # ---- проверка контакта перед AI ----
    def contact_check(self):
        """4 мА через каждое реле по очереди; плата сама показывает, на какой вход пришёл ток.
        Так проверяется контакт и заодно определяется, какое реле на какой вход заведено
        (если провода/выводы перепутаны, программа это учитывает и сообщает в журнале)."""
        self.log("Проверка контакта: 4 мА через каждое реле, смотрю на каких входах платы появился ток")
        self.set_current(4.0)
        got, problems = {}, []                     # реле -> вход
        for r in range(self.n_ai):
            self.chk()
            self.detail(f"проверка контакта · реле {r + 1} из {self.n_ai}")
            self.relay.ai(r)
            self.reassert()
            time.sleep(0.3)
            vals = [statistics.mean(x) for x in zip(*[self.dev.ai_ma() for _ in range(3)])]
            live = [i for i, v in enumerate(vals[:self.n_ai]) if v >= 2.0]
            if len(live) == 1:
                got[r] = live[0]
                tag = "" if live[0] == r else f"  (ожидался AI{r} — реле заведено на другой вход)"
                self.log(f"Реле {r + 1}: ток на AI{live[0]} = {vals[live[0]]:.3f} мА{tag}")
            elif not live:
                problems.append(f"реле {r + 1}: ток не пришёл ни на один вход")
                self.log(f"Реле {r + 1}: ток не пришёл ни на один вход — НЕТ КОНТАКТА")
            else:
                problems.append(f"реле {r + 1}: ток сразу на нескольких входах ({', '.join('AI%d' % i for i in live)})")
                self.log(f"Реле {r + 1}: ток на нескольких входах: {', '.join('AI%d=%.2f' % (i, vals[i]) for i in live)}")
        self.relay.off()
        inputs = list(got.values())
        if len(set(inputs)) != len(inputs):
            problems.append("два реле подают ток на один и тот же вход")
        missing = [f"AI{i}" for i in range(self.n_ai) if i not in inputs]
        if problems or missing:
            raise RuntimeError("Нет контакта: " + "; ".join(problems + ([f"ни одно реле не дошло до {', '.join(missing)}"] if missing else []))
                               + ". Проверьте провода и реле, затем повторите")
        self.ai_map = [0] * self.n_ai
        for r, i in got.items():
            self.ai_map[i] = r
        if self.ai_map != list(range(self.n_ai)):
            self.log("Реле заведены на входы не по порядку. Программа это учтёт: вход AI0..AI%d <- реле %s"
                     % (self.n_ai - 1, ", ".join(str(r + 1) for r in self.ai_map)))
        self.contact_ok = True

    # ---- измерение по всем каналам на текущем токе калибратора ----
    def scan_ai(self, what, label=""):
        out = []
        for ch in range(self.n_ai):
            self.chk()
            self.detail(f"канал {ch + 1} из {self.n_ai} (AI{ch})" + (f" · {label}" if label else ""))
            self.sw_ai(ch)
            self.reassert()
            vals = []
            for _ in range(self.samples):
                vals.append(self.dev.ai_adc()[ch] if what == "adc" else self.dev.ai_ma()[ch])
                time.sleep(0.05)
            out.append(robust_mean(vals, floor=0.002, log=self.log, what=f"AI{ch}"))
        self.relay.off()
        return out

    # ---- шаг 4: калибровка AI ----
    def step_ai_cal(self):
        """Калибровка AI так же, как в конфигураторе (снято сниффером 16.10.2026):
        таблица 801 ×32 = 8 каналов × [код 4 мА, код 16 мА, код 18 мА, код 20 мА].
        Кнопки «4 мА»/«20 мА» конфигуратора только запоминают сырой код АЦП (рег. 17+ch),
        «Записать» шлёт ВСЮ таблицу одним кадром 0x64. Слова 16 и 18 мА не меняются."""
        hv = self.cfg.get("hv", "3.3")
        fac = self.dev.ai_cal_factory(hv)
        old = self.dev.ai_cal_read_all()
        self.log("Калибровка AI: таблица в плате до сброса: " +
                 "; ".join(f"AI{c}=[{', '.join(map(str, r))}]" for c, r in enumerate(old)))
        # Сначала сбрасываем прежнюю калибровку (заводские коды во всех каналах), как «АИ все → Сохранить» в конфигураторе.
        back = self.dev.ai_cal_reset(None, hv)
        if any(list(r) != fac for r in back):
            self.log("!!! Калибровка AI: сброс прежней калибровки не подтвердился чтением, плата вернула: " + str(back))
            for ch in range(self.n_ai):
                if list(back[ch]) != fac:
                    self.flag("ai", ch, "сброс прежней калибровки не применился")
        else:
            self.log(f"Калибровка AI: прежняя калибровка сброшена (заводские коды {fac}, ревизия {hv})")
        codes = {}
        for ma in (4.0, 20.0):
            self.set_current(ma)
            for ch in range(self.n_ai):
                self.chk()
                self.detail(f"канал {ch + 1} из {self.n_ai} (AI{ch}) · {ma:g} мА")
                self.sw_ai(ch)
                self.reassert()
                time.sleep(float(self.cfg.get("ai_settle", 1.0)))
                vals = []
                for _ in range(self.samples):
                    vals.append(self.dev.ai_adc()[ch])
                    time.sleep(0.05)
                code = round(robust_mean(vals, floor=2, log=self.log, what=f"AI{ch} {ma:g} мА, коды АЦП"))
                codes[(ch, ma)] = code
                self.log(f"[AI-кал {ch + 1}/{self.n_ai} · AI{ch}] {ma:g} мА → код АЦП {code} (разброс {min(vals)}…{max(vals)})")
            self.relay.off()

        self.same_channels("ai", {ch: (codes[(ch, 4.0)], codes[(ch, 20.0)]) for ch in range(self.n_ai)},
                           "одинаковые коды калибровки 4/20 мА")
        new = [list(fac) for _ in range(self.n_ai)]     # коды 16 и 18 мА — заводские
        for ch in range(self.n_ai):
            c4, c20 = codes[(ch, 4.0)], codes[(ch, 20.0)]
            new[ch][0], new[ch][3] = c4, c20      # пишем в любом случае, что намерили
            # Подозрительные коды (вне ±5 % от заводских) — не блокируют запись, но помечаются
            if not (fac[0] * 0.95 <= c4 <= fac[0] * 1.05 and fac[3] * 0.95 <= c20 <= fac[3] * 1.05 and c20 > c4):
                self.flag("ai", ch, f"коды калибровки вне нормы: 4 мА = {c4} (завод {fac[0]}), 20 мА = {c20} (завод {fac[3]}) — записаны как есть")
        self.log("Запись калибровки AI (801 ×32): " +
                 "; ".join(f"AI{c}=[{', '.join(map(str, r))}]" for c, r in enumerate(new)))
        self.dev.ai_cal_write_all(new)
        back = self.dev.ai_cal_read_all()
        if back != new:
            for ch in range(self.n_ai):
                if list(back[ch]) != new[ch]:
                    self.flag("ai", ch, f"плата вернула не то, что записано: {back[ch]} вместо {new[ch]}")
        else:
            self.log("Калибровка AI записана и прочитана обратно — совпадает")
        self.results["ai_cal"] = [list(r) for r in back]
        return True

    # ---- шаг 5: проверка AI ----
    def prev_meas(self, kind, n, npts):
        """Замеры прошлого прогона этого номера за сегодня (из .json протокола): {канал: [точки]} или None."""
        r = (self.cfg.get("prev_results") or {}).get(kind)
        if isinstance(r, dict):
            r = [r.get(str(ch), r.get(ch)) for ch in range(n)]
        try:
            out = [[float(v) for v in r[ch]] for ch in range(n)]
        except (TypeError, ValueError, IndexError, KeyError):
            return None
        return out if all(len(x) == npts for x in out) else None

    def measure_ai_channels(self, sp, meas, chans):
        """Замер только указанных каналов AI во всех точках (каналы вне допуска)."""
        for i, s in enumerate(sp):
            self.set_current(s)
            for ch in chans:
                self.chk()
                self.detail(f"AI{ch} · {s:g} мА")
                self.sw_ai(ch)
                self.reassert()
                v = statistics.mean(self.dev.ai_ma()[ch] for _ in range(self.samples))
                meas[ch][i] = v
                good = abs((v - s) * 100 / 16) <= self.tol
                self.hook("point", "ai", ch, i, s, v, good)
                self.log(f"AI{ch} @ {s:g} мА: {v:.3f} мА — {'OK' if good else 'ВНЕ ДОПУСКА'}")
            self.relay.off()

    def step_ai_check(self, proto):
        sp = proto.setpoints_ai()
        prev = self.prev_meas("ai", self.n_ai, len(sp)) if self.cfg.get("retry_bad") else None
        if prev is not None:
            # 02.11.2026: «Повторить непройденные» — прошлые замеры годных каналов остаются, перемеряются только плохие
            bad = self.failed(sp, prev, self.n_ai)
            if bad:
                for ch in range(self.n_ai):
                    for i, s in enumerate(sp):
                        self.hook("point", "ai", ch, i, s, prev[ch][i], abs((prev[ch][i] - s) * 100 / 16) <= self.tol)
                self.log("Повтор: перемеряю только каналы вне допуска — " + ", ".join(f"AI{c}" for c in bad)
                         + "; остальные — из прошлого прогона")
                return self._finish_ai_check(proto, sp, prev, self.measure_ai_channels(sp, prev, bad) or True)
        meas = [[] for _ in range(self.n_ai)]
        ok = True
        for i, s in enumerate(sp):
            self.set_current(s)
            vals = self.scan_ai("ma", f"точка {i + 1} из {len(sp)}: {s:g} мА")
            lim = self.tol * 16 / 100
            for ch, v in enumerate(vals):
                meas[ch].append(v)
                err = (v - s) * 100 / 16
                good = abs(err) <= self.tol
                self.hook("point", "ai", ch, i, s, v, good)
                self.log(f"[AI-пров {ch + 1}/{self.n_ai} · AI{ch}] {s:g} мА → {v:.3f} мА (допуск ±{lim:.4f})"
                         + ("" if good else f" — погрешность {err:+.3f}% ВНЕ ДОПУСКА"))
                if not good:
                    ok = False
        return self._finish_ai_check(proto, sp, meas, ok)

    def _finish_ai_check(self, proto, sp, meas, ok):
        ok = self.remeasure_ai(sp, meas) and not self.failed(sp, meas, self.n_ai)
        self.same_channels("ai", {ch: tuple(round(v, 3) for v in meas[ch]) for ch in range(self.n_ai)},
                           "одинаковые показания во всех точках")
        for ch in range(self.n_ai):
            proto.put_ai(ch, meas[ch])
            proto.mark_bad_points('ai', ch, [i for i, (v, s) in enumerate(zip(meas[ch], sp)) if abs((v - s) * 100 / 16) > self.tol])
        self.results["ai"] = meas
        self.log("Проверка AI: " + ("OK" if ok else "ЕСТЬ ВЫХОД ЗА ДОПУСК"))
        return ok

    def failed(self, sp, meas, n):
        return [ch for ch in range(n) if any(abs((v - s) * 100 / 16) > self.tol for v, s in zip(meas[ch], sp))]

    def ask(self, text, yes, no):
        f = getattr(self.ui, "ask", None)
        return f(text, yes, no) if f else self.ui.confirm(text)

    def remeasure_ai(self, sp, meas, tries=2):
        """Перемерить только каналы, вышедшие за допуск (по выбору оператора). True — все в допуске."""
        for _ in range(tries):
            bad = self.failed(sp, meas, self.n_ai)
            if not bad:
                return True
            names = ", ".join(f"AI{c}" for c in bad)
            if not self.ask(f"Вне допуска: {names}.\nПеремерить только эти каналы?", "Перемерить", "Оставить как есть"):
                return False
            self.log(f"Повторный замер: {names}")
            self.measure_ai_channels(sp, meas, bad)
        return not self.failed(sp, meas, self.n_ai)

    def remeasure_ao(self, sp_by_ch, meas, tries=2):
        for _ in range(tries):
            bad = [ch for ch in range(2) if any(abs((v - s) * 100 / 16) > self.tol for v, s in zip(meas[ch], sp_by_ch[ch]))]
            if not bad:
                return True
            names = ", ".join(f"AO{c}" for c in bad)
            if not self.ask(f"Вне допуска: {names}.\nПеремерить только эти выходы?", "Перемерить", "Оставить как есть"):
                return False
            self.log(f"Повторный замер: {names}")
            for ch in bad:
                for i, s in enumerate(sp_by_ch[ch]):
                    v = self.measure_ao(ch, s)
                    meas[ch][i] = v
                    good = abs((v - s) * 100 / 16) <= self.tol
                    self.hook("point", "ao", ch, i, s, v, good)
                    self.log(f"AO{ch} @ {s:g} мА: {v:.3f} — {'OK' if good else 'ВНЕ ДОПУСКА'}")
        return all(abs((v - s) * 100 / 16) <= self.tol for ch in range(2) for v, s in zip(meas[ch], sp_by_ch[ch]))

    # ---- измерение выхода ----
    def _read_ao(self, ch, set_ma):
        if self.cal:
            time.sleep(float(self.cfg.get("ao_settle", 1.0)))
            return self.cal.measure_ma(n=int(self.cfg.get("cal_samples", 3)), interval=float(self.cfg.get("cal_interval", 1.1)))
        v = self.ui.ask_float(f"AO{ch}: на плате задано {set_ma:g} мА. Введите показание калибратора (MEASURE), мА")
        if v is None:
            raise Abort("Остановлено оператором")
        return v

    def measure_ao(self, ch, set_ma, expect=None):
        """Задать ток на AO и измерить его CA71.
        Особенность платы: иногда значение «не встаёт» (чаще 4 мА). Тогда, как делают вручную,
        задаём другую точку (для 4 мА — 19 мА, для 19 мА — 4 мА), затем снова нужную."""
        self.chk()
        self.detail(f"выход {ch + 1} из 2 (AO{ch}) · {set_ma:g} мА")
        expect = set_ma if expect is None else expect
        self.relay.ao(ch)
        self.dev.ao_set(ch, set_ma)
        time.sleep(0.5)
        self.log(f"AO{ch}: записал {set_ma:g} мА, плата сообщает {self.dev.ao_get(ch):.4f} мА")
        v = self._read_ao(ch, set_ma)
        lim = float(self.cfg.get("ao_stuck_ma", 0.5))
        for attempt in range(int(self.cfg.get("ao_kick_tries", 3))):
            if abs(v - expect) <= lim:
                break
            other = 19.0 if set_ma < 11.5 else 4.0
            self.log(f"AO{ch}: задано {set_ma:g} мА, а измерено {v:.3f} мА — значение не встало. "
                     f"Задаю {other:g} мА и снова {set_ma:g} мА (попытка {attempt + 1})")
            self.dev.ao_set(ch, other)
            time.sleep(1.0)
            self.dev.ao_set(ch, set_ma)
            time.sleep(0.5)
            v = self._read_ao(ch, set_ma)
        else:
            if abs(v - expect) > lim:
                self.log(f"AO{ch}: после {attempt + 1} попыток значение {set_ma:g} мА так и не встало ({v:.3f} мА)")
        return v

    # ---- шаг 6: калибровка AO ----
    def step_ao_cal(self):
        """Калибровка AO так же, как в конфигураторе (снято сниффером 16.10.2026):
        таблица 851 ×8 = 4 float (младшее слово первым): AO0@4, AO0@19, AO1@4, AO1@19 —
        ИЗМЕРЕННЫЕ калибратором токи при заданных 4 и 19 мА.
        1) «Все»/«Сохранить все»: записать заводские [4, 19, 4, 19] — выход становится «сырым»;
        2) задать 4 мА (рег. 503/505), измерить; задать 19 мА, измерить;
        3) «Сохранить калибровку»: записать измеренные значения одним кадром 851 ×8."""
        old = self.dev.ao_cal_read()
        self.log("Калибровка AO: в плате было " + ", ".join(f"AO{c}=({p[0]:.4f}, {p[1]:.4f})" for c, p in enumerate(old)))
        self.dev.ao_cal_write([(4.0, 19.0), (4.0, 19.0)])
        self.log("Калибровка AO: записаны заводские точки 4 / 19 мА (как кнопка «Все»)")
        time.sleep(0.5)
        pts = []
        for ch in range(2):
            m4 = self.measure_ao(ch, 4.0)
            m19 = self.measure_ao(ch, 19.0)
            self.log(f"[AO-кал {ch + 1}/2 · AO{ch}] задано 4 → измерено {m4:.4f} мА, задано 19 → измерено {m19:.4f} мА")
            if not (3.6 <= m4 <= 4.4 and 18.6 <= m19 <= 19.4):
                self.flag("ao", ch, f"измерения калибровки вне нормы: 4 мА -> {m4:.3f}, 19 мА -> {m19:.3f} — записаны как есть")
            pts.append((round(m4, 4), round(m19, 4)))
        self.dev.ao_cal_write(pts)
        back = self.dev.ao_cal_read()
        self.results["ao_cal"] = [list(p) for p in back]
        for ch, (p, q) in enumerate(zip(pts, back)):
            if any(abs(a - b) >= 1e-3 for a, b in zip(p, q)):
                self.flag("ao", ch, f"плата вернула не то, что записано: {q} вместо {p}")
        self.log("Калибровка AO записана (851 ×8): " + ", ".join(f"AO{c}=({p[0]:.4f}, {p[1]:.4f})" for c, p in enumerate(pts)))
        self.dev.ao_set(0, 4.0); self.dev.ao_set(1, 4.0)
        return True

    # ---- шаг 7: проверка AO ----
    def step_ao_check(self, proto):
        sp_by_ch, meas = {}, {}
        prev = None
        if self.cfg.get("retry_bad"):
            sps = {ch: proto.setpoints_ao(ch) for ch in range(2)}
            prev = self.prev_meas("ao", 2, len(sps[0]))
            bad = [ch for ch in range(2) if prev and any(abs((v - s) * 100 / 16) > self.tol for v, s in zip(prev[ch], sps[ch]))]
            if prev is not None and bad:
                self.log("Повтор: перемеряю только " + ", ".join(f"AO{c}" for c in bad) + "; остальные — из прошлого прогона")
                for ch in range(2):
                    sp_by_ch[ch], meas[ch] = sps[ch], list(prev[ch])
                    for i, s in enumerate(sps[ch]):
                        v = prev[ch][i] if ch not in bad else self.measure_ao(ch, s)
                        meas[ch][i] = v
                        good = abs((v - s) * 100 / 16) <= self.tol
                        self.hook("point", "ao", ch, i, s, v, good)
                        if ch in bad:
                            self.log(f"[AO-повтор · AO{ch}] {s:g} мА → {v:.3f} мА" + ("" if good else " — ВНЕ ДОПУСКА"))
            else:
                prev = None
        for ch in (range(2) if prev is None else ()):
            sp = proto.setpoints_ao(ch)
            sp_by_ch[ch], meas[ch] = sp, []
            for i, s in enumerate(sp):
                v = self.measure_ao(ch, s)
                meas[ch].append(v)
                good = abs((v - s) * 100 / 16) <= self.tol
                self.hook("point", "ao", ch, i, s, v, good)
                self.log(f"[AO-пров {ch + 1}/2 · AO{ch}] {s:g} мА → {v:.3f} мА (допуск ±{self.tol * 16 / 100:.4f})"
                         + ("" if good else " — ВНЕ ДОПУСКА"))
        ok = self.remeasure_ao(sp_by_ch, meas)
        for ch in range(2):
            proto.put_ao(ch, meas[ch])
            proto.mark_bad_points('ao', ch, [i for i, (v, s) in enumerate(zip(meas[ch], sp_by_ch[ch])) if abs((v - s) * 100 / 16) > self.tol])
            self.results["ao"][ch] = meas[ch]
        self.dev.ao_set(0, 4.0); self.dev.ao_set(1, 4.0)
        self.relay.off()
        self.log("Проверка AO: " + ("OK" if ok else "ЕСТЬ ВЫХОД ЗА ДОПУСК"))
        return ok

    # ---- вопрос оператору при неправильном подключении ----
    def retry_or_skip(self, text):
        """Окно только когда что-то не так: «Повторить» -> True, «Пропустить шаг» -> StepSkip."""
        if self.ask(text, "Повторить", "Пропустить шаг"):
            return True
        raise StepSkip("пропущено оператором")

    # ---- шаг: ток и мощность потребления (табл. 1, ТУ п. 8.4) ----
    def step_power(self, proto):
        lim_ma = float(self.cfg.get("power_ma_max", 400))
        lim_w = float(self.cfg.get("power_w_max", 6))
        if self.psu is not None and hasattr(self.psu, "measure_board"):
            time.sleep(float(self.cfg.get("power_settle", 2.0)))
            vv, aa = [], []
            for _ in range(5):
                v, a = self.psu.measure_board()
                vv.append(v); aa.append(a)
                time.sleep(0.3)
            v = robust_mean(vv, floor=0.01); ma = robust_mean(aa, floor=0.0005) * 1000
            src = "CH1 блока питания"
        else:
            ma = self.ui.ask_float("Ток потребления платы: введите показание амперметра, мА")
            if ma is None:
                raise StepSkip("ток не введён")
            v = float(self.cfg.get("board_v", 24.0))
            src = "ввод оператора"
        w = v * ma / 1000
        ok = ma <= lim_ma and w <= lim_w
        self.results["power"] = {"V": round(v, 3), "mA": round(ma, 1), "W": round(w, 3), "ok": ok}
        self.log(f"[Питание] {v:.2f} В, {ma:.1f} мА, {w:.2f} Вт ({src}); норма ≤ {lim_ma:g} мА и ≤ {lim_w:g} Вт — "
                 + ("OK" if ok else "ВНЕ ДОПУСКА"))
        if proto:
            proto.set_current_consumption(ma, ok)
        return ok

    # ---- шаги: Ethernet (Modbus TCP) и RS-485 1–3 (Modbus RTU) — 02.11.2026 ----
    # После CI: эталон читается через Service Port, Service Port отключается, плата читается по TCP,
    # потом по RTU через переходник на каждой из трёх клемм RS-485. Совпало — интерфейс годен.
    RS485_PINS = {0: "XP10: A — конт. 3, B — конт. 2, общий — конт. 4",
                  1: "XP9: A — конт. 3, B — конт. 2, общий — конт. 4",
                  2: "XP9: A — конт. 7, B — конт. 6, общий — конт. 8"}
    TIME_TOL = 5.0                            # с: время по сети должно совпасть с ПК или с Service Port

    @staticmethod
    def _offset(dev):
        """Время платы минус время ПК, с (None — время платы не разобрать)."""
        y, mo, d, h, mi, s = dev.datetime_read()
        try:
            return time.mktime((y, mo, d, h, mi, s, 0, 0, -1)) - time.time(), f"{d:02d}.{mo:02d}.{y} {h:02d}:{mi:02d}:{s:02d}"
        except (OverflowError, ValueError):
            return None, f"{d:02d}.{mo:02d}.{y} {h:02d}:{mi:02d}:{s:02d}"

    def identity(self, dev):
        off, text = self._offset(dev)
        return {"sn": dev.serial_read(), "mac": dev.mac_read().upper(), "off": off, "time": text}

    def reference(self):
        """Эталон — то, что плата отдаёт через основное подключение (Service Port)."""
        ref = self.identity(self.dev)
        self.log(f"Эталон ({getattr(self.dev, 'link', 'Service Port')}): серийный {ref['sn']}, MAC {ref['mac']}, время {ref['time']}")
        want = str(self.cfg.get("serial") or "").strip()
        if want and want.isdigit() and int(want) != ref["sn"]:
            self.note(f"серийный в плате {ref['sn']} не совпадает с номером с этикетки ({want}) — запустите шаг «Серийный № и MAC»")
        return ref

    def compare(self, name, ref, got):
        """-> (годен, текст). Время: совпадает с ПК (±5 с) или с тем, что плата отдала через Service Port."""
        bad = []
        if got["sn"] != ref["sn"]:
            bad.append(f"серийный {got['sn']} ≠ {ref['sn']}")
        if got["mac"] != ref["mac"]:
            bad.append(f"MAC {got['mac']} ≠ {ref['mac']}")
        t_ok = got["off"] is not None and (abs(got["off"]) <= self.TIME_TOL or
                                           (ref["off"] is not None and abs(got["off"] - ref["off"]) <= self.TIME_TOL))
        if not t_ok:
            bad.append(f"время {got['time']} не совпадает ни с ПК, ни с Service Port ({ref['time']})")
        text = f"серийный {got['sn']}, MAC {got['mac']}, время {got['time']}"
        if got["off"] is not None and abs(got["off"]) > self.TIME_TOL and t_ok:
            text += f" (с ПК расходится на {got['off']:+.0f} с — как и через Service Port)"
        return not bad, text + ("" if not bad else " — НЕ СОВПАЛО: " + "; ".join(bad))

    def release_main(self):
        """Отключиться от основного порта платы (Service Port), чтобы проверить другой интерфейс."""
        if self.cfg.get("sim"):
            return
        try:
            self.dev.close()
            self.log(f"Основное подключение отключено ({getattr(self.dev, 'link', 'Service Port')})")
        except Exception as e:
            self.log(f"Основное подключение: не закрылось ({e})")

    def restore_main(self):
        if self.cfg.get("sim"):
            return
        last = None
        for _ in range(5):
            try:
                self.dev.reopen()
                self.log(f"Основное подключение снова открыто ({getattr(self.dev, 'link', 'Service Port')})")
                return
            except Exception as e:
                last = e
                time.sleep(1.0)
        self.log(f"!!! Основное подключение не открылось заново: {last}")

    def _iface(self, proto, name, state):
        self.results["ifaces"][name] = state
        self.hook("iface", name, state)
        if proto:
            proto.put_iface(name, state)

    def _check_link(self, name, ref, open_dev, hint):
        """Подключиться open_dev(), прочитать серийный/MAC/время, сравнить. Нет связи — «Повторить / Пропустить шаг»."""
        while True:
            self.chk()
            try:
                d = open_dev()
                try:
                    got = self.identity(d)
                finally:
                    if d is not self.dev:
                        d.close()
                ok, text = self.compare(name, ref, got)
                self.log(f"[{name}] {text}" + (" — OK" if ok else ""))
                if not ok:
                    self.note(f"{name}: {text}")
                return "годен" if ok else "не годен"
            except (Abort, StepSkip):
                raise
            except Exception as e:
                self.log(f"!!! [{name}] нет связи: {type(e).__name__}: {e}")
                try:
                    self.retry_or_skip(f"{name}: плата не отвечает.\n{hint}")
                except StepSkip:
                    return "не годен"

    def step_eth(self, proto):
        ip = str(self.cfg.get("tcp_ip") or "10.155.26.140").strip()
        port, unit = int(self.cfg.get("tcp_port", 502) or 502), int(self.cfg.get("tcp_unit", 1) or 1)
        tmo = float(self.cfg.get("eth_timeout", 2.0))
        name = "Ethernet 10/100 Base-T"
        ref = self.reference()
        hint = (f"Проверьте сетевой кабель и что сетевая карта ПК в той же подсети, что и плата {ip} "
                f"(например, IP ПК {'.'.join(ip.split('.')[:3])}.100, маска 255.255.255.0).")
        self.release_main()
        try:
            self.detail(f"Modbus TCP {ip}:{port}, адрес {unit}")
            state = self._check_link(name, ref, (lambda: self.dev) if self.cfg.get("sim") else
                                     (lambda: MK201(host=ip, tcp_port=port, unit=unit, timeout=tmo,
                                                    trace=getattr(self.dev, "trace", None))), hint)
        finally:
            self.restore_main()
        self._iface(proto, name, state)
        return state == "годен"

    def step_rs485(self, proto):
        port = str(self.cfg.get("rs485_port") or "")
        if not port and not self.cfg.get("sim"):
            self.log("RS-485: переходник RS-485 (MOXA) не выбран на вкладке «Подключение» — состояние портов берётся "
                     "из ручного выбора (табл. 7)")
            if proto:
                for p in range(3):
                    st = (self.cfg.get("ifaces") or {}).get(f"RS-485_{p + 1}")
                    if st:
                        proto.put_iface(f"RS-485_{p + 1}", st)
            return "manual"
        baud = int(self.cfg.get("rtu_baud", 19200) or 19200)
        parity = str(self.cfg.get("rtu_parity", "N") or "N")[:1].upper()
        stop = int(float(self.cfg.get("rtu_stop", 1) or 1))
        unit = int(self.cfg.get("rtu_unit", 1) or 1)
        ref = self.reference()
        try:                                   # настройки портов в самой плате — запасной вариант и подсказка
            regs = self.dev.ports_read()
        except Exception:
            regs = None
        self.release_main()
        ok_all = True
        try:
            for p in range(3):
                name = f"RS-485_{p + 1}"
                alt = None
                if regs:
                    q = regs[8 + 6 * p: 14 + 6 * p]
                    if q[1] != 2:
                        self.log(f"!!! [{name}] в плате порт не в режиме MbSlave (режим {q[1]}) — проверка может не пройти; "
                                 "запустите шаг «Порты 1–3»")
                    alt = (BAUDS.get(q[3], baud), {0: "N", 1: "O", 2: "E"}.get(q[4], "N"), {0: 1, 2: 2}.get(q[5], 1), q[0] & 255 or unit)
                    if alt == (baud, parity, stop, unit):
                        alt = None
                self.need((f"Подключите переходник RS-485 к клемме RS-485 {p + 1}" if p == 0 else
                           f"Переключите переходник RS-485 на клемму RS-485 {p + 1}") + f"\n({self.RS485_PINS[p]}).")
                self.detail(f"{name}: {port}, {baud} бод, 8{parity}{stop}, адрес {unit}")

                def open_dev(alt=alt):
                    if self.cfg.get("sim"):
                        return self.dev
                    tr = getattr(self.dev, "trace", None)
                    try:
                        d = MK201(port, baud, unit, timeout=1.0, parity=parity, stopbits=stop, trace=tr)
                        d.serial_read()
                        return d
                    except Exception as e:
                        try: d.close()
                        except Exception: pass
                        if not alt:
                            raise
                        self.log(f"[RS-485] с настройками вкладки «Подключение» нет ответа ({e}) — пробую настройки "
                                 f"порта из платы: {alt[0]} бод, 8{alt[1]}{alt[2]}, адрес {alt[3]}")
                        return MK201(port, alt[0], alt[3], timeout=1.0, parity=alt[1], stopbits=alt[2], trace=tr)
                state = self._check_link(name, ref, open_dev,
                                         f"Проверьте подключение ({self.RS485_PINS[p]}), полярность A/B и настройки RTU "
                                         f"на вкладке «Подключение» ({baud} бод, 8{parity}{stop}, адрес {unit}).")
                ok_all = ok_all and state == "годен"
                self._iface(proto, name, state)
        finally:
            self.restore_main()
        return ok_all

    # ---- шаг: проверка DO -> DI (петля на самой плате) ----
    @staticmethod
    def bits(mask):
        return ", ".join(f"DI{i}" for i in range(16) if mask >> i & 1) or "нет"

    def psu_set(self, volts):
        self.chk()
        self.psu.set(volts, float(self.cfg.get("psu_amps", 0.2)))
        time.sleep(float(self.cfg.get("psu_settle", 0.8)))

    def psu_measure(self):
        if not getattr(self.psu, "measured", False):
            return None, None
        try:
            return self.psu.measure()
        except Exception as e:
            self.log(f"Блок питания: не удалось измерить ({e})")
            return None, None

    def step_dio(self, proto):
        """Подключение: XP7 конт. 5,10,15,20 (COM5–8) -> CH2 «+»; XP6 конт. 5,10,15,20 (COM1–4) -> CH2 «−»;
        перемычки XP7 конт. N -> XP6 конт. N.
        1) рабочее 24 В: DO по одному: включили -> пауза 0,5 с -> читаем DI -> выключили -> пауза 0,5 с.
        2) Все DO включены: 30 В -> 1 с -> читаем -> выключили -> 1 с; так же 11 В (горят все) и 5 В (не горит ни один).
        Окно оператору — только если похоже на неправильное подключение (не загорелся ни один DI)."""
        if not self.psu:
            raise RuntimeError("Блок питания не задан")
        t_on, t_volt = float(self.cfg.get("dio_on", 0.5)), float(self.cfg.get("dio_volt", 1.0))
        v_work, v_hi = float(self.cfg.get("dio_v_work", 24)), float(self.cfg.get("dio_v_hi", 30))
        v_mid, v_lo = float(self.cfg.get("dio_v_mid", 11)), float(self.cfg.get("dio_v_lo", 5))
        ok = True
        do_ok = [True] * 16
        res = {"single": [None] * 16, "all": {}}
        try:
            while True:
                self.dev.do_write(0)
                self.detail(f"{v_work:g} В на CH2")
                self.psu_set(v_work)
                for n in range(16):
                    self.chk()
                    self.detail(f"{v_work:g} В · выход {n + 1} из 16 (DO{n})")
                    self.dev.do_write(1 << n)
                    time.sleep(t_on)
                    got = self.dev.di_read() & 0xFFFF
                    v, a = self.psu_measure()
                    self.dev.do_write(0)
                    time.sleep(t_on)
                    res["single"][n] = (got, a)
                if any(g for g, _ in res["single"]):
                    break
                self.log(f"!!! [DO→DI {v_work:g} В] не загорелся ни один DI — похоже на неправильное подключение")
                self.psu.off()
                self.retry_or_skip("Проверка DO → DI: не загорелся ни один DI.\nПроверьте: COM5–COM8 (XP7 конт. 5, 10, 15, 20) "
                                   "— на «+» CH2, COM1–COM4 (XP6 конт. 5, 10, 15, 20) — на «−» CH2, "
                                   "перемычки XP7 конт. N → XP6 конт. N, выход CH2 блока питания.")
            for n, (got, a) in enumerate(res["single"]):
                cur = f", ток {a * 1000:.1f} мА" if a is not None else ""
                self.hook("dio", "single", n, got == (1 << n), got)
                if got == 1 << n:
                    self.log(f"[DO→DI {v_work:g} В · {n + 1}/16] включён DO{n} → горит DI{n}{cur} — OK")
                else:
                    ok = do_ok[n] = False
                    why = "не горит ни один DI" if not got else f"горят: {self.bits(got)}"
                    self.log(f"!!! [DO→DI {v_work:g} В · {n + 1}/16] включён DO{n}: {why} (ожидался только DI{n}){cur}")
            self.psu.off()
            time.sleep(t_volt)
            for volts, expect in ((v_hi, 0xFFFF), (v_mid, 0xFFFF), (v_lo, 0)):
                self.chk()
                self.detail(f"все DO включены · {volts:g} В")
                self.dev.do_write(0xFFFF)
                self.psu_set(volts)
                time.sleep(t_volt)
                got = self.dev.di_read() & 0xFFFF
                v, a = self.psu_measure()
                self.psu.off()
                self.dev.do_write(0)
                time.sleep(t_volt)
                res["all"][volts] = (got, a)
                self.hook("dio", volts, None, got == expect, got)
                per = (a / 16 * 1000) if a is not None else None
                cur = f", ток всего {a * 1000:.1f} мА (на вход ≈ {per:.2f} мА)" if a is not None else ""
                if got == expect:
                    self.log(f"[DO→DI {volts:g} В · все 16] " + ("горят все 16 DI" if expect else "не горит ни один DI") + cur + " — OK")
                else:
                    ok = False
                    if expect:
                        miss = [i for i in range(16) if not got >> i & 1]
                        self.log(f"!!! [DO→DI {volts:g} В · все 16] не горят: {', '.join(f'DI{i}' for i in miss)}{cur}")
                        for i in miss: do_ok[i] = False
                    else:
                        self.log(f"!!! [DO→DI {volts:g} В · все 16] при {volts:g} В не должен гореть ни один DI, горят: {self.bits(got)}{cur}")
        finally:
            try: self.dev.do_write(0)
            except Exception: pass
            try: self.psu.off()
            except Exception: pass
        if proto:
            lim = {v_lo: 30.0, v_mid: 6.0, v_hi: 30.0}
            col = {v_lo: ("B", "C"), v_mid: ("D", "E"), v_hi: ("F", "G")}
            for ch in range(16):
                cells = {}
                for volts, (cs, ci) in col.items():
                    got, a = res["all"].get(volts, (None, None))
                    if got is None:
                        continue
                    lit = bool(got >> ch & 1)
                    want = volts > v_lo
                    cells[cs] = ("да" if lit else "нет", lit == want)
                    if a is not None:
                        per = a / 16 * 1000
                        cells[ci] = ("да" if per < lim[volts] else "нет", per < lim[volts])
                proto.put_di(ch, cells)
                proto.put_do(ch, do_ok[ch])
            if not getattr(self.psu, "measured", False):
                proto.note("C70", "ток входов не измерялся: блок питания выставлялся вручную")
        self.results["dio"] = {"single": [g for g, _ in res["single"] if True], "all": {str(k): v[0] for k, v in res["all"].items()},
                               "do_ok": do_ok}
        self.log("Проверка DO → DI: " + ("OK" if ok else "ЕСТЬ ОШИБКИ — см. красные строки"))
        return ok

    # ---- шаг: счётные входы ----
    def counts_settle(self, limit):
        """Ждём, пока счётчики перестанут меняться (прибор после настройки может сам выдать пакет)."""
        step = float(self.cfg.get("ci_quiet", 1.0))
        t_end, last = time.time() + limit, self.dev.ci_counts()
        while True:
            self.chk()
            time.sleep(step)
            now = self.dev.ci_counts()
            if now == last:
                return True, now
            if time.time() > t_end:
                return False, (last, now)
            last = now

    def ci_burst(self, f, n, label):
        """Выдать n импульсов частотой f; -> прирост каждого из 4 счётчиков. Генератор молчит до запуска,
        счётчики обнуляются (или запоминаются) уже после настройки прибора."""
        box = {}

        def before():
            lim = min(n, int(self.cfg.get("gen_burst_max", 50000))) / f + 3
            quiet, r = self.counts_settle(lim)
            if not quiet:
                raise RuntimeError(f"счётчики растут без запуска пакета ({r[0]} -> {r[1]}): генератор выдаёт непрерывный сигнал")
            if self.can_reset:
                try:
                    self.dev.ci_reset()
                except ModbusError as e:
                    if e.code != 2:
                        raise
                    self.can_reset = False
                    self.log("Сброс счётчиков (рег. 409) плата не принимает — считаю по разнице показаний до и после")
            quiet, r = self.counts_settle(3)
            if not quiet:
                raise RuntimeError(f"счётчики растут без запуска пакета ({r[0]} -> {r[1]})")
            box["start"] = r

        self.log(f"[CI {label}] генератор: {f:g} Гц, {n} импульсов")
        self.gen.pulses(f, n, burst_max=int(self.cfg.get("gen_burst_max", 50000)),
                        method=self.cfg.get("gen_method", "burst"), stop=self.chk,
                        trig=self.cfg.get("gen_trig", "C1:BTWV MTRIG"), ask=self.need, before=before,
                        probe=lambda: any((a - b) % (1 << 32) for a, b in zip(self.dev.ci_counts(), box["start"])),
                        progress=lambda p: self.hook("sub_progress", int(p * 100)))
        ok_trig = getattr(self.gen, "trig_ok", None)
        if ok_trig and ok_trig != self.cfg.get("gen_trig"):
            self.cfg["gen_trig"] = ok_trig
            self.hook("gen_trig", ok_trig)
        time.sleep(0.5)
        got = self.dev.ci_counts()
        return [(g - s) % (1 << 32) for g, s in zip(got, box["start"])], box["start"], got

    def ci_eval(self, proto, i, f, n, chans, cnts, start, got, label):
        tol = int(self.cfg.get("ci_tol", 1))
        ok = True
        for ch in chans:
            err = cnts[ch] - n
            good = abs(err) <= tol
            self.hook("ci", ch, i, f, n, cnts[ch], good)
            self.results["ci"][f"{ch}:{i}"] = {"f": f, "n": n, "got": cnts[ch], "ok": good}
            if proto:
                proto.put_ci(ch, i, cnts[ch], err, good)
            self.log(f"[CI {label} · CI{ch + 1}] {f:g} Гц: подано {n}, насчитано {cnts[ch]} "
                     f"(было {start[ch]}, стало {got[ch]}), погрешность {err:+d}" + (" — OK" if good else " — ВНЕ ДОПУСКА (±%d)" % tol))
            ok = ok and good
        return ok

    def ci_freq_check(self, f, chans, label):
        """ТУ п. 8.9.1: режим частотомера. Непрерывный сигнал f, читаем частоту, погрешность от 30 кГц ≤ 0,01 %."""
        tol = float(self.cfg.get("ci_freq_tol", 0.01))
        self.dev.ci_cfg_write(modes=[2, 2, 2, 2])
        ok = True
        try:
            self.gen.continuous(f)
            time.sleep(max(float(self.cfg.get("ci_freq_settle", 3.0)), 20.0 / f))
            fr = self.dev.ci_freq()
            for ch in chans:
                d = abs(fr[ch] - f) / 30000 * 100
                good = d <= tol
                ok = ok and good
                self.results["ci_freq"].append({"ch": ch + 1, "f": f, "meas": round(fr[ch], 4), "err_pct": round(d, 5), "ok": good})
                self.log(f"[CI {label} · CI{ch + 1}] частотомер: задано {f:g} Гц, измерено {fr[ch]:.4f} Гц, "
                         f"погрешность {d:.4f} % (допуск {tol:g} %)" + (" — OK" if good else " — ВНЕ ДОПУСКА"))
        finally:
            self.gen.stop_continuous()
            self.dev.ci_cfg_write(modes=[1, 1, 1, 1])
        return ok

    def wiring(self, text, want, ask=True):
        """Просьба переключить клеммы генератора (в симуляторе — переключает «провода» сама).
        ask=False — оператор уже получил подсказку в окне «Повторить / Пропустить шаг»."""
        if ask:
            self.need(text)
        sim = getattr(self.gen, "sim_wire", None)
        if sim:
            sim(want)

    def step_ci(self, proto):
        """Синус 20 Vpp с генератора напрямую на счётные входы (без ключа и 24 В).
        29.10.2026: по клеммам — сначала одна (CI1+CI2 или CI3+CI4, любая) на всех частотах 10 Гц / 15 кГц / 30 кГц,
        потом другая на всех частотах (все 4 входа сразу иногда теряли или добавляли импульсы).
        На каждой частоте ещё проверка в режиме частотомера (ТУ п. 8.9.1). Окна — только «переключите на другую
        клемму» и если что-то подключено не так."""
        if not self.gen:
            self.log("Генератор не подключён к ПК — проверка вручную по подсказкам")
            self.gen = ManualGen(self.need, margin=float(self.cfg.get("gen_margin", 0.5)))
        modes, period, bounce = self.dev.ci_cfg_read()
        self.log(f"Счётные входы: в плате режимы {list(modes)}")
        amp = float(self.cfg.get("gen_amp", 20))
        self.log(f"Генератор: синус {amp:g} Vpp, смещение 0, Hi-Z, Burst · N Cycle · ручной запуск, выход вкл")
        self.gen.setup(amp)
        pts = proto.ci_points(0) if proto else [(10, 300), (15000, 450000), (30000, 900000)]
        self.can_reset = True
        ok = True
        pairs = ((0, 1), (2, 3))
        try:
            self.dev.ci_cfg_write(modes=[1, 1, 1, 1], bounce=None if bounce is None else [0, 0, 0, 0])
            back = self.dev.ci_cfg_read()[0]
            if list(back) != [1, 1, 1, 1]:
                raise RuntimeError(f"плата не приняла режим «Счётчик»: режимы {list(back)}")
            # 29.10.2026: одна клемма (пара входов) — все частоты подряд, потом другая клемма — все частоты.
            # Какая клемма подключена, видно по счётчикам первой точки. В начале окна нет: генератор остаётся
            # на той клемме, где был у прошлой платы; окно — только «переключите на другую» и если что-то не так.
            done = set()
            again = False                        # после «Повторить» второе окно с той же просьбой не показываем
            while len(done) < 2:
                self.chk()
                if not done:
                    self.wiring("Счётные входы: подключите генератор к одной клемме — CI1+CI2 или CI3+CI4 (любую).",
                                "pair", ask=False)
                else:
                    q = [x for x in range(2) if x not in done][0]
                    self.wiring(f"Переключите генератор на другую клемму: CI{pairs[q][0] + 1}+CI{pairs[q][1] + 1}.",
                                "other", ask=not again)
                again = True
                p = None
                i = 0
                restart = False
                while i < len(pts):
                    self.chk()
                    f, n = pts[i]
                    label = f"{i + 1}/{len(pts)}"
                    where = "клемма не определена" if p is None else f"клемма CI{pairs[p][0] + 1}+CI{pairs[p][1] + 1}"
                    self.detail(f"точка {label}: {f:g} Гц × {n} имп (≈ {n / f:.0f} с), {where}")
                    try:
                        cnts, start, got = self.ci_burst(f, n, label)
                    except RuntimeError as e:
                        self.log(f"!!! [CI {label}] {e}")
                        self.retry_or_skip(f"Счётные входы, {f:g} Гц: {e}.")
                        continue                 # та же точка ещё раз
                    grown = {ch for ch in range(4) if cnts[ch]}
                    if p is None:
                        hit = [x for x in range(2) if grown & set(pairs[x])]
                        if not hit:
                            self.log(f"!!! [CI {label}] счётчики не изменились — генератор не подключён?")
                            self.retry_or_skip("Счётчики не изменились. Подключите генератор к одной клемме — "
                                               + ("CI1+CI2 или CI3+CI4." if not done else
                                                  f"CI{pairs[1 - list(done)[0]][0] + 1}+CI{pairs[1 - list(done)[0]][1] + 1}."))
                            restart = True; break
                        if len(hit) > 1:
                            self.log(f"!!! [CI {label}] импульсы пришли на обе клеммы ({sorted(c + 1 for c in grown)})")
                            self.retry_or_skip("Импульсы пришли на обе клеммы сразу. Оставьте генератор подключённым "
                                               "только к одной: CI1+CI2 или CI3+CI4.")
                            restart = True; break
                        if hit[0] in done:
                            q = 1 - hit[0]
                            self.log(f"!!! [CI {label}] клемма CI{pairs[hit[0]][0] + 1}+CI{pairs[hit[0]][1] + 1} уже проверена")
                            self.retry_or_skip(f"Эта клемма уже проверена. Подключите генератор к CI{pairs[q][0] + 1}+CI{pairs[q][1] + 1}.")
                            restart = True; break
                        p = hit[0]
                        self.log(f"Счётные входы: генератор на клемме CI{pairs[p][0] + 1}+CI{pairs[p][1] + 1}")
                    stray = sorted(ch for ch in grown if ch not in pairs[p])
                    if stray:
                        ok = False
                        self.note(f"{f:g} Гц: генератор на CI{pairs[p][0] + 1}+CI{pairs[p][1] + 1}, а выросли и "
                                  + ", ".join(f"CI{c + 1} (+{cnts[c]})" for c in stray) + " — наводка или перемычка между клеммами?")
                    ok = self.ci_eval(proto, i, f, n, pairs[p], cnts, start, got, label) and ok
                    ok = self.ci_freq_check(f, pairs[p], label) and ok
                    i += 1
                if restart or p is None:
                    continue
                done.add(p)
                again = False
        except StepSkip:
            raise
        finally:
            try:
                self.dev.ci_cfg_write(modes=modes, bounce=bounce)
                self.log(f"Счётные входы: прежние настройки восстановлены (режимы {list(modes)})")
            except Exception as e:
                self.log(f"Счётные входы: не удалось вернуть прежние настройки ({e})")
        if proto and self.results["ci_freq"]:
            bad = [r for r in self.results["ci_freq"] if not r["ok"]]
            proto.note("D112", "Частотомер: " + "; ".join(f"CI{r['ch']} {r['f']:g} Гц → {r['meas']} Гц" for r in self.results["ci_freq"])
                       + (" — есть выход за допуск" if bad else " — в допуске"))
        self.log("Проверка счётных входов: " + ("OK" if ok else "ЕСТЬ ОШИБКИ"))
        return ok

    # ---- полный сценарий ----
    TABLE = [("power", "Ток и мощность потребления"), ("ports", "Настройка портов"), ("time", "Дата и время"),
             ("serial", "Серийный номер и MAC"), ("ai_cal", "Калибровка AI"), ("ai_check", "Проверка AI"),
             ("ao_cal", "Калибровка AO"), ("ao_check", "Проверка AO"), ("dio", "Проверка DO → DI"),
             ("ci", "Счётные входы CI"), ("eth", "Ethernet (Modbus TCP)"), ("rs485", "RS-485 1–3 (Modbus RTU)")]

    def run(self, steps):
        base = self.cfg.get("base_protocol") or self.cfg.get("template")
        proto = Protocol(base, self.cfg.get("template")) if base else None
        if proto and self.cfg.get("base_protocol"):
            self.log(f"Дописываю в протокол: {self.cfg['base_protocol']}")
        self.results["ifaces"] = dict(self.cfg.get("ifaces") or {})
        if proto:
            proto.set_title(self.cfg.get("protocol_number") or self.cfg["serial"])
            if self.cfg.get("operator"):
                proto.set_operator(self.cfg["operator"], time.strftime("%d.%m.%Y"))
            for name, state in self.results["ifaces"].items():
                auto = (name == "Ethernet 10/100 Base-T" and "eth" in steps) or (name.startswith("RS-485") and "rs485" in steps)
                if state and not auto:
                    proto.put_iface(name, state)
        fns = {"power": lambda: self.step_power(proto), "ports": self.step_ports, "time": self.step_time,
               "serial": self.step_serial_mac, "ai_cal": self.step_ai_cal, "ai_check": lambda: self.step_ai_check(proto),
               "ao_cal": self.step_ao_cal, "ao_check": lambda: self.step_ao_check(proto), "dio": lambda: self.step_dio(proto),
               "ci": lambda: self.step_ci(proto), "eth": lambda: self.step_eth(proto), "rs485": lambda: self.step_rs485(proto)}
        sel = [(k, t, fns[k]) for k, t in self.TABLE if k in steps]
        done = 0
        try:
            for n_, (key, title, fn) in enumerate(sel, 1):
                self.chk()
                self.pos = (n_, len(sel), title)
                self.hook("progress", done, len(sel))
                self.hook("phase", title)
                self.detail()
                self.log(f"=== Шаг {n_} из {len(sel)}: {title} ===")
                self.hook("step", key, "run")
                try:
                    if key in ("ai_cal", "ai_check") and not self.contact_ok:
                        self.contact_check()
                    if key == "ai_cal":
                        self.warmup()
                    self.hook("phase", title)
                    okstep = fn()
                except Abort:
                    self.hook("step", key, "fail"); self.results["steps"][key] = "fail"
                    raise
                except StepSkip as e:
                    okstep = None
                    self.hook("step", key, "fail"); self.results["steps"][key] = "fail"
                    self.results["ok"] = False
                    self.log(f"!!! Шаг «{title}» пропущен: {e}")
                except Exception as e:
                    self.hook("step", key, "fail"); self.results["steps"][key] = "fail"
                    self.results["ok"] = False
                    self.log(f"ОШИБКА на шаге «{title}»: {type(e).__name__}: {e}")
                    if lost_link(e):
                        self.log("!!! Пропала связь с платой — дальнейшие шаги не выполняются")
                        break
                    done += 1
                    continue
                if okstep == "manual":
                    self.hook("step", key, "manual"); self.results["steps"][key] = "manual"
                elif okstep is not None:
                    self.hook("step", key, "ok" if okstep else "warn")
                    self.results["steps"][key] = "ok" if okstep else "warn"
                    if not okstep:
                        self.results["ok"] = False
                        self.log(f"!!! Шаг «{title}» не пройден — продолжаю")
                done += 1
            self.hook("progress", done, len(sel))
        except Abort as e:
            self.log(str(e)); self.results["ok"] = False; self.results["stopped"] = True
        finally:
            self.hook("detail", "")
            try: self.relay.off()
            except Exception: pass
            if self.cal:
                try: self.cal.source_off()
                except Exception: pass
            if proto:
                self.finish_protocol(proto, steps)
        return self.results

    def finish_protocol(self, proto, steps):
        for (kind, ch), notes in self.flags.items():
            proto.flag(kind, ch, "; ".join(notes))
        r = self.results
        names = dict(self.TABLE)
        # повторный прогон дописывает протокол: шаги прошлых прогонов этого номера (за сегодня) тоже учитываются
        allsteps = dict(self.cfg.get("prev_steps") or {})
        allsteps.update(r["steps"])
        r["steps_all"] = allsteps
        for key, val in (self.cfg.get("prev_results") or {}).items():   # коды калибровки и т.п. из прошлого прогона
            if key in ("power", "ai_cal", "ao_cal", "ci_freq") and val and not r.get(key):
                r[key] = val
        bad = [names.get(k, k) for k, v in allsteps.items() if v not in ("ok", "manual")]
        concl = "ГОДЕН" if r["ok"] and not bad else "НЕ ГОДЕН: " + ", ".join(bad or ["прогон остановлен"])
        r["conclusion"] = concl
        rows = [("Заключение", concl), ("Номер изделия", self.cfg.get("protocol_number", "")),
                ("Серийный номер в плате", self.cfg.get("serial", "")), ("Оператор", self.cfg.get("operator", "")),
                ("Дата и время", time.strftime("%d.%m.%Y %H:%M:%S")), ("Версия программы", self.cfg.get("version", "")),
                ("Выполнялись шаги", ", ".join(names[k] for k in steps if k in names))]
        for k, v in allsteps.items():
            rows.append((f"Шаг: {names.get(k, k)}" + ("" if k in r["steps"] else " (прошлый прогон)"), {"ok": "ОК", "warn": "!!! замечания", "fail": "!!! ошибка",
                                                      "manual": "вручную (прибор не подключён)"}.get(v, v)))
        if r.get("power"):
            p = r["power"]
            rows.append(("Потребление", f"{p['V']} В, {p['mA']} мА, {p['W']} Вт" + ("" if p["ok"] else " — !!! вне нормы")))
        if r.get("ai_cal"):
            rows.append(("Калибровка AI (коды 4/16/18/20 мА)", "; ".join(f"AI{c}={row}" for c, row in enumerate(r["ai_cal"]))))
        if r.get("ao_cal"):
            rows.append(("Калибровка AO (4 / 19 мА)", "; ".join(f"AO{c}=({p[0]:.4f}, {p[1]:.4f})" for c, p in enumerate(r["ao_cal"]))))
        for x in r.get("ci_freq", []):
            rows.append((f"Частотомер CI{x['ch']}, {x['f']:g} Гц", f"{x['meas']} Гц, погрешность {x['err_pct']} %"
                         + ("" if x["ok"] else " — !!! вне допуска")))
        for (kind, ch), notes in self.flags.items():
            rows.append((f"!!! {kind.upper()}{ch}", "; ".join(notes)))
        for t in r.get("notes", []):
            rows.append(("!!! Предупреждение", t))
        proto.data_sheet(rows)
        out = self.cfg["out_path"]
        try:
            proto.save(out)
        except PermissionError:
            base, ext = os.path.splitext(out)
            out = f"{base}_{time.strftime('%H%M%S')}{ext}"
            self.log("Файл протокола открыт в Excel — сохраняю под другим именем")
            proto.save(out)
        self.cfg["out_path"] = out
        r["protocol"] = out
        try:
            with open(os.path.splitext(out)[0] + ".json", "w", encoding="utf-8") as f:
                json.dump({"cfg": {k: v for k, v in self.cfg.items() if isinstance(v, (str, int, float, bool, dict, list))},
                           "results": r, "steps": list(steps)}, f, ensure_ascii=False, indent=1, default=str)
        except Exception as e:
            self.log(f"Не удалось сохранить .json рядом с протоколом: {e}")
        self.log(f"Протокол сохранён: {out}")
        self.log(f"Заключение: {concl}")
