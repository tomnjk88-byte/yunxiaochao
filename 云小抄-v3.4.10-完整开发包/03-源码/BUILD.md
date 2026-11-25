# 云小抄 安卓端 v2.5.1 — 完整可编译工程

这是**完整 Android 工程**，不是代码片段：Gradle Wrapper、`settings.gradle.kts`、根 `build.gradle.kts`、`app/build.gradle.kts`、`AndroidManifest.xml`、`res/`、`assets/` 全都在，403 个 `.kt` 齐全，`app/libs/gopeed-classes.jar` 本地依赖也在。

```
yunx-guide-android-v2.5.1/
├── gradlew  gradlew.bat               # 包装器脚本
├── gradle/wrapper/                    # gradle-wrapper.jar + .properties（Gradle 9.0.0）
├── gradle/libs.versions.toml          # 版本目录（AGP 8.13.0 / Kotlin 2.1.0）
├── settings.gradle.kts                # 仓库与模块声明
├── build.gradle.kts                   # 根构建脚本
├── gradle.properties
├── guide.keystore                     # debug 签名证书（密码见下）
└── app/
    ├── build.gradle.kts               # compileSdk 36 / minSdk 24 / targetSdk 34
    ├── libs/gopeed-classes.jar        # 下载内核的本地 jar，缺它编不过
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/java|kotlin/          # 403 个 .kt
        ├── main/res/                  # 50 个资源文件
        ├── main/assets/crosshairs/    # 17 张内置准星
        ├── test/  androidTest/
```

---

## 一、环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17** | AGP 8.13 硬性要求，JDK 21 也能编，JDK 11 及以下直接失败 |
| Gradle | **不用装** | `gradlew` 首次运行会自己下载 `gradle-9.0.0-bin.zip`（约 130 MB，需联网） |
| Android SDK | **Platform 36 + Build-Tools 36.0.0** | `compileSdk = 36`，缺了会报 `failed to find target with hash string 'android-36'` |
| 磁盘 | ≥ 3 GB | 工程 13 MB + 依赖缓存 + Gradle 缓存 |
| 内存 | **≥ 8 GB 物理内存** | Gradle 堆已设 3072 MB，另需留给 Kotlin 编译守护进程 |
| CPU 架构 | **x86_64 / arm64 都行** | 工程里没有任何 `.so`，不碰 NDK，不分架构 |

## 二、构建

