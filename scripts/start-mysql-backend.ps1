param([string]$LocalConfig, [string]$JavaHome)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $LocalConfig) { $LocalConfig = Join-Path $projectRoot 'artifacts/mysql-app.local.ps1' }
if (-not (Test-Path -LiteralPath $LocalConfig)) { throw 'Run scripts/setup-mysql.ps1 first, or supply -LocalConfig with the existing project credentials.' }
. ([ScriptBlock]::Create([System.IO.File]::ReadAllText($LocalConfig, [System.Text.Encoding]::UTF8)))
if (-not $env:DB_USERNAME -or -not $env:DB_PASSWORD -or $env:ADMIN_INITIAL_PASSWORD.Length -lt 4) {
    throw 'DB_USERNAME, DB_PASSWORD and ADMIN_INITIAL_PASSWORD (at least 4 characters) are required in the local config.'
}
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to JDK 17, or pass -JavaHome.'
}
$javaRelease = Join-Path $env:JAVA_HOME 'release'
if (-not (Test-Path -LiteralPath $javaRelease) -or -not (Select-String -LiteralPath $javaRelease -Pattern '^JAVA_VERSION="17\.' -Quiet)) {
    throw 'This project uses JDK 17. Pass -JavaHome pointing to the JDK 17 installation.'
}
if ($env:DB_URL -and -not $env:DB_URL.StartsWith('jdbc:mysql:')) { throw 'The local database URL must use jdbc:mysql:.' }
$env:SPRING_PROFILES_ACTIVE = 'mysql'
# Do not let an earlier H2 verification URL override the MySQL profile.
Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
Remove-Item Env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
& (Join-Path $PSScriptRoot 'start-backend.ps1')
