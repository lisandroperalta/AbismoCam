param([Parameter(Mandatory=$true)][string]$LibraryPath)
$ErrorActionPreference = 'Stop'
$library = Get-Item -LiteralPath $LibraryPath
if ($library.PSIsContainer -or $library.Name -ne 'libndi.so') { throw 'Selecciona libndi.so del SDK oficial NDI para Android ARM64.' }
$bytes = [IO.File]::ReadAllBytes($library.FullName)
if ($bytes.Length -lt 64 -or $bytes[0] -ne 127 -or $bytes[1] -ne 69 -or $bytes[2] -ne 76 -or $bytes[3] -ne 70 -or $bytes[4] -ne 2 -or $bytes[18] -ne 183 -or $bytes[19] -ne 0) {
    throw 'El archivo no es una biblioteca ELF ARM64 valida.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Copy-Item -LiteralPath $library.FullName -Destination (Join-Path $destination 'libndi.so')
Write-Output 'Biblioteca incorporada. Asegurate de que sea Android (no Linux) y conserva las licencias del SDK.'
Write-Output 'Compila con: powershell -ExecutionPolicy Bypass -File .\tools\build.ps1 -Test'
