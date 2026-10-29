param(
    [string]$MysqlPath = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [string]$CredentialFile,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $CredentialFile) { $CredentialFile = Join-Path $projectRoot 'artifacts/mysql-credentials.local.ps1' }
$localConfig = Join-Path $projectRoot 'artifacts/mysql-app.local.ps1'
if (-not (Test-Path -LiteralPath $MysqlPath)) { throw 'MySQL client not found. Set -MysqlPath to mysql.exe.' }
if (Test-Path -LiteralPath $CredentialFile) {
    . ([ScriptBlock]::Create([System.IO.File]::ReadAllText($CredentialFile, [System.Text.Encoding]::UTF8)))
}
if (-not $env:LIANZHI_MYSQL_ADMIN_USER -or -not $env:LIANZHI_MYSQL_ADMIN_PASSWORD) {
    throw 'Set LIANZHI_MYSQL_ADMIN_USER and LIANZHI_MYSQL_ADMIN_PASSWORD in the local credential file. Do not commit passwords.'
}

function Invoke-LocalMysql([string]$Sql, [string]$Username, [string]$Password) {
    if ($Username -notmatch '^[a-zA-Z0-9_]+$') { throw 'Unsupported database username.' }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $MysqlPath
    $startInfo.Arguments = "--no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 --connect-timeout=5 --default-character-set=utf8mb4 --batch --skip-column-names --user=$Username"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    if ($startInfo.PSObject.Properties['StandardInputEncoding']) {
        $startInfo.StandardInputEncoding = New-Object System.Text.UTF8Encoding($false)
    }
    $startInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $startInfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    # Password is passed only to this child process, never in command arguments or SQL logs.
    $startInfo.EnvironmentVariables['MYSQL_PWD'] = $Password
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $process.StandardInput.WriteLine($Sql)
        $process.StandardInput.Close()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $outputText = $outputTask.Result
        $errorText = $errorTask.Result
        if ($process.ExitCode -ne 0) {
            $safeError = $errorText.Replace($Password, '[REDACTED]')
            if ($env:DB_PASSWORD) { $safeError = $safeError.Replace($env:DB_PASSWORD, '[REDACTED]') }
            if ($env:LIANZHI_VERIFY_PASSWORD) { $safeError = $safeError.Replace($env:LIANZHI_VERIFY_PASSWORD, '[REDACTED]') }
            throw "MySQL command failed: $safeError"
        }
        return $outputText.Trim()
    } finally { $process.Dispose() }
}

function New-LocalPassword {
    $bytes = New-Object byte[] 24
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return 'Lz@' + (-join ($bytes | ForEach-Object { $_.ToString('x2') }))
}

$adminUser = $env:LIANZHI_MYSQL_ADMIN_USER
$adminPassword = $env:LIANZHI_MYSQL_ADMIN_PASSWORD
$version = Invoke-LocalMysql 'SELECT VERSION();' $adminUser $adminPassword
Write-Host "Connected to MySQL $version on 127.0.0.1:3306."
$tables = [int](Invoke-LocalMysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='lianzhi';" $adminUser $adminPassword)
$identityTables = [int](Invoke-LocalMysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='lianzhi' AND table_name IN ('sys_user','sys_department','flyway_schema_history');" $adminUser $adminPassword)
if ($tables -gt 0 -and $identityTables -ne 3) {
    throw 'The existing lianzhi database is not a recognized project database. No changes were made. Inspect it before choosing a separate database.'
}
Write-Host "Existing lianzhi table count: $tables. Existing data will be preserved."
if ($CheckOnly) { Write-Host 'Check-only completed. No database, account or app config changes.'; return }

$userExists = [int](Invoke-LocalMysql "SELECT COUNT(*) FROM mysql.user WHERE User='lianzhi' AND Host='localhost';" $adminUser $adminPassword)
if (Test-Path -LiteralPath $localConfig) {
    . ([ScriptBlock]::Create([System.IO.File]::ReadAllText($localConfig, [System.Text.Encoding]::UTF8)))
    if ($env:DB_USERNAME -ne 'lianzhi' -or -not $env:DB_PASSWORD -or $env:ADMIN_INITIAL_PASSWORD.Length -lt 4) {
        throw 'The existing local app config is incomplete. It has not been overwritten.'
    }
} else {
    if ($userExists -gt 0) { throw 'The lianzhi database account already exists. Supply its existing password in artifacts/mysql-app.local.ps1; its password will not be reset.' }
    $env:DB_USERNAME = 'lianzhi'
    $env:DB_PASSWORD = New-LocalPassword
    $env:ADMIN_INITIAL_PASSWORD = New-LocalPassword
    $env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/lianzhi?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
    [void](New-Item -ItemType Directory -Path (Split-Path $localConfig -Parent) -Force)
    @(
        '# Local credentials only. artifacts/ is ignored by Git. Keep this file to reuse the account.'
        "`$env:DB_USERNAME = 'lianzhi'"
        "`$env:DB_PASSWORD = '$env:DB_PASSWORD'"
        "`$env:ADMIN_INITIAL_PASSWORD = '$env:ADMIN_INITIAL_PASSWORD'"
        "`$env:DB_URL = '$env:DB_URL'"
    ) | Set-Content -LiteralPath $localConfig -Encoding UTF8
}
if ($userExists -gt 0) {
    [void](Invoke-LocalMysql 'SELECT 1;' $env:DB_USERNAME $env:DB_PASSWORD)
} else {
    # Generated password is hexadecimal with a fixed prefix, safe inside a SQL string literal.
    if ($env:DB_PASSWORD -notmatch '^Lz@[a-f0-9]{48}$') { throw 'For a new account, use the generated local config. Existing accounts are never reset.' }
}
[void](Invoke-LocalMysql 'CREATE DATABASE IF NOT EXISTS lianzhi CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' $adminUser $adminPassword)
if ($userExists -eq 0) {
    [void](Invoke-LocalMysql "CREATE USER 'lianzhi'@'localhost' IDENTIFIED BY '$env:DB_PASSWORD';" $adminUser $adminPassword)
}
[void](Invoke-LocalMysql "GRANT ALL PRIVILEGES ON lianzhi.* TO 'lianzhi'@'localhost';" $adminUser $adminPassword)
[void](Invoke-LocalMysql 'USE lianzhi; SELECT 1;' $env:DB_USERNAME $env:DB_PASSWORD)
Write-Host "Project database and scoped account are ready. Local app config: $localConfig"
Write-Host 'Next: powershell -ExecutionPolicy Bypass -File ./scripts/start-mysql-backend.ps1'
