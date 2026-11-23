#!/usr/bin/env python3
"""Fails when native code of this project could write the state of a model to a file.

    check_state_files.py --nm NM --source DIR [--source DIR ...] LIBRARY [LIBRARY ...]

llama.cpp has two calls that write a state to a file in clear, llama_state_save_file and
llama_state_seq_save_file. A memory leaves a session only through the caller's buffer, a piece
at a time (pp_session_export_memory), and what becomes of a piece is the caller's affair; no
native code of this project may hand a state to a file itself. Two things are looked at, each
for what the other cannot see:
- the libraries as they were built: none may refer to either function, whatever the source
  looks like (a macro, a function pointer);
- the sources under the given directories, which include code that the host does not build
  (the JNI binding): none may call either function. Naming one in a comment is not a call.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

from pathlib import Path

FORBIDDEN = ("llama_state_save_file", "llama_state_seq_save_file")
CALL = re.compile(r"\b(" + "|".join(FORBIDDEN) + r")\s*\(")
SOURCES = {".c", ".cc", ".cpp", ".h", ".hpp"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nm", required=True)
    parser.add_argument("--source", type=Path, action="append", default=[])
    parser.add_argument("libraries", type=Path, nargs="+")
    args = parser.parse_args()

    found = []
    for library in args.libraries:
        symbols = subprocess.run([args.nm, "--undefined-only", str(library)], capture_output=True, text=True, check=True).stdout
        for name in FORBIDDEN:
            if re.search(r"\b" + name + r"\b", symbols):
                found.append(f"{library.name} refers to {name}")
    n_files = 0
    for directory in args.source:
        for path in sorted(directory.rglob("*")):
            if path.suffix not in SOURCES or not path.is_file():
                continue
            n_files += 1
            for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                code = line.split("//", 1)[0]
                match = CALL.search(code)
                if match:
                    found.append(f"{path.name}:{number} calls {match.group(1)}")
    for problem in found:
        print(f"FAIL  {problem}: a state of the model must not be written to a file")
    if not found:
        print(f"ok    no reference to {' or '.join(FORBIDDEN)} in {len(args.libraries)} libraries, and no call in {n_files} source files")
    # A directory that holds no source is a path that has moved, not a rule that holds.
    if n_files == 0:
        print("FAIL  no source file was found under the directories given")
        return 1
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
