package de.luisbenedikt.movieselector.web

internal val CSS = """
:root { color-scheme: dark; }
* { box-sizing: border-box; }
html, body { margin: 0; height: 100%; background: #0b0e14; color: #f4f1e8; font-family: system-ui, sans-serif; overflow-x: hidden; }
#app { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px; }
.screen { width: 100%; max-width: 420px; display: flex; flex-direction: column; align-items: center; gap: 16px; }
.center { text-align: center; }
h1 { font-size: 1.6rem; margin: 0; }
.subtitle { opacity: 0.8; margin: 0; text-align: center; }
h3 { align-self: flex-start; margin: 8px 0 4px; opacity: 0.7; font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.05em; }
.filter-row { display: flex; flex-wrap: wrap; gap: 8px; width: 100%; }
.chip { background: #1a2030; border: 1px solid #313a4b; border-radius: 999px; padding: 8px 14px; cursor: pointer; font-size: 0.9rem; display: flex; align-items: center; gap: 6px; }
.chip input { accent-color: #ff4f70; }
button { font: inherit; border: none; border-radius: 12px; padding: 12px 20px; cursor: pointer; }
button.primary { background: #ff4f70; color: white; font-weight: 600; width: 100%; }
button.ghost { background: transparent; color: #f4f1e8; border: 1px solid #313a4b; }
button.reject, button.accept { font-size: 1.8rem; width: 72px; height: 72px; border-radius: 50%; }
button.reject { background: #2a1420; }
button.accept { background: #14301f; }
.game { width: 100%; }
.topbar { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.remaining { opacity: 0.7; font-variant-numeric: tabular-nums; }
.card { width: 100%; aspect-ratio: 2 / 3; max-height: 60vh; background: #14161f; border-radius: 20px; overflow: hidden; touch-action: none; user-select: none; cursor: grab; box-shadow: 0 20px 60px rgba(0,0,0,0.5); display: flex; flex-direction: column; }
.card.static { cursor: default; aspect-ratio: 2/3; max-height: 50vh; }
.poster { flex: 1; background-size: cover; background-position: center; display: flex; align-items: center; justify-content: center; background-color: #1a2030; }
.poster-fallback { font-size: 4rem; }
.card-info { padding: 16px; background: linear-gradient(0deg, rgba(0,0,0,0.6), transparent); }
.card-info h2 { margin: 0 0 4px; }
.meta { display: flex; gap: 12px; opacity: 0.8; margin: 0 0 8px; font-size: 0.9rem; }
.badges { display: flex; gap: 6px; flex-wrap: wrap; }
.badge { background: rgba(255,255,255,0.1); border-radius: 999px; padding: 2px 10px; font-size: 0.75rem; }
.actions { display: flex; gap: 24px; justify-content: center; width: 100%; }
.actions.column { flex-direction: column; width: 100%; }
.hint { opacity: 0.5; font-size: 0.8rem; text-align: center; }
.spinner { width: 32px; height: 32px; border: 3px solid #313a4b; border-top-color: #ff4f70; border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.error p { opacity: 0.8; }
@media (max-width: 400px) { .actions { gap: 16px; } button.reject, button.accept { width: 60px; height: 60px; font-size: 1.5rem; } }
""".trimIndent()
