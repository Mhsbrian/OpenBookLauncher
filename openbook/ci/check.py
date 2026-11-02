#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""OpenBook Launcher's CI: checks what OpenBook changed on top of upstream AOSP Launcher3.

usage: check.py [--root DIR] [--upstream REF]

REF is the untouched upstream branch (default origin/aosp/android17-release). Checks:
  upstream    REF is an ancestor of HEAD: main is upstream plus OpenBook's commits
  identity    every non-merge commit above REF has an allowed author and committer
  hygiene     files OpenBook added or changed: no evidence, logs, build output, disk images, archives, APKs, keys,
              private keys, home-network addresses, stray images or big files (OpenBook's check-hygiene.py rules)
  whitespace  `git diff --check` on OpenBook's changes
  xml         XML files OpenBook added or changed parse
The build itself is the author's lane build in OpenBook's AOSP tree, recorded in the pull request: Launcher3 only
builds inside an AOSP tree. Exit 0 when clean, 1 with one line per finding.
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# OpenBook's own identities. The owner's no-reply address only: never a personal address or full name.
# "B" is the account's GitHub display name, which GitHub's squash merges use as the author.
OWN = {
    "9922368+Mhsbrian@users.noreply.github.com": {"B Honey", "B"},
    "noreply@anybook.invalid": {"OpenBook", "AnyBook aosp-patches.sh"},
}
GITHUB = ("GitHub", "noreply@github.com")  # the committer of merges made on GitHub
# Upstream commits picked into main keep their authors (AOSP's own addresses).
THIRD_PARTY_AUTHOR = re.compile(r"@(google\.com|android\.com)$")

MAX_BYTES = 1024 * 1024
FORBIDDEN_DIRS = re.compile(r"^(evidence|logs|out|dist)/")
FORBIDDEN_NAMES = re.compile(
    r"\.(log|img|iso|qcow2|raw|vmdk|tar|tgz|xz|zst|zip|apk|aab|pk8|key|p12|jks|keystore)$|\.tar\.[a-z0-9]+$", re.I)
# Drawables live in res*/ and test goldens in assets/; captures and screenshots go on the issue.
IMAGE = re.compile(r"\.(png|jpe?g|gif|webp)$", re.I)
IMAGE_OK = re.compile(r"(^|/)(res[^/]*|assets)/")
PRIVATE_KEY = re.compile(rb"^-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]{64,}", re.M)
# A home-network address points at somebody's own machines. Examples use 192.0.2.0/24 (RFC 5737).
HOME_LAN_IPV4 = re.compile(rb"(?<![\d.])192\.168\.\d{1,3}\.\d{1,3}(?![\d.])")
TEXT_LIMIT = 4 * MAX_BYTES


def git(root, *args, check=True):
    return subprocess.run(["git", "-C", str(root), *args], check=check, capture_output=True)


def own(name, email):
    return name in OWN.get(email, ())


def identity(root, upstream):
    out = git(root, "log", "--no-merges", "-z", "--format=%h%x1f%an%x1f%ae%x1f%cn%x1f%ce", f"{upstream}..HEAD")
    for rec in filter(None, out.stdout.decode().split("\0")):
        sha, an, ae, cn, ce = rec.strip("\n").split("\x1f")
        # The rejected name and address are not printed: CI logs must not copy them either.
        if not own(an, ae) and not THIRD_PARTY_AUTHOR.search(ae):
            yield f"commit {sha}", "the author is not an allowed identity (git log -1 --format='%an <%ae>' to see it)"
        if not own(cn, ce) and (cn, ce) != GITHUB:
            yield f"commit {sha}", "the committer is not an allowed identity (git log -1 --format='%cn <%ce>')"


def changed(root, upstream):
    out = git(root, "diff", "-z", "--name-only", "--diff-filter=AMR", f"{upstream}...HEAD").stdout
    return [p for p in out.decode().split("\0") if p]


def hygiene(root, names):
    for name in names:
        path = root / name
        if FORBIDDEN_DIRS.search(name):
            yield name, "evidence, logs and build output stay outside the repository"
            continue
        if FORBIDDEN_NAMES.search(name):
            yield name, "disk images, archives, APKs, logs and keys are never committed"
            continue
        if IMAGE.search(name) and not IMAGE_OK.search(name):
            yield name, "images belong in a res*/ or assets/ directory; screenshots go on the issue"
        if path.is_symlink() or not path.is_file():
            continue
        size = path.stat().st_size
        if size > MAX_BYTES:
            yield name, f"{size} bytes is over the {MAX_BYTES}-byte limit"
        with path.open("rb") as handle:
            head = handle.read(TEXT_LIMIT)
        if PRIVATE_KEY.search(head):
            yield name, "contains a private key"
        if b"\0" not in head[:8192]:
            match = HOME_LAN_IPV4.search(head)
            if match:  # the line, not the address, so the CI log doesn't copy it
                line = head.count(b"\n", 0, match.start()) + 1
                yield f"{name}:{line}", "contains a home-network address; use 192.0.2.x in examples"


def whitespace(root, upstream):
    out = git(root, "diff", "--check", f"{upstream}...HEAD", check=False)
    for line in out.stdout.decode(errors="replace").splitlines():
        if not line.startswith(("+", "-")):
            yield line.split(": ", 1)[0], "whitespace: " + line.split(": ", 1)[-1]


def xml(root, names):
    for name in names:
        if name.endswith(".xml") and (root / name).is_file():
            try:
                ET.parse(root / name)
            except ET.ParseError as err:
                yield name, f"XML does not parse: {err}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--upstream", default="origin/aosp/android17-release", metavar="REF")
    args = parser.parse_args()
    root, up = args.root.resolve(), args.upstream
    if git(root, "rev-parse", "-q", "--verify", f"{up}^{{commit}}", check=False).returncode:
        print(f"upstream: {up} is not a commit here (fetch it, e.g. a checkout with fetch-depth 0)")
        return 1
    if git(root, "merge-base", "--is-ancestor", up, "HEAD", check=False).returncode:
        print(f"upstream: {up} is not an ancestor of HEAD; main must be upstream plus OpenBook's commits")
        return 1
    names = changed(root, up)
    problems = [*identity(root, up), *hygiene(root, names), *whitespace(root, up), *xml(root, names)]
    for name, reason in problems:
        print(f"{name}: {reason}")
    if problems:
        print(f"{len(problems)} finding(s). See README.md, \"Rules\".", file=sys.stderr)
        return 1
    count = int(git(root, "rev-list", "--count", "--no-merges", f"{up}..HEAD").stdout)
    print(f"openbook checks: ok ({count} commits and {len(names)} files above {up})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
