$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 5) {
    throw 'Windows PowerShell 5.1 or newer is required.'
}

function Resolve-Adb {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:ANDROID_SDK_ROOT) { $candidates.Add((Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')) }
    if ($env:ANDROID_HOME) { $candidates.Add((Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')) }
    if ($env:LOCALAPPDATA) { $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')) }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates.Insert(0, $command.Source) }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return $candidate
        }
    }
    throw 'adb.exe was not found. Install Android SDK Platform-Tools.'
}

function Invoke-AdbChecked {
    param(
        [string]$Adb,
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    & $Adb @Arguments
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

function Test-Engine {
    param(
        [string]$Adb,
        [string]$Token
    )

    $localPort = 47532
    & $Adb forward --remove "tcp:$localPort" 2>$null | Out-Null
    & $Adb forward "tcp:$localPort" 'tcp:47531' | Out-Null
    if ($LASTEXITCODE -ne 0) { return $false }

    $client = $null
    $reader = $null
    $writer = $null
    try {
        $client = New-Object -TypeName System.Net.Sockets.TcpClient
        $client.Connect('127.0.0.1', $localPort)
        $stream = $client.GetStream()
        $utf8NoBom = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
        $writer = New-Object -TypeName System.IO.StreamWriter -ArgumentList $stream, $utf8NoBom
        $utf8 = [System.Text.Encoding]::UTF8
        $reader = New-Object -TypeName System.IO.StreamReader -ArgumentList $stream, $utf8
        $writer.NewLine = "`n"
        $writer.AutoFlush = $true
        $writer.WriteLine($Token)
        $writer.WriteLine('PING')
        return $reader.ReadLine() -eq 'OK PONG'
    }
    catch {
        return $false
    }
    finally {
        if ($reader) { $reader.Dispose() }
        if ($writer) { $writer.Dispose() }
        if ($client) { $client.Dispose() }
        & $Adb forward --remove "tcp:$localPort" 2>$null | Out-Null
    }
}

function Start-EngineAttempt {
    param(
        [string]$Adb,
        [string]$Command,
        [string]$Token
    )

    & $Adb shell $Command | Out-Null
    Start-Sleep -Seconds 2
    return Test-Engine -Adb $Adb -Token $Token
}

try {
    Write-Host '============================================================'
    Write-Host 'QuietShield Dormant Automatic Closing Setup'
    Write-Host 'USB test activation'
    Write-Host '============================================================'

    $adb = Resolve-Adb
    Write-Host "[PASS] adb: $adb"

    $deviceLines = & $adb devices
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read connected Android devices.' }
    $devices = @($deviceLines | Where-Object { $_ -match "`tdevice$" })
    if ($devices.Count -ne 1) {
        throw "Connect exactly one unlocked Android phone with USB debugging allowed. Found $($devices.Count)."
    }
    Write-Host '[PASS] One Android phone is connected.'

    $packageName = 'com.ajcoder.quietshield.dormant.debug'
    $pathOutput = & $adb shell pm path $packageName
    if ($LASTEXITCODE -ne 0 -or -not $pathOutput) {
        throw 'QuietShield Dormant Alpha 3 debug APK is not installed. Run 02_INSTALL_DEBUG_TO_PHONE.bat first.'
    }
    $apkPath = (($pathOutput | Select-Object -First 1) -replace '^package:', '').Trim()
    if (-not $apkPath.EndsWith('.apk')) { throw 'The installed APK path could not be read.' }
    Write-Host "[PASS] Installed app: $packageName"

    $token = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $temporaryToken = Join-Path $env:TEMP ("qsd-engine-token-{0}.txt" -f [Guid]::NewGuid().ToString('N'))
    $utf8NoBom = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
    [System.IO.File]::WriteAllText($temporaryToken, $token, $utf8NoBom)

    try {
        Invoke-AdbChecked -Adb $adb -Arguments @('push', $temporaryToken, '/data/local/tmp/qsd_engine_token') -FailureMessage 'Unable to copy the private setup key to the phone.'
        Invoke-AdbChecked -Adb $adb -Arguments @('shell', 'chmod', '644', '/data/local/tmp/qsd_engine_token') -FailureMessage 'Unable to prepare the private setup key.'
        Invoke-AdbChecked -Adb $adb -Arguments @('shell', 'run-as', $packageName, 'mkdir', '-p', 'files') -FailureMessage 'The installed app is not a debug build. Install the Alpha 3 debug APK.'
        Invoke-AdbChecked -Adb $adb -Arguments @('shell', 'run-as', $packageName, 'cp', '/data/local/tmp/qsd_engine_token', 'files/engine_token') -FailureMessage 'Unable to store the private setup key in QuietShield Dormant.'
        & $adb shell rm -f /data/local/tmp/qsd_engine_token | Out-Null
    }
    finally {
        Remove-Item -LiteralPath $temporaryToken -Force -ErrorAction SilentlyContinue
    }

    & $adb shell "pkill -f com.ajcoder.quietshield.dormant.engine.DormantShellMain >/dev/null 2>&1 || true" | Out-Null
    & $adb shell rm -f /data/local/tmp/qsd_engine.log | Out-Null

    $baseCommand = "CLASSPATH=$apkPath app_process /system/bin com.ajcoder.quietshield.dormant.engine.DormantShellMain --port 47531 --token $token"
    $started = Start-EngineAttempt -Adb $adb -Token $token -Command "nohup $baseCommand >/data/local/tmp/qsd_engine.log 2>&1 </dev/null &"
    if (-not $started) {
        $started = Start-EngineAttempt -Adb $adb -Token $token -Command "setsid $baseCommand >/data/local/tmp/qsd_engine.log 2>&1 </dev/null &"
    }
    if (-not $started) {
        $started = Start-EngineAttempt -Adb $adb -Token $token -Command "$baseCommand >/data/local/tmp/qsd_engine.log 2>&1 </dev/null &"
    }

    if (-not $started) {
        $engineLog = & $adb shell cat /data/local/tmp/qsd_engine.log 2>$null
        if ($engineLog) {
            Write-Host 'Phone setup log:' -ForegroundColor Yellow
            $engineLog | ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
        }
        throw 'The automatic closing helper did not start on this phone.'
    }

    Write-Host '[PASS] Automatic closing helper responded correctly.' -ForegroundColor Green

    Invoke-AdbChecked -Adb $adb -Arguments @(
        'shell', 'am', 'start',
        '-n', "$packageName/com.ajcoder.quietshield.dormant.MainActivity",
        '--ez', 'start_automatic_closing', 'true'
    ) -FailureMessage 'The helper started, but QuietShield Dormant could not be opened.'

    Write-Host ''
    Write-Host 'AUTOMATIC CLOSING HELPER IS READY' -ForegroundColor Green
    Write-Host 'Finish the two setup cards in the app. The main switch will turn on when both are allowed.' -ForegroundColor Cyan
    Write-Host 'Keep USB debugging enabled during this Alpha test. Turning it off or restarting the phone may stop the helper.' -ForegroundColor Yellow
    Write-Host 'Before using a banking app, run 05_STOP_AUTOMATIC_CLOSING.bat, then turn off USB debugging and Developer Options.' -ForegroundColor Yellow
    exit 0
}
catch {
    Write-Host "`n[FAILED] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