```bash
# 1. 指到你的 SDK（或设 ANDROID_HOME 环境变量，二选一）
echo "sdk.dir=/opt/android-sdk" > local.properties

# 2. 出可安装的 debug 包（已用 guide.keystore 签名，可直接装手机）
./gradlew assembleDebug

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

只想验证能不能编过：

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

## 三、签名

`app/build.gradle.kts` 里 debug 签名写死了本工程的证书：

```kotlin
storeFile = file("../guide.keystore")
storePassword = "guide123456"
keyAlias = "guide"
keyPassword = "guide123456"
```

所以 `assembleDebug` 出来的包**自带签名、可直接安装**，不需要你另外配。applicationId 是 `com.yunx.app.guide`，与官方云析不冲突，可共存。

release 包默认**不签名**（产物 `app-release-unsigned.apk`）。要签就导出四个环境变量再构建：

```bash
export YUNX_KEYSTORE_FILE=/path/to/your.jks
export YUNX_KEYSTORE_PASSWORD=你的库口令
export YUNX_KEY_ALIAS=你的别名
export YUNX_KEY_PASSWORD=你的密钥口令
./gradlew assembleRelease
```

## 四、这份工程相对原始工程的改动（只有 2 行，都是构建参数）

| 文件 | 原值 | 现值 | 原因 |
|---|---|---|---|
| `gradle.properties` | `org.gradle.jvmargs=-Xmx2048m` | `-Xmx3072m -XX:MaxMetaspaceSize=768m` | 403 个 kt + Compose 编译插件，2 GB 堆在 `compileDebugKotlin` 阶段会被 OOM kill |
| `gradle.properties` | `android.builder.sdkDownload=false` | 已注释掉 | 这一行是给离线沙箱准备的；在能联网的机器上留着它，缺 SDK 组件时 AGP 不会自动补装，会直接报错 |

代码本身**一行没动**，与产出 v2.5.1 APK（versionCode 69）的源码完全一致。

## 五、源码在哪（按功能）

| 功能 | 文件 | 行数 |
|---|---|---|
| 主入口与整体装配（权限、SAF 目录授权、内置准星落地、启动路由） | `app/src/main/kotlin/com/yunx/app/overlay/MainActivity.kt` | 2031 |
| 悬浮窗服务（WindowManager、拖动缩放、最小尺寸、锁定穿透、收球） | `.../overlay/service/FloatingService.kt` | 2460 |
| 悬浮窗界面（分类、网格、翻页、缩放滑杆） | `.../overlay/ui/FloatingContent.kt` | 1314 |
| 入口卡片入场动画（v2.5.1 修「按钮点不动」的地方） | `.../overlay/ui/TileEntrance.kt` | 431 |
| 本地小抄目录扫描与快照持久化 | `.../overlay/data/LocalGuideRepository.kt` | 395 |
| 缩略图磁盘缓存（键 = 路径+修改时间+大小） | `.../overlay/data/LocalThumbCache.kt` | 221 |
| 统一取图入口（本地走缩略图缓存，网盘走内存 LRU） | `.../overlay/data/MediaRepository.kt` | 1065 |
| 一键下载全部（递归列举、并发、`.part` 原子落盘、代次防串） | `.../overlay/data/GuideDownloader.kt` | 734 |
| 下载页 UI（一键下载按钮、下载目录完整路径） | `.../overlay/ui/DownloadTab.kt` | 326 |
| 下载根目录、目录选择与文件索引 | `.../overlay/data/ImageVault.kt` | 1485 |
| 准星页 + 内置准星横向选择条 | `.../overlay/ui/CrosshairScreen.kt` | 732 |
| 17 个内置准星的清单与元数据 | `.../overlay/data/BuiltinCrosshairs.kt` | 100 |
| 主题与玻璃质感组件 | `.../overlay/ui/GlassTheme.kt` | 909 |
| 目录抽屉（v2.5.1 把边缘热区 28dp 收到 12dp） | `.../overlay/ui/GuideDrawer.kt` | 546 |
| 开屏过场（v2.5.1 加淡出兜底） | `.../overlay/ui/SplashOverlay.kt` | 264 |

准星图片：`app/src/main/assets/crosshairs/`，17 张 PNG。

## 六、常见报错对照

| 报错 | 原因 | 处理 |
|---|---|---|
| `compileDebugKotlin` 被 OOM kill / 进程无故消失 | 机器内存不足或堆太小 | `./gradlew assembleDebug -Dorg.gradle.jvmargs="-Xmx4096m" -Dorg.gradle.workers.max=2`，机器至少 8 GB |
| `Failed to find target with hash string 'android-36'` | 缺 Platform 36 | `sdkmanager "platforms;android-36"` |
| `Build-Tools 36.0.0 missing` | 缺 build-tools | `sdkmanager "build-tools;36.0.0"` |
| `Unsupported class file major version 65` / `invalid source release: 17` | JDK 不是 17 | `export JAVA_HOME=/path/to/jdk-17` |
| `SDK location not found` | 没有 `local.properties` 也没设 `ANDROID_HOME` | 二选一配上 |
| `Could not resolve com.android.application:8.13.0` | 拉不到依赖 | 检查网络；`settings.gradle.kts` 内已带阿里云镜像开关，`export YUNX_USE_MIRROR=false` 可切官方源 |
| `Could not find gopeed-classes.jar` | 解压时漏了 `app/libs/` | 重新完整解压 |

## 七、这份工程不是「还原出来的」

`app/src` 与生成 v2.5.1 APK 的构建工程做过 `diff -rq`，**差异 0 处**；`versionCode = 69`、`versionName = "2.5.1-guide"` 与已发布 APK 一致。所以它能编出和 `云小抄-安卓-v2.5.1.apk` 行为相同的包。
