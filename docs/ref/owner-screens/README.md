# Owner's screenshots of build 0.1.0-debug (vivo I2407, 2026-10-28/29), 660 px wide

Reference for UI work. Observations by the lead (the owner's own words are in docs/BACKLOG.md):
* 01 voice_tab: the info box ("Порядок и включение…") overlaps the bottom edge of the first card; the Edge card says "включите переключателем" but no switch is visible; label/value columns ("голоса:", "доступ:") are misaligned and wrap under the labels; buttons are truncated ("Пров…", "основной в о…").
* 02/03 chat_bubbles: user bubbles are not against the right edge (fixed by the lead: missing `layout_gravity`), the model label + time line is wider than the bubble and gets cut ("20:").
* 04/07/09: a blank assistant turn is drawn as a tiny "…" bubble with a clipped time ("1"); tool cards "Файлы: List" and "Open" have different widths/left insets.
* 08 files_empty: breadcrumb "песочница" floats at the top without a container, the toolbar (upload / new folder / new file / free space / refresh) sits in the middle of the screen, the list is EMPTY although the agent's `fs_list` returned 7 entries (docs, downloads, inbox, music, work, tmp, bin).
* 10 settings: memory card title "Что ассистент запомнил" is squeezed between the "Добавить"/"Очистить" buttons and wraps by characters; the memory item has an empty icon placeholder; every tool in the list uses the same wrench icon.
* The small black video window in some shots is another app's picture-in-picture (not ours).
