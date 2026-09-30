param(
    [Parameter(Mandatory = $true)][string]$InputApk,
    [Parameter(Mandatory = $true)][string]$OutputApk,
    [Parameter(Mandatory = $true)][string]$BuildToolsDir,
    [switch]$InitializeKey
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$keyDir = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\release-keys'
$keyFile = Join-Path $keyDir 'FCMHelper-release.p12'
$passwordFile = Join-Path $keyDir 'FCMHelper-release.password.dpapi'
$alias = 'fcm-helper-release'
$zipalign = Join-Path $BuildToolsDir 'zipalign.exe'
$apksigner = Join-Path $BuildToolsDir 'apksigner.bat'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

foreach ($required in @($InputApk, $zipalign, $apksigner, $keytool)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing required file: $required"
    }
}

$keyExists = Test-Path -LiteralPath $keyFile -PathType Leaf
$passwordExists = Test-Path -LiteralPath $passwordFile -PathType Leaf
if ($keyExists -ne $passwordExists) {
    throw 'Release key or its local encrypted password is missing. Refusing to generate a replacement key.'
}
if (-not $keyExists -and -not $InitializeKey) {
    throw 'Release key is missing. Restore the original key and password, or explicitly initialize a first release key.'
}

$password = $null
$alignedApk = $null
try {
    if (-not $keyExists) {
        New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
        $randomBytes = New-Object byte[] 48
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
        $password = [Convert]::ToBase64String($randomBytes)
        $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
        ConvertFrom-SecureString $securePassword | Set-Content -LiteralPath $passwordFile -Encoding Ascii -NoNewline
        $env:FCM_HELPER_RELEASE_PASSWORD = $password
        & $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keyFile `
            -alias $alias -keyalg RSA -keysize 4096 -validity 36500 `
            -dname 'CN=FCM Helper Release, O=FCM Helper' `
            -storepass:env FCM_HELPER_RELEASE_PASSWORD -keypass:env FCM_HELPER_RELEASE_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
        Write-Output "Created release key outside project: $keyFile"
    } else {
        $securePassword = Get-Content -LiteralPath $passwordFile -Raw | ConvertTo-SecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try {
            $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
        $env:FCM_HELPER_RELEASE_PASSWORD = $password
    }

    $outputDirectory = Split-Path -Parent $OutputApk
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $alignedApk = Join-Path $outputDirectory 'FCM-Helper-1.0.0.aligned.tmp.apk'
    & $zipalign -p -f 4 $InputApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }

    & $apksigner sign --ks $keyFile --ks-type PKCS12 --ks-key-alias $alias `
        --ks-pass env:FCM_HELPER_RELEASE_PASSWORD --key-pass env:FCM_HELPER_RELEASE_PASSWORD `
        --out $OutputApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }

    & $apksigner verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
} finally {
    Remove-Item Env:FCM_HELPER_RELEASE_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    if ($alignedApk -and (Test-Path -LiteralPath $alignedApk)) {
        Remove-Item -LiteralPath $alignedApk
    }
}
