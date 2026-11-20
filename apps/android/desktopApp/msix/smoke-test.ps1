# Vérifie en CI que le MSIX s'installe et que l'app démarre dans son conteneur : signe une copie avec un
# certificat de test (même éditeur que le manifeste), l'installe, la lance et vérifie qu'elle tourne encore.
param(
  [Parameter(Mandatory)] [string] $Msix,
  [Parameter(Mandatory)] [string] $Publisher
)
$ErrorActionPreference = "Stop"
$signed = Join-Path ([IO.Path]::GetTempPath()) "J-Method-test.msix"
Copy-Item $Msix $signed -Force

$cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject $Publisher -CertStoreLocation Cert:\CurrentUser\My `
  -TextExtension @("2.5.29.19={text}")
$pfx = Join-Path ([IO.Path]::GetTempPath()) "jmethod-test.pfx"
$password = ConvertTo-SecureString -String "jmethod-test" -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $password | Out-Null
Import-PfxCertificate -FilePath $pfx -CertStoreLocation Cert:\LocalMachine\TrustedPeople -Password $password | Out-Null

$signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" |
  Sort-Object FullName -Descending | Select-Object -First 1
& $signtool.FullName sign /fd SHA256 /f $pfx /p "jmethod-test" $signed
if ($LASTEXITCODE -ne 0) { throw "signtool a échoué ($LASTEXITCODE)" }

Add-AppxPackage -Path $signed
$package = Get-AppxPackage | Where-Object { $_.Publisher -eq $Publisher } | Select-Object -First 1
if (-not $package) { throw "Paquet non installé" }
Write-Host "Installé : $($package.PackageFullName) dans $($package.InstallLocation)"

Start-Process explorer.exe -ArgumentList "shell:AppsFolder\$($package.PackageFamilyName)!JMethod"
Start-Sleep -Seconds 25
$process = Get-Process -Name "J-Method" -ErrorAction SilentlyContinue
if (-not $process) { throw "J-Method ne tourne pas 25 s après le lancement" }
Write-Host "J-Method tourne (PID $($process.Id -join ', '))"
$process | Stop-Process -Force
Remove-AppxPackage -Package $package.PackageFullName
