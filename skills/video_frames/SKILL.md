---
name: 视频帧提取
displayName: Video Frames
description: 用 ffmpeg/ffprobe 从本地视频提取封面帧、指定时间帧或读取视频信息
type: python
entrypoint: scripts/run.py
category: office
tier: utility
inputHint: 传入 videoPath 或 inputFile，可选 mode=info/frame、time、index、outputFile、dryRun
priority: 44
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 视频帧
  - 截帧
  - 提取封面
  - 视频信息
triggerExamples:
  - 从这个视频第 10 秒截一帧
  - 查看 video.mp4 的视频信息
---

# Video Frames

从 workspace 内的视频文件提取图片帧或读取视频元信息。默认输出到工作区 `data/skills/video_frames/`。

## Safety

- 只读取和写入当前 workspace 内的文件。
- 不自动安装 `ffmpeg`。
- 缺少 `ffmpeg` / `ffprobe` 时返回 `missingDependency` 和安装建议。
