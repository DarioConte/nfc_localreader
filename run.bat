@echo off
REM Avvio del servizio identita' (Windows).
REM Uso: run.bat            (legge identity.properties accanto al jar, se presente)
REM      run.bat -Dport=9000 -Dconsole.enabled=false
REM Richiede: Java 17+ . Il lettore PC/SC usa lo Smart Card service di Windows (gia' attivo).
setlocal
set "JAR=%~dp0target\cie-cns-wedge.jar"
if not exist "%JAR%" set "JAR=%~dp0cie-cns-wedge.jar"
java --add-modules java.smartcardio -jar "%JAR%" %*
