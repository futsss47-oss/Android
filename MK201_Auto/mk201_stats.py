"""Устойчивое среднее (как в калибраторе МИБ): одиночные выбросы отбрасываются."""


def robust_mean(vals, floor=None, log=None, what=""):
    """Медиана -> отклонения -> значения дальше 3·σ (σ по медианному отклонению, не меньше floor) отбрасываются,
    остальные усредняются. Если осталось меньше половины — берётся медиана."""
    vals = [float(v) for v in vals]
    if not vals:
        raise ValueError("нет отсчётов")
    if len(vals) <= 2:
        return sum(vals) / len(vals)
    s = sorted(vals)
    n = len(s)
    med = s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2
    mad = sorted(abs(v - med) for v in vals)[n // 2]
    if floor is None:
        floor = max(abs(med) * 1e-4, 1e-3)
    lim = max(3 * 1.4826 * mad, floor)
    keep = [v for v in vals if abs(v - med) <= lim]
    drop = [v for v in vals if abs(v - med) > lim]
    if drop and log:
        log(f"{what + ': ' if what else ''}отброшены выбросы {[round(x, 4) for x in drop]} (медиана {med:.4f})")
    if len(keep) < n / 2:
        return med
    return sum(keep) / len(keep)
