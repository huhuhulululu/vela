#!/usr/bin/env python3
"""Replace meta.epoch with a new random value.

Run this immediately after restoring a snapshot, before serving traffic.
Clients that still hold the old epoch stop writing (403 / epoch mismatch).
"""

from __future__ import annotations

import argparse
import os
import secrets
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import app


def rotate(db_path):
    conn = app.connect(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            row = conn.execute("SELECT value FROM meta WHERE key='epoch'").fetchone()
            if row is None:
                conn.execute("ROLLBACK")
                raise SystemExit("epoch missing")
            old = row[0]
            new = secrets.token_hex(16)
            if new == old:
                new = secrets.token_hex(16)
            conn.execute("UPDATE meta SET value=? WHERE key='epoch'", (new,))
            conn.execute("COMMIT")
            return new
        except SystemExit:
            raise
        except Exception:
            try:
                conn.execute("ROLLBACK")
            except sqlite3.Error:
                pass
            raise
    finally:
        conn.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Rotate the sync server epoch")
    parser.add_argument("--db", required=True)
    args = parser.parse_args(argv)
    sys.stdout.write(rotate(args.db) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
