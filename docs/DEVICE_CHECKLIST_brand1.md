# Device checklist: brand1 (launcher icon and orb skin, B-01 / B-02 / H-04)

Not verified on the phone: ADB was off when this was built. Run these after the next install of `Jarvis.apk` (or the CI emulator build) on the vivo I2407. Nothing here needs the microphone or speaker.

Prerequisites: the build contains `assets/brand/orb.webp` (checked in the APK: 19 184 bytes) and the launcher layers in `res/drawable-nodpi-v4/`.

1. Launcher icon, normal theme. Long-press the home screen, add or look at the Jarvis icon. Expected: a pearl sphere on a teal-to-peach gradient tile, no shadow, no rounded square drawn by us. Compare the sphere with `build/brand/review.png` (circle mask row).
2. Themed icon (Android 13+). Settings → Wallpaper & style → turn on themed icons, then look at the Jarvis icon. Expected: a white ring with a dot on a tinted tile (the monochrome layer). If the icon stays coloured, the system is not using the monochrome layer; record the Android version.
3. Icon crop. Expected: the sphere is fully visible in the circle, squircle and rounded-square launcher masks the phone uses (switch the icon style if the launcher offers it). No part of the sphere is cut.
4. Orb in the app, dark theme. Open the app → Home. Expected: the pearl picture is the orb body, the ring and the halo are visible around it, and the orb breathes slowly. The picture has no black edge.
5. Orb in the app, light theme. Settings → appearance → light (S-11). Expected: the same orb on the light background, readable, no dark fringe.
6. States. Use `python3 tools/jv.py ui_screenshot '{}'` (the MCP tool `ui_screenshot`) while the orb is in each state the app shows without audio: idle, thinking (send a text message from the Agent screen). Expected: thinking turns the picture slowly, idle does not. Do not start voice input.
7. Reduce motion. Settings → animations → «сниженные» (S-12). Repeat step 6. Expected: rotation is about 15 % of the normal speed (almost still).
8. Fallback. Only if you can rebuild without the asset: remove `app/assets/brand/orb.webp`, build, install, open Home. Expected: the old procedural orb (rings plus a gradient core) and no crash. Restore the asset afterwards.
9. Logs. `adb logcat -d | grep -i "BrandSkin\|OrbView"`. Expected: no exception lines.

Result columns to fill in: step, pass/fail, Android version, screenshot path.
