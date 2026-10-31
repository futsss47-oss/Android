"""
algorithm - алгоритм калибровки MIB-212 (без интерфейса и без прямой работы с портами).

Погрешность считается как в шаблоне: (измеренный - заданный) / span * 100 %, span = 16 мА.
"""
import datetime as dt
import os
import re
import time
from dataclasses import dataclass

from xlsx_patch import patch_xlsx

POINTS = [0, 4, 8, 12, 16, 20]
# ячейки шаблона, лист «Данные»: канал -> (коэффициент, первая строка точек, строка «20 мА, 400 Ом»)
CELLS = {1: ("C9", 12, 18), 2: ("C23", 26, 32)}


# ячейки листа «Лист1» с результатами калибровки канала (для прочерков у неиспользуемого канала)
L1_CALIB = {1: ["F12", "F13", "F14", "F15", "F16", "F17", "F18", "F19", "F20", "F21", "I12", "I13", "I21"],
            2: ["F29", "F30", "F31", "F32", "F33", "F34", "F35", "F36", "F37", "F38", "I29", "I30", "I38"]}


# ячейки «Решение» листа «Лист1»: канал -> (погрешность при 20 мА, максимальная, при 20 мА и 400 Ом)
L1_DECISION = {1: ("I12", "I13", "I21"), 2: ("I29", "I30", "I38")}
EPS = 1e-9


def channels_for(product: str) -> tuple:
    """Изделие, название которого заканчивается на 1 (MIB-211, MMC-221...), одноканальное."""
    name = re.sub(r"[^0-9A-Za-zА-Яа-я]", "", product or "")
    return (1,) if name.endswith("1") else (1, 2)


class Stopped(Exception):
    """Оператор остановил процесс."""


@dataclass
class Config:
    tol: float = 0.08        # допуск, %
    aim: float = 0.02        # целевая погрешность при подборе, %
    span: float = 16.0       # диапазон для расчёта погрешности, мА
    settle: float = 1.5      # пауза после смены режима, с
    n_avg: int = 3           # число усредняемых отсчётов CA71
    read_delay: float = 1.1  # пауза между отсчётами CA71, с
    max_dev: float = 1.0     # аварийный предел отклонения тока от заданного, мА


def err_pct(measured: float, setpoint: float, span: float) -> float:
    return (measured - setpoint) / span * 100.0


def pause(seconds: float, check):
    """Пауза, которую можно прервать (check() бросает Stopped)."""
    end = time.monotonic() + seconds
    while True:
        check()
        left = end - time.monotonic()
        if left <= 0:
            return
        time.sleep(min(0.1, left))


def tune_code(measure_err, start, lo=0, hi=256, aim=0.02, max_iter=12, max_step=24, log=print):
    """
    Подбор кода потенциометра методом секущих.
    measure_err(code) -> погрешность, %. Возвращает (код, погрешность).
    Зависимость может быть как возрастающей, так и убывающей.
    """
    cache = {}

    def ev(c):
        if c not in cache:
            cache[c] = measure_err(c)
            log(f"    код {c:3d}: погрешность {cache[c]:+.4f} %")
        return cache[c]

    c0 = max(lo, min(hi, start))
    e0 = ev(c0)
    if abs(e0) > aim:
        c1 = c0 + 4 if c0 + 4 <= hi else c0 - 4
        pts = [(c0, e0), (c1, ev(c1))]
        for _ in range(max_iter):
            (ca, ea), (cb, eb) = pts[-2], pts[-1]
            if abs(eb) <= aim:
                break
            if eb == ea:
                raise RuntimeError("погрешность не меняется при изменении кода: проверьте подключение")
            slope = (eb - ea) / (cb - ca)
            cn = cb - eb / slope
            cn = int(round(max(cb - max_step, min(cb + max_step, cn))))
            cn = max(lo, min(hi, cn))
            if cn in cache:
                break
            pts.append((cn, ev(cn)))
    best_c, best_e = min(cache.items(), key=lambda kv: abs(kv[1]))
    if abs(best_e) > aim:
        for c in (best_c - 1, best_c + 1):
            if lo <= c <= hi:
                ev(c)
        best_c, best_e = min(cache.items(), key=lambda kv: abs(kv[1]))
    return best_c, best_e


