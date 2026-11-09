$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$classes = Join-Path $root 'backend/target/classes'
if (!(Test-Path -LiteralPath (Join-Path $classes 'cn/lianzhi/attendance/face/ArcSoftFaceProvider.class'))) {
    throw 'Build the backend first with Java 17.'
}
. (Join-Path $root 'artifacts/arcsoft-credentials.local.ps1')
$env:LIANZHI_ARCSOFT_JAR = Join-Path $root 'artifacts/arcsoft-v3/libs/arcsoft-sdk-face-3.0.0.0.jar'
$env:LIANZHI_ARCSOFT_NATIVE_DIRECTORY = Join-Path $root 'artifacts/arcsoft-v3/libs/WIN64'
$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += (Join-Path $env:JAVA_HOME 'bin/java.exe') }
$javaCandidates += @(Get-ChildItem -Path "$env:ProgramFiles/Java/jdk-*/bin/java.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
$java = $null
foreach ($candidate in $javaCandidates) {
    try {
        $ErrorActionPreference = 'Continue'
        $version = & $candidate -version 2>&1 | Out-String
    } finally { $ErrorActionPreference = 'Stop' }
    if ($LASTEXITCODE -eq 0 -and $version -match '\b(\d+)\.' -and [int]$Matches[1] -ge 17) { $java=$candidate;break }
}
if (!$java) { throw 'Java 17 or newer is required.' }
# Keep SDK activation files in an ignored directory.
Set-Location -LiteralPath (Join-Path $root 'artifacts/arcsoft-v3')
& $java '-Dfile.encoding=UTF-8' -cp $classes (Join-Path $PSScriptRoot 'CalibrateFace.java')
exit $LASTEXITCODE
