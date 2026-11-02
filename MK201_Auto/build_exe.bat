@echo off
rem Сборка MK201 Auto (Windows). Запускать из папки с mk201_auto.py.
rem 02.11.2026: сборка ПАПКОЙ (--onedir), а не одним файлом. Один файл при каждом запуске распаковывается
rem во временную папку %TEMP%\_MEI...; без прав администратора (или когда антивирус это запрещает)
rem выходило "Could not create temporary directory!". Программе-папке временный каталог не нужен.
cd /d "%~dp0"

set PY=py
where py >nul 2>nul || set PY=python

echo === 1/3 Установка библиотек (pyinstaller, pyserial, openpyxl, pyvisa) ===
%PY% -m pip install --upgrade pyinstaller pyserial openpyxl pyvisa
if errorlevel 1 goto err

echo.
echo === 2/3 Сборка MK201_Auto.exe ===
rem 29.10.2026: модули переименованы в mk201_*.py (workflow.py совпадал с чужим пакетом и ломал PyInstaller).
rem Старые файлы с прежними именами удаляем, чтобы не путались.
for %%f in (workflow settings stats labels ports relay firmware instruments simulator fallback) do if exist %%f.py del /q %%f.py
if exist __pycache__ rmdir /s /q __pycache__
if exist build rmdir /s /q build
rem 02.11.2026: номер версии берётся из mk201_version.py и вшивается в свойства exe
for /f "delims=" %%v in ('%PY% mk201_version.py version_info.txt') do set VER=%%v
echo Версия: %VER%
%PY% -m PyInstaller --noconfirm --clean --onedir --windowed --name MK201_Auto --distpath dist ^
  --version-file version_info.txt ^
  --collect-submodules pyvisa ^
  --hidden-import serial.tools.list_ports ^
  --hidden-import openpyxl ^
  --exclude-module mk201_sniffer --exclude-module ca71_selftest ^
  mk201_auto.py
if errorlevel 1 goto err

echo.
echo === 3/3 Папка программы: %CD%\MK201_Auto_app ===
rem Готовая программа - папка MK201_Auto_app (MK201_Auto.exe + _internal). Настройки mk201_auto.json и журналы
rem лежат рядом с exe; при первой сборке настройки копируются из этой папки.
if not exist MK201_Auto_app mkdir MK201_Auto_app
robocopy "dist\MK201_Auto" MK201_Auto_app /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto err
if exist mk201_auto.json if not exist MK201_Auto_app\mk201_auto.json copy /y mk201_auto.json MK201_Auto_app\ >nul
if exist mk201_fw_lock.json if not exist MK201_Auto_app\mk201_fw_lock.json copy /y mk201_fw_lock.json MK201_Auto_app\ >nul
rem старый однофайловый exe больше не нужен - запускайте MK201_Auto_app\MK201_Auto.exe
if exist MK201_Auto.exe del /q MK201_Auto.exe
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
if exist MK201_Auto.spec del /q MK201_Auto.spec
if exist version_info.txt del /q version_info.txt
echo MK201 Auto %VER%> MK201_Auto_app\VERSION.txt

echo.
echo Готово: MK201 Auto %VER%
echo %CD%\MK201_Auto_app\MK201_Auto.exe
echo Запускать можно без администратора. Ярлык на рабочий стол - на этот exe.
echo На другой ПК переносить всю папку MK201_Auto_app целиком.
pause
exit /b 0

:err
echo.
echo !!! ОШИБКА сборки, смотрите сообщения выше
pause
exit /b 1
