#!/usr/bin/env bash
# 云小抄 v3.0.2 —— 在 Linux / macOS / 或任何 x86_64 机器上一键出 APK
#
# 用法：
#   bash 本地一键打包.sh
#
# 前提：
#   - x86_64 的 Linux（Ubuntu / Debian / Fedora 都行）或 macOS
#   - 已装 JDK 17（脚本会检查并提示怎么装）
#   - 磁盘剩余 6GB 以上
#
# 它会自动：
#   1. 检查 JDK 17
#   2. 下载 Android SDK 命令行工具 + platform-36 + build-tools 36.0.0
#   3. 接受许可
#   4. 跑 assembleDebug
#   5. 把 APK 复制到当前目录，命名为 云小抄-v3.0.2-debug.apk

set -u

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
die()  { echo -e "${RED}[X]${NC} $*"; exit 1; }

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
OUT="$ROOT/云小抄-v3.0.2-debug.apk"

echo "=========================================="
echo " 云小抄 v3.0.2 本地打包"
echo " 工程：$ROOT"
echo "=========================================="

# ---------- 1. 架构 ----------
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) ok "架构 $ARCH，官方 aapt2 可直接用" ;;
  arm64|aarch64)
    die "这台机器是 $ARCH。Google 从不发布 arm64 版 aapt2（已核实官方仓库 0 处 aarch64），
   在 ARM 上编译 Android 需要复杂的 qemu 模拟，不建议。
   请换一台 x86_64 电脑，或用 GitHub Actions（见 一键云编译.md）。" ;;
  *) warn "未知架构 $ARCH，继续尝试" ;;
esac

# ---------- 2. JDK ----------
if command -v java >/dev/null 2>&1; then
  JV="$(java -version 2>&1 | head -1)"
  ok "已找到 Java：$JV"
  MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  [ "$MAJOR" = "17" ] || warn "当前不是 JDK 17（AGP 8.13 要求 17）。若编译报 source release 17 错误，请换 17。"
else
  die "没找到 java。请先装 JDK 17：
   Ubuntu/Debian:  sudo apt install -y openjdk-17-jdk-headless
   Fedora:         sudo dnf install -y java-17-openjdk-devel
   macOS:          brew install openjdk@17 && export JAVA_HOME=\$(brew --prefix openjdk@17)"
fi

# ---------- 3. Android SDK ----------
if [ -d "$SDK/platforms/android-36" ] && [ -d "$SDK/build-tools/36.0.0" ]; then
  ok "Android SDK 已就绪：$SDK"
else
  warn "Android SDK 不全，开始安装到 $SDK（约 200MB，需要几分钟）"
  mkdir -p "$SDK/cmdline-tools"

  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "下载 cmdline-tools..."
    ( command -v curl >/dev/null && curl -fL --progress-bar -o /tmp/clt.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip ) \
     || ( command -v wget >/dev/null && wget -q --show-progress -O /tmp/clt.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip ) \
     || die "下载 cmdline-tools 失败，检查网络"
    ( command -v unzip >/dev/null || die "缺少 unzip，请先装：sudo apt install -y unzip" )
    rm -rf /tmp/cltx && unzip -q -o /tmp/clt.zip -d /tmp/cltx
    rm -rf "$SDK/cmdline-tools/latest"
    mv /tmp/cltx/cmdline-tools "$SDK/cmdline-tools/latest"
  fi

  export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platforms;android-36" "build-tools;36.0.0" "platform-tools" || die "SDK 组件安装失败"
  ok "Android SDK 装好了"
fi

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
echo "sdk.dir=$SDK" > "$ROOT/local.properties"
ok "已写 local.properties"

# ---------- 4. 编译 ----------
cd "$ROOT" || die "进不去工程目录"
chmod +x gradlew 2>/dev/null

# 国内网络：Gradle 官方源很慢，换腾讯镜像（只影响这次运行，不改工程文件）
if [ -z "${GRADLE_MIRROR_DONE:-}" ] && ! curl -sS -m 6 -o /dev/null https://services.gradle.org 2>/dev/null; then
  warn "官方 Gradle 源不通，本次改用腾讯镜像"
  export GRADLE_DISTRIBUTION_URL="https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip"
fi

echo
echo "开始编译（首次约 10~20 分钟，要下 Gradle 和依赖，请耐心）..."
echo

./gradlew :app:assembleDebug --no-daemon --stacktrace 2>&1 | tee /tmp/yunx_build.log
RC=${PIPESTATUS[0]}

if [ "$RC" -ne 0 ]; then
  echo
  echo "==================== 编译失败 ===================="
  echo "关键报错："
  grep -E "^e: |FAILURE:|What went wrong|Caused by:" /tmp/yunx_build.log | head -30
  echo "=================================================="
  echo "完整日志：/tmp/yunx_build.log"
  die "把上面那段报错贴给我，我改代码"
fi

# ---------- 5. 收产物 ----------
APK="$(find app/build/outputs/apk -name '*.apk' 2>/dev/null | head -1)"
[ -n "$APK" ] || die "编译成功但没找到 APK，检查 app/build/outputs/apk/"
cp "$APK" "$OUT"
ok "完成：$OUT"
ls -lh "$OUT"
echo
echo "传到手机安装即可（versionCode 70 > 69，可覆盖升级，数据保留）。"
