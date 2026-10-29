param(
    [string]$MysqlPath = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [string]$CredentialFile,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
# Reuse the safe client helper and read-only preflight. No changes to the app database here.
. (Join-Path $PSScriptRoot 'setup-mysql.ps1') -MysqlPath $MysqlPath -CredentialFile $CredentialFile -CheckOnly
$verificationConfig = Join-Path $projectRoot 'artifacts/mysql-verification.local.ps1'
$tables = [int](Invoke-LocalMysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='lianzhi_verify';" $adminUser $adminPassword)
$identityTables = [int](Invoke-LocalMysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='lianzhi_verify' AND table_name IN ('sys_user','sys_department','flyway_schema_history');" $adminUser $adminPassword)
if ($tables -gt 0 -and $identityTables -ne 3) { throw 'lianzhi_verify contains unrecognized tables. No data was removed; choose a separate verification database.' }
$verifyUserExists = [int](Invoke-LocalMysql "SELECT COUNT(*) FROM mysql.user WHERE User='lianzhi_verify' AND Host='localhost';" $adminUser $adminPassword)
if (Test-Path -LiteralPath $verificationConfig) {
    . ([ScriptBlock]::Create([System.IO.File]::ReadAllText($verificationConfig, [System.Text.Encoding]::UTF8)))
    if ($env:LIANZHI_VERIFY_PASSWORD -notmatch '^Lz@[a-f0-9]{48}$') { throw 'The existing verification config is incomplete. It has not been overwritten.' }
} else {
    if ($verifyUserExists -gt 0) { throw 'Verification account exists without its local config. Its password has not been reset.' }
    $env:LIANZHI_VERIFY_PASSWORD = New-LocalPassword
    @('# Local test credentials only; not for the app database.', "`$env:LIANZHI_VERIFY_PASSWORD = '$env:LIANZHI_VERIFY_PASSWORD'") |
        Set-Content -LiteralPath $verificationConfig -Encoding UTF8
}
if ($verifyUserExists -gt 0) { [void](Invoke-LocalMysql 'SELECT 1;' 'lianzhi_verify' $env:LIANZHI_VERIFY_PASSWORD) }
[void](Invoke-LocalMysql 'CREATE DATABASE IF NOT EXISTS lianzhi_verify CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' $adminUser $adminPassword)
if ($verifyUserExists -eq 0) {
    [void](Invoke-LocalMysql "CREATE USER 'lianzhi_verify'@'localhost' IDENTIFIED BY '$env:LIANZHI_VERIFY_PASSWORD';" $adminUser $adminPassword)
}
[void](Invoke-LocalMysql "GRANT ALL PRIVILEGES ON lianzhi_verify.* TO 'lianzhi_verify'@'localhost';" $adminUser $adminPassword)
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3306/lianzhi_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:SPRING_DATASOURCE_USERNAME = 'lianzhi_verify'
$env:SPRING_DATASOURCE_PASSWORD = $env:LIANZHI_VERIFY_PASSWORD
$env:SPRING_PROFILES_ACTIVE = 'test'
Write-Host 'Running tests against lianzhi_verify. The app database lianzhi is not used by these tests.'
Write-Host 'API integration tests use MySQL; pure policy tests and explicit isolated schema/concurrency fixtures are separate.'
Push-Location (Join-Path $projectRoot 'backend')
try {
    & .\mvnw.cmd -B -ntp test
    if ($LASTEXITCODE -ne 0) { throw "MySQL verification failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
