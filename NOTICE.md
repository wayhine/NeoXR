# NeoXR 社区二开版：版权、许可与权益说明

本说明适用于 **NeoXR Community Fork 1.6.0-community.1**，修改日期为 **2026-11-04**。

## 来源与维护者

- 原项目：[NeoXR-app/NeoXR](https://github.com/NeoXR-app/NeoXR)。
- 上游基线：NeoXR 1.5，提交 `22e3234fa479c54071e662a10b1827b294821510`。
- 二开维护者：[wayhine](https://github.com/wayhine)。
- 二开源码与发布：[wayhine/NeoXR](https://github.com/wayhine/NeoXR)。
- 本版本为非官方社区二次开发，不属于 NeoXR 上游或 XREAL 等硬件厂商的官方发行版。

## 版权与许可

原项目代码、文档、演示视频、截图及其他原有内容的版权，继续归原作者与相应贡献者所有。
本项目保留这些内容中的原有署名与许可声明，不将上游作品声明为二开维护者的原创。

本次二开新增代码、文档及对原项目的修改，其权利归相应贡献者所有；本说明不改变上游
原有内容的权属。原始贡献与本次修改可以通过 Git 历史及 `CHANGELOG.md` 区分。

项目继续依据 **GNU General Public License, version 3（GPL-3.0）** 发布，
许可证全文为仓库根目录的 `LICENSE`，该文件保持上游原文。
使用、修改及再分发须遵守该许可证；发布修改版本时保留许可与署名，标明修改，
并按许可证要求提供相应源代码。本二开不另加“禁止商用”或其他限制 GPL 权利的条件。

APK 同时携带 `LICENSE` 和本说明；帮助页包含项目来源、维护者、许可说明与完整许可证。
对应版本的源代码随 GitHub Release 标签公开提供。

## 本次修改范围

- 本地、SMB 与 WebDAV 媒体库的递归扫描、保存恢复、缩略图、搜索、文件夹浏览与排序。
- 中文界面、播放器手势、临时倍速、横竖屏切换及控制栏布局。
- 外接眼镜的单画面／SBS 输出、页面窗口交接、断连恢复及诊断日志采集。
- XREAL One 系列与 1S 的传感器数据读取、校验及连接恢复改进。

具体功能与验证范围见 `CHANGELOG.md` 和 `docs/`。

## 第三方组件及品牌

第三方组件、字体、库及素材保留各自的版权与许可证；本说明不会替代其原有许可。
包括经 Jellyfin Media3 组件提供的 FFmpeg 音频解码器，其说明保留在 README 的
Third-party components 部分。社区协议实现的资料出处保留在相关文档中。

NeoXR、XREAL 及其他品牌名称、标识涉及的相关权利归各自权利人所有。
使用这些名称用于说明来源或兼容性，不表示官方关联、背书或授权。

## 担保

软件按 GPL-3.0 的条款提供，不附带该许可证未要求的担保；责任与担保范围以
`LICENSE` 为准。不同设备、系统、固件及网络服务器的实际兼容性，以测试结果为准。

## English attribution

This is an unofficial modification of NeoXR 1.5 by the community fork maintained by
wayhine, dated 2026-11-04. The upstream project is https://github.com/NeoXR-app/NeoXR,
based on commit 22e3234fa479c54071e662a10b1827b294821510.

Copyright in the original work remains with its original authors and contributors.
Rights in additions and modifications remain with their respective contributors.
Original notices are retained. This fork is distributed under GNU GPL version 3,
with the full text in LICENSE, and without warranty as specified there.
Third-party components retain their own licenses. No upstream or hardware-vendor
endorsement is implied. Corresponding source is published at https://github.com/wayhine/NeoXR.
