#!/usr/bin/env python3
"""
Консольная версия мини-стенда (то же ядро, что у окна stand_gui.py).

    python stand_cli.py --serial 1112120002600740 --product MIB-212 --ca71 COM2
    python stand_cli.py --serial 1112120002600740 --mode cal --gate COM3 --warmup-min 10
    python stand_cli.py --selftest --ca71 COM2

Проверка без оборудования:  python stand_cli.py --simulate --serial TEST
"""
import argparse
import sys
import time

from check_logic import MODE_CAL, MODE_CHECK, StandConfig, StandDevices, StandSequencer


class ConsoleIO:
    def log(self, text):
        print(f"[{time.strftime('%H:%M:%S')}] {text}")

    def state(self, name):
        pass

    def timer(self, remaining, total):
        print(f"\rПрогрев: {int(remaining // 60):02d}:{int(remaining % 60):02d}   ", end="", flush=True)

    def point(self, ch, label, meas, err, ok):
        self.log(f"  канал {ch}, {label:>12} мА -> {meas:.4f} мА, {err:+.4f} % {'OK' if ok else 'ВНЕ ДОПУСКА'}")

    def supply(self, ma, volts):
        pass

    def done(self, path, lines, ok):
        for line in lines:
            self.log(line)
        self.log(f"Итог: {'PASS' if ok else 'FAIL'}. Протокол: {path}")

    def finished(self):
        pass


def main():
    ap = argparse.ArgumentParser(description="Мини-стенд MIB-211/212")
    ap.add_argument("--serial", help="серийный номер изделия")
    ap.add_argument("--product", default="MIB-212", help="MIB-211 - один канал, MIB-212 - два")
    ap.add_argument("--operator", default="")
    ap.add_argument("--mode", choices=[MODE_CHECK, MODE_CAL], default=MODE_CHECK)
    ap.add_argument("--stand", default="", help="порт стенда (пусто - найти автоматически)")
    ap.add_argument("--ca71", default="COM2")
    ap.add_argument("--gate", default="", help="порт MK-531 (нужен для калибровки и чтения коэффициента)")
    ap.add_argument("--template", default="", help="шаблон протокола .xlsx MIB_Calibrator")
    ap.add_argument("--outdir", default="Протоколы")
    ap.add_argument("--warmup-min", type=float, default=1.0)
    ap.add_argument("--tol", type=float, default=0.08)
    ap.add_argument("--i-min", type=float, default=10.0)
    ap.add_argument("--i-max", type=float, default=150.0)
    ap.add_argument("--selftest", action="store_true", help="самоконтроль стенда без изделия")
    ap.add_argument("--simulate", action="store_true")
    a = ap.parse_args()
    if not a.selftest and not a.serial:
        ap.error("укажите --serial")

    cfg = StandConfig(tol=a.tol, i_min=a.i_min, i_max=a.i_max)
    if a.simulate:
        cfg.settle = cfg.read_delay = cfg.power_settle = 0.0
        cfg.keepalive = 0.2
    io = ConsoleIO()
    dev = StandDevices(a.stand, a.gate, a.ca71, a.simulate)
    dev.connect(io.log)
    seq = StandSequencer(dev, cfg, io, a.mode, 0 if a.simulate else a.warmup_min * 60, a.serial or "",
                         a.operator, a.template, a.outdir, a.product)
    try:
        if a.selftest:
            return 0 if seq.run_selftest() else 1
        seq.run()
    except KeyboardInterrupt:
        seq.stop()
    finally:
        dev.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
