@echo off
rem Сборка MK201 Auto в один exe (Windows). Запускать из папки с mk201_auto.py.
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
%PY% -m PyInstaller --noconfirm --clean --onefile --windowed --name MK201_Auto ^
  --collect-submodules pyvisa ^
  --hidden-import serial.tools.list_ports ^
  --hidden-import openpyxl ^
  --exclude-module mk201_sniffer --exclude-module ca71_selftest ^
  mk201_auto.py
if errorlevel 1 goto err

echo.
echo === 3/3 Копирую exe в эту папку ===
rem exe кладём рядом с mk201_auto.json: настройки, журналы (logs) и операторы останутся те же
copy /y "dist\MK201_Auto.exe" "MK201_Auto.exe" >nul
if errorlevel 1 goto err
if exist build rmdir /s /q build
if exist MK201_Auto.spec del /q MK201_Auto.spec

echo.
echo Готово: %CD%\MK201_Auto.exe
echo (копия лежит в папке dist)
pause
exit /b 0

:err
echo.
echo !!! ОШИБКА сборки, смотрите сообщения выше
pause
exit /b 1
