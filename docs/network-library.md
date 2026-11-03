# Network libraries (SMB / WebDAV)

WebDAV uses the same saved library, folder picker and thumbnail grid. See
[WebDAV setup and supported authentication](webdav.md) for the Chinese setup guide.

1. Select **Web / SMB** and add a device, for example `smb://192.168.0.184/`
   for guests or `smb://user:password@192.168.0.184/` for an account. Existing
   saved devices work too.
2. Tap the device to open its saved network media library.
3. Select **Manage → Add Folder**, open a share and navigate to the desired folder.
   Press **Add This Folder**. A share or folder can be indexed; the entire server
   cannot be selected. Only directories appear in this folder picker.
4. Videos and subfolders are recursively indexed. The grid shows thumbnails,
   titles, durations and play badges. Filter by folder, search titles, or select
   a card to stream the original SMB video.
5. Reopen the device later to see its saved index while it refreshes in the
   background. **Manage → Rescan All** updates it manually; **Remove** beside a
   saved folder forgets its record and keeps the server's files.

Libraries are stored separately per server/account in private preferences. The
account is retained in stored SMB URLs, as with the original saved device list;
titles, thumbnail cache identifiers and provider errors omit credentials.
Local libraries and website feed browsing keep their existing behavior.

The read-only, non-exported `SmbPreviewProvider` supplies Android with a seekable
file descriptor backed by SMB range reads. Glide extracts and caches visible
previews; background workers cache durations. No complete video file is saved to
the phone for a preview. Reads use a 256 KiB buffer, at most two open preview
descriptors, and a 64 MiB budget per descriptor. Unsupported codecs, offline
shares and over-budget previews show a placeholder; cards can still be selected
for playback. File size and modification time invalidate cached previews after
rescanning. Directory reparse points are skipped to avoid following junction loops.

Failed scans retain the last successful index and mark the folder unavailable.
Reconnect, check the server/account and rescan. Cached images remain available
while retained by Android/Glide's cache.

## Device acceptance checks

- Browse guest and authenticated shares; confirm no password is shown.
- Add nested folders containing MP4, MKV, MOV and WEBM; check cards, search,
  filters and streaming playback. Scroll quickly and check recycled cards.
- Force-stop/reopen and reboot/reopen; verify the saved device library returns.
- Disconnect the NAS; confirm cached records survive. Reconnect and rescan.
- Add/delete/replace a remote video and rescan; check listing and preview updates.
- Test large videos and seeking. Check that preview reads do not save full videos.
- Remove a library root and reopen; confirm its files remain on the server.
- Test two devices/accounts independently, glasses mode, cancellation and rotation
  while listing directories or scanning a library.

Android bridge reference: [StorageManager.openProxyFileDescriptor](https://developer.android.com/reference/android/os/storage/StorageManager).
