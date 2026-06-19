# 更新日志

## [Unreleased]

### 优化 (2026-06-19)

#### UI/UX 改进
- **颜色系统重构**
  - 优化背景色：`#F3F1F2` → `#F7F8FA`（更干净明亮）
  - 优化卡片背景：`#FAFAFA` → `#FFFFFF`（纯白，提升层次）
  - 优化文本颜色层次：主文本 `#1E1E2E`，次要 `#6B7280`，辅助 `#9CA3AF`
  - 优化主色调：`#2287F2` → `#3B82F6`（更现代的蓝色）
  - 优化状态颜色：Stable `#10B981`，Warning `#F59E0B`，Danger `#EF4444`

- **布局间距优化**
  - 统一卡片间距为 12dp（更紧凑）
  - 优化卡片内部 padding：20-22dp 水平，14-18dp 垂直
  - 分段控件高度：60dp → 52dp（更精致）
  - 轴向卡片高度：60dp → 72dp（更易点击）

- **圆角优化**
  - 卡片圆角：22dp → 16dp（更现代）
  - 分段控件圆角：18dp → 12dp
  - 激活状态圆角：14dp → 10dp

- **文本层次优化**
  - 标题：30sp → 28sp，增加 letterSpacing
  - 字段数值：25sp → 32sp（更突出）
  - 标签文本：15sp → 13sp，增加 letterSpacing
  - 按钮文本：18sp → 16sp
  - 底部文本：16sp → 12sp

- **组件优化**
  - 轴向卡片：高度增加，文本大小提升，间距优化
  - 图表区域：高度 180dp → 200dp，padding 10dp → 12dp
  - 按钮：高度 48dp → 52dp，drawablePadding 2dp → 8dp

#### 文件修改
- `app/src/main/res/values/colors.xml` - 重构颜色资源
- `app/src/main/res/drawable/card_background.xml` - 优化卡片背景
- `app/src/main/res/drawable/segment_background.xml` - 优化分段背景
- `app/src/main/res/drawable/segment_active.xml` - 优化激活状态
- `app/src/main/res/layout/activity_main.xml` - 优化主布局

#### 新增文档
- `UI_OPTIMIZATION.md` - 详细优化说明文档
- `ui-comparison.html` - 可视化对比页面

---

## [1.0.0] - 2026-06-19

### 新增功能
- 初始版本发布
- 支持 Android 7.0 (API 24) 及以上
- 读取手机磁力计数据
- 显示磁场强度（单位 μT）
- 通过 FFT 显示能量最高的 8 个频率点
- 根据常见地磁范围显示 Stable/Unstable 状态
- 支持采样率调整（200Hz、500Hz、1000Hz、2000Hz、4000Hz）
- 支持校准功能
- 支持录制和回放功能
- 频谱和轴向数据两个标签页
- 轴向数据实时图表显示
