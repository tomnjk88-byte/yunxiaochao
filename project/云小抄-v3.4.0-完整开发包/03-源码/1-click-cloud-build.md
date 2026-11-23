# 云小抄 v2.5.3 — 一键云编译（GitHub Actions）

> 你本地不用装 JDK、不用装 Android Studio、不用跑 gradle。
> 把代码推到 GitHub → 点一下按钮 → 等 5 分钟 → 下载 APK。
> 免费额度：私有仓库每月 2000 分钟，一次约 5~8 分钟，够跑 250 次。

---

## 为什么走这条路

你之前说的「云端容器内存太小老是卡死」——那说的是**我所在的沙箱容器**（1 核 / 可用内存 300~600MB）。
GitHub 的 runner 是 **4 核 / 16GB 内存 / x86_64**，比你的 Windows 笔记本还宽裕，而且
`aapt2` 原生 x86_64 可用（不需要 qemu 模拟），是 Android 官方支持的标准构建环境。

---

## 步骤 1：建一个 GitHub 仓库

1. 打开 <https://github.com/new>
2. `Repository name` 填随意，比如 `yunx-guide`
3. 选 **Private**（私有，别人看不到你的代码）
4. **不要**勾 `Add a README file`（否则会冲突）
5. 点 `Create repository`

---

## 步骤 2：把代码推上去

### 方式 A：用 GitHub Desktop（推荐，不用敲命令）

1. 下载安装 <https://desktop.github.com/>
2. 打开后 `File → Add local repository`
3. 选你的工程文件夹（我给你的那个 `yunx-guide-android-v2.5.1` 解压后的目录）
4. 如果提示 "not a git repository"，点 `create a repository` → 点 `Create repository`
5. 右上角 `Publish repository` → **取消勾选** `Keep this code private` 的反面（即勾选私有）
6. 点 `Publish`

### 方式 B：命令行

在工程根目录（有 `gradlew.bat` 那一层）打开 cmd：

```bat
git init
git add -A
git commit -m "v2.5.3 iOS glass + freeze fix"
git branch -M main
git remote add origin https://github.com/你的用户名/yunx-guide.git
git push -u origin main
```

第一次 push 会弹浏览器让你登录 GitHub，登录完就推上去了。

---

## 步骤 3：打开 Actions，点一下

1. 仓库页面上方点 **`Actions`** 标签
2. 左边列表点 **`Build Debug APK`**
3. 右边有 **`Run workflow`** 按钮 → 绿色按钮点一下
4. 刷新页面，会出现一条黄色的运行记录，点进去看实时日志

大约 **5~8 分钟**后变成绿勾。

---

## 步骤 4：下载 APK

运行详情页最下面 **`Artifacts`** 区域：

- `云小抄-v2.5.3-debug` ← 点这个，下载一个 zip
- 解压得到 `app-debug.apk`
- 传到手机安装（微信传会变成 `.apk.1`，改回 `.apk` 就行）

**注意**：GitHub 的 Artifacts 需要登录才能下载，且**保留 90 天**后自动删除。

---

## 这个 workflow 做了什么

| 步骤 | 说明 |
|---|---|
| 检出代码 | 用 `actions/checkout@v4` |
| 装 JDK 17 | Temurin，AGP 8.13 的硬性要求 |
| 配 Android SDK | runner 预装了 platform-36 / build-tools-36，`licenses` 已接受 |
| 缓存 Gradle | 第二次构建起快很多 |
| `assembleDebug` | 用工程自带的 `guide.keystore` 签名，可直接装 |
| 上传产物 | `app/build/outputs/apk/debug/app-debug.apk` |

---

## 常见报错

| 报错 | 原因与解法 |
|---|---|
| `Permission denied: ./gradlew` | workflow 里已有 `chmod +x ./gradlew`，若还报，检查 `.gitattributes` 里换行符 |
| `Failed to find target with hash string 'android-36'` | runner 镜像版本旧。把 workflow 里的 `android-actions/setup-android` 那段打开（已注释备用） |
| `Could not resolve all files` | 阿里云镜像在 GitHub 上不可达。workflow 已设 `YUNX_USE_MIRROR: 'false'` 走官方源 |
| `Keystore was tampered with` | `guide.keystore` 没推上去。检查 `git status` 里是否有这个文件（`.gitignore` 可能挡了 `*.jks`，但 `.keystore` 通常不挡） |
| 构建 OOM | 不太可能（16GB），若真发生，在 `gradle.properties` 里把 `-Xmx3g` 提到 `-Xmx4g` |

---

## 如果 provider 连不上 GitHub

国内访问 GitHub 有时慢。备选：

- **Gitee（码云）**：`https://gitee.com`，也有 Actions（叫 "Gitee Go"），免费额度更少
- **Coding.net**：腾讯旗下，国内速度快，有免费 CI
- 或者直接翻墙/用加速器推 GitHub

---

## 我这边能帮什么

推送成功、点完 `Run workflow` 之后，**把失败日志里 `FAILURE:` 之后那段贴给我**，我按报错改代码。
这台沙箱的内存跑不动编译，但改代码、看报错、出补丁是我的强项。
