from __future__ import annotations

import argparse

from pelmeni_desktop.tun_broker import run_tun_helper


def main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--request", required=True)
    args = parser.parse_args()
    return run_tun_helper(args.request)


if __name__ == "__main__":
    raise SystemExit(main())
