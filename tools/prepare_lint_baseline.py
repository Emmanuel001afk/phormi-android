from pathlib import Path
p=Path(__file__).resolve().parents[1]/'app/build.gradle.kts'
t=p.read_text()
if 'baseline = file("lint-baseline.xml")' not in t:
    needle='''android {\n'''
    replacement='''android {\n    lint {\n        // Baseline captures pre-existing legacy lint findings; CI still fails on newly introduced errors.\n        baseline = file("lint-baseline.xml")\n    }\n'''
    if needle in t:
        t=t.replace(needle,replacement,1)
        p.write_text(t)
        print('Lint baseline configuration enabled')
    else:
        raise SystemExit('android block not found')
else:
    print('Lint baseline configuration already enabled')
