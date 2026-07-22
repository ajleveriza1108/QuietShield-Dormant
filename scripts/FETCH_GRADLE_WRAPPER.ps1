$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 5) {
    throw 'Windows PowerShell 5.1 or newer is required.'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar'
$url = 'https://github.com/gradle/gradle/raw/refs/tags/v9.4.1/gradle/wrapper/gradle-wrapper.jar'
$expected = '55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c'

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null

if (-not (Test-Path -LiteralPath $destination)) {
    Write-Host 'Downloading the official Gradle 9.4.1 wrapper JAR...'

    $previousProtocol = [System.Net.ServicePointManager]::SecurityProtocol
    try {
        [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
        $downloaded = $false

        for ($attempt = 1; $attempt -le 3; $attempt++) {
            try {
                Invoke-WebRequest -Uri $url -OutFile $destination -UseBasicParsing -TimeoutSec 60
                $downloaded = $true
                break
            }
            catch {
                Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
                if ($attempt -eq 3) { throw }
                Write-Host "[NOTICE] Wrapper download attempt $attempt failed; retrying..." -ForegroundColor Yellow
                Start-Sleep -Seconds 2
            }
        }

        if (-not $downloaded) {
            throw 'The Gradle wrapper JAR could not be downloaded.'
        }
    }
    finally {
        [System.Net.ServicePointManager]::SecurityProtocol = $previousProtocol
    }
}

$actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) {
    Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
    throw "Gradle wrapper checksum mismatch. Expected $expected but received $actual."
}

Write-Host '[PASS] Gradle wrapper JAR verified.'
