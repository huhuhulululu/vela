#!/usr/bin/env python3
"""Optional cold backup of the sync database.

Uses sqlite3.Connection.backup() for a consistent snapshot. The snapshot file
is mode 0600. The destination directory is mode 0700 (root-only when the
directory is owned by root). Snapshots older than 7 days are removed.
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import app

KEEP_SECONDS = 7 * 24 * 3600


def backup(db_path, dest_dir, now=None):
    os.makedirs(dest_dir, mode=0o700, exist_ok=True)
    os.chmod(dest_dir, 0o700)
    if hasattr(os, "geteuid") and os.geteuid() != 0:
        sys.stderr.write(
            "backup directory should be owned by root with mode 0700 in production\n"
        )
    when = time.time() if now is None else now
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(when))
    name = "sync-%s.sqlite" % stamp
    dest = os.path.join(dest_dir, name)
    if os.path.exists(dest):
        dest = os.path.join(dest_dir, "sync-%s-%d.sqlite" % (stamp, os.getpid()))
    fd = os.open(dest, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
    os.close(fd)
    src = app.connect(db_path)
    dst = sqlite3.connect(dest)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()
    os.chmod(dest, 0o600)
    purge_old(dest_dir, when)
    return dest


def purge_old(dest_dir, now):
    cutoff = now - KEEP_SECONDS
    for name in os.listdir(dest_dir):
        if not (name.startswith("sync-") and name.endswith(".sqlite")):
            continue
        path = os.path.join(dest_dir, name)
        try:
            if os.path.isfile(path) and os.path.getmtime(path) < cutoff:
                os.remove(path)
        except OSError:
            continue


def main(argv=None):
    parser = argparse.ArgumentParser(description="Consistent cold backup of the sync database")
    parser.add_argument("--db", required=True)
    parser.add_argument("--dest", required=True)
    args = parser.parse_args(argv)
    path = backup(args.db, args.dest)
    sys.stdout.write(path + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
