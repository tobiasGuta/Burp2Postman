#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if command -v mvn >/dev/null 2>&1; then
  maven_command=(mvn)
elif [[ -x "$source_dir/maven/apache-maven-3.9.9/bin/mvn" ]]; then
  maven_command=("$source_dir/maven/apache-maven-3.9.9/bin/mvn")
else
  printf 'Maven 3.9+ was not found.\n' >&2
  exit 1
fi

cd "$source_dir"
"${maven_command[@]}" clean package
printf 'Built: %s\n' "$source_dir/target/Burp2Postman.jar"
