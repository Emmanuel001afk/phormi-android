#!/usr/bin/env python3
"""Mirror JUnit XML failures into GitHub check annotations."""
from __future__ import annotations

import glob
import xml.etree.ElementTree as ET


def main() -> None:
    for path in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
        for case in ET.parse(path).getroot().iter("testcase"):
            failure = case.find("failure")
            if failure is None:
                continue
            name = f"{case.get('classname')}.{case.get('name')}"
            message = " ".join((failure.get("message") or failure.text or "").split())[:500]
            print(f"::error title=Failing unit test::{name}: {message}")


if __name__ == "__main__":
    main()
