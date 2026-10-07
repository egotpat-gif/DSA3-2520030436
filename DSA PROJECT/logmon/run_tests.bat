@echo off
cd /d "%~dp0"
dir /s /b src\*.java > sources.txt
if not exist out\classes mkdir out\classes
javac -d out\classes @sources.txt
java -cp out\classes logmon.TestRunner
