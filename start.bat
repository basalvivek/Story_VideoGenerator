@echo off
setlocal
cd /d "%~dp0"
set PORT=1011

echo [1/3] Killing old sessions on port %PORT%...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do (
    echo   killing PID %%P
    taskkill /F /T /PID %%P >nul 2>&1
)

echo [2/3] Starting database (docker)...
docker compose up -d moviedb
if errorlevel 1 (
    echo   WARNING: docker compose failed. Assuming DB already running on port 5434.
)

if not defined GEMINI_API_KEY (
    echo NOTE: GEMINI_API_KEY not set - using offline cartoon scenes. Set it for realistic AI scenes:
    echo       setx GEMINI_API_KEY your-key   ^(then open a new terminal^)
)

echo [3/3] Starting app on http://localhost:%PORT% ...
call mvn spring-boot:run
endlocal
