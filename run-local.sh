#!/usr/bin/env sh
set -eu

rm -rf build
mkdir -p build/classes
javac -d build/classes $(find src/main/java -name '*.java')
exec java -cp build/classes example.mediaexport.MediaExportServer
