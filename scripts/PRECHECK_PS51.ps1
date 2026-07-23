param(
    [Parameter(Mandatory = $true)]
    [string]$ScriptPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 5) {
    Write-Host '[PARSER] Windows PowerShell 5.1 or newer is required.' -ForegroundColor Red
    exit 1
}

if (-not (Test-Path -LiteralPath $ScriptPath -PathType Leaf)) {
    Write-Host ("[PARSER] Script was not found: {0}" -f $ScriptPath) -ForegroundColor Red
    exit 1
}

$tokens = $null
$parseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile(
    $ScriptPath,
    [ref]$tokens,
    [ref]$parseErrors
)

if ($null -ne $parseErrors -and $parseErrors.Count -gt 0) {
    foreach ($parseError in $parseErrors) {
        Write-Host ("[PARSER] {0}" -f $parseError.Message) -ForegroundColor Red
    }
    exit 1
}

Write-Host '[PASS] Windows PowerShell 5.1 parser preflight passed.' -ForegroundColor Green
exit 0