def calibrate_channel(ch, dev, cfg: Config, log, check, on_point):
    """
    Калибровка одного канала. CA71 уже подключён к этому каналу.
    Возвращает (коэффициент, {точка_мА: измеренный_ток_мА}).
    """
    gate, ca71 = dev.gate, dev.ca71
    dev.set_channel(ch)
    log(f"=== КАНАЛ {ch}: калибровка ===")
    ca71.set_current(20.0)
    ca71.output(True)
    try:
        def measure_at(setpoint):
            pause(cfg.settle, check)
            m = ca71.read_current_avg(cfg.n_avg, cfg.read_delay)
            check()
            if abs(m - setpoint) > cfg.max_dev:
                raise RuntimeError(f"измеренный ток {m:.3f} мА слишком далёк от заданного {setpoint} мА")
            return m

        try:
            start = gate.read_wiper(ch)
        except Exception as e:
            log(f"  не удалось прочитать текущий коэффициент ({e}), старт с 128")
            start = 128
        log(f"  текущий коэффициент: {start}")

        def measure_err(code):
            check()
            gate.write_wiper(ch, code, rom=False, check=True)
            return err_pct(measure_at(20.0), 20.0, cfg.span)

        code, e = tune_code(measure_err, start, aim=cfg.aim, log=log)
        gate.write_wiper(ch, code, rom=False, check=True)
        gate.write_wiper(ch, code, rom=True, check=False)
        try:
            back = gate.read_wiper(ch, rom=True)
            if back != code:
                log(f"  ВНИМАНИЕ: в ROM прочитано {back}, записывали {code}")
        except Exception as ex:
            log(f"  не удалось проверить запись в ROM чтением: {ex}")
        log(f"  выбран коэффициент {code}, погрешность при 20 мА {e:+.4f} %")

        meas = {}
        for p in POINTS:
            check()
            ca71.set_current(float(p))
            meas[p] = measure_at(float(p))
            er = err_pct(meas[p], p, cfg.span)
            on_point(ch, f"{p}", meas[p], er, abs(er) <= cfg.tol)
        er20 = err_pct(meas[20], 20, cfg.span)
        on_point(ch, "20 (400 Ом)", meas[20], er20, abs(er20) <= cfg.tol)  # отдельно не измеряется
    finally:
        try:
            ca71.output(False)
        except Exception:
            pass
    return code, meas


def channel_errors(meas: dict, span: float) -> list:
    """Погрешности по точкам POINTS, % (по значениям, округлённым как в протоколе)."""
    return [err_pct(round(meas[p], 4), p, span) for p in POINTS]


def decisions(meas: dict, cfg: Config):
    """-> (погрешность при 20 мА, максимальная, годно при 20 мА, годно по максимуму)."""
    errs = channel_errors(meas, cfg.span)
    e20, emax = errs[-1], max(abs(x) for x in errs)
    return e20, emax, abs(e20) <= cfg.tol + EPS, emax <= cfg.tol + EPS


def evaluate(results: dict, cfg: Config):
    """-> (строки итога, общий результат)."""
    lines, ok_all = [], True
    for ch, (code, meas) in sorted(results.items()):
        e20, emax, ok20, okmax = decisions(meas, cfg)
        ok = ok20 and okmax
        ok_all &= ok
        lines.append(f"Канал {ch}: коэффициент {code}, при 20 мА {e20:+.4f} %, максимум {emax:.4f} % -> "
                     f"{'PASS' if ok else 'FAIL'} (допуск {cfg.tol} %)")
    return lines, ok_all


def save_protocol(template, outdir, serial, operator, results: dict, product: str = "",
                  cfg: Config = None) -> str:
    """Записать результаты и решения PASS/FAIL в копию шаблона, вернуть путь к файлу."""
    cfg = cfg or Config()
    cells, l1 = {}, {}
    for ch, (coef_cell, row0, row400) in CELLS.items():
        if ch in results:
            code, meas = results[ch]
            cells[coef_cell] = int(code)
            for i, p in enumerate(POINTS):
                cells[f"B{row0 + i}"] = round(meas[p], 4)
            cells[f"B{row400}"] = round(meas[20], 4)
            _, _, ok20, okmax = decisions(meas, cfg)
            d20, dmax, d400 = L1_DECISION[ch]
            l1[d20] = "PASS" if ok20 else "FAIL"
            l1[dmax] = "PASS" if okmax else "FAIL"
            l1[d400] = "PASS" if ok20 else "FAIL"  # на 400 Ом значение то же, что при 20 мА
        else:  # канала нет: убрать образцовые значения шаблона, в протоколе поставить прочерки
            cells[coef_cell] = None
            for r in range(row0, row400 + 1):
                cells[f"B{r}"] = None
            for ref in L1_CALIB[ch]:
                l1[ref] = "-"
    now = dt.datetime.now()
    day = (now - dt.datetime(1899, 12, 30)).total_seconds() / 86400.0
    l1.update({"D7": serial, "H7": operator, "I7": day})
    if product:
        l1["D5"] = product
    os.makedirs(outdir, exist_ok=True)
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in serial)
    out = os.path.join(outdir, f"Протокол_{safe}_{now:%Y%m%d_%H%M%S}.xlsx")
    patch_xlsx(template, out, {"Данные": cells, "Лист1": l1})
    return out
