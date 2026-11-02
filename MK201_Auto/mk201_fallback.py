"""Прибор пропал во время прогона — не останавливаем прогон (29.10.2026).

SafeDev оборачивает реле / CA71 / блок питания / генератор: если команда прибору не прошла, оператор выбирает
«Повторить» или «Дальше вручную». Во втором случае эта и все следующие команды идут «вручную» — через подсказки
(NoRelay, ManualCal, ManualPSU, ManualGen), а для калибратора объект становится «ложным», и сценарий сам
переходит на свои подсказки (if self.cal: …)."""


class ManualCal:
    """Калибратор CA71 недоступен: ток ставит и показания вводит оператор."""
    def __init__(self, ui):
        self.ui = ui

    def set_source(self, ma, settle=2.0):
        if abs(ma) < 0.05:
            text = "Выключите выход калибратора (0 мА, OFF) и нажмите «Готово»"
        else:
            text = f"Установите на калибраторе {ma:g} мА (SOURCE) и нажмите «Готово»"
        if not self.ui.confirm(text):
            raise RuntimeError("Остановлено оператором")

    def source_off(self):
        pass

    def measure_ma(self, n=3, interval=1.1):
        v = self.ui.ask_float("Калибратор не отвечает: введите его показание (MEASURE), мА")
        if v is None:
            raise RuntimeError("показание калибратора не введено")
        return float(v)

    def init(self):
        pass

    def close(self):
        pass


class SafeDev:
    NOOP = ("close", "ping", "init", "off", "source_off", "output", "stop_continuous")

    def __init__(self, dev, name, ui, manual, falsy_when_manual=False):
        self._dev, self._name, self._ui, self._manual = dev, name, ui, manual
        self._is_manual = False
        self._falsy = falsy_when_manual

    def __bool__(self):
        return not (self._is_manual and self._falsy)

    @property
    def is_manual(self):
        return self._is_manual

    def _go_manual(self, why):
        self._is_manual = True
        try:
            self._ui.log(f"!!! {self._name}: {why} — дальше вручную, по подсказкам", "warn")
        except TypeError:
            self._ui.log(f"!!! {self._name}: {why} — дальше вручную, по подсказкам")

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        if self._is_manual:
            if hasattr(self._manual, name):
                return getattr(self._manual, name)
            if name in self.NOOP:
                return lambda *a, **k: None
            raise AttributeError(name)
        attr = getattr(self._dev, name)          # нет у прибора -> AttributeError, как у самого прибора
        if not callable(attr):
            return attr

        def call(*a, **k):
            while True:
                try:
                    return attr(*a, **k)
                except Exception as e:
                    if getattr(self._ui, "stopped", False):
                        raise
                    if type(e).__name__ in ("Abort", "StepSkip"):
                        raise
                    text = (f"{self._name} не отвечает:\n{type(e).__name__}: {e}\n\n"
                            "Проверьте кабель USB и питание прибора и нажмите «Повторить» "
                            "или продолжайте без него («Дальше вручную»).")
                    if self._ui.ask(text, "Повторить", "Дальше вручную"):
                        continue
                    self._go_manual(str(e))
                    m = getattr(self._manual, name, None)
                    if m is None:
                        if name in self.NOOP:
                            return None
                        raise RuntimeError(f"{self._name} отключён — действие «{name}» вручную не выполняется")
                    return m(*a, **k)
        return call
