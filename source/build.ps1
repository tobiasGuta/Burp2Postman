$ErrorActionPreference = "Stop"
mvn clean package
Write-Host "Built: $PWD/target/Burp2Postman.jar"
