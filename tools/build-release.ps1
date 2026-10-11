param(
    [string]$KeyStorePath,
    [string]$PasswordFile,
    [string[]]$Tasks = @(':app:assembleRelease', ':app:lintRelease', ':app:testDebugUnitTest')
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if (-not $KeyStorePath) { $KeyStorePath = Join-Path $projectDirectory 'signing/savely-release.jks' }
if (-not $PasswordFile) { $PasswordFile = Join-Path $projectDirectory 'signing/password.dpapi' }
if (-not (Test-Path -LiteralPath $KeyStorePath -PathType Leaf)) { throw 'Keystore di distribuzione mancante.' }
if (-not (Test-Path -LiteralPath $PasswordFile -PathType Leaf)) { throw 'Password DPAPI mancante: usare lo stesso utente Windows che ha preparato la chiave.' }
$previous = @{}
foreach ($name in @('SAVELY_KEYSTORE', 'SAVELY_STORE_PASSWORD', 'SAVELY_KEY_ALIAS', 'SAVELY_KEY_PASSWORD')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$passwordPointer = [IntPtr]::Zero
try {
    $securePassword = (Get-Content -LiteralPath $PasswordFile -Raw).Trim() | ConvertTo-SecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $env:SAVELY_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:SAVELY_KEY_PASSWORD = $env:SAVELY_STORE_PASSWORD
    $env:SAVELY_KEY_ALIAS = 'savely-release'
    $env:SAVELY_KEYSTORE = (Resolve-Path -LiteralPath $KeyStorePath).Path
    Push-Location $projectDirectory
    try {
        & (Join-Path $projectDirectory 'gradlew.bat') @Tasks --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build fallita, exit code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
