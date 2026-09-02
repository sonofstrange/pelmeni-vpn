from __future__ import annotations

import ast
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path(__file__).resolve().parents[1] / "pelmeni_desktop" / "android_scripts.json"


def joined_method(source: str, name: str) -> str:
    match = re.search(
        rf"private static String {re.escape(name)}\(\)\s*\{{\s*"
        rf"return String\.join\(\"\\n\",(?P<body>.*?)\);\s*\}}",
        source,
        re.DOTALL,
    )
    if not match:
        raise RuntimeError(f"Android method {name} was not found")
    tokens = re.findall(r'"(?:\\.|[^"\\])*"', match.group("body"))
    return "\n".join(ast.literal_eval(token) for token in tokens)


def main() -> None:
    manager = (ROOT / "app/src/main/java/com/example/sshtunnel/ServerAccessManager.java").read_text(encoding="utf-8")
    public = (ROOT / "app/src/main/java/com/example/sshtunnel/PublicServerManager.java").read_text(encoding="utf-8")
    payload = {
        "worker": joined_method(manager, "workerPython"),
        "policy": joined_method(manager, "policyPython"),
        "public_installer": joined_method(public, "installerPython"),
        "public_claim": joined_method(public, "claimPython"),
    }
    OUTPUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
