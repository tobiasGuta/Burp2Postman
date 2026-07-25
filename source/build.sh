#!/usr/bin/env bash
set -euo pipefail
mvn clean package
printf 'Built: %s\n' "$(pwd)/target/Burp2Postman.jar"
