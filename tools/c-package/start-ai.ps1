$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $projectRoot
$python = Join-Path $projectRoot 'artifacts/c-ai-venv/Scripts/python.exe'
if (!(Test-Path -LiteralPath $python -PathType Leaf)) {
    throw 'Local AI environment is missing. See docs/modules/c-media-services.md.'
}
# User-owned local settings; neither SDK binaries nor licensing information belong in Git.
$credentials = Join-Path $projectRoot 'artifacts/arcsoft-credentials.local.ps1'
if (Test-Path -LiteralPath $credentials -PathType Leaf) {
    . $credentials
    $env:LIANZHI_ARCSOFT_JAR = Join-Path $projectRoot 'artifacts/arcsoft-v3/libs/arcsoft-sdk-face-3.0.0.0.jar'
    $env:LIANZHI_ARCSOFT_NATIVE_DIRECTORY = Join-Path $projectRoot 'artifacts/arcsoft-v3/libs/WIN64'
    $env:LIANZHI_ARCSOFT_ENABLED = 'true'
    $env:LIANZHI_FACE_ENABLED = 'true'
    # Keep the face comparison policy closed until a calibrated score is explicitly configured.
    # The SDK writes its device activation cache in the application's ignored working directory.
}
$arguments = @('tools/c-package/run-full.py', '--port', '18080')
$oldData = Join-Path $projectRoot 'artifacts/classroom-demo'
if (Test-Path -LiteralPath (Join-Path $oldData 'demo-data/lianzhi.mv.db') -PathType Leaf) {
    $arguments += @('--reuse-classroom-data', $oldData)
}
& $python @arguments
exit $LASTEXITCODE
