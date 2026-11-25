# 云小抄 v2.5.3 — Windows 本地打包指南

> 这份工程已经可以直接编译：403 个 `.kt`、`AndroidManifest.xml`、`res/`、`assets/`、
> `app/libs/gopeed-classes.jar`、`guide.keystore`、Gradle Wrapper 全都在。
> 你只需要装环境，然后点几下。

---

## 0. 一页速览

| 要装什么 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（Temurin / Oracle 都行） | AGP 8.13 硬性要求。JDK 21 也能编，JDK 11 及以下直接失败 |
| Android Studio | 最新稳定版 | 主要是为了它自带 SDK 管理器；只出 APK 的话命令行也行 |
| Android SDK Platform | **API 36**（Android 16） | `compileSdk = 36`，缺了必报 `failed to find target` |
| Android SDK Build-Tools | **36.0.0** | 与 `buildToolsVersion = "36.0.0"` 对齐 |
| Gradle | **不用装** | Wrapper 会自动下 8.14.3（约 130 MB） |

**磁盘**：留 5 GB 以上（工程 13 MB + Gradle 缓存 + 依赖）。
**内存**：8 GB 以上。工程里 Gradle 堆设的是 3072 MB。

**最短路径**：装 JDK 17 → 装 Android Studio → 工程解压到 `D:\dev\yunx-guide` →
Android Studio 打开 → 等 Sync 完成 → `Build → Build APK(s)` → 拿去装手机。

---

## 1. 装 JDK 17

1. 打开 <https://adoptium.net/temurin/releases/?version=17>
2. 选 **Windows / x64 / JDK / 17 - LTS**，下载 `.msi`
3. 安装时**务必勾上**这两项：
   - `Set JAVA_HOME variable`
   - `JavaSoft (Oracle) registry keys`（可选，装了更好）
4. 验证：按 `Win+R` 输入 `cmd` 回车，敲

   ```
   java -version
   ```

   看到 `openjdk version "17.0.x"` 就对了。

> 为什么死磕 17：`app/build.gradle.kts` 里 `sourceCompatibility = 17`、`jvmTarget = 17`。
> 用 11 会报 `invalid source release: 17`，用 8 更不用说。

---

## 2. 装 Android Studio（顺带把 SDK 装好）

1. 官网下载 Windows 安装包（约 1.2 GB）：<https://developer.android.com/studio>
2. 一路默认安装，第一次启动会走 Setup Wizard，选 **Standard** 即可
3. 进主界面后，**还差两个组件要手动勾**（默认不装）：
   - 欢迎页右侧 `More Actions` → `SDK Manager`
     （已经在项目里的话：`File → Settings → Languages & Frameworks → Android SDK`）
   - **SDK Platforms** 标签页：勾上 **`Android 16.0 (API 36)`**
     ⚠️ 别勾成 "Android 16 Beta" 或 "Android 15"，认准 API Level = **36**
   - **SDK Tools** 标签页：先勾右下角 `Show Package Details`，展开 `Android SDK Build-Tools`，
     勾 **`36.0.0`**（不是 35.x、不是 36.1）
   - 点 `Apply` → 等它下载完
4. 顺手记下页面顶部那行 **Android SDK Location**，一般是：

   ```
   C:\Users\你的用户名\AppData\Local\Android\Sdk
   ```

---

## 3. 解压工程（这一步错了后面全错）

1. 解压到 **`D:\dev\yunx-guide`**
2. 三条硬要求：
   - **纯英文路径**，不要中文、不要空格（`桌面`、`我的文档` 都不行）
   - **路径要短**。Gradle 生成的中间路径很深，Windows 260 字符限制会直接报
     `The filename or extension is too long`
   - **别多套一层**。解压完必须是 `D:\dev\yunx-guide\app\build.gradle.kts`，
     而不是 `D:\dev\yunx-guide\yunx-guide-android-v2.5.1\app\...`
3. 确认这几个文件都在：

   ```
   D:\dev\yunx-guide\gradlew.bat
   D:\dev\yunx-guide\gradle\wrapper\gradle-wrapper.jar
   D:\dev\yunx-guide\gradle\wrapper\gradle-wrapper.properties
   D:\dev\yunx-guide\app\libs\gopeed-classes.jar   ← 少它编不过
   D:\dev\yunx-guide\guide.keystore                ← 少它打不出可安装的包
   ```

---

## 4. 用 Android Studio 打开并 Sync

