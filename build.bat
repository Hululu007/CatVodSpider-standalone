@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title TVBox Spider Build
color 0A

set "JAR_NAME=spider.jar"

echo ==========================================
echo   TVBox Spider Build
echo ==========================================
echo.

if not defined JAVA_HOME call :find_java
if not defined JAVA_HOME goto no_java
if not exist "%JAVA_HOME%\bin\javac.exe" goto no_java

if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%" set "ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk"
if not exist "%ANDROID_HOME%" goto no_sdk

set "BT="
for /f "delims=" %%d in ('dir /b /ad /o-n "%ANDROID_HOME%\build-tools" 2^>nul') do (
  if not defined BT set "BT=%ANDROID_HOME%\build-tools\%%d"
)
set "PLAT="
for /f "delims=" %%d in ('dir /b /ad /o-n "%ANDROID_HOME%\platforms" 2^>nul') do (
  if not defined PLAT set "PLAT=%ANDROID_HOME%\platforms\%%d"
)
if not defined BT goto no_sdk
if not defined PLAT goto no_sdk

echo JDK : %JAVA_HOME%
echo SDK : %ANDROID_HOME%
echo.

echo [1/5] Compiling Java ...
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > sources.txt
"%JAVA_HOME%\bin\javac.exe" --release 8 -encoding UTF-8 -cp "%PLAT%\android.jar" -d out @sources.txt
if errorlevel 1 goto fail
del sources.txt >nul 2>nul

echo [2/5] Dexing ...
if exist dex rmdir /s /q dex
if exist classes.jar del classes.jar
mkdir dex
"%JAVA_HOME%\bin\jar.exe" cf classes.jar -C out .
call "%BT%\d8.bat" --release --min-api 21 --lib "%PLAT%\android.jar" --output dex classes.jar
if errorlevel 1 goto fail
del classes.jar >nul 2>nul

echo [3/5] Packing jar ...
if not exist dist mkdir dist
copy /y dex\classes.dex dist\classes.dex >nul
"%JAVA_HOME%\bin\jar.exe" cf dist\spider.jar -C dist classes.dex -C out .
del dist\classes.dex >nul 2>nul
if errorlevel 1 goto fail

echo [4/5] MD5 ...
for /f "delims=" %%h in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm MD5 'dist\spider.jar').Hash.ToLower()"') do set "MD5=%%h"
if not defined MD5 goto fail
> dist\spider.jar.md5 echo %MD5%

echo [5/5] Preparing upload files ...
copy /y dist\spider.jar "%JAR_NAME%" >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$m='%MD5%'; if(Test-Path 'tv.json'){ $p='tv.json'; $t=Get-Content -Raw -Encoding UTF8 $p; $t=[regex]::Replace($t,';md5;[a-fA-F0-9]{32}',';md5;'+$m); $t=$t -replace 'spider-v[0-9]+\.jar','%JAR_NAME%'; [IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($false))) } }"

echo.
echo ==========================================
echo  BUILD OK
echo ==========================================
echo  jar : %CD%\%JAR_NAME%
echo  md5 : %MD5%
echo  cfg : %CD%\tv.json  (md5 updated)
echo.
echo Upload these two files to Gitee root:
echo   1. %JAR_NAME%
echo   2. tv.json
echo.
echo URL: https://gitee.com/gongchuang666/tvbox/raw/master/tv.json
echo.
pause
exit /b 0

:find_java
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" goto :eof
for %%R in ("%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%LOCALAPPDATA%\Programs\Eclipse Adoptium" "%LOCALAPPDATA%\Programs\Microsoft") do (
  for /d %%D in ("%%~R\jdk*") do (
    if exist "%%~D\bin\javac.exe" set "JAVA_HOME=%%~D"
  )
)
if defined JAVA_HOME goto :eof
for /f "delims=" %%J in ('where javac 2^>nul') do (
  for %%H in ("%%~dpJ.") do set "JAVA_HOME=%%~dpH.."
)
goto :eof

:no_java
echo [ERROR] JDK not found. Install JDK 8+ or set JAVA_HOME.
goto fail

:no_sdk
echo [ERROR] Android SDK build-tools/platforms not found. Set ANDROID_HOME.
goto fail

:fail
echo.
echo [FAILED] Build did not finish. Send the error text to me.
pause
exit /b 1
