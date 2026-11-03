"""Версия MK201 Auto — ОДНО место, где она меняется.

Версия 1.N: N — сквозной номер, как у архивов (MK201_Auto_20.zip = 1.20, _21 = 1.21, …).
Новая выдача программы -> MINOR +1, дата, строка в HISTORY.
Номер видно: в шапке и заголовке окна, в журнале (logs), в протоколе (лист «Данные»), в журнале плат (.csv)
и в свойствах MK201_Auto.exe (правый клик -> Свойства -> Подробно).
"""
import sys

MAJOR, MINOR = 1, 28
NUM = f"{MAJOR}.{MINOR}"                     # например «1.28»
DATE = "03.11.2026"
VERSION = f"v{NUM} от {DATE}"

HISTORY = [
    ("1.28", DATE, "build_exe.bat сам создаёт ярлык на рабочем столе (exe работает только рядом с _internal)"),
    ("1.27", "02.11.2026", "Номер версии в шапке, протоколе, журналах и свойствах exe"),
    ("1.26", "02.11.2026", "CA71 и переходник RS-485 на двух портах MOXA не путаются"),
    ("1.25", "02.11.2026", "Service Port новой платы находится сам; переходник MOXA для RTU"),
    ("1.24", "02.11.2026", "Связь с платой по Modbus RTU и TCP; проверка Ethernet и RS-485 1–3; окна по центру; "
               "повтор не стирает прошедшее"),
    ("1.23", "02.11.2026", "Сборка exe папкой — запуск без администратора"),
    ("1.22", "02.11.2026", "Повтор AI/AO только по плохим каналам; переоткрытие приборов после USB; дата/время с ожиданием"),
    ("1.21", "02.11.2026", "Крестик после перемера, проверенная запись даты/времени, DI/DO с 1, подключения, новый вид, "
               "результаты на одной странице"),
    ("1.20", "02.11.2026", "Версия, присланная в архиве MK201_Auto_20.zip"),
]


def pyinstaller_version_file(path):
    """Файл свойств exe для PyInstaller (--version-file): «Версия файла» = 1.N.0.0"""
    text = f"""VSVersionInfo(
  ffi=FixedFileInfo(filevers=({MAJOR}, {MINOR}, 0, 0), prodvers=({MAJOR}, {MINOR}, 0, 0), mask=0x3f, flags=0x0, OS=0x40004,
                    fileType=0x1, subtype=0x0, date=(0, 0)),
  kids=[StringFileInfo([StringTable('041904B0', [
      StringStruct('ProductName', 'MK201 Auto'),
      StringStruct('FileDescription', 'MK201 Auto — проверка и калибровка MK201'),
      StringStruct('FileVersion', '{NUM}.0.0'),
      StringStruct('ProductVersion', '{VERSION}'),
      StringStruct('OriginalFilename', 'MK201_Auto.exe')])]),
        VarFileInfo([VarStruct('Translation', [0x0419, 1200])])])
"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


if __name__ == "__main__":
    # build_exe.bat: «py mk201_version.py version_info.txt» — файл свойств exe и номер на экран
    if len(sys.argv) > 1:
        pyinstaller_version_file(sys.argv[1])
    print(f"v{NUM} ({DATE})")              # без кириллицы: консоль батника в другой кодировке