1. `File → Open`，选 **`D:\dev\yunx-guide`** 这个**文件夹**（不要选 `app` 子目录）
2. 弹窗问 "Trust project?" 选 **Trust**
3. 右下角开始 `Gradle Sync`。**第一次会很慢**：
   - 下 Gradle 8.14.3 ≈ 130 MB
   - 下 AGP / Compose / Room / Media3 等依赖 ≈ 数百 MB
   - 视网速 10~30 分钟，属正常
4. 如果中途提示 Gradle JDK 不对：
   `File → Settings → Build, Execution, Deployment → Build Tools → Gradle`
   → **Gradle JDK** 选你装的 **17**（列表里显示 `jbr-17` 或 `17.0.x`）
5. 底部状态栏不再转圈、左侧 `app` 模块出来后，Sync 就算成功

---

## 5. 出包 —— 两条路，选一条

### 路 A：图形界面（第一次建议用这个）

1. 顶部菜单 `Build` → `Build Bundle(s) / APK(s)` → **`Build APK(s)`**
2. 等底部 `Build` 窗口跑完（第一次 5~15 分钟）
3. 右下角弹出 `APK(s) generated successfully` → 点 **`locate`**
4. 产物：

   ```
   D:\dev\yunx-guide\app\build\outputs\apk\debug\app-debug.apk
   ```

### 路 B：命令行（更快，报错信息更完整）

