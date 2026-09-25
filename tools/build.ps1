param([switch]$Test)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $bundledJava = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath "$bundledJava\bin\java.exe") { $env:JAVA_HOME = $bundledJava }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
# Solo en este proceso: algunos instaladores dejan comillas sueltas en PATH.
# AGP lo incorpora a java.library.path y esas comillas rompen el proceso de tests.
$env:Path = (($env:Path -split ';' | ForEach-Object { $_.Trim('"') }) -join ';')
Push-Location $projectRoot
try {
    if ($Test) { & .\gradlew.bat exportValidation exportDebugApk --console=plain --no-daemon }
    else { & .\gradlew.bat exportDebugApk --console=plain --no-daemon }
    if ($LASTEXITCODE -ne 0) { throw "Gradle termino con codigo $LASTEXITCODE" }
    Write-Output "APK: $projectRoot\entregables\AbismoCam-debug.apk"
} finally { Pop-Location }
