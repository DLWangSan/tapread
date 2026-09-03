@echo off
set DIRNAME=%~dp0
if "%JAVA_HOME%"=="" set JAVA_HOME=D:\java\jdk
"%JAVA_HOME%\bin\java.exe" -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
