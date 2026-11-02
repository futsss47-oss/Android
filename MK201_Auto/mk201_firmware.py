"""Заливка ПО в MK201 — точно по алгоритму конфигуратора (вкладка «Обновление ПО», файл .hex):
hex -> bin (как arm-none-eabi-objcopy -I ihex -O binary), CRC16 Modbus, блоки по 250 байт,
функции 0x51 (инфо), 0x52 (активация), 0x50 (сегмент), 0x53 (старт)."""
import time
from mk201_proto import crc16, ModbusError

SEG = 250


def hex_to_bin(path):
    text = open(path, "r", errors="ignore").read()
    i1, i2 = text.find(":02000004D"), text.find(":04000005")      # как в конфигураторе: вырезается область 0xD000…
    if i1 != -1 and i2 != -1:
        text = text[:i1] + text[i2:]
    mem, base = {}, 0
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith(":"):
            continue
        raw = bytes.fromhex(line[1:])
        if sum(raw) & 0xFF:
            raise ValueError(f"Ошибка контрольной суммы в hex-файле: {line[:20]}…")
        n, off, typ = raw[0], (raw[1] << 8) | raw[2], raw[3]
        data = raw[4:4 + n]
        if typ == 0:
            for k, b in enumerate(data):
                mem[base + off + k] = b
        elif typ == 4:
            base = ((data[0] << 8) | data[1]) << 16
        elif typ == 2:
            base = ((data[0] << 8) | data[1]) << 4
        elif typ == 1:
            break
    if not mem:
        raise ValueError("В hex-файле нет данных")
    lo, hi = min(mem), max(mem)
    out = bytearray(hi - lo + 1)                                    # пропуски = 0x00, как у objcopy
    for a, b in mem.items():
        out[a - lo] = b
    return bytes(out)


def split_bin_by_250(b):
    res, start, end = [[0, bytes(b[0:SEG])]], 0, SEG
    i = 1
    while end <= len(b):
        start += SEG
        end += SEG
        sec = bytes(b[start:end])
        if len(sec) == SEG:
            res.append([i, sec])
        else:
            res.append([i, sec + bytes([255]) * (SEG - len(sec))])
            break
        i += 1
    return res


class FirmwareLockError(ModbusError):
    """После заливки основная программа платы так и не ответила — блокировку не снимать."""


def app_alive(dev):
    """True, если отвечает ОСНОВНАЯ программа платы. Загрузчик на чтение Settings-регистров (101)
    не отвечает (видно в логе 15.10.2026 15:49:14), а основная программа отвечает."""
    try:
        dev.read_set(1, 16)
        return True
    except ModbusError:
        return False


def upload_app(dev, hex_path, log, progress=None, stop=None, force=False, countdown=None, lock=None,
               settle=3, boot_timeout=180):
    """Загрузка программы пользователя (empty_project.hex) через загрузчик платы — как кнопка
    «Загрузить» в конфигураторе. Возвращает 'same' (уже загружена) или 'done'.

    Защита от «кирпича» (15.10.2026 плата не стартовала после заливки):
      * если в плате уже такая же программа (длина + CRC) — ничего не пишем;
      * если плата не ответила на запрос информации — не начинаем;
      * lock(True) вызывается ДО активации загрузчика; lock(False) — только когда основная
        программа платы снова отвечает и CRC совпал. Пока блокировка стоит, программа MK201 Auto
        не даёт ничего делать с платой;
      * после команды «старт» settle секунд ничего не шлём, затем каждые 2 с спрашиваем плату и
        заканчиваем СРАЗУ, как только ответила основная программа (значит загрузчик закончил).
        Обрывать само ожидание нельзя: в это время загрузчик переписывает флеш.
    """
    chk = stop or (lambda: None)
    lock = lock or (lambda state: None)
    img = hex_to_bin(hex_path)
    total_len, total_crc = len(img), crc16(img)
    segs = split_bin_by_250(img)
    log(f"Файл ПО: {total_len} байт, CRC16 {total_crc:04X}, блоков {len(segs)}")
    length, crc = dev.app_info()                       # нет ответа -> исключение, заливку не начинаем
    log(f"В плате сейчас: {length} байт, CRC16 {crc:04X}")
    if not force and (length, crc) == (total_len, total_crc):
        log("Такая программа уже загружена — прошивка не требуется (OK)")
        return "same"

    chk()
    lock(True)                                         # с этого момента плата считается «в загрузке»
    log("Блокировка включена: до перезапуска платы другие действия запрещены")
    log(f"Активация загрузчика (1): код {dev.app_activate(1)}")
    time.sleep(0.5)                                    # как в конфигураторе
    log(f"Активация загрузчика (2): код {dev.app_activate(1)}")

    for k, (num, data) in enumerate(segs):
        chk()
        err = dev.app_write_segment(num, data)
        if err != 0:
            raise FirmwareLockError(f"Блок {num}: плата вернула код ошибки {err}")
        if progress:
            progress((k + 1) * 100 // len(segs))

    log("Все блоки записаны, команда «старт программирования»…")
    dev.set_timeout(4.0)
    try:
        code = dev.app_start(total_len, total_crc)
    finally:
        dev.set_timeout(getattr(dev, "base_timeout", 1.0))
    if code != 1:
        raise FirmwareLockError(f"Программирование не подтверждено платой (код {code}, ожидался 1)")

    log("Плата переписывает флеш и перезапускается. НЕ ОТКЛЮЧАЙТЕ ПИТАНИЕ")
    time.sleep(settle)
    log("Жду ответа основной программы платы (закончу сразу, как ответит)…")
    t1 = time.time()
    while time.time() - t1 < boot_timeout:
        if countdown:
            countdown(int(boot_timeout - (time.time() - t1)))
        if app_alive(dev):
            got = dev.app_info()
            if countdown:
                countdown(None)
            if got != (total_len, total_crc):
                raise FirmwareLockError(f"Плата запустилась, но в ней {got[0]} байт, CRC {got[1]:04X} — "
                                        "не совпало с файлом")
            log(f"Плата перезапустилась, основная программа отвечает. ПО: {got[0]} байт, CRC16 {got[1]:04X} (OK)")
            lock(False)
            log("Блокировка снята")
            return "done"
        time.sleep(2.0)
    if countdown:
        countdown(None)
    raise FirmwareLockError("Основная программа платы не ответила за 3 минуты. Блокировка остаётся. "
                            "Питание НЕ отключать; нажмите «Проверить плату» позже. Если плата так и "
                            "останется в загрузчике — залить ПО заново конфигуратором.")


# Перезагрузка платы командой 0x55 — так делает конфигуратор («Устройство успешно перезагружено!»):
# код 1 с ключом 1685766474 возвращает код сброса, код 2 с ключом 299914474 и этим кодом перезагружает.
# Это команда ОСНОВНОЙ программы; отвечает ли на неё загрузчик — не проверено.
RESET_KEY_GET, RESET_KEY_DO = 1685766474, 299914474


def reboot(dev, log):
    code, ret = dev.exec_cmd(1, RESET_KEY_GET, 0)
    log(f"Команда «получить код сброса»: ответ {ret}")
    code, ret2 = dev.exec_cmd(2, RESET_KEY_DO, ret)
    if ret2 != 1:
        raise ModbusError(f"Плата отказала в перезагрузке (ответ {ret2})")
    log("Плата приняла команду перезагрузки")
