# Runs on the Windows PowerShell 5.1 .NET Framework runtime.
# Verifies raw UTF-8 transport (including non-ASCII) without printing secrets.
$ErrorActionPreference = 'Stop'

if ($args.Length -gt 0 -and $args[0] -eq '-Child') {
    $stream = [Console]::OpenStandardInput()
    $buffer = New-Object System.IO.MemoryStream
    try {
        while (($byte = $stream.ReadByte()) -ge 0) {
            $buffer.WriteByte([byte]$byte)
        }
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            Write-Output ([BitConverter]::ToString($sha.ComputeHash($buffer.ToArray())).Replace('-', ''))
        } finally { $sha.Dispose() }
    } finally { $buffer.Dispose() }
    exit 0
}

. (Join-Path $PSScriptRoot '..\Write-Utf8PasswordToProcess.ps1')
$samples = @(
    'A-Strong-Preview-Test-Password-123',
    'Arabic-مذكرات-Preview-123'
)
$testIndex = 0
foreach ($sample in $samples) {
    $testIndex++
    $procInfo = New-Object System.Diagnostics.ProcessStartInfo
    $procInfo.FileName = 'powershell.exe'
    $procInfo.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -Child'
    $procInfo.UseShellExecute = $false
    $procInfo.RedirectStandardInput = $true
    $procInfo.RedirectStandardOutput = $true
    $procInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $procInfo
    try {
        $previousInputEncoding = [Console]::InputEncoding
        try {
            [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
            if (-not $process.Start()) { throw 'Could not start the test child.' }
        } finally {
            [Console]::InputEncoding = $previousInputEncoding
        }
        Write-Utf8PasswordToProcess -Process $process -Password $sample
        $actual = $process.StandardOutput.ReadToEnd().Trim()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Child process failed.' }
        $expectedBytes = [System.Text.Encoding]::UTF8.GetBytes($sample + "`n")
        try {
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try {
                $expected = [BitConverter]::ToString($sha.ComputeHash($expectedBytes)).Replace('-', '')
            } finally { $sha.Dispose() }
        } finally { [System.Array]::Clear($expectedBytes, 0, $expectedBytes.Length) }
        if ($actual -cne $expected) { throw ('UTF-8 stream mismatch for test sample ' + $testIndex + '; received hash ' + $actual + '; expected hash ' + $expected) }
    } finally { $process.Dispose() }
}
Write-Host 'Windows PowerShell 5.1 UTF-8 stdin transport passed (ASCII and non-ASCII).'
