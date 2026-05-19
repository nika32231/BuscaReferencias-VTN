@echo off
REM Script para compilar el proyecto con Maven

echo.
echo ===== COMPILACION DEL PROYECTO =====
echo.

REM Ir al directorio del proyecto
cd /d "%~dp0"

REM Intentar compilar con Maven Wrapper
echo Compilando proyecto...
echo.

call mvnw clean compile

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ===== COMPILACION EXITOSA =====
    echo.
    echo Cambios implementados correctamente.
    echo Puedes proceder a ejecutar el proyecto.
    echo.
    pause
) else (
    echo.
    echo ===== ERROR EN COMPILACION =====
    echo.
    echo Por favor verifica:
    echo 1. Que Java este instalado y JAVA_HOME este configurado
    echo 2. Que todos los archivos fueron modificados correctamente
    echo 3. Los mensajes de error arriba
    echo.
    pause
)

