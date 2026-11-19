#!/usr/bin/env python3
"""Report when stored envelope bytes reach 70% of the 256 MiB cap.

Prints totals only (no blob ids). Exit 0 when under the threshold, 1 when
at or over it, 2 when the database cannot be read.
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import app


def main(argv=None):
    parser = argparse.ArgumentParser(description="Sync storage healthcheck")
    parser.add_argument("--db", required=True)
    args = parser.parse_args(argv)
    if not os.path.isfile(args.db):
        sys.stderr.write("db not found\n")
        return 2
    try:
        conn = app.connect(args.db)
        try:
            total = int(conn.execute("SELECT total(size) FROM blobs").fetchone()[0] or 0)
            spaces = int(conn.execute("SELECT count(*) FROM blobs").fetchone()[0])
        finally:
            conn.close()
    except sqlite3.Error:
        sys.stderr.write("db unreadable\n")
        return 2
    if total >= app.HEALTH_BYTE_LIMIT:
        sys.stdout.write(
            "ALERT total_bytes %d spaces %d threshold %d\n"
            % (total, spaces, app.HEALTH_BYTE_LIMIT)
        )
        return 1
    sys.stdout.write("ok total_bytes %d spaces %d\n" % (total, spaces))
    return 0


if __name__ == "__main__":
    sys.exit(main())
