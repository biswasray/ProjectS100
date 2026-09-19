@echo off
rem Wrapper: run bkerler edl.py with the Python 3.12 install (not on PATH yet)
rem -u = unbuffered stdout/stderr so progress is visible live (not buffered to exit)
set PYTHONUNBUFFERED=1
set PYTHONIOENCODING=utf-8
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" -u "%~dp0edl\edl.py" %*
