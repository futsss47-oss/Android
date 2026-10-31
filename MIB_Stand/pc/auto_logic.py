"""
auto_logic - автоматика: подключение приборов и последовательность
    проверка приборов -> 20 мА -> таймер -> калибровка канала 1 -> выход выключен, сообщение
    «переключите канал» -> 20 мА -> таймер -> калибровка канала 2 -> протокол.

Sequencer не знает про интерфейс: всё общение через объект io с методами
    log(text), state(name), timer(remaining, total), point(ch, label, meas, err, ok),
    ask_switch(ch), done(path, lines, ok), finished()
"""
import threading
import time

from algorithm import Config, Stopped, calibrate_channel, channels_for, evaluate, pause, save_protocol


class Devices:
    def __init__(self, gate_port="COM3", ca71_port="COM2", simulate=False):
        self.gate_port, self.ca71_port, self.simulate = gate_port, ca71_port, simulate
        self.gate = None
        self.ca71 = None

    def connect(self, log):
        self.close()
        if self.simulate:
            from simulation import FakeCA71, FakeGate
            self.gate = FakeGate()
            self.ca71 = FakeCA71(self.gate)
            log("Режим имитации: оборудование не используется")
            return
        try:
            from mk531 import MK531
            self.gate = MK531(self.gate_port)
            log(f"Шлюз MK-531 подключён: {self.gate_port}")
        except Exception as e:
            self.gate = None
            log(f"Шлюз {self.gate_port}: ОШИБКА - {e}")
        try:
            from ca71 import CA71
            self.ca71 = CA71(self.ca71_port)
            log(f"CA71 подключён: {self.ca71_port}")
        except Exception as e:
            self.ca71 = None
            log(f"CA71 {self.ca71_port}: ОШИБКА - {e}")

    def close(self):
        for name in ("gate", "ca71"):
            dev = getattr(self, name)
            if dev is not None:
                try:
                    dev.close()
                except Exception:
                    pass
            setattr(self, name, None)

    def set_channel(self, ch):
        if hasattr(self.ca71, "ch"):  # только для имитации
            self.ca71.ch = ch


class Sequencer:
    def __init__(self, dev, cfg: Config, io, warmup_s, serial, operator, template, outdir, product=""):
        self.dev, self.cfg, self.io = dev, cfg, io
        self.warmup_s, self.serial, self.operator = warmup_s, serial, operator
        self.template, self.outdir, self.product = template, outdir, product
        self.channels = channels_for(product)
        self._stop, self._skip, self._go = threading.Event(), threading.Event(), threading.Event()

    # --- управление из интерфейса ---
    def stop(self):
        self._stop.set()

    def skip_timer(self):
        self._skip.set()

    def confirm_switch(self):
        self._go.set()

    def check(self):
        if self._stop.is_set():
            raise Stopped()

    # --- этапы ---
    def _precheck(self):
        if self.dev.gate is None or self.dev.ca71 is None:
            raise RuntimeError("подключите шлюз и CA71 (кнопка «Подключить»)")
        self.dev.gate.find_interface()
        self.dev.ca71.measure_setup()
        sf = self.dev.ca71.source_function()
        self.io.log(f"Источник CA71: {sf}")
        if sf != "4-20mA":
            self.io.log("ВНИМАНИЕ: на CA71 ожидается функция источника 4-20mA")

    def _warmup(self, ch):
        """Таймер перед калибровкой канала ch (пропускается кнопкой, при 0 мин не запускается)."""
        total = float(self.warmup_s)
        self._skip.clear()
        if total <= 0:
            return
        self.io.state(f"warmup{ch}")
        self.io.log(f"Канал {ch}: прогрев {int(total // 60)} мин {int(total % 60)} с, "
                    f"затем автоматическая калибровка")
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

    def _source_on(self, ch):
        """Сразу подать 20 мА на канал ch: плата прогревается под током во время таймера."""
        self.dev.set_channel(ch)
        self.dev.ca71.set_current(20.0)
        self.dev.ca71.output(True)
        self.io.log(f"Канал {ch}: CA71 выдаёт 20 мА")

    def _wait_switch(self, ch):
        self._go.clear()
        self.io.state("switch")
        self.io.ask_switch(ch)
        while not self._go.is_set():
            pause(0.1, self.check)

    def run(self):
        results = {}
        try:
            self.io.state("precheck")
            self._precheck()
            if len(self.channels) == 1:
                self.io.log(f"Изделие {self.product}: один канал, калибруется только канал 1")
            for i, ch in enumerate(self.channels):
                if i > 0:
                    self._wait_switch(ch)  # выход CA71 на время переключения выключен
                self._source_on(ch)       # 20 мА сразу, ещё до таймера
                self._warmup(ch)
                self.io.state(f"cal{ch}")
                results[ch] = calibrate_channel(ch, self.dev, self.cfg, self.io.log, self.check, self.io.point)
            lines, ok = evaluate(results, self.cfg)
            path = save_protocol(self.template, self.outdir, self.serial, self.operator, results,
                                 self.product, self.cfg)
            self.io.state("done_ok" if ok else "done_fail")
            self.io.done(path, lines, ok)
        except Stopped:
            self.io.state("stopped")
            self.io.log("Остановлено оператором")
        except Exception as e:
            self.io.state("error")
            self.io.log(f"ОШИБКА: {e}")
        finally:
            try:
                self.dev.ca71.output(False)
            except Exception:
                pass
            self.io.finished()
