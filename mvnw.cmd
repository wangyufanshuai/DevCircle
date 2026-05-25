@echo off
setlocal
set MAVEN_VERSION=3.9.11
set MAVEN_HOME=%~dp0.mvn\wrapper\apache-maven-%MAVEN_VERSION%
set MAVEN_ZIP=%~dp0.mvn\wrapper\apache-maven-%MAVEN_VERSION%-bin.zip
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip -OutFile '%MAVEN_ZIP%'; Expand-Archive -Force '%MAVEN_ZIP%' '%~dp0.mvn\wrapper'"
)
"%MAVEN_HOME%\bin\mvn.cmd" %*
