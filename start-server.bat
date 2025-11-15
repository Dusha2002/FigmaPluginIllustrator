@echo off
setlocal

REM Определяем корень проекта относительно BAT-файла
set "PROJECT_DIR=%~dp0"

REM Переходим в директорию server-java и запускаем Spring Boot через Maven Wrapper
pushd "%PROJECT_DIR%server-java" >nul
call mvnw.cmd spring-boot:run
popd >nul

endlocal
