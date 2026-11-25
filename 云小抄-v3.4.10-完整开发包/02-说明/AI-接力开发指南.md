# AI 接力开发指南 · 云小抄 v3.4.10

> 给下一个接手的 AI（或人）看的交接文档。
> 目标：**看懂现状 → 选一条路线 → 改完能自己构建出正确的包**。
> 全程用中文，代码注释也是中文，保持这个习惯。

---

## 一、这是什么

安卓悬浮窗小抄 App「云小抄」，包名 `com.yunx.app.guide`，namespace `com.yunx.app`。
基于开源项目 **云析 YunX**（`https://github.com/CYQawa/YunX`，AGPL-3.0）二次开发，
UI 走 Miuix / SukiSU 那套玻璃卡片风格。

**它不是 Root 管理器**。没有内核伪装、AVC 日志、SUSFS 这些东西，也不需要。
它做的事情：游戏上面挂一个小抄悬浮窗、连招宏、准星、解析下载、换肤。

发布仓库：`https://github.com/tomnjk88-byte/yunxiaochao-Android`

---

## 二、30 秒上手

```bash
cd 03-源码

# 1. 环境：JDK 17 + Android SDK（platform 36 / build-tools 36.0.0）
# 2. 签名变量（release 必须，否则产物是 unsigned）
export YUNX_KEYSTORE_FILE="$PWD/guide.keystore"
export YUNX_KEYSTORE_PASSWORD="guide123456"
export YUNX_KEY_ALIAS="guide"
export YUNX_KEY_PASSWORD="guide123456"

# 3. 构建（首次会下依赖；内存小的机器把堆调小）
./gradlew :app:assembleRelease

# 产物
app/build/outputs/apk/release/app-release.apk
```

本地 `local.properties` 写 `sdk.dir=/你的/Android/Sdk`，**不要提交**。

不想本地装环境：把代码推到 GitHub，仓库里有 `.github/workflows/build-apk.yml`，
在 Actions 里手动触发 `build-apk` 就能下 APK（详见 `1-click-cloud-build.md`）。

---

## 三、代码地图（按功能找文件）

路径都在 `03-源码/app/src/main/kotlin/com/yunx/app/overlay/` 下。

### 界面骨架
| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 主界面容器：顶栏、抽屉开合、页面路由、页签数据、Dock 开关 |
| `ui/GuideDrawer.kt` | 左侧抽屉（页面列表）。`DrawerItem` 在这里定义，`key` 用于自定义图标/文字 |
| `ui/DockNav.kt` | 底部 Dock 胶囊、四个 Dock 按钮、`rememberMenuTapHandler`（菜单键三击） |
| `ui/NavSwitchDialog.kt` | 三击弹出的「导航方式」选择，两张示意图 |
| `ui/UiStyle.kt` | 玻璃组件（GlassCard / GlassRow / GlassSwitch / GlassSectionTitle / GlassHint）、`ThemeTuning`、文本 key 表 |

### 主题系统
| 文件 | 职责 |
| --- | --- |
| `ui/ThemeStudioScreen.kt` | 主题设置全套分页：颜色 / 背景 / 图标文字 / 首页卡片 / 底栏与导航 / 交互手势 / 界面细节 / 主题包 |
| `ui/ThemeStoreScreen.kt` | 主题商店：本机包列表 + SukiSU 在线目录、搜索、标签、导入、R18 过滤 |
| `ui/ThemeStoreRemote.kt` | 在线商店接口：取令牌、拉目录、下包、下预览图、SHA-256 校验 |
| `data/ThemePack.kt` | 本机主题包的存取、封面、`attachCover` 补图 |
| `data/SukisuTheme.kt` | `.sukisu` 解密（AES-GCM）与解析，图片资源提取 |
| `ui/DynamicPalette.kt` / `ui/FrostedGlass.kt` | 取色与毛玻璃 |

### 功能页
| 文件 | 职责 |
| --- | --- |
| `ui/GameAssistSections.kt` | 连招 / 准星 / 帧率悬浮窗等设置行；帧率开关有单飞锁 |
| `ui/GuideHomeScreen.kt` 等 | 小抄首页、解析下载、权限页、我的 |
| `service/FloatingService.kt` | 悬浮窗前台服务：窗口挂载、帧率窗、指令分发（`showFps` / `dispatch`） |
| `data/OverlayPrefs.kt` | 全部本地偏好（主题、Dock 图标、抽屉图标、应用名等） |
| `ui/UpdateNotesData.kt` | App 内更新说明数据，**每版必须在这里加一条** |

需要精确定位时直接搜中文文案 —— 这个项目的文案都是中文，比搜类名快。

---

## 四、版本号规则（必须遵守）

`app/build.gradle.kts` 里两个字段：

- `versionName`：**十进制逢十进一**。`3.4.9 → 3.5.0`，`3.9.9 → 4.0.0`。
  绝不出现 `3.4.10` 之后的 `3.4.11`（本版 `3.4.10` 是历史遗留写法，后续按新规则走 `3.5.0`）。
  等等 —— **本条有例外**：`3.4.10` 已经发出去了，继续沿用三位小数编号也行，
  只要 `versionCode` 一直 +1。二选一，别中途换。
- `versionCode`：**每版 +1，只增不减**。当前 `115`。

改完版本号，同步在 `ui/UpdateNotesData.kt` 最前面加一条（三段式：新增 / 优化 / 修复）。

---

## 五、发版检查清单

发之前逐条过：

1. `versionCode` 比上一版大，`versionName` 正确。
2. **`AndroidManifest.xml` 的权限一个字都不改** —— 现在 17 项。用户明确要求过：
   「不要乱改包的权限」。加权限会导致覆盖安装后行为变化、甚至装不上。
