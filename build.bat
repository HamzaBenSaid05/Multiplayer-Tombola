@echo off
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 -d out src\Project_Tombola\game\*.java src\Project_Tombola\network\*.java src\Project_Tombola\server\*.java src\Project_Tombola\client\*.java
jar --create --file TombolaClient.jar --main-class=Project_Tombola.client.TombolaClient -C out .
jar --create --file TombolaServer.jar --main-class=Project_Tombola.server.TombolaServer -C out .
echo Build completata.
pause
