# 媒体平台

当前版本支持 `TEXT`、`IMAGE`、`VIDEO` 三类帖子。内容服务负责帖子和媒体关联，文件服务负责上传和媒体元数据。

## 数据模型

- `post.type`: `TEXT`、`IMAGE`、`VIDEO`。
- `media_asset`: 对象存储 key、公开 URL、content type、大小、宽高、时长、审核状态。
- `post_media`: 帖子和媒体的一对多关联。

开发环境仍使用一个 MySQL 实例多个 schema，服务之间不直接跨库 join。

## 上传规则

`dc-file` 允许：

- `image/jpeg`
- `image/png`
- `image/webp`
- `video/mp4`

上传成功后写入 MinIO，并把元数据保存为 `PENDING_REVIEW`。当前版本不做 FFmpeg 转码、多清晰度切片，只保留任务状态占位，方便后续扩展。

## 前端展示

- 推荐页混排文本、图片、视频。
- 图片页使用瀑布流布局。
- 视频页使用纵向 Feed 卡片和 HTML5 video。
