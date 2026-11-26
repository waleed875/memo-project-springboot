# Portable across Windows PowerShell 5.1 (.NET Framework) and PowerShell 7.
# Send one UTF-8 password line through a redirected stdin pipe, not via
# arguments, environment variables, command history, or text files.
function Write-Utf8PasswordToProcess {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$Process,

        [Parameter(Mandatory = $true)]
        [string]$Password
    )

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Password)
    try {
        $stream = $Process.StandardInput.BaseStream
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.WriteByte(10) # Unix LF: the Java CLI reads exactly one line.
        $stream.Flush()
    }
    finally {
        [System.Array]::Clear($bytes, 0, $bytes.Length)
        $Process.StandardInput.Close()
    }
}
