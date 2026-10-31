# Icon requests for kit1 (from set1, 2026-10-29)

Format: `usage -> lucide-name`. The current workaround is noted; once the icon is fixed, switch the code back.

- `settings: section «Языковая модель (LLM)» -> lucide "brain"`. Current `ic_brain.xml` renders as an empty box on
  device: its arcs use compact flags (`a4 4 0 11-7.967…`, `a4 4 0 01-2-…`) that Android's PathParser does not read.
  Fix in `tools/gen-icons.py` (write flags as separate `0 1 1` tokens) and regenerate. Workaround: `ic_bot`.
- `memory: строка записи памяти, пустое состояние памяти -> lucide "file-text"`. Present already (`ic_file_text`), used now.
- `help: кнопка «?» на карточках и строках настроек -> lucide "circle-help"`. Added by set1 as `ic_help.xml`
  (hand-written Lucide path, same stroke style); kit1 may regenerate it from gen-icons.py.
