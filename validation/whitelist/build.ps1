param([Parameter(Mandatory=$true)][string]$ServerRoot,[switch]$OnlineProfile)
$ErrorActionPreference='Stop'
$compiler=(Get-Command javac.exe -ErrorAction Stop).Source
$jar=Join-Path (Split-Path $compiler -Parent) 'jar.exe'
$root=(Resolve-Path -LiteralPath $ServerRoot).Path
$libs=Get-ChildItem -LiteralPath (Join-Path $root 'libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName
$plugins=Get-ChildItem -LiteralPath (Join-Path $root 'plugins') -Filter '*.jar' | ForEach-Object FullName
$versions=Get-ChildItem -LiteralPath (Join-Path $root 'versions') -Recurse -Filter '*.jar' | ForEach-Object FullName
$classpath=(@($libs)+@($versions)+@($plugins)) -join ';'
$build=Join-Path $PSScriptRoot 'target'
$classes=Join-Path $build 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$source=if($OnlineProfile){'OnlineProfileHarness.java'}else{'WhitelistHarness.java'}
& $compiler --release 25 -encoding UTF-8 -classpath $classpath -d $classes (Join-Path $PSScriptRoot $source)
if($LASTEXITCODE -ne 0){throw 'Whitelist fixture compile failed'}
$meta=Join-Path $build 'meta'; New-Item -ItemType Directory -Path $meta -Force | Out-Null
$metadata=if($OnlineProfile){'online-plugin.yml'}else{'plugin.yml'}
$filename=if($OnlineProfile){'online-profile-checks.jar'}else{'whitelist-checks.jar'}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot $metadata) -Destination (Join-Path $meta 'plugin.yml') -Force
& $jar --create --file (Join-Path $build $filename) -C $classes . -C $meta plugin.yml
if($LASTEXITCODE -ne 0){throw 'Whitelist fixture packaging failed'}
