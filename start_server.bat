@echo off
setlocal

rem Usage: start_server.bat [sandbox|live]   (default: sandbox)
cd /d "%~dp0"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=sandbox"

where java >nul 2>&1
if errorlevel 1 (
    echo Java was not found. Install Java 25 or later and add it to PATH.
    pause
    exit /b 1
)

where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven was not found. Install Maven 3.6.3 or later and add it to PATH.
    pause
    exit /b 1
)

rem Pick up user-level variables set after this shell (or its parent) started.
for %%V in (TRADIER_ACCOUNT_ID TRADIER_ACCESS_TOKEN TRADIER_LIVE_ACCOUNT_ID TRADIER_LIVE_ACCESS_TOKEN) do (
    for /f "tokens=2,*" %%A in ('reg query "HKCU\Environment" /v %%V 2^>nul ^| findstr /i "%%V"') do set "%%V=%%B"
)

if /i "%MODE%"=="sandbox" (
    set "TRADIER_ENVIRONMENT=SANDBOX"
    echo Starting in SANDBOX mode ^(paper trading^).
) else if /i "%MODE%"=="live" (
    if "%TRADIER_LIVE_ACCESS_TOKEN%"=="" (
        echo TRADIER_LIVE_ACCESS_TOKEN is not set.
        pause
        exit /b 1
    )
    if "%TRADIER_LIVE_ACCOUNT_ID%"=="" (
        echo TRADIER_LIVE_ACCOUNT_ID is not set. Set it to your live Tradier account number.
        pause
        exit /b 1
    )
    echo *** LIVE MODE: orders will be sent to your real Tradier account. ***
    set /p "CONFIRM=Type LIVE to continue: "
    if not "%CONFIRM%"=="LIVE" (
        echo Cancelled.
        exit /b 1
    )
    set "TRADIER_ENVIRONMENT=LIVE"
) else (
    echo Unknown mode "%MODE%". Use sandbox or live.
    exit /b 1
)

call mvn spring-boot:run
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo The server exited with code %EXIT_CODE%.
    pause
)

exit /b %EXIT_CODE%
