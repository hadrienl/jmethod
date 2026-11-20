# Construit J-Method.msix à partir de l'app jpackage (createDistributable).
# Sans les valeurs d'identité réservées dans l'Espace partenaires, utilise des valeurs de test :
# le paquet se construit et se teste, mais le Store le refusera.
param(
  [Parameter(Mandatory)] [string] $AppDir,      # dossier J-Method contenant J-Method.exe
  [Parameter(Mandatory)] [string] $Version,     # X.Y.Z
  [Parameter(Mandatory)] [string] $Out,         # chemin du .msix
  [string] $IdentityName = "JMethod.Test",
  [string] $Publisher = "CN=J-Method Test",
  [string] $PublisherDisplayName = "J-Method"
)
$ErrorActionPreference = "Stop"
if (-not $IdentityName) { $IdentityName = "JMethod.Test" }
if (-not $Publisher) { $Publisher = "CN=J-Method Test" }
if (-not $PublisherDisplayName) { $PublisherDisplayName = "J-Method" }
if (-not (Test-Path (Join-Path $AppDir "J-Method.exe"))) { throw "J-Method.exe introuvable dans $AppDir" }

$staging = Join-Path ([IO.Path]::GetTempPath()) "jmethod-msix"
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
Copy-Item -Recurse $AppDir $staging
Copy-Item -Recurse (Join-Path $PSScriptRoot "Assets") (Join-Path $staging "Assets")

# Le Store exige une version à quatre nombres dont le dernier vaut 0.
$manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot "AppxManifest.xml")
$manifest = $manifest.Replace("@IDENTITY_NAME@", [Security.SecurityElement]::Escape($IdentityName))
$manifest = $manifest.Replace("@PUBLISHER@", [Security.SecurityElement]::Escape($Publisher))
$manifest = $manifest.Replace("@PUBLISHER_DISPLAY_NAME@", [Security.SecurityElement]::Escape($PublisherDisplayName))
$manifest = $manifest.Replace("@VERSION@", "$Version.0")
[IO.File]::WriteAllText((Join-Path $staging "AppxManifest.xml"), $manifest, (New-Object Text.UTF8Encoding $false))

$makeappx = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\makeappx.exe" |
  Sort-Object FullName -Descending | Select-Object -First 1
if (-not $makeappx) { throw "makeappx.exe introuvable (SDK Windows)" }
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
& $makeappx.FullName pack /d $staging /p $Out /o /h SHA256
if ($LASTEXITCODE -ne 0) { throw "makeappx a échoué ($LASTEXITCODE)" }
Write-Host "Paquet : $Out ($IdentityName, $Publisher, $Version.0)"
