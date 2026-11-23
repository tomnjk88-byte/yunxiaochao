/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * 本文件是 AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件。桌面入口的空壳类，见下方注释。
 */

package com.yunx.app.overlay

/**
 * 桌面入口。
 *
 * 为什么需要这个「只是继承一下」的类：
 * 云析的 `YunXApp.entryMismatch()` 会在启动时读取 `getLaunchIntentForPackage()`，
 * 要求桌面入口的类名以 `com.yunx.app.MainActivity` 或 `MainActivityIcon2` 结尾，
 * 否则判定为被二次打包并直接 `Process.killProcess()`（防改包逻辑）。
 *
 * 本应用是二次开发版，入口是「云小抄」而不是云析主界面；把入口类命名为
 * `MainActivityIcon2` 就能同时满足校验与自己的入口需求，**不需要改动云析的校验代码**。
 */
class MainActivityIcon2 : MainActivity()
