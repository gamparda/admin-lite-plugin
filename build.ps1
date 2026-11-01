param([string]$BerryNamesJar)
$ErrorActionPreference = 'Stop'
# The javac on PATH may be Oracle's javapath launcher, whose folder is not a JDK. Ask java for its real home instead.
function Resolve-Javac {
    $javaHome = (cmd /c "java -XshowSettings:properties -version 2>&1" | Where-Object { $_ -match '^\s*java\.home = ' } | Select-Object -First 1) -replace '^\s*java\.home = ', ''
    if ($javaHome) { $real = Join-Path $javaHome.Trim() 'bin\javac.exe'; if (Test-Path $real) { return $real } }
    return (Get-Command javac.exe -ErrorAction Stop | Select-Object -First 1).Source
}
$compilerPath = Resolve-Javac
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
