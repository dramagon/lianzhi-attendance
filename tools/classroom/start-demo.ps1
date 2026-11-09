param(
    [switch]$CheckOnly,
    [switch]$NoPause,
    [ValidateRange(15, 300)][int]$StartupTimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
$demoRoot = $PSScriptRoot
$demoProcess = $null
$demoExitCode = 0
try {
    foreach ($demoRequired in @('app.jar', 'manifest.json', 'checksums.sha256', 'start-demo.cmd', 'start-demo.sh', 'check-demo.cmd', 'README.txt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $demoRoot $demoRequired) -PathType Leaf)) {
            throw "Incomplete package: $demoRequired is missing. Extract the entire ZIP before starting."
        }
    }
    $demoManifest = Get-Content -LiteralPath (Join-Path $demoRoot 'manifest.json') -Raw | ConvertFrom-Json
    $demoFiles = @('app.jar', 'start-demo.cmd', 'start-demo.ps1', 'start-demo.sh', 'check-demo.cmd', 'README.txt')
    foreach ($demoFile in $demoFiles) {
        $demoExpected = $demoManifest.fileHashes.$demoFile
        if ($demoExpected -notmatch '^[0-9a-fA-F]{64}$') { throw "Invalid package checksum manifest: $demoFile. Download and extract the new package again." }
        $demoActual = (Get-FileHash -LiteralPath (Join-Path $demoRoot $demoFile) -Algorithm SHA256).Hash
        if ($demoActual -ne $demoExpected) { throw "Package checksum mismatch: $demoFile. Extract a clean copy; keep your demo-data directory." }
    }
    Write-Host "Package verified. Source: $($demoManifest.commit)"
    $javaExecutable = $null
    if (Test-Path -LiteralPath (Join-Path $demoRoot 'runtime\bin\java.exe') -PathType Leaf) {
        $javaExecutable = Join-Path $demoRoot 'runtime\bin\java.exe'
    } elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } elseif (Get-Command java -CommandType Application -ErrorAction SilentlyContinue) {
        $javaExecutable = (Get-Command java -CommandType Application).Source
    }
    if (-not $javaExecutable) { throw 'Java 17+ is required. Install a JDK, set JAVA_HOME, or place an extracted Windows JRE in runtime. No Node, MySQL or face SDK is required.' }
    $demoJavaInfo = New-Object System.Diagnostics.ProcessStartInfo
    $demoJavaInfo.FileName = $javaExecutable
    $demoJavaInfo.Arguments = '-version'
    $demoJavaInfo.UseShellExecute = $false
    $demoJavaInfo.CreateNoWindow = $true
    $demoJavaInfo.RedirectStandardOutput = $true
    $demoJavaInfo.RedirectStandardError = $true
    $demoVersionProcess = [System.Diagnostics.Process]::Start($demoJavaInfo)
    try {
        if (-not $demoVersionProcess.WaitForExit(10000)) { $demoVersionProcess.Kill(); throw 'Java version check timed out.' }
        $demoVersionText = $demoVersionProcess.StandardError.ReadToEnd() + $demoVersionProcess.StandardOutput.ReadToEnd()
        $demoVersionMatch = [regex]::Match($demoVersionText, 'version\s+"(\d+)(?:\.(\d+))?')
        if ($demoVersionProcess.ExitCode -ne 0 -or -not $demoVersionMatch.Success) { throw 'Cannot determine Java version. Use a working Java 17+ installation.' }
        $demoMajor = [int]$demoVersionMatch.Groups[1].Value
        if ($demoMajor -eq 1) { $demoMajor = [int]$demoVersionMatch.Groups[2].Value }
        if ($demoMajor -lt 17) { throw "Java $demoMajor is too old. Java 17+ is required. Selected executable: $javaExecutable" }
    } finally { $demoVersionProcess.Dispose() }
    Write-Host "Java $demoMajor ready: $javaExecutable"
    $demoProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 18080)
    try { $demoProbe.Start() } catch { throw 'Port 18080 is occupied. Close your previous demo window, or inspect the other service. No existing process was stopped.' } finally { $demoProbe.Stop() }
    # A temporary file checks permissions without creating or modifying the demo database.
    $demoWriteProbe = Join-Path $demoRoot ('.demo-check-' + [guid]::NewGuid().ToString('N'))
    try { [System.IO.File]::WriteAllText($demoWriteProbe, 'check') } finally {
        if (Test-Path -LiteralPath $demoWriteProbe) { Remove-Item -LiteralPath $demoWriteProbe }
    }
    Write-Host 'Preflight passed: package, Java 17+, port 18080 and writable directory.' -ForegroundColor Green
    if (-not $CheckOnly) {
        New-Item -ItemType Directory -Force (Join-Path $demoRoot 'demo-data') | Out-Null
        $demoArguments = @('-jar', 'app.jar', '--spring.profiles.active=demo,classroom', '--lianzhi.demo=true',
            '--server.address=127.0.0.1', '--server.port=18080', '--server.servlet.session.cookie.secure=false',
            '--spring.datasource.url=jdbc:h2:file:./demo-data/lianzhi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;OPTIMIZE_REUSE_RESULTS=FALSE',
            '--spring.datasource.username=sa', '--spring.datasource.password=', '--lianzhi.avatar-directory=./demo-data/avatars',
            '--lianzhi.face.enabled=false', '--lianzhi.face.required-for-punch=false', '--LIANZHI_KNOWLEDGE_ENABLED=true',
            '--LIANZHI_AI_BASE_URL=', '--LIANZHI_AI_MODEL=', '--LIANZHI_EMBEDDING_BASE_URL=', '--LIANZHI_EMBEDDING_MODEL=',
            '--LIANZHI_OCR_URL=', '--LIANZHI_TRANSCRIPTION_URL=')
        $demoProcess = Start-Process -FilePath $javaExecutable -ArgumentList $demoArguments -WorkingDirectory $demoRoot -PassThru -NoNewWindow `
            -RedirectStandardOutput (Join-Path $demoRoot 'demo-data\backend.log') -RedirectStandardError (Join-Path $demoRoot 'demo-data\backend-error.log')
        $demoReady = $false
        $demoDeadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
        Write-Host "Starting demo (timeout ${StartupTimeoutSeconds}s). Logs: demo-data/backend.log and backend-error.log"
        while ([DateTime]::UtcNow -lt $demoDeadline) {
            if ($demoProcess.HasExited) { throw 'Demo service stopped. See demo-data/backend.log and backend-error.log.' }
            try {
                $demoInfo = Invoke-RestMethod 'http://127.0.0.1:18080/api/system/info' -TimeoutSec 1
                if ($demoInfo.data.demo -eq $true) { $demoReady = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        if (-not $demoReady) { throw 'Demo startup timed out. Check demo-data/backend.log and backend-error.log; your database was not deleted.' }
        $demoUrl = 'http://127.0.0.1:18080/login?redirect=/attendance/presentation'
        Write-Host "Open $demoUrl"
        Write-Host 'Choose the demo administrator, log in, then choose the full-site demo button.'
        Write-Host 'Synthetic employees and simulated location. Face SDK is not enabled.'
        try { Start-Process $demoUrl } catch { Write-Host "Browser could not open automatically. Open this address manually: $demoUrl" }
        Read-Host 'Keep this window open. Press Enter to stop the demo'
    } elseif (-not $NoPause) {
        Read-Host 'Checks passed. Press Enter to close; double-click start-demo.cmd to run'
    }
} catch {
    $demoExitCode = 1
    Write-Host $_.Exception.Message -ForegroundColor Red
    if (-not $NoPause) { Read-Host 'Press Enter to close' }
} finally {
    if ($demoProcess -and -not $demoProcess.HasExited) { Stop-Process -Id $demoProcess.Id }
}
exit $demoExitCode
