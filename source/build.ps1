$ErrorActionPreference = "Stop"
$sourceDirectory = $PSScriptRoot
$bundledMaven = Join-Path $sourceDirectory "maven/apache-maven-3.9.9/bin/mvn.cmd"
$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($null -eq $maven) {
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
}
$mavenCommand = if ($null -ne $maven) { $maven.Source } elseif (Test-Path -LiteralPath $bundledMaven) {
    $bundledMaven
} else {
    throw "Maven 3.9+ was not found."
}

Push-Location -LiteralPath $sourceDirectory
try {
    & $mavenCommand clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
    Write-Host "Built: $(Join-Path $sourceDirectory 'target/Burp2Postman.jar')"
} finally {
    Pop-Location
}
