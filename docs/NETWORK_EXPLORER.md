# Network files

Tools provides two distinct flows: **Share folder** exposes an authorized Thor folder over FTP; **Explore devices** browses local SAF folders and signed SMB2/3 shares. Opening the section does not start a server or import folders into the gallery.

## Transfers and recovery

Copy/Move review freezes the selected tree. Existing files are not silently replaced. Move re-reads the destination, checks its digest and rechecks the source before deletion; source directory shells remain. Cancellation keeps journal records.

Directory batches persist their tree and created-folder bindings in the existing Room journal. After interruption, open Operations, reconnect the original device/computer folders and retry the parent operation. Completed children are skipped; published output is verified before continuing. ZIP recovery validates the cached archive and reuses destination bindings. Extraction rejects traversal, excessive entry counts/expansion and failed CRCs, and preserves the original archive. Legacy isolated directory-creation rows can recover their recorded destination folder after reconnecting that destination. An existing directory is reused, a conflicting file is preserved and blocks recovery, and a recorded published folder must keep its identity. This does not invent missing child-transfer plans: select any remaining files for a new transfer.

Errors distinguish unreachable endpoints, timeout, unresolved host, bad credentials, missing share and insufficient permissions. Follow the presented reconnect/permission action rather than guessing a new destination. Passwords are not saved; SMB signing is required, but encryption is not claimed. FTP upload/download was verified on a local network with matching bytes. Windows SMB browsing was checked on Thor and the maintainer reported a successful small transfer; these checks do not certify every server or interruption scenario.

## Test sharing from a computer

1. Put Thor and the computer on the same trusted Wi-Fi. Open Settings → Tools → Share folder.
2. Open Share folder, select an empty test folder, grant access and start sharing.
3. In an FTP client, enter exactly the address/port, username and password shown by the app. Use passive FTP.
4. Upload a small disposable file with a unique name. Confirm progress and that the file appears in the chosen Thor folder.
5. Download it again and compare the bytes or SHA-256 with the original.
6. Upload the same filename again: replacement should be rejected, leaving the existing file intact.
7. Stop sharing. New connections must fail. Restart only explicitly; credentials change.

FTP is unencrypted and intended for a trusted LAN. Do not expose it through router port forwarding. FTPS and partial-transfer resume are not implemented.

## Test browsing a computer

Share a disposable folder on the computer first. In Explore devices choose Computer and enter its SMB share address, account and password (not the Windows Hello PIN). Open a test destination in the other pane. Exercise Copy before Move; use disposable files for interruption/recovery. No credentials or shares are created automatically by the app.
