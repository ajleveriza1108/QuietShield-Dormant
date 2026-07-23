@echo off
setlocal EnableExtensions
cd /d "%~dp0"

git show-ref --verify --quiet refs/heads/main
if errorlevel 1 (
  git checkout -b main || goto failed
) else (
  git checkout main || goto failed
)

git add -A || goto failed
git diff --cached --quiet
if errorlevel 1 git commit -m "QuietShield Dormant Alpha 4: add on-device Wireless Debugging pairing and restore" || goto failed
git push -u origin main || goto failed

echo [PASS] QuietShield Dormant pushed to GitHub.
pause
exit /b 0

:failed
echo [FAILED] GitHub push failed. Review the message above.
pause
exit /b 1