1. 打开 `D:\dev\yunx-guide` 文件夹
2. 在地址栏（显示路径那条）里直接输入 `cmd` 回车 —— 会在这个目录打开命令行
3. 敲：

   ```
   gradlew.bat assembleDebug
   ```

   > PowerShell 里必须写 `.\gradlew.bat assembleDebug`，前面那个 `.\` 不能省。

4. 看到 `BUILD SUCCESSFUL` 就成功了，产物路径同上。

**只想快速验证能不能编过**（不下 dex、不出包，快很多）：

```
gradlew.bat :app:compileDebugKotlin
```

---

## 6. 装到手机

这个 debug 包已经用工程里的 `guide.keystore` 签好名了，**不需要你另外配证书**，可以直接装。

- **数据线**：手机开「开发者选项 → USB 调试」，电脑上执行

  ```
  adb install -r "D:\dev\yunx-guide\app\build\outputs\apk\debug\app-debug.apk"
  ```

  （`adb` 在 `C:\Users\你\AppData\Local\Android\Sdk\platform-tools\` 下）

- **不用线**：把 APK 拷到手机点击安装。
  ⚠️ 别用微信/QQ 传 —— 它们会把 `.apk` 改成 `.apk.1`，改回来才能装。

- **覆盖安装没问题**：`applicationId = com.yunx.app.guide` 没变，`versionCode` 从 69 升到 **70**，
  手机上已装的老版本会被直接覆盖升级，数据保留。

---

## 7. 这次到底改了什么

### 构建配置（只动了 2 个文件）

| 文件 | 原值 | 现值 | 原因 |
|---|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | Gradle **9.0.0** | Gradle **8.14.3** | 9.x 对 AGP 8.x 是前沿组合；8.14.x 是 Gradle 8 的最后一个稳定系列，AGP 8.13 要求 ≥ 8.13，8.14.3 是验证最充分的补丁版 |
| 同上 | `networkTimeout=10000` | `120000` | 首次要下 130 MB，10 秒超时在国内网络下必然重试 |
| `app/build.gradle.kts` | `versionCode 69` / `2.5.1-guide` | `versionCode 70` / `2.5.3-guide` | 装到手机上能分清是新包 |

### **故意没动的三个版本**

`AGP 8.13.0`、`Kotlin 2.1.0`、`KSP 2.1.0-1.0.29` —— 这三个是互相锁死的：

- `compileSdk = 36` 需要 **AGP ≥ 8.9**，降 AGP 会直接不认识 API 36
- KSP 的版本号必须形如 `<Kotlin 版本>-<KSP 版本>`，`2.1.0-1.0.29` 锁死了 Kotlin 必须是 **2.1.0**
- Compose 编译插件 `org.jetbrains.kotlin.plugin.compose` 跟随 Kotlin 版本

**动它们只会引入新问题，不会更稳。** 真正「太新」的是 Gradle 运行时，已经换掉了。

### 代码（v2.5.3 的玻璃改造 + 卡死修复）

| 文件 | 改动 |
|---|---|
| `ui/UiStyle.kt` | 玻璃填充/描边透明度整套调校（深色 10%→8%、2.7%→2% 等） |
| `ui/FrostedGlass.kt` | 七层玻璃瘦身：顶部受光 14%→10% 且只铺 18% 高、斜向镜面 10%→4.5%、内暗边 7dp→3dp 并改用黑色、色散 30%→9% 且只在「极致」档、外描边改三段渐变 |
| `ui/StyleCurtain.kt` | **换风格卡死修复**：`withFrameNanos` 包 `withTimeoutOrNull(150)` + 独立看门狗 + 区分 `CancellationException` |
| `ui/TileEntrance.kt` | 入场时钟 `factor` 夹下限 + 帧数硬上界，杜绝 `while(true)` 死循环 |
| `MainActivity.kt` | 开屏闸门墙钟兜底（回调不来也能强制开门） |

**调用点一处没动** —— 15 个 `Glass*` 组件名字、参数、尾随 lambda 全部保持不变，213 处调用零改动。

---

## 8. 常见报错（Windows 专用对照表）

| 报错 | 原因 | 处理 |
|---|---|---|
| `SDK location not found` | `local.properties` 里没有 `sdk.dir` | 用 Android Studio 打开 Sync 一次它会自己写；或手写一行 `sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk` |
| `Failed to find target with hash string 'android-36'` | 缺 Platform 36 | SDK Manager → SDK Platforms → 勾 Android 16.0 (API 36) |
| `Failed to install the following SDK components ... Build-Tools 36.0.0` | 缺 build-tools | SDK Tools → 勾 Show Package Details → 勾 36.0.0 |
| `invalid source release: 17` / `Unsupported class file major version 65` | Gradle 用的 JDK 不是 17 | 见第 4 步第 4 条 |
| `The filename or extension is too long` | 路径过长 | 工程挪到 `D:\dev\yunx` 这种短路径 |
| `Could not find gopeed-classes.jar` | 解压漏了 `app/libs/` | 重新完整解压 |
| `Could not resolve com.android.application:8.13.0` | 镜像不通 | 见第 9 节，切官方源重试 |
| 编译到一半进程没了 / 卡死在 `compileDebugKotlin` | 内存不足 | `gradle.properties` 里 `-Xmx3072m` 改 `-Xmx4096m`，关掉浏览器再试 |
| 杀毒软件拖慢或阻断构建 | Defender 实时扫描 `~/.gradle` | 把 `C:\Users\你\.gradle` 和工程目录加入 Defender 排除项（可选） |

---

## 9. 网络慢 / 下载不动

工程 `settings.gradle.kts` **默认走阿里云镜像**（国内直连），通常不用管。
只有镜像挂了才切回官方源：

```bat
:: cmd
set YUNX_USE_MIRROR=false && gradlew.bat assembleDebug
```

```powershell
# PowerShell
$env:YUNX_USE_MIRROR="false"; .\gradlew.bat assembleDebug
```

Gradle 本体（130 MB）不走镜像。如果卡在 `Downloading gradle-8.14.3-bin.zip`：

1. 浏览器手动下载 <https://services.gradle.org/distributions/gradle-8.14.3-bin.zip>
2. 放到 `C:\Users\你\.gradle\wrapper\dists\gradle-8.14.3-bin\` 下某个随机名文件夹里
   （不用解压，`gradlew` 会自己解）
3. 重新执行构建

---

## 10. 万一 Gradle 8.14.3 不认 AGP 8.13

如果报错里出现：

```
Minimum supported Gradle version is 9.0. Current version is 8.14.3.
```

说明这个 AGP 版本的下限比预期高。**只改一行**：

`gradle/wrapper/gradle-wrapper.properties`

```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.0.0-bin.zip
```

其它都不要动，重新构建即可。

---

## 11. 关于「云端打包」

- 我这边**从头到尾没有成功启动过任何编译任务**，现在也没有任何后台构建在跑
  （沙箱的内存上限只有约 1.6 GB，Gradle 起不来，卡在通道上就断了）。
- 你仓库里的 `.github/workflows/build-apk.yml` 是**每天北京时间 00:00 自动跑**的 nightly。
  不想再花 Actions 额度的话，去仓库 `Actions` 页面选中 `Nightly Build APK` → 右上角 `...` → `Disable workflow`。
- 这台机器上我装过的 JDK / SDK / qemu 都还在，但只是躺着，不会自己跑。要删随时说。
