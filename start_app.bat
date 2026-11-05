@echo off
set "JAVA_HOME=C:\Users\Muhammad.Rafih\Apps\jdk-21"
set "PATH=%JAVA_HOME%\bin;C:\Users\Muhammad.Rafih\Apps\apache-maven-3.9.16\bin;%PATH%"

echo =======================================================
echo 1. Starting GLiNER Python Knowledge Graph Service...
echo =======================================================
start "GLiNER Python Service" cmd /k "python gliner_service.py"

echo Waiting 5 seconds for GLiNER service to initialize...
timeout /t 5 /nobreak > nul

echo =======================================================
echo 2. Starting Spring Boot Application (Java 21)...
echo =======================================================
cd spring-backend
java -jar target\hfe-ner-app-0.0.1-SNAPSHOT.jar