3. 签名证书必须是 `guide.keystore`（alias `guide`，口令 `guide123456`）。
   校验：`apksigner verify --print-certs app-release.apk`，
   SHA-256 应为 `e47cd6b1...cf04`（与历史版本同一把，才能覆盖安装）。
4. 体积：正常在 8.1 MB 左右（R8 + 资源压缩）。**明显变大必须解释加了什么**。
   历史事故：曾误发 debug 包，35 MB 且签名不同，用户装不上。
   —— 永远用 `assembleRelease`，不要用 `assembleDebug` 交付。
5. 权限数量核对：`aapt dump permissions app-release.apk | wc -l` → 应为 18 行（含 package 行）。
6. Git 提交 + 打 tag + 上传 Release（APK + 源码 zip）。

---

## 六、可选开发路线（自由挑一条，或都不走）

### 路线 A：继续补 SukiSU 的「首页卡片」设置
用户上一轮点名要过，但还没做：
- 经典 / 沉浸两种首页卡片
- 状态卡背景图
- 隐藏卡片装饰
- 隐藏「工作中」与版本号
- 状态卡贴纸（最多 5 个，可拖动、旋转、缩放）

入口文件：`ui/GuideHomeScreen.kt`（卡片渲染）+ `ui/ThemeStudioScreen.kt`（设置项）+ `OverlayPrefs`（持久化）。
参考截图在用户对话里（「首页卡片」页）。

### 路线 B：主题格式自研（.yxskin）
现在只能读 `.sukisu`。可以定义自己的包格式：`theme.json` + `background` + `card_background`
+ `bottom_bar_icon_*` + 自定义文本表 + 贴纸配置，顺便支持「导出主题文件」。
入口：`data/SukisuTheme.kt`（学会解析后照着写一个 Writer）、`ui/ThemeStudioScreen.kt` 主题包页。

### 路线 C：补完交互与细节
- 界面缩放（80% ~ 120%），需要处理固定布局溢出
- 图标微调（自定义图片的圆角 / 大小）
- 预设配色方案（Monet 之外的几套）
入口：`ui/UiStyle.kt` + `ThemeTuning`。

### 路线 D：修 Bug / 优化性能
- 低端机上玻璃层的重绘开销
- 主题商店图片缓存
- 悬浮窗被系统回收后的恢复逻辑

### 路线 E：完全自由
只要遵守第五节的三条硬约束（版本号、权限、签名），怎么改都行。
改完请更新 `02-说明/云小抄-更新说明-完整版.md` 和 `00-开始这里.md`。

---

## 七、硬约束（别做）

1. **不要加 `guardedConflicting` 之类的权限或新的危险权限** —— 用户明确反对改权限。
2. **不要用 debug 包交付**，不要改 `applicationId`。
3. **不要引入体积大的依赖**（比如整套 Material Icons Extended 已经在了，别再引第二套 UI 库）。
   目标是守住 20 MB 以内，现在 8 MB。
4. **不要删 `app/libs/gopeed-classes.jar`** —— 缺它 `mergeReleaseNativeLibs` 直接失败。
5. 改完必须能 `assembleRelease` 成功，且 `apksigner verify` 通过。
6. 涉及 SukiSU 的代码只做兼容实现，不复制其源码；关于页里已有引用声明，新增引用要补进去。

---

## 八、已知坑

- **构建内存**：默认 `org.gradle.jvmargs=-Xmx1536m`。在 4GB 的小容器里构建时：
  `--max-workers=1`，`kotlin.compiler.execution.strategy=in-process`，
  并跳过 `lintVital`（`android.disableLintVitalCheck=true`）。
  正常机器可以改回 `-Xmx3g`。
- **Gradle wrapper 指向腾讯镜像**（`gradle-8.14.3-bin.zip`）。境外网络下如果下载超时，
  改 `gradle/wrapper/gradle-wrapper.properties` 或用本机已装 Gradle 8.14.x。
- **Java 的网络代理**：`JAVA_TOOL_OPTIONS` 里配 `-Dhttp.proxyHost` 等，Java 不读系统代理变量。
- **`.sukisu` 包内没有预览图**，商店封面是独立文件（`previews/`），拿图要带
  `X-Sukisu-Theme-Token`，否则 401。
- **R8**：反射/序列化类在 `app/proguard-rules.pro` 里 keep；新增反射调用记得补规则。
- 主题包图片存在 App 私有目录，卸载即失。

---

## 九、当前能力清单（v3.4.10）

已实现：
- 悬浮窗小抄 / 连招宏 / 准星 / 解析下载 / 帧率窗
- 两套导航：左侧抽屉、底部 Dock（互斥；菜单键三击切换）
- 主题：背景图、卡面图、强调色（吸色）、毛玻璃、圆角、透明度、液态玻璃
- 自定义：应用图标（桌面快捷方式）、应用名、顶栏标题、底栏四个标签名/图标、抽屉七项名称/图标
- 主题商店：SukiSU 在线目录（带 R18 过滤）、搜索/标签/排序、导入 `.sukisu`、预览图缓存
- 主题包导入 / 本机包管理 / 封面补全
- 更新说明内置

未实现（可选）：
- 首页卡片那一整页设置（见路线 A）
- 导出主题文件（见路线 B）
- 界面缩放、图标微调（见路线 C）

---

## 十、给 AI 的第一句话

如果你是接手这个项目的 AI，建议先回一句：

> 我看了交接文档，当前 v3.4.10，能构建、签名和权限约束清楚。
> 我可以按路线 A/B/C 继续，也可以听你的具体需求 —— 你想先做哪个？

然后把选择权交给用户。不要一上来就大改。
