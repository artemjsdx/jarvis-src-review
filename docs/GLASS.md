# Liquid glass (glass1): blur, drop, segmented control

Owner's words: floating elements must blur what is behind them; the dock's selected item is a 3D liquid drop that flows to the tapped
item; the journal filters use the same drop. Backlog: U-05, U-06, H-02, L-02, L-03, C-08 (hook), D-15 (not this kit).

## Status

* Built and verified on this phone through the headless ART preview (`app_process` + HardwareRenderer). **Not verified on a real window
  or on the owner's phone** (ADB is off; see `docs/DEVICE_CHECKLIST_glass1.md`).
* Pure math (spring, SDF, frame governor) is unit-tested on the JVM: 12 tests, `tools/run-tests.sh app.jarvis.ui.glass.*Test`.

## Spike S0: what renders offscreen on this phone (`app/src/debug/java/app/jarvis/dev/checks/GlassSpike.java`)

| Experiment | Result |
|---|---|
| `HardwareRenderer` + `ImageReader` surface, `syncAndDraw`, read back via `Bitmap.wrapHardwareBuffer` | works in `app_process` (no window, no Activity) |
| `RenderEffect.createBlurEffect` on a node whose content is drawn **directly** into it | works (blurred pixels) |
| `RenderEffect` on a node whose content is a **child RenderNode** (`drawRenderNode(child)`) | renders **empty** in all 4 nestings (child in effect, effect as child of a root, effect set after recording, child with effect) |
| child RenderNode without an effect | works |
| effect node drawn as a child of a root (`root.drawRenderNode(effectNode)`) | works |
| `RuntimeShader` (AGSL) lens chained after a blur (`createChainEffect`), spike variant fed from a child node | compiles, output empty (same child-node problem). The production lens records the snapshot directly; in the preview, lens on vs off differs by a mean of 18 levels inside the drop |
| one syncAndDraw of a 600x900 blur on a reused renderer | ~0.5-2.6 ms (GPU side, this phone) |

**Consequence (design deviation):** the TASK design (GPU RenderNode capture of the live view tree, blurred in an effect) cannot work here,
because the view tree is a child-node tree. The blur source is therefore a **reduced-resolution software snapshot** of a band of the content
layer, recorded as a plain bitmap draw into the effect node. The GPU does the blur and the lens. This is also what makes previews real:
`OffscreenRenderer` renders the same effect nodes.

## Design as built (`app/src/main/java/app/jarvis/ui/glass/`)

* `GlassHost` (FrameLayout): `content()` (screens + backdrop) below, `overlay()` (dock, dialogs, toasts, floating buttons) above.
  Each frame with glass present: content is drawn normally, then **one** blur pass (`BlurLayer`) clipped to the union of all glass
  surfaces, then lenses, then the overlay. Glass surfaces are views tagged with `Glass.mark(view, radiusDp)`; the host finds them in the overlay.
* `BlurLayer` (API 31+): a snapshot bitmap at 1/4 (FULL) or 0.16 (REDUCED) of host size. A **band** (full width, glass rows +/- 40 dp) is
  captured with `content.draw(softwareCanvas)` under a clip, throttled to 40 ms (120 ms REDUCED), and immediately when the band is not covered
  by the last capture. The blur RenderNode draws the snapshot with `RenderEffect.createBlurEffect` (radius 16 dp).
* `LiquidIndicator` (the drop): four `Spring`s (left, top, right, bottom), damping 0.85 and omega 14 rad/s (settles ~0.35 s, overshoot
  under 1 %), stepped on `Choreographer` only while attached. Stretch along horizontal velocity, area-preserving (width x s, height / s,
  s up to 1.16). Without refraction: translucent tint + rim. With refraction (API 33+, FULL): the host draws `LensPass` before the overlay.
* `LensPass` (API 33+): the blurred snapshot passes through an AGSL convex lens (SDF of the rounded rect, displacement stronger near the
  edge, chromatic split of 1.2 px, specular rim from top-left, transparent outside the drop). Chained after a 10 dp blur.
* `SegmentedGlass`: glass control with a `LiquidIndicator` drop over its segments; `setItems(String[])`, `setSelected(int)`, `setListener(...)`.
* `Glass`: `mark`, `background(radius)`, `hostOf(view)`, `floating(Host, view, gravity, l,t,r,b dp, radiusDp)` (adds a glass view to the
  overlay with margins).
* `GlassLevel`: `ui.glass` = auto | full | reduced | off. `GlassLevel.effective()` is the level in use. Below API 31 it is OFF (translucent
  gradient, no blur). AUTO starts at FULL and steps down (FULL -> REDUCED -> OFF) when the p95 of the glass CPU cost is above 14 ms over a 2 s
  window (`FrameGovernor`). `Ui.reduceMotion` snaps the drop instead of animating; blur is not disabled by it.
