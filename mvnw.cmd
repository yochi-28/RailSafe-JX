@echo off
setlocal
set MAVEN_VERSION=3.9.9
set BASE_DIR=%~dp0
where mvn >nul 2>nul
if not errorlevel 1 goto use_installed
set WRAPPER_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%
set MAVEN_HOME=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%
if exist "%MAVEN_HOME%\bin\mvn.cmd" goto run_wrapper
if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip'; $archive='%WRAPPER_DIR%\maven.zip'; Invoke-WebRequest -Uri $url -OutFile $archive; Expand-Archive -Path $archive -DestinationPath '%WRAPPER_DIR%' -Force; Remove-Item $archive"
if errorlevel 1 exit /b 1
:run_wrapper
call "%MAVEN_HOME%\bin\mvn.cmd" -f "%BASE_DIR%pom.xml" %*
exit /b %ERRORLEVEL%
:use_installed
call mvn -f "%BASE_DIR%pom.xml" %*
exit /b %ERRORLEVEL%
