#!/usr/bin/env python3
"""Push only the files that changed, as ONE commit.

    python3 tools/push_to_github.py --dry-run          # show what would go up
    python3 tools/push_to_github.py -m "v4.5: ..."     # push it

Why this exists: uploading files one at a time through the Contents API makes
one commit — and therefore one CI build — per file. Pushing twelve files that
way produced eleven builds of half-updated trees, all of which failed, plus one
that passed. This script diffs against the remote tree first and sends a single
commit containing only the changed blobs.

Needs `gh` on PATH, authenticated with write access (Contents: write) to the
target repo — a normal user token works; a GitHub App installation token that
only has read access will fail on the first blob with a 403.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = os.environ.get("AUDTLINK_REPO", "Aditya-FullStack/AuDTLink")
BRANCH = os.environ.get("AUDTLINK_BRANCH", "main")
SKIP_DIRS = {".git", "__pycache__", "build", ".gradle", ".idea", "dist"}
SKIP_SUFFIX = {".pyc"}


def gh(path: str, method: str = "GET", payload=None):
    cmd = ["gh", "api", "-X", method, path]
    if payload is None:
        proc = subprocess.run(cmd, capture_output=True, text=True)
    else:
        cmd += ["--input", "-"]
        proc = subprocess.run(cmd, input=json.dumps(payload), capture_output=True, text=True)
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).strip()
        if "Resource not accessible by integration" in err:
            raise SystemExit(
                "gh cannot write git objects with this token (403).\n"
                "  The credential is a GitHub App installation token without\n"
                "  Contents:write on %s. Either use `git push` with your own\n"
                "  credentials, or connect an account that can write." % REPO
            )
        raise SystemExit("gh api %s failed: %s" % (path, err[:300]))
    return json.loads(proc.stdout or "{}")


def git_blob_sha(data: bytes) -> str:
    """Same hash git itself would give the file — the API compares these."""
    h = hashlib.sha1()
    h.update(b"blob %d\0" % len(data))
    h.update(data)
    return h.hexdigest()


def local_files() -> dict:
    out = {}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if pathlib.Path(fn).suffix in SKIP_SUFFIX:
                continue
            full = pathlib.Path(dirpath) / fn
            rel = full.relative_to(ROOT).as_posix()
            data = full.read_bytes()
            out[rel] = (git_blob_sha(data), data)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-m", "--message", default="Update from local tree")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    head = gh("repos/%s/commits/%s" % (REPO, BRANCH))
    head_sha = head["sha"]
    base_tree = head["commit"]["tree"]["sha"]

    remote_tree = gh("repos/%s/git/trees/%s?recursive=1" % (REPO, base_tree))
    remote = {e["path"]: e["sha"] for e in remote_tree.get("tree", []) if e.get("type") == "blob"}

    local = local_files()
    added = sorted(p for p in local if p not in remote)
    modified = sorted(p for p in local if p in remote and local[p][0] != remote[p])
    deleted = sorted(p for p in remote if p not in local)

    print("remote %s @ %s" % (BRANCH, head_sha[:10]))
    print("  unchanged : %d" % (len(local) - len(added) - len(modified)))
    print("  added     : %d" % len(added))
    for p in added:
        print("      + %s" % p)
    print("  modified  : %d" % len(modified))
    for p in modified:
        print("      ~ %s" % p)
    print("  deleted   : %d" % len(deleted))
    for p in deleted:
        print("      - %s" % p)

    if not (added or modified or deleted):
        print("\nnothing to push — remote is up to date")
        return 0

    if args.dry_run:
        print("\ndry run: %d file(s) would go up in one commit" % (len(added) + len(modified) + len(deleted)))
        return 0

    tree = []
    for path in added + modified:
        blob = gh("repos/%s/git/blobs" % REPO, "POST", {
            "content": base64.b64encode(local[path][1]).decode("ascii"),
            "encoding": "base64",
        })
        tree.append({"path": path, "mode": "100755" if path.endswith("airlink_cli.py") else "100644",
                     "type": "blob", "sha": blob["sha"]})
    for path in deleted:
        tree.append({"path": path, "mode": "100644", "type": "blob", "sha": None})

    new_tree = gh("repos/%s/git/trees" % REPO, "POST", {"base_tree": base_tree, "tree": tree})
    commit = gh("repos/%s/git/commits" % REPO, "POST", {
        "message": args.message,
        "tree": new_tree["sha"],
        "parents": [head_sha],
    })
    gh("repos/%s/git/refs/heads/%s" % (REPO, BRANCH), "PATCH",
       {"sha": commit["sha"], "force": False})

    print("\npushed 1 commit: %s (%d file(s))" % (commit["sha"][:10], len(tree)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
