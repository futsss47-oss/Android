@echo off
rem Build MIB_Stand.exe (Windows, Python 3.9+)
cd /d "%~dp0"

set PY=python
python --version >nul 2>&1
if errorlevel 1 set PY=py
%PY% --version
if errorlevel 1 (
  echo Python not found in PATH
  goto fail
)

%PY% -m pip install --upgrade pyinstaller pyserial
if errorlevel 1 goto fail

%PY% -m PyInstaller --noconfirm --clean --onefile --noconsole --name MIB_Stand ^
  --hidden-import mk531 --hidden-import ca71 --hidden-import simulation --hidden-import stand ^
  stand_gui.py
if errorlevel 1 goto fail

echo.
echo DONE: dist\MIB_Stand.exe
echo Put next to the exe (optional): protocol template SHABLON*.xlsx
pause
exit /b 0

:fail
echo.
echo BUILD FAILED, see messages above
pause
exit /b 1
