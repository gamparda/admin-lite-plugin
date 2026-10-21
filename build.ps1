param([string]$BerryNamesJar)
$ErrorActionPreference = 'Stop'
$compilerPath = (Get-Command javac.exe -ErrorAction Stop).Source
$compilerVersion = & $compilerPath -version 2>&1
if ("$compilerVersion" -notmatch '^javac 25\.') {
    throw 'Java 25 JDK가 필요합니다. Java 25의 bin 폴더가 PATH에 있는지 확인하세요.'
}
$originalJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path (Split-Path $compilerPath -Parent) -Parent
    Push-Location $PSScriptRoot
    try {
        # The read-only BerryNames API 1.3.0 is also present in the compatible 1.3.2 server JAR.
        if ($BerryNamesJar) {
            $apiJar = (Resolve-Path -LiteralPath $BerryNamesJar -ErrorAction Stop).Path
            & mvn.cmd -B -ntp install:install-file "-Dfile=$apiJar" '-DgroupId=kr.berrynames' '-DartifactId=BerryNames' '-Dversion=1.3.0' '-Dpackaging=jar' '-DgeneratePom=true'
            if ($LASTEXITCODE -ne 0) { throw 'BerryNames API 등록에 실패했습니다.' }
        }
        & mvn.cmd -B -ntp package
        if ($LASTEXITCODE -ne 0) { throw 'Maven 빌드에 실패했습니다.' }
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $originalJavaHome }
