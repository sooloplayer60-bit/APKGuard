@echo off
setlocal
cd /d "%~dp0.."
set ROOT=%CD%
set OUT=%ROOT%\desktop\build
set DIST=%ROOT%\desktop\dist
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%OUT%\classes" || exit /b 1
mkdir "%OUT%\package" || exit /b 1
javac -encoding UTF-8 -d "%OUT%\classes" "%ROOT%\app\src\main\java\com\apkguard\app\Scanner.java" "%ROOT%\desktop\src\com\apkguard\app\desktop\Main.java"
if errorlevel 1 exit /b 1
jar --create --file "%OUT%\package\APKGuard.jar" --main-class com.apkguard.app.desktop.Main -C "%OUT%\classes" .
if errorlevel 1 exit /b 1
jpackage --type app-image --name APKGuard --input "%OUT%\package" --main-jar APKGuard.jar --main-class com.apkguard.app.desktop.Main --dest "%DIST%" --app-version 2.1.0 --vendor "APK Guard"
if errorlevel 1 exit /b 1
echo.
echo Build complete: %DIST%\APKGuard\APKGuard.exe
echo Copy the entire APKGuard folder, not only the EXE.
endlocal
