$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
dotnet publish -c Release -r win-x64 --self-contained true -o "$root\build"
Write-Host "built $root\build\PadScreen.exe"
