# One-time PostgreSQL *preview* admin initialization. Run in PowerShell 5.1+.
# Never stores the password in command arguments, env variables or project files.
# Requires existing Docker preview database initialized to schema 070.
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $root 'deploy\.env.preview'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Missing deploy/.env.preview: initialize the disposable preview first.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is not available.'
}

Write-Host 'This will activate the original admin account ONLY on an unused memodb_preview database.'
Write-Host 'If you have notes, users, or an already activated administrator, the server will refuse.'
$first = Read-Host 'Choose a new admin password (12-72 characters)' -AsSecureString
$second = Read-Host 'Confirm the new admin password' -AsSecureString
$ptr1 = [IntPtr]::Zero
$ptr2 = [IntPtr]::Zero
$plain1 = $null
$plain2 = $null
try {
    $ptr1 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($first)
    $ptr2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($second)
    $plain1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr1)
    $plain2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr2)
    if ($plain1 -cne $plain2) { throw 'Password confirmation does not match.' }
    if ($plain1.Length -lt 12 -or $plain1.Length -gt 72) {
        throw 'Password must have 12-72 characters.'
    }
    if ([Text.Encoding]::UTF8.GetByteCount($plain1) -gt 72) {
        throw 'Password must fit within 72 UTF-8 bytes (BCrypt limit).'
    }
    if ($plain1.Contains([string][char]0) -or [string]::IsNullOrWhiteSpace($plain1)) {
        throw 'Invalid password.'
    }

    $procInfo = New-Object System.Diagnostics.ProcessStartInfo
    $procInfo.FileName = 'docker'
    $procInfo.Arguments = 'compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml run --rm -T --no-deps -e SPRING_PROFILES_ACTIVE=preview-admin-bootstrap -e SPRING_MAIN_WEB_APPLICATION_TYPE=none -e MEMO_PREVIEW_BOOTSTRAP_STDIN=true memo-api'
    $procInfo.WorkingDirectory = $root
    $procInfo.UseShellExecute = $false
    $procInfo.RedirectStandardInput = $true
    $procInfo.CreateNoWindow = $false

    # Windows PowerShell 5.1 (.NET Framework) does not provide ProcessStartInfo.StandardInputEncoding.
    # Write UTF-8 bytes directly to the redirected standard-input stream instead.
    . (Join-Path $PSScriptRoot 'Write-Utf8PasswordToProcess.ps1')
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $procInfo
    if (-not $process.Start()) { throw 'Could not start the one-time Docker bootstrap command.' }
    try {
        Write-Utf8PasswordToProcess -Process $process -Password $plain1
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'Admin activation was refused or failed. Review the message above.'
        }
    } finally {
        $process.Dispose()
    }
} finally {
    if ($ptr1 -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr1) }
    if ($ptr2 -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr2) }
    $plain1 = $null
    $plain2 = $null
    $first.Dispose()
    $second.Dispose()
}