* Non-glass surfaces (cards in lists) keep the static `GlassDrawable` gradient. Only floating surfaces blur, as the task said.

Wiring already done: `Shell` (dock = glass, drop under the items, settings listener for `ui.glass`), `OverlayHost` (dialog/sheet panels
and toasts are marks, panel fill 72 % instead of 95 % so the blur shows).

Integration points left to the lead (screens are not touched by this kit):
1. Chat scroll-to-bottom (C-08): `Glass.floating(host, button, Gravity.BOTTOM | Gravity.END, l, t, r, b, 22)`; the button must be in the
   overlay, not in the chat list, otherwise it cannot blur the content behind it.
2. Journal filters (L-02): `SegmentedGlass seg = new SegmentedGlass(ctx); seg.setItems(new String[]{"Все","Инфо","Предупр.","Ошибки"});`
   `seg.setListener(i -> ...)`; place it in the log screen. Its drop animates on `setSelected`.
3. Kit1 (AppearancePage): key `GlassLevel.KEY` (= `"ui.glass"`), values `GlassLevel.AUTO/FULL/REDUCED/OFF` (`.key` strings); changing the
   setting reconfigures the running Shell through its settings listener.
4. Journal event window (L-03): already a dialog/sheet through `OverlayHost`, so it blurs. Nothing to do.

## Measurements (this phone, vivo I2407, headless preview via app_process; NOT the owner's window, NOT the Realme Note 50)

Scene `GlassBench`: 392x850 dp host, 40 text lines behind the segmented bar, 24 frames per level, 45 ms apart (past the capture throttle).

| Level | run 1: record / GPU (ms, median) | run 2 (band): record / GPU | run 3 (loaded phone): record / GPU |
|---|---|---|---|
| full (band capture) | 8.2 / 3.5 | 8.2 / 3.5 | 16.8 / 9.7 |
| reduced (band capture) | 4.0 / 2.6 | 4.0 / 2.6 | 9.5 / 6.5 |
| off | 6.1 / 3.6 | 3.1 / 1.8 | 8.6 / 3.6 |
| full, whole-screen capture (before the band change) | 32.4 / 8.7 | - | - |

The other agents share this phone; run 3 happened during their jobs (the software snapshot alone went from 5.7 to 9.9 ms), so
the spread between runs is about 2x. Under load, FULL's glass work (record incl. snapshot) is at or above the 14 ms budget, so
AUTO can step down on a busy phone. Not measured on the Realme Note 50.
Software snapshot of the content layer at 1/4 scale: 5.7 ms (269x584 px, full screen). Governor budget 14 ms: the glass work at FULL is
inside it on this phone (8 ms), so AUTO should stay at FULL here; unverified on the Realme.

## Screenshots (build/preview/, not committed)

* Dock before: `build/glass/before_chrome_home.png` (accent pill, translucent gradient); after: `build/preview/glass_home.png`.
* Drop mid-flight, 3 frames 80 ms apart after a tap on "Журнал": `build/preview/glass_dock_flow_{1,2,3}.png`; contact sheet
  `build/glass/dock_sheet.png`.
* Dialog over a text-heavy screen (blur under a 72 % panel, scrim above): `build/preview/glass_dialog_over_log.png`.
* Scroll-to-bottom button over chat: `build/preview/glass_scroll_button.png`.
* Segmented control, text behind it blurred and text outside sharp: `build/preview/glass_segmented.png`;
  lens on/off comparison `build/preview/glass_segmented*.png` (see `build/glass/lens_full_vs_reduced.png`).

## Known limitations

* The refraction is subtle on a blurred backdrop (the lens refracts blur, not sharp text). Verify its look on the phone.
* The icon under the drop is not magnified (icons are drawn above the lens, not inside it).
* The lens adds a second blur pass (the host's blur feeds the union, the lens blurs its own snapshot). Per-frame cost is in the measurements.
* Scrims are drawn above the blur, so a dialog's blurred area is dimmed by the scrim. The panel itself is 72 % opaque.
* `FrameMetrics` is not used; the governor measures the glass CPU cost of each frame, not GPU time or the whole frame.
* Glass surfaces inside the content layer (list cards) are not blurred (by design, per the task).
* No entrance animation for floating glass (C-08 asks for one). `Glass.floating` only places it; the caller can animate `alpha`/`translationY`.
* API 26-30 and the Realme Note 50 (3 GB) are not verified; on API 26-30 the look is the translucent gradient with the same spring motion.
