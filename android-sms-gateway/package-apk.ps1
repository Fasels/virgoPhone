param(
    [ValidateSet("debug", "debugInsecure", "insecure", "release")]
    [string]$Variant = "release",

    [string]$KeystorePath,

    [string]$KeyAlias = "smsgateway",

    [switch]$Clean,

    [switch]$NoCreateKeystore
)

$ErrorActionPreference = "Stop"

function Convert-SecureStringToPlainText {
    param(
        [Parameter(Mandatory = $true)]
        [securestring]$SecureString
    )

    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

function Read-PlainPassword {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt
    )

    $secure = Read-Host $Prompt -AsSecureString
    $plain = Convert-SecureStringToPlainText $secure
    if ([string]::IsNullOrWhiteSpace($plain)) {
        throw "Password cannot be empty."
    }

    $plain
}

function Read-ValueWithDefault {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,

        [Parameter(Mandatory = $true)]
        [string]$DefaultValue
    )

    $value = Read-Host "$Prompt [$DefaultValue]"
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }

    $value.Trim()
}

function Read-SecretEnvValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Prompt
    )

    $existing = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrEmpty($existing)) {
        return $existing
    }

    Read-PlainPassword $Prompt
}

function Resolve-Keytool {
    $command = Get-Command "keytool" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "keytool was not found. Install a JDK or add keytool to PATH."
}

function New-ReleaseKeystore {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Alias,

        [Parameter(Mandatory = $true)]
        [string]$StorePassword,

        [Parameter(Mandatory = $true)]
        [string]$KeyPassword
    )

    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }

    $keytool = Resolve-Keytool
    $keytoolArgs = @(
        "-genkeypair",
        "-v",
        "-keystore", $Path,
        "-alias", $Alias,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-storepass", $StorePassword,
        "-keypass", $KeyPassword,
        "-dname", "CN=Android SMS Gateway, OU=Release, O=Local, L=Unknown, ST=Unknown, C=US"
    )

    Write-Host "Creating release keystore: $Path"
    & $keytool @keytoolArgs
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed to create the release keystore."
    }
}

function Initialize-ReleaseSigning {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$DefaultAlias,

        [switch]$NoCreate
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        if ($NoCreate) {
            throw "Release keystore was not found: $Path"
        }

        $answer = Read-ValueWithDefault "No release keystore found. Create it now? y/n" "y"
        if ($answer -notin @("y", "Y", "yes", "YES", "Yes")) {
            throw "Release keystore is required for the '$Variant' build."
        }

        $alias = Read-ValueWithDefault "Key alias" $DefaultAlias
        $storePassword = Read-PlainPassword "New keystore password"
        $samePassword = Read-ValueWithDefault "Use the same password for the key? y/n" "y"
        if ($samePassword -in @("y", "Y", "yes", "YES", "Yes")) {
            $keyPassword = $storePassword
        }
        else {
            $keyPassword = Read-PlainPassword "New key password"
        }

        New-ReleaseKeystore `
            -Path $Path `
            -Alias $alias `
            -StorePassword $storePassword `
            -KeyPassword $keyPassword
    }
    else {
        $alias = [Environment]::GetEnvironmentVariable("SIGNING_KEY_ALIAS")
        if ([string]::IsNullOrWhiteSpace($alias)) {
            $alias = Read-ValueWithDefault "Key alias" $DefaultAlias
        }

        $storePassword = Read-SecretEnvValue `
            -Name "SIGNING_STORE_PASSWORD" `
            -Prompt "Keystore password"
        $keyPassword = Read-SecretEnvValue `
            -Name "SIGNING_KEY_PASSWORD" `
            -Prompt "Key password"
    }

    [Environment]::SetEnvironmentVariable("SIGNING_STORE_PASSWORD", $storePassword, "Process")
    [Environment]::SetEnvironmentVariable("SIGNING_KEY_ALIAS", $alias, "Process")
    [Environment]::SetEnvironmentVariable("SIGNING_KEY_PASSWORD", $keyPassword, "Process")
}

function Get-GradleTaskName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BuildVariant
    )

    switch ($BuildVariant) {
        "debug" { "assembleDebug" }
        "debugInsecure" { "assembleDebugInsecure" }
        "insecure" { "assembleInsecure" }
        "release" { "assembleRelease" }
    }
}

function Show-ApkOutputs {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root
    )

    $outputRoot = Join-Path $Root "app\build\outputs\apk"
    if (-not (Test-Path -LiteralPath $outputRoot)) {
        Write-Host "No APK output directory found."
        return
    }

    $apks = Get-ChildItem -LiteralPath $outputRoot -Recurse -Filter "*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 5

    if (-not $apks) {
        Write-Host "No APK files found."
        return
    }

    Write-Host ""
    Write-Host "APK output:"
    foreach ($apk in $apks) {
        $sizeMb = [math]::Round($apk.Length / 1MB, 2)
        Write-Host "  $($apk.FullName) ($sizeMb MB)"
    }
}

$root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($root)) {
    $root = (Get-Location).Path
}

if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Join-Path $root "app\keystore.jks"
}
elseif (-not [System.IO.Path]::IsPathRooted($KeystorePath)) {
    $KeystorePath = Join-Path $root $KeystorePath
}

$gradlew = Join-Path $root "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "gradlew.bat was not found in $root"
}

$taskName = Get-GradleTaskName $Variant
$needsReleaseSigning = $Variant -in @("release", "insecure")

Push-Location $root
try {
    if ($needsReleaseSigning) {
        Initialize-ReleaseSigning `
            -Path $KeystorePath `
            -DefaultAlias $KeyAlias `
            -NoCreate:$NoCreateKeystore
    }

    if ($Clean) {
        Write-Host "Running Gradle clean..."
        & $gradlew "clean"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle clean failed."
        }
    }

    Write-Host "Running Gradle task: $taskName"
    & $gradlew $taskName
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle task failed: $taskName"
    }

    Show-ApkOutputs $root
}
finally {
    Pop-Location
}
