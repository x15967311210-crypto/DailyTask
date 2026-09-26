# 版本更新日志

> 提交范围: `66c2177c` → `02c9766` | [返回 README](README.md)

## 🐛 修复 (Bug Fixes)

### 远程指令

- **修复远程指令解析未识别折叠通知的问题** — 原实现要求指令必须以 `DT#` 开头，但微信多条消息会折叠合并成一条通知（正文形如 `[4条]昵称: DT#执行任务`），导致 `startsWith` 判断失败、指令被静默丢弃。现改为查找 `DT#` 前缀位置并截取其后的指令正文，兼容折叠通知格式。

---

## 🔧 优化 (Improvements)

### UI 与样式

- **更新设置页图标控件及文案** — 目标应用图标由 `ShapeableImageView` + `RoundedStyle` 改为 `ImageFilterView` + `roundPercent`，并精简"返回截图"开关的标题与说明文案
- **移除未使用的 `RoundedStyle` 样式** — 删除 `styles.xml` 及不再引用的圆角样式

### 代码清理

- **调整配置常量格式** — 格式化 `Constant.kt` 中 `REMOTE_CLOCK_IN_CAPTURE_KEY` 常量定义

---

## 🔕 隐蔽性 (Stealth)

### 应用名与文案

- **应用显示名改为「快捷指令」** — `app_name` 与通知监听服务名（`快捷指令通知监听服务`）一并中性化，桌面图标、应用管理、通知使用权列表里不再出现 `DailyTask`
- **通知渠道与正文中性化** — 渠道名改为「后台同步」「屏幕共享」，正文改为「正在同步数据」「正在共享屏幕」，去掉「为保证程序正常运行，请勿移除此通知」这类暴露用途的措辞

### 通知降噪

- **两个前台服务通知渠道降到 `IMPORTANCE_MIN`** — 配合 `PRIORITY_MIN` + `setShowWhen(false)`，状态栏不再常驻图标；渠道的 importance 一经创建不可修改，因此加了「检测到不一致 → 删除重建」的逻辑

### 悬浮窗收敛

- **空闲状态完全隐藏** — 只有「倒计时进行中」且「未显示伪装息屏蒙层」时才可见，App 启动后不再默认常驻显示；
  倒计时结束由 `TaskScheduler` / `MainActivity` 主动发 `updateTime(0)` 收尾，另有「停发 tick 超过 5s 自动收起」的超时兜底
- **修复隐藏时仍会吞掉点击的问题** — 不可见时同步置 `FLAG_NOT_TOUCHABLE`（`alpha=0` 只影响绘制，不影响触摸命中）
- **内存采样 1s → 30s**（省电模式 60s）

### 其他

- **安装包名（`applicationId`）改为 `com.quickcommand.app`** — 源码 `namespace` 保持 `com.pengxh.daily.app` 不动。
  ⚠️ 包名变更等于换了一个新应用：**无法覆盖安装旧版本**，需先卸载旧版，悬浮窗/通知使用权/电池优化等权限与任务配置均需重新授权填写
- **不进「最近任务」** — 6 个 Activity 全部加 `android:excludeFromRecents="true"`
