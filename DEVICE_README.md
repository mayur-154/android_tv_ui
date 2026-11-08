# CleanYourTV Guide for Xiaomi Mi Box S (MIBOX4)

This guide documents the changes made to your Mi Box S, how to restore the stock setup, how to re-enable any app, how to customize your launcher, and how to close network ADB.

---

## 1. Quick Scripts

- **Restore to Stock**:
  ```bash
  ./backup/restore.sh
  ```
  Or specify IP:
  ```bash
  ./backup/restore.sh 10.0.0.104
  ```

- **Rebuild and Reinstall Launcher**:
  ```bash
  ./build-and-install.sh
  ```

---

## 2. How to Re-enable Any App

All debloated packages are safely **disabled**, not uninstalled.

To re-enable any specific app:
```bash
TV_HOST=10.0.0.104 tools/venv/bin/python3 tools/tv.py "pm enable <package_name>"
```

### Common Package Names:
- **Disney+ Hotstar**: `in.startv.hotstar`
- **SonyLIV**: `com.sonyliv`
- **JioCinema**: `com.jio.media.stb.ondemand`
- **JioSaavn**: `com.jio.media.jiobeats`
- **Google Play Movies & TV**: `com.google.android.videos`
- **Google Play Games**: `com.google.android.play.games`
- **YouTube Music**: `com.google.android.youtube.tvmusic`
- **Live Channels**: `com.google.android.tv`
- **Xiaomi System Updates**: `com.xiaomi.mitv.updateservice`

---

## 3. How to Change Dock Order, App Count & Wallpaper

Theme configuration is located in `themes/glass/config.json`.

### Changing Dock Apps & Order:
Edit `themes/glass/config.json`:
```json
{
  "dock_apps": [
    "com.google.android.youtube.tv",
    "com.amazon.amazonvideo.livingroom",
    "com.spotify.tv.android",
    "com.netflix.ninja"
  ],
  "columns": 4
}
```
You can change the number of columns (4, 5, 6, or 7) and list any installed package names.

### Changing Wallpaper:
1. Place your desired image (JPEG) as `themes/glass/wallpaper.jpg`.
2. Apply the updated theme to the TV:
   ```bash
   TV_HOST=10.0.0.104 ./tools/theme.sh themes/glass
   ```

---

## 4. Ad-Blocking Private DNS

AdGuard Private DNS is currently active (`dns.adguard-dns.com`):
- Blocks tracking and ad servers system-wide.
- Note: It does not block YouTube in-video ads (which are served from YouTube video CDNs).

### To Revert to Standard DNS:
```bash
TV_HOST=10.0.0.104 tools/venv/bin/python3 tools/tv.py "settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier"
```

---

## 5. Closing Network ADB (Important Security Step)

Leaving port 5555 open allows anyone on your local network to connect. When you are finished configuring:
1. On your TV, navigate to:
   **Settings > Device Preferences > Developer options**
2. Toggle **USB debugging** to **OFF**.
3. Select **Revoke USB debugging authorizations** and click **OK**.
4. Reboot the TV.
5. Verify port 5555 is closed by running:
   ```bash
   tools/venv/bin/python3 tools/adb-closed.py 10.0.0.104
   ```
