$ErrorActionPreference = 'Stop'
$demoRoot = $PSScriptRoot
$demoProcess = $null
try {
    $javaExecutable = $null
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } elseif (Get-Command java -ErrorAction SilentlyContinue) {
        $javaExecutable = (Get-Command java).Source
    }
    if (-not $javaExecutable) { throw 'Java 17+ is required. Install a JDK, or set JAVA_HOME. No Node, MySQL or face SDK is required.' }
    $demoProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 18080)
    try { $demoProbe.Start() } finally { $demoProbe.Stop() }
    New-Item -ItemType Directory -Force (Join-Path $demoRoot 'demo-data') | Out-Null
    $demoArguments = @('-jar', 'app.jar', '--spring.profiles.active=demo,classroom', '--lianzhi.demo=true',
        '--server.address=127.0.0.1', '--server.port=18080', '--server.servlet.session.cookie.secure=false',
        '--spring.datasource.url=jdbc:h2:file:./demo-data/lianzhi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE',
        '--spring.datasource.username=sa', '--spring.datasource.password=', '--lianzhi.avatar-directory=./demo-data/avatars',
        '--lianzhi.face.enabled=false', '--lianzhi.face.required-for-punch=false')
    $demoProcess = Start-Process -FilePath $javaExecutable -ArgumentList $demoArguments -WorkingDirectory $demoRoot -PassThru -NoNewWindow `
        -RedirectStandardOutput (Join-Path $demoRoot 'demo-data\backend.log') -RedirectStandardError (Join-Path $demoRoot 'demo-data\backend-error.log')
    $demoReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($demoProcess.HasExited) { throw 'Demo service stopped. See demo-data/backend.log and backend-error.log.' }
        try {
            $demoInfo = Invoke-RestMethod 'http://127.0.0.1:18080/api/system/info' -TimeoutSec 1
            if ($demoInfo.data.demo -eq $true) { $demoReady = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $demoReady) { throw 'Demo startup timed out. Check Java 17+ and the logs.' }
    $demoUrl = 'http://127.0.0.1:18080/login?redirect=/attendance/presentation'
    Write-Host "Open $demoUrl"
    Write-Host 'Choose the demo administrator, log in, then follow steps 1-5.'
    Write-Host 'Synthetic employees and simulated location. Face SDK is not enabled.'
    Start-Process $demoUrl
    Read-Host 'Keep this window open. Press Enter to stop the demo'
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    Read-Host 'Press Enter to close'
} finally {
    if ($demoProcess -and -not $demoProcess.HasExited) { Stop-Process -Id $demoProcess.Id }
}
