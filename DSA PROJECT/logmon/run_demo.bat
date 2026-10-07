@echo off
rem Usage: run_demo.bat data\test2_critical_burst.log
cd /d "%~dp0"
dir /s /b src\*.java > sources.txt
if not exist out\classes mkdir out\classes
javac -d out\classes @sources.txt
if "%~1"=="" (java -cp out\classes logmon.Main) else (java -cp out\classes logmon.Main %1)
