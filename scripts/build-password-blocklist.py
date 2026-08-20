#!/usr/bin/env python3
"""Build the checked-in password digest artifact without network access."""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import unicodedata

UPSTREAM_SHA256 = "c2e5696882c603b76bb67a47ee970897e5a76fc4c3f5547abe3d0ca340c576e0"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seclists", required=True, type=pathlib.Path)
    parser.add_argument("--repository-list", required=True, type=pathlib.Path)
    parser.add_argument("--output-directory", required=True, type=pathlib.Path)
    args = parser.parse_args()

    upstream = args.seclists.read_bytes()
    actual = hashlib.sha256(upstream).hexdigest()
    if actual != UPSTREAM_SHA256:
        raise SystemExit(f"SecLists SHA-256 mismatch: expected {UPSTREAM_SHA256}, got {actual}")

    digests: set[bytes] = set()
    for source in (upstream.decode("utf-8").splitlines(), args.repository_list.read_text(encoding="utf-8").splitlines()):
        for password in source:
            normalized = unicodedata.normalize("NFC", password)
            digests.add(hashlib.sha256(normalized.encode("utf-8")).digest())

    artifact = b"".join(sorted(digests))
    args.output_directory.mkdir(parents=True, exist_ok=True)
    (args.output_directory / "password-blocklist.sha256").write_bytes(artifact)
    (args.output_directory / "password-blocklist.properties").write_text(
        "source.name=SecLists 100k-most-used-passwords-NCSC\n"
        "source.version=2026.1\n"
        "source.path=Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt\n"
        f"source.sha256={UPSTREAM_SHA256}\n"
        f"artifact.count={len(digests)}\n"
        f"artifact.sha256={hashlib.sha256(artifact).hexdigest()}\n"
        "license=MIT\n",
        encoding="ascii",
    )


if __name__ == "__main__":
    main()
