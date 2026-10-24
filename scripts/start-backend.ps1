$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JDK 17 is required. Install JDK 17 and reopen PowerShell.'
}
Push-Location (Join-Path $projectRoot 'backend')
try {
    & .\mvnw.cmd spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
} finally { Pop-Location }
