#!/bin/sh
# Avvio del servizio identità (Linux / macOS).
# Uso: ./run.sh            (legge identity.properties accanto al jar, se presente)
#      ./run.sh -Dport=9000 -Dconsole.enabled=false
# Richiede: Java 17+ e (Linux) pcscd + libccid attivi per il lettore.
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$DIR/target/cie-cns-wedge.jar"
[ -f "$JAR" ] || JAR="$DIR/cie-cns-wedge.jar"
exec java --add-modules java.smartcardio -jar "$JAR" "$@"
