@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

git show-ref --verify --quiet refs/heads/main
if errorlevel 1 (
  git checkout -b main || goto failed
) else (
  git checkout main || goto failed
)

git add -A || goto failed
git diff --cached --quiet
if errorlevel 1 git commit -m "QuietShield Dormant v0.2.0 Beta 1 R3: automatic wireless pairing discovery" || goto failed
git push -u origin main || goto failed

echo [PASS] QuietShield Dormant Beta 1 R3 pushed to GitHub.
pause
exit /b 0

:failed
echo [FAILED] GitHub push failed. The validated local source remains available.
pause
exit /b 1
