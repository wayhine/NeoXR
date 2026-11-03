# NeoXR Community Fork / NeoXR 社区二开版

**当前版本：`1.6.0-community.1`（versionCode `7`），发布日期：2026-11-04。**

本项目由 [wayhine](https://github.com/wayhine) 维护，基于
[NeoXR-app/NeoXR](https://github.com/NeoXR-app/NeoXR) 1.5 二次开发，
属于非官方社区发行版。上游基线提交为
[`22e3234`](https://github.com/NeoXR-app/NeoXR/commit/22e3234fa479c54071e662a10b1827b294821510)。

原项目及原有代码、素材的版权归原作者与相应贡献者所有；二开新增与修改部分的
权利归相应贡献者所有。完整保留上游 [GPL-3.0 许可证](LICENSE)，
本二开版本继续按该许可证发布。详见[版权与权益说明](NOTICE.md)和[版本变更](CHANGELOG.md)。

本版增加本地、SMB、WebDAV 缩略图媒体库，文件夹浏览与排序，中文界面，
最高 6× 长按倍速，进度／音量／亮度手势和横竖屏控制，并修复眼镜窗口交接与显示恢复。
完整功能说明与[下载发布页](https://github.com/wayhine/NeoXR/releases)见下方。
下方演示视频和截图沿用上游素材，界面与本社区版本可能有所不同。

This is an unofficial community fork maintained by wayhine, based on NeoXR 1.5.
Upstream authors retain their rights to the original work; changes remain under GPL-3.0.
See [NOTICE.md](NOTICE.md) for attribution and [CHANGELOG.md](CHANGELOG.md) for this fork's changes.

## Upstream project and features

A small VR video player for XREAL glasses.

Plug the glasses into your phone. The video plays **on the glasses** at their native
resolution, and the phone becomes a remote control with a touchpad. NeoXR plays
180° and 360° video, side-by-side and over-under 3D, local files, and video from
sites that serve a [DeoVR JSON API](https://deovr.com/app/doc) feed.

This fork includes saved local / SMB / WebDAV libraries with thumbnails, folder
views and sorting. See [library browsing](docs/library-browsing.md),
[playback controls](docs/playback-controls.md), and
[XREAL 1S output / tracking troubleshooting](docs/xreal-1s.md).

## Demo

Left: what you see in the glasses. Right: the phone acting as the remote.

<p align="center">
  <a href="docs/video/demo.mp4">
    <img src="docs/video/demo-preview.gif" width="720" alt="NeoXR demo: video on the glasses, phone as the remote">
  </a>
  <br>
  <a href="docs/video/demo.mp4"><b>▶ Watch the full demo (1:44)</b></a>
</p>

| Main menu | Video list | Player remote |
|---|---|---|
| <img src="docs/screenshots/01-main-menu.jpg" width="220"> | <img src="docs/screenshots/02-video-list.jpg" width="220"> | <img src="docs/screenshots/04-player-remote.jpg" width="220"> |

Playback starts in single-view. The output button can enable SBS for the glasses'
3D mode; the screenshot below shows the optional SBS layout on the phone:

<p align="center">
  <img src="docs/screenshots/05-sbs-on-phone.jpg" width="640" alt="Split SBS view on the phone">
</p>

## What it does

- **Plays on the glasses, not on the phone.** The glasses are a real external
  display over USB-C, so NeoXR renders straight to them. Nothing is mirrored and
  nothing is rescaled.
- **The phone is the remote.** A large touchpad moves a pointer on the glasses:
  tap to click, two fingers to scroll. The screens you need are in the glasses, the
  buttons are under your thumb.
- **Head tracking.** The view can follow your head, using the motion sensor inside
  the glasses (see [Head tracking](#head-tracking) below).
- **Any format, detected automatically.** Screen shape (flat, wide, 180°, 360°) and
  3D layout (2D, side-by-side, over-under) come from the video metadata, the file
  name, or the frame shape — and you can always set them by hand.
- **Four ways to get video:** a site feed, the built-in browser, a local file
  (local videos are grouped by folder), or a **network share** — browse an SMB
  server and play from it, with nothing downloaded first.
- **Picks up where you left off.** Reopening a video resumes at the position you
  stopped at.
- **Picture controls:** zoom, width, height, stereo depth and eye swap, per video,
  plus an optional ambient glow — bias lighting that carries the colour of the
  picture's edges into the surround, the way a TV's LED strip does, in three
  strengths and adjustable width.
- **Calibration for your glasses:** black level, brightness, contrast and gamma.
  Panels differ in how they render shadows, and the glasses have no picture menu of
  their own — lower the gamma and dark scenes open up.
- **Single-view output** when watching on the phone itself, instead of the stereo
  pair the glasses need.
- **Subtitle style:** size, height, weight, colour, and a switch for tracks that
  already ship as a stereo pair. The line can sit anywhere from inside the bottom
  black bar to the top of the picture.
- **Audio tracks and subtitles** for files that carry more than one.
- **Plays the audio your phone can't.** Bundled FFmpeg decoders handle AC-3, E-AC-3,
  DTS and TrueHD — the formats most film rips use and most phones lack.
- **No account, no ads, no tracking.** The app has no analytics and makes no network
  requests beyond the sites you open yourself.

## Requirements

- XREAL One or One Pro glasses — the pair NeoXR is developed on. The Air series and
  other glasses that work as a USB-C display work too, minus what is noted below —
  [tell us](../../issues/new?template=device_report.md) what you find.
- An Android phone with USB-C DisplayPort output, Android 8.0 or newer.
- For stereo video: **3D / SBS mode enabled** in the glasses menu.

> **Tip:** you switch SBS on and off often — once for 3D video, once back for sharp
> 2D browsing. Assign **3D / SBS** to the glasses' Quick Button (for example to a
> long press) and it becomes a one-touch change instead of a walk through the menu.

## Install

1. Download the latest `NeoXR.apk` from [Releases](../../releases/latest).
2. Open the file on your phone and allow the install.
3. Android warns you about installing from an unknown source. That is normal for
   apps distributed outside Google Play.

To get updates automatically, use [Obtainium](https://github.com/ImranR98/Obtainium):
add this repository URL and it will follow new releases.

## Getting started

1. Connect the glasses to your phone with a USB-C cable.
2. Open NeoXR. The menu appears in the glasses; the phone shows the touchpad.
3. Add a video source:
   - **A site feed** — select *Web / SMB*, type a site address and press Add (or Done
     on the keyboard). If the site serves a DeoVR JSON API feed, you get a video
     list with previews. If it does not, the site opens in the built-in browser.
   - **A local file** — press *Open File* and pick a video from your phone. NeoXR
     also appears in the system *Open with* and *Share* menus.
   - **A local library** — select *Local Library*, then *Manage → Add Folder*. Choose a folder on the phone
     and allow access. Its videos and subfolders are indexed and saved. Preview
     cards with titles and durations appear directly on the home screen; filter by
     folder and search titles there. *Web / SMB* shows saved websites and SMB shares.
     The library returns on the next launch and updates in the background.
     *Manage* contains *Rescan All* and the saved folder list, where *Remove*
     forgets an individual root without deleting files. See [library details](docs/local-library.md).
   - **A network share** — type `smb://user:password@192.168.1.10` in the same input
     field (or `smb://192.168.1.10` for a guest share) and press Add. Tap the device,
     then *Manage → Add Folder*, choose a shared directory and press *Add This Folder*.
     Its saved network library displays preview cards, folder filters and title
     search; videos stream directly over SMB. See [network library details](docs/network-library.md).
   - **WebDAV** — select *网站 / 网络 → 添加 WebDAV 媒体库*, enter the HTTP(S)
     directory address and optional account, then choose *添加此目录*. Saved libraries,
     thumbnails, search and direct playback use the same flow as SMB.
     See the [WebDAV setup guide](docs/webdav.md) for supported authentication and server requirements.
   - **From the browser** — play a video on any site, then press **▶ Play VR** to
     open that stream in the VR player.

## Controls

In the menu, the video list and the browser:

| Action | Result |
|---|---|
| Drag on the touchpad | Move the pointer |
| Tap | Click |
| Two fingers | Scroll |
| Long press (without glasses) | Turn the split SBS view on or off |

In the player:

| Action | Result |
|---|---|
| Swipe | Look around |
| Hold the phone video | Temporary configured 1.5–6× speed; release to restore |
| Center button | Center the view |
| Hold the blank glasses controller pad | Center the view |
| Double tap | Phone gyroscope on or off |

**Controls** selects the phone swipe mode (look around or preview-and-seek) and
the hold speed (Off, 1.5×, 2×, 3×, 4×, 5× or 6×). These choices are saved.
**Controls / Center / ✕** share the top action row.

With glasses connected, the phone has a permanent transport row: **−10s**, **Play /
Pause**, **+15s** and **Hold speed**. The speed hold restores on release,
cancellation and app/display transitions. See [playback controls](docs/playback-controls.md).
| Tap (without glasses) | Show or hide the controls |

The player has two button columns. **Screen** sets the shape: Flat, Wide, 180°,
360°. **Layout** sets the 3D format: 2D, SBS (side-by-side), OU (over-under).
`Z` sets the zoom, `W` and `H` scale the picture horizontally and vertically —
useful when a video is encoded with the wrong scale. `◄◄` and `►►` skip 10 seconds
back and 15 forward; hold them for one minute back and five forward. The `3D` button
opens depth adjustment and an eye-swap switch (for sources with the halves
reversed), and in landscape it also holds `W` and `H`. `PIC` holds the picture calibration —
black level, brightness, contrast, gamma — plus the ambient glow width and the head
tracking sensitivity; those settings describe your glasses, so they are kept between
videos. `CC` appears when a video
carries several audio tracks or subtitles; unsupported tracks are marked, and a
track the device cannot decode no longer stops playback — the video keeps running
without sound. The same menu sets subtitle size, height, weight and colour.

On a device with more than one display — a foldable, or a dual-screen handheld — a
**Screen** button appears and moves the video to the next display, in case the app
guessed wrong about which one is the glasses.

## Head tracking

**Works on XREAL One and Air series.** Both expose their motion sensor — the One
series over a network link, the Air series over USB — and NeoXR reads either and
turns it into camera movement, so the video stays in place while you look around.
Both are now confirmed on real hardware — the Air-series path was written from the
published protocol alone and verified by an Air 2 Pro owner in 1.5. If the view turns
faster or slower than you do, adjust the sensitivity under `PIC`.
On other glasses use the phone's gyroscope instead (double-tap in the player, then
turn the phone to turn the view).

To use it:

1. In the **glasses menu**, switch the display mode from **Anchor** to **Follow**,
   and turn **Stabilizer off**. On Air-series glasses Android also asks once for
   permission to reach them over USB — allow it. In Anchor mode the glasses hold the image in space
   themselves, so their motion is added on top of the app's and the picture moves
   twice. Recent firmware is required.
2. Open a video and press **Head** in the left column. The button appears only when
   the glasses are connected.
3. Look straight ahead and long-press to center the view.

Press **Head** again to go back to the phone gyroscope.

**If a VPN runs on your phone** (One series only, which connects over the network),
it may capture the connection to the glasses and head tracking will not start. Either turn the VPN off, or use a client that lets
apps bypass the tunnel (in OpenVPN for Android: *Allow apps to bypass VPN* in the
profile settings).

## Known limits

- **3D SBS halves the horizontal resolution.** In stereo mode the glasses split the
  frame in two, so each eye receives half the pixels. This is a limit of the display
  link, not of the app. For maximum sharpness, watch flat video in 2D.
- **The keyboard does not work on the glasses.** Text fields open an editor on the
  phone instead. This is an Android limitation for external displays.
- **WebXR does not work** in the built-in browser: Android's WebView has no WebXR
  support, so "Enter VR" buttons on websites do nothing. Use ▶ Play VR instead.
- **Fisheye video** (MKX200, RF52 and similar) is shown as a regular dome, so the
  edges are not perfectly correct.
- Sites that need a login may work in the built-in browser, but NeoXR has no account
  support of its own.
- **Subtitle formats:** SRT, ASS/SSA, WebVTT, TTML and PGS (Blu-ray) work. VobSub
  (the DVD format, `S_VOBSUB` in MKV) is not supported by the underlying player and
  is marked as such in the track list.
- **Head tracking works on XREAL One and Air series.** Both protocols are publicly
  documented thanks to community reverse-engineering. Viture and other brands keep
  their sensor behind a closed SDK with no public protocol, so they cannot be
  supported from open sources — the phone gyroscope works with any glasses.
- **Network shares speak SMB2 and SMB3**, the dialects current NAS boxes and Windows
  use. SMB1 is not supported, and the share's password is stored as typed in the app's
  own storage. Shares are new in 1.4 and have been tested against few servers — if
  yours does not open, a report with the server type is the fastest way to fix it.
- **Samsung DeX is not supported.** In DeX the file browser opens behind the main
  window and playback does not start. Switch the phone to screen mirroring instead.
- **A 360° video does not surround you.** The glasses show roughly a 50° field of
  view, so any video is a window into the sphere rather than a dome around your head
  — you look around inside it by swiping, by the phone gyroscope, or with head
  tracking.

## Build from source

```bash
git clone https://github.com/wayhine/NeoXR.git
cd NeoXR
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/NeoXR.apk
```

You need JDK 17 and the Android SDK (compileSdk 35). Release builds are signed with
the key described in `keystore.properties`; without that file a release build falls
back to the debug key, so a fresh clone still builds.

## Contributing

Device reports are the most useful contribution. NeoXR is developed on one pair of
glasses and one phone, so reports from other hardware are the only way to learn what
works. Please use the
[device report](../../issues/new?template=device_report.md) template.

## Third-party components

Audio decoding for formats Android does not cover uses
[FFmpeg](https://ffmpeg.org/) (LGPL-2.1), packaged for Media3 by the
[Jellyfin project](https://github.com/jellyfin/jellyfin-androidx-media).

## License

[GPL-3.0](LICENSE). This is free software: you may use, study, share and modify it,
and any distributed derivative must stay under the same license.

The original NeoXR copyright and license notices are preserved. This fork's
modifications, maintainer and modification date are documented in [NOTICE.md](NOTICE.md).
The APK includes the full license and attribution; the license is also available
at the end of the in-app Help page. No upstream or hardware-vendor endorsement is implied.
