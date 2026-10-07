@echo off
setlocal
cd /d "%~dp0"

echo ==================================================
echo   TOMBOLA - creazione degli eseguibili
echo ==================================================
echo.

rem --- controlli iniziali ---
where javac >nul 2>nul
if errorlevel 1 (
    echo ERRORE: javac non trovato. Installa un JDK 17 o superiore e aggiungilo al PATH.
    pause
    exit /b 1
)
where jpackage >nul 2>nul
if errorlevel 1 (
    echo ERRORE: jpackage non trovato. Serve un JDK 14 o superiore.
    pause
    exit /b 1
)
if not exist "src\Project_Tombola" (
    echo ERRORE: cartella src\Project_Tombola non trovata.
    echo Metti questo file nella cartella principale del progetto ^(quella che contiene src^).
    pause
    exit /b 1
)

findstr /c:"\"localhost\"" "src\Project_Tombola\network\HubConfig.java" >nul 2>nul
if not errorlevel 1 (
    echo ATTENZIONE: in HubConfig.java l'indirizzo e' ancora "localhost".
    echo Funzionera' solo sul tuo PC o sulla stessa rete. Per giocare da reti diverse
    echo metti il tuo IP pubblico o dominio in src\Project_Tombola\network\HubConfig.java
    echo e rilancia questo file.
    echo.
    choice /c SN /m "Continuare comunque? (S = si, N = no)"
    if errorlevel 2 exit /b 1
    echo.
)

rem --- pulizia ---
if exist out rmdir /s /q out
if exist dist rmdir /s /q dist
if exist release rmdir /s /q release
mkdir out
mkdir dist\client
mkdir dist\server

echo [1/4] Compilazione...
javac -encoding UTF-8 -d out src\Project_Tombola\game\*.java src\Project_Tombola\network\*.java src\Project_Tombola\server\*.java src\Project_Tombola\client\*.java
if errorlevel 1 (
    echo ERRORE durante la compilazione.
    pause
    exit /b 1
)

echo [2/4] Creazione dei JAR...
jar --create --file dist\client\TombolaClient.jar --main-class Project_Tombola.client.TombolaClient -C out .
if errorlevel 1 goto :errore
jar --create --file dist\server\TombolaServer.jar --main-class Project_Tombola.server.TombolaServer -C out .
if errorlevel 1 goto :errore

echo [3/4] Creazione degli exe ^(puo' richiedere un minuto^)...
jpackage --type app-image --name TombolaClient --input dist\client --main-jar TombolaClient.jar --main-class Project_Tombola.client.TombolaClient --dest release
if errorlevel 1 goto :errore
jpackage --type app-image --name TombolaServer --input dist\server --main-jar TombolaServer.jar --main-class Project_Tombola.server.TombolaServer --win-console --dest release
if errorlevel 1 goto :errore

echo [4/4] Creazione degli ZIP da distribuire...
powershell -NoProfile -Command "Compress-Archive -Path 'release\TombolaClient' -DestinationPath 'release\TombolaClient.zip' -Force; Compress-Archive -Path 'release\TombolaServer' -DestinationPath 'release\TombolaServer.zip' -Force"
if errorlevel 1 goto :errore

echo.
echo ==================================================
echo   FATTO!
echo ==================================================
echo   Giocatori:   release\TombolaClient.zip
echo   Server hub:  release\TombolaServer.zip
echo.
echo   Gli exe si trovano dentro le cartelle release\TombolaClient
echo   e release\TombolaServer ^(vanno distribuiti con tutta la cartella^).
echo.
start "" "release"
pause
exit /b 0

:errore
echo.
echo ERRORE: la creazione e' fallita. Controlla i messaggi qui sopra.
pause
exit /b 1
