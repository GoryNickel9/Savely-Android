param([string]$Destination)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $Destination) { $Destination = Join-Path $env:USERPROFILE 'Documents/Savely-Signing-Backup' }
$taskDestination = [IO.Path]::GetFullPath($Destination)
if ($taskDestination.StartsWith([IO.Path]::GetFullPath($taskRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Il backup della firma deve restare fuori dal checkout.'
}
New-Item -ItemType Directory -Force -Path $taskDestination | Out-Null
$taskSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$taskAcl = [Security.AccessControl.DirectorySecurity]::new()
$taskAcl.SetOwner($taskSid)
$taskAcl.SetAccessRuleProtection($true, $false)
$taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($taskSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
Set-Acl -LiteralPath $taskDestination -AclObject $taskAcl
$taskKeyStore = Join-Path $taskRoot 'signing/savely-release.jks'
$taskPassword = (Get-Content -LiteralPath (Join-Path $taskRoot 'signing/password.dpapi') -Raw).Trim() | ConvertTo-SecureString
$taskPointer = [IntPtr]::Zero
$taskKey = [byte[]]::new(32)
$taskNonce = [byte[]]::new(12)
[Security.Cryptography.RandomNumberGenerator]::Fill($taskKey)
[Security.Cryptography.RandomNumberGenerator]::Fill($taskNonce)
$taskPlain = $null
$taskRestored = $null
$taskAes = [Security.Cryptography.AesGcm]::new($taskKey, 16)
try {
    $taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskPassword)
    $taskPayload = @{
        schemaVersion = 1
        keyAlias = 'savely-release'
        keyStore = [Convert]::ToBase64String([IO.File]::ReadAllBytes($taskKeyStore))
        password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer)
    } | ConvertTo-Json -Compress
    $taskPlain = [Text.Encoding]::UTF8.GetBytes($taskPayload)
    $taskPayload = $null
    $taskCipher = [byte[]]::new($taskPlain.Length)
    $taskTag = [byte[]]::new(16)
    $taskAes.Encrypt($taskNonce, $taskPlain, $taskCipher, $taskTag)
    $taskId = [guid]::NewGuid().ToString()
    $taskArchive = Join-Path $taskDestination "savely-$taskId.keybackup.json"
    $taskRecovery = Join-Path $taskDestination "savely-$taskId.recovery-key"
    @{ schemaVersion = 1; algorithm = 'AES-256-GCM'; nonce = [Convert]::ToBase64String($taskNonce);
       tag = [Convert]::ToBase64String($taskTag); ciphertext = [Convert]::ToBase64String($taskCipher) } |
        ConvertTo-Json | Set-Content -LiteralPath $taskArchive -Encoding utf8
    [IO.File]::WriteAllBytes($taskRecovery, $taskKey)
    # Verify recovery using only the files just written, with no DPAPI or original plaintext password.
    $taskDisk = Get-Content -LiteralPath $taskArchive -Raw | ConvertFrom-Json
    $taskRecoveredKey = [IO.File]::ReadAllBytes($taskRecovery)
    $taskDiskCipher = [Convert]::FromBase64String($taskDisk.ciphertext)
    $taskRestored = [byte[]]::new($taskDiskCipher.Length)
    $taskVerifier = [Security.Cryptography.AesGcm]::new($taskRecoveredKey, 16)
    try {
        $taskVerifier.Decrypt([Convert]::FromBase64String($taskDisk.nonce), $taskDiskCipher, [Convert]::FromBase64String($taskDisk.tag), $taskRestored)
        if (-not [Linq.Enumerable]::SequenceEqual[byte]($taskPlain, $taskRestored)) { throw 'Ripristino del backup diverso dal contenuto originale.' }
        $taskDiskCipher[0] = $taskDiskCipher[0] -bxor 1
        $taskTamperRejected = $false
        try { $taskVerifier.Decrypt([Convert]::FromBase64String($taskDisk.nonce), $taskDiskCipher, [Convert]::FromBase64String($taskDisk.tag), $taskRestored) }
        catch [Security.Cryptography.AuthenticationTagMismatchException] { $taskTamperRejected = $true }
        if (-not $taskTamperRejected) { throw 'Integrità del backup non verificata.' }
    } finally {
        $taskVerifier.Dispose()
        [Array]::Clear($taskRecoveredKey)
    }
    $taskResult = @{ archive = $taskArchive; recoveryKeyFile = $taskRecovery; portableRecoveryVerified = $true;
        integrityVerified = $true; outsideGit = $true; offDeviceBackupVerified = $false }
    $taskResult | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot 'artifacts/signing-backup-result.json') -Encoding utf8
    Write-Output 'Backup portabile creato fuori da Git; ripristino e integrità verificati. Copia archivio e chiave in due destinazioni protette separate.'
} finally {
    $taskAes.Dispose()
    if ($taskPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
    foreach ($taskBuffer in @($taskKey, $taskPlain, $taskRestored)) {
        if ($null -ne $taskBuffer) { [Array]::Clear($taskBuffer) }
    }
}
