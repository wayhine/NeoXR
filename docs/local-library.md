# Local media library

1. On the home screen, select **Local Library**, then **Manage → Add Folder**.
   Android opens its folder picker on the phone, including when the menu is displayed in the glasses.
2. Choose a folder and allow access. NeoXR saves the read grant, recursively scans
   its subfolders in the background, and updates the library on the home screen.
3. The library appears directly below the home screen source controls, as a grid of rounded video previews, titles, play badges and
   durations, using the same cards as the website feed browser. Tap **All folders** to
   select a folder or subfolder, or use **Search titles** to filter videos. Select
   a card to use the existing VR player. MP4, MKV, MOV and WEBM are recognized by extension, along with other
   common video extensions and files identified by a `video/*` MIME type.
4. Relaunch NeoXR: the cached library appears directly on the home screen. The
   saved folders are rescanned in the background on a fresh app process. The two
   top tabs switch between **Local Library** and **Web / SMB**. The website input
   appears only in Web / SMB; the folder filter and title search appear in Local Library.
5. Use **Manage → Rescan All** after adding, moving or deleting videos. The same
   management panel lists every saved folder with a **Remove** button beside it.
   This forgets that library root without deleting files, independently of the current filter.

Normal browsing uses one toolbar: folder filter, title search and **Manage**.
Adding, rescanning and removing library folders have one home in the management
panel. Press **Manage** again or Back to return to the video grid. The filter and
management panels close each other; choosing a folder or adding one returns to videos.

Previews are extracted from the local video by Glide and cached on disk. Durations
are loaded on two background workers when cards become visible and saved in private
preferences. Failed previews show a play placeholder; the card remains playable.
File size and modification time invalidate cached metadata when supplied by the
document provider. Older library indexes load without migration or reselecting folders.

The index and selected folder URIs are stored together in the app's private
`local_library` SharedPreferences. Folder grants persist across device restarts.
No all-files access or broad video permission is needed for this library. Existing
**Open File**, website and SMB features remain available.

If storage is disconnected or access is revoked, the last successful listing is
kept and the folder is marked unavailable. Reconnect the storage or use **Add
Folder** to select the same directory again, then refresh. Adding the same tree
URI updates its grant instead of creating a duplicate root. A complete successful
scan replaces that root's cache, including removing entries for deleted videos.

Android controls which directories the system picker can authorize. On Android
11 and later, storage roots, the Download root, Android/data and Android/obb are
restricted. Select a permitted subfolder, for example Movies/VR or Download/VR.

## Device acceptance checks

- Add a directory containing MP4, MKV, MOV, WEBM, a non-video file and nested
  subfolders. Verify only videos appear and nested navigation works.
- Add two different folders with the same display name; verify each keeps its own
  videos. Add the same folder twice; verify one root remains.
- Play a nested video; verify playback and the existing resume position behavior.
- Verify preview cards, durations, folder filtering and title search. Scroll quickly
  and confirm recycled cards do not show another video's duration or preview.
- Relaunch and check cached previews. Replace a video, refresh and verify its preview
  and duration update when the storage provider reports its new size or timestamp.
- Force-stop and reopen, then reboot and reopen. Verify the saved library appears
  on the home screen without asking for broad storage permission or selecting the folder again.
- Switch to **Web / SMB**, open a website or SMB share, then return to **Local Library**.
  Open and close **Open File**, and verify the home grid and its filter remain intact.
- Add/delete a video and use **Manage → Rescan All**; verify the listing updates.
- Verify the website input is shown only under **Web / SMB**, and each active tab
  is highlighted. Open and close Manage, switch to the folder filter and test Back.
- In Manage, remove a root other than the currently filtered root; verify only the
  chosen root disappears. Add a folder while a search is active and verify it
  returns to the unfiltered grid after the picker succeeds.
- Disconnect removable storage or revoke the directory grant. Verify cached
  entries remain with an unavailable message; reconnect/regrant and refresh.
- Rotate the phone or attach glasses during a scan. Verify scans finish, the
  library stays consistent, and the home grid remains usable through the glasses.
- Remove a root, restart, and verify it stays removed while its files remain.

Android behavior reference: [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
