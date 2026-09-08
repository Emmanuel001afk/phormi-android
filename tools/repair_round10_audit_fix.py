from pathlib import Path
R = Path(__file__).resolve().parents[1]
p = R / 'app/src/main/java/com/uong/phormi/PhormiQuickAccessRenderer.kt'
s = p.read_text()
s = s.replace(
    'val favorites = PhormiFavorites.getAll(context).map { Item(it.title, it.url, "Favorite") }',
    'val favorites = PhormiFavorites.getAll(context).filter { PhormiFavorites.contains(context, it.url) }.map { Item(it.title, it.url, "Favorite") }',
)
s = s.replace(
    'val frequent = PhormiVisitTracker.top(context, 20).map { Item(it.title, it.url, "Frequent", it.visits) }',
    'val frequent = PhormiVisitTracker.top(context, 20).filter { it.visits > 10 }.take(10).map { Item(it.title, it.url, "Frequent", it.visits) }',
)
p.write_text(s)
print('Round 10 audit markers and visit threshold fixed')
