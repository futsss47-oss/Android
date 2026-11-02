"""Управление блоком реле на ESP32 по USB-serial. Протокол — текстовые строки, ответ «OK».
Сторожевой таймер: ESP32 размыкает все реле, если ~5 с не было ни одной команды. Пока порт открыт,
программа раз в секунду шлёт PING, поэтому реле держатся только пока программа жива."""
import serial, threading, time

RELAY_ID = "MK201-RELAY"


class Relay:
    def __init__(self, port, baud=115200, settle=0.5, ping=1.0, log=None, strict=False):
        self.port, self.baud, self.settle = port, baud, settle
        self.strict = strict                     # False: порт выбран оператором — не отвечает на ID? -> всё равно работаем
        self.recognized = False
        self.log = log or (lambda s: None)
        self.lock = threading.RLock()
        self.ser = None
        self._open()
        self.alive = True
        threading.Thread(target=self._keepalive, args=(ping,), daemon=True).start()

    def _open(self):
        """Открыть порт и проверить, что там блок реле. ESP32 перезагружается при открытии порта."""
        if self.ser:
            try: self.ser.close()
            except Exception: pass
        self.ser = serial.Serial(self.port, self.baud, timeout=1.0)
        time.sleep(1.6)
        self.ser.reset_input_buffer()
        ident = ""
        for _ in range(3):                       # ESP32 после сброса может стартовать дольше 1,6 с
            ident = self._io("ID?")
            if ident == RELAY_ID:
                break
            time.sleep(0.5)
        self.recognized = ident == RELAY_ID
        if not self.recognized:
            if self.strict:
                self.ser.close()
                raise RuntimeError(f"На {self.port} не блок реле MK201 (ответ «{ident}»). Залейте esp32_relay.ino")
            self.log(f"Блок реле: на {self.port} ответ на ID? «{ident}» вместо «{RELAY_ID}» — порт выбран вручную, "
                     "команды всё равно отправляю (если реле не щёлкают — залейте esp32_relay.ino)")

    def _io(self, line):
        self.ser.reset_input_buffer()
        self.ser.write((line + "\n").encode())
        return self.ser.readline().decode(errors="replace").strip()

    def reconnect(self, wait=10.0):
        """Порт «пропал» (USB-переподключение ESP32, ошибка WriteFile / Отказано в доступе):
        закрыть и открыть заново, пока Windows не вернёт порт (до wait секунд)."""
        t0, last = time.time(), None
        while time.time() - t0 < wait:
            try:
                self._open()
                self.log(f"Блок реле: связь по {self.port} восстановлена")
                return
            except Exception as e:
                last = e
                time.sleep(1.0)
        raise RuntimeError(f"Блок реле на {self.port} не отвечает и не переподключился: {last}. "
                           f"Проверьте USB-кабель ESP32 и что порт не занят другой программой (Arduino IDE)")

    def _raw(self, line):
        with self.lock:
            try:
                return self._io(line)
            except (serial.SerialException, OSError) as e:
                self.log(f"Блок реле: ошибка порта ({e}) — переподключаюсь…")
                self.reconnect()
                return self._io(line)

    def _keepalive(self, period):
        while self.alive:
            time.sleep(period)
            try:
                if self.alive and self.lock.acquire(timeout=0.1):
                    try: self._io("PING")      # без переподключения: этим займётся следующая команда
                    finally: self.lock.release()
            except Exception:
                pass

    def _cmd(self, line):
        ans = self._raw(line)
        if ans != "OK":
            if not self.recognized:              # порт выбран вручную, ответ не наш — не останавливаемся
                self.log(f"Блок реле: на «{line}» ответ «{ans}» (ожидалось OK)")
            else:
                raise RuntimeError(f"Блок реле ответил '{ans}' на '{line}'")
        time.sleep(self.settle)

    def ai(self, ch):  self._cmd(f"AI {ch + 1}")     # канал 0..7 -> реле 1..8
    def ao(self, ch):  self._cmd(f"AO {ch + 1}")     # канал 0..1
    def off(self):     self._cmd("OFF")

    def ping(self):
        """Проверка связи перед прогоном (с переподключением при необходимости)."""
        return self._raw("PING")

    def close(self):
        self.alive = False
        try:
            with self.lock:
                self._io("OFF")
        except Exception:
            pass
        finally:
            try: self.ser.close()
            except Exception: pass


class NoRelay:
    """Ручной режим: оператор переключает провода сам."""
    def __init__(self, prompt): self.prompt = prompt
    def ai(self, ch):  self.prompt(f"Подключите калибратор (SOURCE) к AI{ch} и нажмите «Готово»")
    def ao(self, ch):  self.prompt(f"Подключите калибратор (MEASURE) к AO{ch} и нажмите «Готово»")
    def off(self):     pass
    def close(self):   pass
