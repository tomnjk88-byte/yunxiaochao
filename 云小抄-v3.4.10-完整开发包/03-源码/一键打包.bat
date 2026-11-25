@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  云小抄 v3.0.2 —— Windows 一键出 APK
REM
REM  用法：双击本文件即可
REM
REM  前提：
REM    1. 装了 JDK 17（https://adoptium.net/temurin/releases/?version=17
REM       安装时务必勾 "Set JAVA_HOME variable"）
REM    2. 装了 Android Studio，并在 SDK Manager 里装了
REM       Android 16.0 (API 36) 和 Build-Tools 36.0.0
REM    3. 工程放在纯英文、无空格的路径下，比如 D:\dev\yunx-guide
REM ============================================================

cd /d "%~dp0"

echo ==========================================
echo   云小抄 v3.0.2 Windows 本地打包
echo   当前目录：%CD%
echo ==========================================
echo.

REM ---------- 1. 检查路径 ----------
echo %CD% | findstr /R "[^ -~]" >nul
if not errorlevel 1 (
    echo [警告] 当前路径含中文或空格：%CD%
    echo       建议把工程移到 D:\dev\yunx-guide 再跑，否则可能报 filename too long
    echo.
    pause
)

REM ---------- 2. 检查 JDK ----------
where java >nul 2>&1
if errorlevel 1 (
    echo [X] 没找到 java。
    echo     请先装 JDK 17：https://adoptium.net/temurin/releases/?version=17
    echo     安装时务必勾选 "Set JAVA_HOME variable"
    pause
    exit /b 1
)
echo [OK] Java 版本：
java -version 2>&1
echo.

if "%JAVA_HOME%"=="" (
    echo [警告] JAVA_HOME 没设置。Android Studio 通常能自己找到，但命令行构建可能失败。
    echo        解决：设 JAVA_HOME 指向 JDK 17 安装目录，例如
    echo        setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
    echo.
)

REM ---------- 3. 找 Android SDK ----------
if not "%ANDROID_HOME%"=="" (
    set "SDK=%ANDROID_HOME%"
) else if not "%ANDROID_SDK_ROOT%"=="" (
    set "SDK=%ANDROID_SDK_ROOT%"
) else (
    set "SDK=%LOCALAPPDATA%\Android\Sdk"
)

if not exist "%SDK%\platforms\android-36" (
    echo [X] 没找到 %SDK%\platforms\android-36
    echo     请打开 Android Studio -^> Settings -^> Languages ^& Frameworks -^> Android SDK
    echo     在 SDK Platforms 里勾 "Android 16.0 (API 36)"
    echo     在 SDK Tools 里勾 "Show Package Details" 后选 Build-Tools 36.0.0
    echo.
    pause
    exit /b 1
)
if not exist "%SDK%\build-tools\36.0.0" (
    echo [X] 没找到 build-tools 36.0.0，同上方式安装
    pause
    exit /b 1
)
echo [OK] Android SDK：%SDK%
echo sdk.dir=%SDK:\=\\%> local.properties
echo [OK] 已写 local.properties
echo.

REM ---------- 4. 编译 ----------
echo 开始编译（首次约 10~20 分钟，要下 Gradle 约 130MB 和依赖数百 MB）
echo 请耐心等，别关窗口。
echo.

call gradlew.bat :app:assembleDebug --no-daemon --stacktrace
if errorlevel 1 (
    echo.
    echo ==================== 编译失败 ====================
    echo 请把上面最后 30 行报错（尤其是 e: 开头和 FAILURE: 之后的内容）发给我
    echo ==================================================
    pause
    exit /b 1
)

REM ---------- 5. 收产物 ----------
set "APK="
for /r "app\build\outputs\apk" %%f in (*.apk) do if not defined APK set "APK=%%f"

if "%APK%"=="" (
    echo [X] 编译成功但没找到 APK
    pause
    exit /b 1
)

copy /y "%APK%" "yunxiao-v3.0.2-debug.apk" >nul
echo.
echo ==========================================
echo   完成！
echo   产物：%CD%\yunxiao-v3.0.2-debug.apk
echo ==========================================
explorer /select,"%CD%\yunxiao-v3.0.2-debug.apk"
pause
