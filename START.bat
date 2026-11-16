@echo off
setlocal EnableExtensions
if not defined OCB_USERNAME set "OCB_USERNAME=ShanguanLinG"
pushd "%~dp0" || exit /b 1

set "TEMURIN_HOME=%USERPROFILE%\.jdks\temurin-25.0.4.1"
if exist "%TEMURIN_HOME%\bin\java.exe" set "JAVA_HOME=%TEMURIN_HOME%"
set "OCB_GAME_DIR=%~dp0run\mc"
set "PACKAGE_DIR=build\release\OpenCheatBreaker-1.8.9"
set "LAUNCHER=%PACKAGE_DIR%\START Hit Registration.bat"

if not exist "%PACKAGE_DIR%\runtime\java.args" goto build
if not exist "%PACKAGE_DIR%\versions\OpenCheatBreaker-1.8.9\OpenCheatBreaker-1.8.9.jar" goto build
if exist "%LAUNCHER%" goto launch

:build
call gradlew.bat releasePackage --console=plain
if errorlevel 1 goto build_failed

:launch
call "%LAUNCHER%"
set "LAUNCH_EXIT=%ERRORLEVEL%"
popd
exit /b %LAUNCH_EXIT%

:build_failed
popd
pause
exit /b 1
