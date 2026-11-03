# Playback controls

## Playing on the phone

- Tap the video to show or hide controls. Play/pause stays on its own button.
- The top row contains **横/竖屏 toggle / Controls / Center / ✕**, side by side. **Center** recenters
  the view and **✕** closes playback.
- In **Controls → Phone video swipe**, select **Look around** or **Seek video**.
  Look around drags the camera as before. Seek video uses horizontal drags on the
  video: right moves forward, left backward. Vertical drags starting on the left
  half adjust the playback window's screen brightness; those starting on the right
  adjust media-stream volume. Up increases, down decreases, with a percentage HUD.
  The initial axis and starting half stay locked for the whole gesture, even if the
  finger crosses the screen center. Brightness never changes the system setting;
  already-applied brightness/volume changes remain on release or cancellation.
- Gesture seeking previews the time and slider position; releasing commits one
  seek. Cancellation, another finger, rotation or losing focus discards the preview.
  One screen width covers up to two minutes, or the full duration for shorter clips.
  Videos without a known, seekable timeline show an explanation instead.
- In **Controls → Hold speed**, select **Off**, **1.5×**, **2×**, **3×**, **4×**,
  **5×** or **6×**. Hold the video to temporarily use that speed, then release to
  restore the previous speed. The badge displays the selected speed while held.
- Both settings persist between videos and app launches. Defaults remain Look
  around and 2×. Double tap still toggles the phone gyro; the seek slider always works.
- Paused videos stay paused when held. Begin playback first to use temporary speed.

## Portrait video display

The top orientation button switches immediately between landscape and portrait;
it opens no selection menu. Its label is the target: **竖屏** switches to portrait,
then the label becomes **横屏** to switch back. The player reads Android's system
auto-rotate setting directly, with no app switch. When enabled it follows the
phone; when disabled it holds its current direction and still allows button turns.
Changing the system setting during playback takes effect immediately. A manual
button turn overrides auto-follow until the phone moves to another stable physical
direction (300 ms near a cardinal orientation), then system following resumes.
Manual choices persist as the fallback when system rotation is off. Glasses
playback changes only the phone controller direction.

Each new phone or glasses playback starts with a single video view and one copy of controls.
The output switch in the stereo/geometry panel can temporarily enable SBS; opening
another media item starts single-view again. The output switch is also available
for connected glasses. Match the glasses' OSD: 2D for single-view, Half / Full SBS
for the corresponding SBS output. See [XREAL 1S troubleshooting](xreal-1s.md).
If glasses output fails, the phone fallback also starts single-view.

Phone portrait lays out **timeline → transport/hold-speed → picture/track buttons**
from top to bottom. Rows rebuild from the original button instances on each turn,
with equally weighted tool buttons rather than overlapping frame anchors.
Landscape restores the compact phone layout. Android widget measurement tests
cover narrow portrait, larger fonts, repeated turns and the glasses controller. Geometry settings
scroll if the available height is short, and subtitles clear the portrait controls.
Rotation cancels active temporary-speed and swipe-preview sessions, closes transient
menus, updates drawable aspect and keeps the existing player/position.

Device acceptance: tap the button repeatedly, check both system rotation on/off,
reopen a video and restart the app. Verify the saved direction, default single-view
playback and both single-view / SBS output in connected glasses. Exercise
seeking, volume/brightness gestures, temporary speed, subtitles and geometry menus
in portrait and landscape, and repeat with glasses connected.

Untagged portrait clips (including 9:16 and 1:2) use the complete flat 2D frame.
Their aspect alone no longer selects the top half as an over-under VR source.
Phone single-view playback fits the full image to the actual drawable area;
glasses SBS output uses its active 16:9 or 16:10 per-eye geometry. Decoder pixel aspect is included.
Surface-size and mono/stereo output changes rebuild the flat mesh.
Explicit filename tags (TB, OU, VR180 and similar), feed metadata and manual
projection/layout selection retain precedence. Untagged top-bottom VR material
can be selected manually with the projection and 上下 buttons.

## With glasses connected

The phone remains a controller. Its always-visible transport row contains:

| Button | Action |
| --- | --- |
| −10s | Seek backward 10 seconds; long-press to seek back one minute |
| Play / Pause | Toggle playback, including while buffering |
| +15s | Seek forward 15 seconds; long-press to seek forward five minutes |
| Hold speed | Uses the selected 1.5–6× hold speed; Off disables this button |

**Center** is beside **Controls** and **✕** in the top action row. Hold-speed settings
also apply to the controller button. Phone swipe-mode settings apply only when the
phone displays the video: the glasses controller pad always adjusts the camera.

The seek slider, format, picture, subtitle and quality controls remain available.
Transport buttons occupy their own row in both portrait and landscape. In portrait
the tuning buttons move to a separate row so the seek slider keeps the full width.
The blank controller pad still supports drag-to-look, double-tap-to-toggle-gyro and
long-press-to-center; hold-to-speed uses its dedicated button in this mode.

Temporary speed restores on release, touch cancellation, an additional finger,
moving outside the speed button, focus loss, rotation, pause, playback end/error
and activity destruction. Screen changes from connecting/disconnecting glasses
also pass through lifecycle cleanup. Speed holds never call play or resume.

## Device acceptance checks

- Phone: hold for several seconds, release, and repeat. Verify the rate and badge
  restore immediately and the camera is not recentered by the new hold gesture.
- Try a short tap, a drag, two fingers, cancellation by the navigation gesture,
  notifications, app switching and rotation. Verify speed never stays accelerated.
- Pause the video and hold; confirm it stays paused. Verify Center and double tap.
- Connect glasses: verify all four transport buttons stay visible and accessible
  in both orientations, with no duplicated transport buttons in the seek row.
- Test rewind/forward near both ends and with a stream that cannot seek. Check
  Play/Pause during buffering and at playback end.
- Select 6× and hold, then unplug glasses, background the app or interrupt playback. Confirm
  the next playback uses the normal speed and the indicator is cleared.
- Exercise picture, track, subtitle, quality and 3D controls after rotating; verify
  their rows do not overlap the transport buttons or seek slider.
- Check every plus/minus button in picture and subtitle settings. Menus use a
  bounded width, full-width rows and dedicated 56dp buttons to prevent clipping.
- Switch both swipe modes, restart and check the saved selection. In Seek video,
  verify left/right preview, boundary clamping, release, cancellation, vertical drags
  and seeking while paused. A paused video must stay paused after the seek.
- In Seek video mode, swipe vertically on each half. Verify brightness/volume
  stay within bounds, show percentages, and never seek, rotate the camera or resume
  paused playback. Crossing the center must keep the original adjustment. Test
  cancel, multitouch, focus loss, rotation and returning to playback. Verify Look
  around mode and the glasses controller still only move the camera. Check media
  volume with speaker/headphones and screen brightness with automatic brightness on.
- Select Off, 1.5× and 6× and check both screen hold and the controller button. Confirm
  release restores the original speed; opening Controls also cancels an active hold.
