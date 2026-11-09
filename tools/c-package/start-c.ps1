param([switch]$Mysql,[ValidateRange(1,65535)][int]$Port=8088)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$config=Join-Path $PSScriptRoot '.env'
if(Test-Path -LiteralPath $config){
  foreach($line in Get-Content -LiteralPath $config){
    if($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$'){
      $name=$Matches[1];$value=$Matches[2].Trim()
      if($name -notmatch '^(LIANZHI_|DB_|ADMIN_INITIAL_PASSWORD$|JAVA_HOME$|SESSION_COOKIE_SECURE$)'){continue}
      if($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))){$value=$value.Substring(1,$value.Length-2)}
      if($value){[Environment]::SetEnvironmentVariable($name,$value,'Process')}
    }
  }
}
$candidates=@()
if($env:JAVA_HOME){$candidates+=Join-Path $env:JAVA_HOME 'bin/java.exe'}
$found=Get-Command java -ErrorAction SilentlyContinue
if($found){$candidates+=$found.Source}
$jdks=Join-Path $env:USERPROFILE '.jdks'
if(Test-Path -LiteralPath $jdks){$candidates+=Get-ChildItem -LiteralPath $jdks -Directory | ForEach-Object {Join-Path $_.FullName 'bin/java.exe'}}
$javaCommand=$null
foreach($candidate in $candidates | Select-Object -Unique){
  if(!(Test-Path -LiteralPath $candidate)){continue}
  $version=(& $candidate --version 2>&1 | Select-Object -First 1).ToString()
  if($version -match '\b(\d+)[. ]' -and [int]$Matches[1] -ge 17){$javaCommand=$candidate;break}
}
if(!$javaCommand){throw '需要Java17或以上，请安装JDK并设置JAVA_HOME。'}
if($Mysql){
  if(!$env:DB_PASSWORD){throw 'MySQL模式需要在本机.env设置DB_PASSWORD及DB_URL/DB_USERNAME。'}
  $profiles='mysql,standalone'
}else{$profiles='demo,standalone'}
Write-Host "正在启动联智C整合包：http://127.0.0.1:$Port；按Ctrl+C停止。"
& $javaCommand -jar (Join-Path $PSScriptRoot 'lianzhi-c.jar') "--spring.profiles.active=$profiles" "--server.address=127.0.0.1" "--server.port=$Port"
exit $LASTEXITCODE
