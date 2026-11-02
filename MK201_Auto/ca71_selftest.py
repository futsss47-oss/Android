"""Проверка связи с CA71: python ca71_selftest.py COM5 — печатает сырые ответы калибратора."""
import sys
from ca71 import CA71
c = CA71(sys.argv[1])
for q in ("MF?", "SF?", "MO?", "OS", "OD", "OE"):
    try:
        print(q, "->", repr(c.query(q)))
    except Exception as e:
        print(q, "-> ОШИБКА", e)
print("разобрано мА:", CA71.parse_od(c.query("OD")))
