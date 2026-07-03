$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "package-apk.ps1"
$batchPath = Join-Path $PSScriptRoot "package-apk.bat"
if (-not (Test-Path -LiteralPath $scriptPath)) {
    throw "Missing package-apk.ps1"
}

if (-not (Test-Path -LiteralPath $batchPath)) {
    throw "Missing package-apk.bat"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $scriptPath,
    [ref]$tokens,
    [ref]$parseErrors
) | Out-Null

if ($parseErrors.Count -gt 0) {
    $messages = $parseErrors | ForEach-Object { $_.Message }
    throw "package-apk.ps1 has parse errors: $($messages -join '; ')"
}

$content = Get-Content -LiteralPath $scriptPath -Raw
$expectedFragments = @(
    "param(",
    "ValidateSet",
    "debug",
    "release",
    "keystore.jks",
    "SIGNING_STORE_PASSWORD",
    "SIGNING_KEY_ALIAS",
    "SIGNING_KEY_PASSWORD",
    "assemble"
)

foreach ($fragment in $expectedFragments) {
    if ($content -notmatch [regex]::Escape($fragment)) {
        throw "Expected package-apk.ps1 to contain '$fragment'"
    }
}

$batchContent = Get-Content -LiteralPath $batchPath -Raw
foreach ($fragment in @("powershell", "ExecutionPolicy", "package-apk.ps1")) {
    if ($batchContent -notmatch [regex]::Escape($fragment)) {
        throw "Expected package-apk.bat to contain '$fragment'"
    }
}

Write-Host "package-apk.ps1 verification passed."
