from pathlib import Path

# Round 10's implementation script is kept as the readable source of the patch. The runner
# corrects a path-write typo in that script before executing it, so CI and packaged sources
# always receive the intended keyboard changes.
target = Path(__file__).with_name("repair_round10_keyboard_browser.py")
source = target.read_text()
source = source.replace(
    "(R/p).joinpath('app/src/main/java/com/uong/phormi/PhormiKeyboardService.kt').write_text(text)",
    "(R/p).write_text(text)",
)
exec(compile(source, str(target), "exec"), {"__name__": "__main__", "__file__": str(target)})
