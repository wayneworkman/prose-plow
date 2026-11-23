# Moving the llama.cpp pin: a runbook

This file explains how to move the llama.cpp commit that Prose Plow is built on to a newer upstream
release, and how to bring the project's patches along with it. It is written for someone who
did not write the patches. Every step is a command or a check, and each check says what you
should see. Where the result can go more than one way, the step says what to do in each case.
Where the right answer depends on what upstream changed, the step tells you to **stop and ask**.

**In every new shell, source the session file first.** Section 2.3 writes it once, to
`work/pin-move/session.sh`. Every block of commands below begins with the line
`. work/pin-move/session.sh`, so each block works on its own, even in a shell that remembers
nothing from the block before.

**Stop and ask** always means the same thing:

1. Do not edit anything further, and do not commit.
2. Write down the step number, the command, and its full output.
3. Ask the project owner, and include what you wrote down. Leave the repository as it is: the
   way back in section 8 works from any point.

Guessing is never the right choice here. A wrong patch can still compile and still pass most
tests while it quietly removes a guarantee, such as the guarantee that a conversation's token
ids are wiped from memory. Deleting a patch, changing a pinned model hash, or widening what the
build allows are decisions for the project owner, never for the person running this runbook.

The background lives in `native/README.md`. "The submodule is never edited" explains the patched
copy and the five patches. "What the tests check" lists every host test. "Moving the upstream pin"
explains which upstream behaviour the project relies on. This runbook turns that into steps, so
read those three parts once before you start.

Conventions used below:

- Every command is run from the root of the repository, the directory that holds `README.md`
  and `native/`. All paths are relative to that directory.
- `capped` is a shell function that the session file defines. It runs a command under a memory
  limit. Every script, build, conversion and test in this runbook goes through it.
- Everything this procedure writes goes under `work/pin-move/`. Git ignores `work/` (see
  `.gitignore`), so the procedure leaves no untracked file in the repository.
- The commands were written for, and the cheap ones tested on, Ubuntu 24.04 with GNU patch
  2.7.6 and git 2.43.0. The heavy ones (host suite, Gradle, phone) were not re-run for this file.

## 1. What is pinned and where it is recorded

The pinned commit is written in two places, and the two must always agree:

| Where | What it holds | How it is changed |
| --- | --- | --- |
| `third_party/llama.cpp` | The git submodule. The repository records the commit it points to | `git add third_party/llama.cpp` after checking out the new commit in it |
| `native/patches/UPSTREAM_COMMIT` | One line: the full 40-character hash, followed by a newline | Overwritten with the new hash |

`native/tools/prepare-llama-src.sh` and `native/tools/make-hook-patch.sh` both refuse to run
while these two disagree. Several files read `UPSTREAM_COMMIT`, and you do not edit them: both
CMake projects (`native/CMakeLists.txt` and `llama/src/main/cpp/CMakeLists.txt`) take the
version stamp from it, `llama/build.gradle.kts` uses it as an input of the
`prepareLlamaSource` task, and `app/src/test/java/us/theworkmans/proseplow/ThirdPartyNoticesTest.kt`
compares the notices with it.

These files repeat the commit as text, and you must update them by hand (section 7):

| File | What names the commit |
| --- | --- |
| `README.md` | The llama.cpp row of the versions table (`Commit \`<hash>\``), and the expected output of `git submodule status` under "Get the source" |
| `app/notices/notices.json` | The entry `native:llama.cpp` (`"commit"` and `"version"`), and every entry inside llama.cpp whose `"version"` is `"as in llama.cpp <first 7 characters>"` |
| `app/notices/NOTICES.txt` | The line `llama.cpp and ggml (commit <hash> of https://github.com/ggml-org/llama.cpp)` |

These files count the patches or describe each one. They change only when a patch is added,
removed or changes its purpose, which a normal pin move does not do:

| File | What it says |
| --- | --- |
| `README.md` | The first paragraph: "with five small patches" |
| `FEATURES.md` | NAT-02: "at a pinned commit with five small patches" |
| `native/README.md` | "The submodule is never edited": the line counts of each patch and "There are four" (the four patches besides 0001) |
| `app/notices/notices.json`, `app/notices/NOTICES.txt` | The `changes` text of `native:llama.cpp`, which counts the patches and names each one, and the `changes` text of `native:tinyblas`, which names patch 0002 |

To find every one of these yourself, run this before you start and keep the output:

```sh
git grep -n -I -E "$(cut -c1-7 native/patches/UPSTREAM_COMMIT)" -- . ':!native/patches'
git grep -n -I -i -E '(four|five|six) (small )?patches|patch 000[0-9]' -- . ':!native/patches'
```

The first command must list only lines in the three files of the table of commit mentions
above. If it lists any other file, add that file to your list for section 7. The second command
lists every place that counts the patches or names one of them by number. Each place that
counts them must be in the last table. A sentence that only names one patch (such as "patch
0004 of `native/patches`" in a test's comment) does not need changing.

## 2. Before starting

### 2.1 What you need, and for which steps

| What | Where it comes from | Needed for |
| --- | --- | --- |
| git, GNU patch, bash, tar, `systemd-run` | The system (on Ubuntu, `patch` is its own package) | Everything |
| Network access | | Section 3 (one fetch) |
| The Python virtualenv with `native/requirements.txt` installed (CMake, Ninja, PyTorch and the converters' packages) | `native/README.md`, "What you need", which creates it with `python3 -m venv` and `pip install -r native/requirements.txt` (the root `README.md` repeats this under "Locations and environment") | Step 2.5 and section 6 (host build, conversions, host tests) |
| All five model checkpoints, including the optional `peacebell-v1-148m` and `shard-2` | The root `README.md`, "Get the model checkpoints", which downloads each one at the revision `models/manifest.json` pins | Step 2.5 and sections 6.3, 6.4 and 6.6. Without a checkpoint its tests are skipped, and a skipped test proves nothing. Shard-2 matters because its hooks are part of patch 0001 |
| The JDK and Android SDK packages | The root `README.md`, "Building on Ubuntu 24.04" | Sections 6.5 and 6.6 |
| An arm64 Android phone with USB debugging enabled, and `adb` | `native/README.md`, "On the phone" | Section 6.6 |
| About 30 GB of free disk | The root `README.md`, "Disk space and time" | Section 6 |

**Sections 3 and 4 need neither the virtualenv nor the checkpoints.** Moving the pin and
re-applying the patches use only git, patch and the project's shell scripts. If you do not have
the virtualenv or the checkpoints yet, you can still do sections 3 and 4. You must skip 2.5,
and you cannot finish the move: stop after section 4, and say in your hand-over that 2.5 and
section 6 remain to be done.

How long the steps take, measured on a 16-thread desktop with 6 compile jobs:

| Step | Time |
| --- | --- |
| `prepare-llama-src.sh` | under a second |
| `make-hook-patch.sh` | about 5 seconds |
| Host build | about 40 seconds from nothing |
| Converting every model, F16 and F32 | about a minute |
| Host test suite (`ctest`) | 8 to 14 minutes |
| `./gradlew check`, then `:app:assembleDebug` | about 1 and 1 to 2.5 minutes |
| Building and installing the phone test APK, then running it | about 2 minutes, then about 4 |

If no patch needs work, the whole move takes about an hour. Each patch that has to be redone
adds the time it takes to understand that patch.

### 2.2 The state of the repository

Run each command and compare its output with the expected result. If any check fails, fix it
before you go on. If you cannot fix it, stop and ask.

1. Be on `main` with nothing uncommitted:

   ```sh
   git branch --show-current
   git status --porcelain
   ```

   The first command prints `main`. The second prints nothing at all.

2. The submodule is checked out at the pinned commit:

   ```sh
   git submodule status third_party/llama.cpp
   cat native/patches/UPSTREAM_COMMIT
   ```

   The first line begins with a space (not `-` or `+`), followed by the same 40-character hash
   that the second command prints, then ` third_party/llama.cpp`, then a tag in parentheses,
   such as `(b12636)`. If the first character is `-`, run
   `git submodule update --init third_party/llama.cpp` and check again.

3. Line endings are not converted. The patches must apply exactly, so a checkout that
   rewrites line endings breaks them:

   ```sh
   git config --get core.autocrlf
   ```

   This prints nothing, `false` or `input`. If it prints `true`, stop: clone again with
   `git clone -c core.autocrlf=false --recurse-submodules ...`, as the root `README.md` says
   for Windows.

4. The tools exist:

   ```sh
   git --version; patch --version | head -n 1; systemd-run --version | head -n 1
   ```

   All three print a version. `patch` is GNU patch.

### 2.3 The session file

This file holds every setting the procedure needs: the locations, the memory limit (the
`capped` function) and the values that the later steps write down. **In every new shell,
source this file first**, from the root of the repository. Every block below already begins with
that line.

**First, write the file.** Paste this block unchanged. It is not indented, so that the
line `EOF` that ends it stays at the start of a line:

```sh
mkdir -p work/pin-move
cat > work/pin-move/session.sh <<'EOF'
# Sourced at the start of every block of native/patches/README.md, from the repository root.
if [ ! -f native/patches/UPSTREAM_COMMIT ] || [ ! -d third_party ]; then
    echo "session.sh: source this from the root of the repository" >&2
    return 1
fi
export PROSEPLOW_VENV=/path/to/venv
export PROSEPLOW_MODEL_SRC=/path/to/checkpoints
export PROSEPLOW_BUILD="$PWD/work/pin-move/build"
export PROSEPLOW_JOBS=6 PROSEPLOW_THREADS=6
unset PROSEPLOW_LLAMA_SRC PROSEPLOW_MODEL_OUT
. ./env.sh
. native/tools/env.sh
export REPO="$PWD" PIN_WORK="$PWD/work/pin-move/notes"
mkdir -p "$PROSEPLOW_BUILD" "$PIN_WORK/original-patches" "$PIN_WORK/hold"
capped() { systemd-run --user --scope --quiet -p MemoryMax=8G -p MemorySwapMax=0 -- "$@"; }
if [ -f "$PIN_WORK/old-commit" ]; then OLD="$(cat "$PIN_WORK/old-commit")"; fi
if [ -f "$PIN_WORK/new-commit" ]; then NEW="$(cat "$PIN_WORK/new-commit")"; fi
if [ -f "$PIN_WORK/new-tag" ]; then NEW_TAG="$(cat "$PIN_WORK/new-tag")"; fi
if [ -f "$PIN_WORK/current-patch" ]; then NAME="$(cat "$PIN_WORK/current-patch")"; fi
EOF
```

**Then set the two paths.** Open `work/pin-move/session.sh` in an editor and change the two
`/path/to/...` lines:

- `PROSEPLOW_VENV`: the directory of the virtualenv that `native/README.md` ("What you need")
  had you create, the one that holds `bin/python`.
- `PROSEPLOW_MODEL_SRC`: the directory that the root `README.md` ("Get the model checkpoints")
  had you download the checkpoints into, the one that holds one directory per model id.

If you do not have them yet (see 2.1), leave both lines as they are. Sourcing the file then
prints `native/tools/env.sh: PROSEPLOW_VENV=/path/to/venv has no bin/python`. That warning is
expected, and it only matters from 2.5 and section 6 on.

**Then check it:**

```sh
. work/pin-move/session.sh
echo "$PROSEPLOW_LLAMA_SRC"; echo "$PROSEPLOW_MODEL_OUT"; echo "$PIN_WORK"
type capped | head -n 1
git check-ignore -q work/pin-move && echo "ignored by git"
```

The three paths end in `work/pin-move/build/llama-src`, `work/pin-move/build/models` and
`work/pin-move/notes`. Then `capped is a function`, then `ignored by git`.

What the file does, so that you know what to expect:

- The attempt gets its own build directory, `work/pin-move/build`. The build you have now stays
  as it is, and an abandoned attempt costs nothing but disk space.
- `unset` matters. `native/tools/env.sh` keeps `PROSEPLOW_LLAMA_SRC` and `PROSEPLOW_MODEL_OUT`
  when they are already set. Without `unset`, the attempt would quietly overwrite the patched copy
  and the models of your normal build.
- `capped` runs a command under a memory limit of 8 GB with no swap, because a test that goes
  wrong can allocate memory until the machine stops responding. Gradle always runs with
  `--no-daemon` inside it, so that no Gradle process outlives the limit. A command killed at
  the limit exits with status 137 (check with `echo $?`). That is a fault to understand, not a
  limit to raise: stop and ask. `capped` needs the user's systemd session of a normal login. If
  it prints `Failed to connect to bus`, log in normally (not through `su` or `sudo`) and try again.
- `OLD`, `NEW`, `NEW_TAG` and `NAME` are read from files that the steps below write. Until a step
  has written its file, the variable is not set.

### 2.4 Write down where you started

```sh
. work/pin-move/session.sh
cp native/patches/*.patch native/patches/UPSTREAM_COMMIT "$PIN_WORK/original-patches/"
git -C third_party/llama.cpp rev-parse HEAD > "$PIN_WORK/old-commit"
git -C third_party/llama.cpp describe --tags > "$PIN_WORK/old-tag" || echo "(no tag)" > "$PIN_WORK/old-tag"
cat "$PIN_WORK/old-commit" "$PIN_WORK/old-tag"
ls "$PIN_WORK/original-patches"
```

The first file holds the same hash as `native/patches/UPSTREAM_COMMIT`. The listing shows exactly
six entries: the five patch files and `UPSTREAM_COMMIT`. (`native/patches/README.md`, this file,
is deliberately not copied.) These saved copies are how you get back (section 8) and what you
compare against (section 4).

### 2.5 Prove the starting point

This step needs the virtualenv and the checkpoints (2.1). Run the host check once at the old
pin. A failure later then cannot be blamed on the move when it was already there:

```sh
. work/pin-move/session.sh
capped native/tools/host-check.sh
```

It ends with `100% tests passed, 0 tests failed out of N`. Under "The following tests did not
run", only `peacebell-bpe-packages` may appear, as `(Skipped)`. A line `fatal: not a git
repository` during the CMake configuration is expected. If anything else fails or is skipped,
stop and ask: the starting point is not sound, and the move must wait.

## 3. Moving the pin

### 3.1 Choose the commit

Always move to a **release tag** of upstream (named `b` followed by a number, such as `b12653`).
Never move to the head of a branch: a branch head moves, and the pin must name something that
never changes.

1. If the person who asked for the move named a tag, use that tag.
2. If not, list the newest tags and take the last one printed:

   ```sh
   . work/pin-move/session.sh
   git -C third_party/llama.cpp ls-remote --tags --refs origin 'b*' | sed 's|.*refs/tags/||' | sort -V | tail -n 5
   ```

3. Record the tag (replace `bNNNNN` with the tag):

   ```sh
   . work/pin-move/session.sh
   echo bNNNNN > "$PIN_WORK/new-tag"
   ```

### 3.2 Fetch it and check it out in the submodule

The submodule is normally a **shallow** clone: `.gitmodules` sets `shallow = true`, so
`git clone --recurse-submodules` fetches only the pinned commit. In a shallow clone, a fetch
without `--depth 1` tries to bring the history in between and prints
`rejected ... because shallow roots are not allowed to be updated`. In a full clone, a fetch with
`--depth 1` turns the clone shallow. The block below therefore asks the submodule which kind it
is and uses the right fetch:

```sh
. work/pin-move/session.sh
if [ "$(git -C third_party/llama.cpp rev-parse --is-shallow-repository)" = true ]; then
    git -C third_party/llama.cpp fetch --depth 1 origin tag "$NEW_TAG"
else
    git -C third_party/llama.cpp fetch origin tag "$NEW_TAG"
fi
git -C third_party/llama.cpp -c advice.detachedHead=false checkout --detach "$NEW_TAG"
git -C third_party/llama.cpp rev-parse HEAD > "$PIN_WORK/new-commit"
git -C third_party/llama.cpp rev-parse "$NEW_TAG^{commit}"
cat "$PIN_WORK/new-commit"
```

The fetch prints `* [new tag]  bNNNNN -> bNNNNN` (or nothing, when the tag was fetched
before). The checkout prints `Previous HEAD position was <old 7 characters> ...` and
`HEAD is now at <new 7 characters> ...`. The last two commands print the same 40-character hash. If the fetch fails with
`couldn't find remote ref`, the tag does not exist: go back to 3.1. If a `rejected ...` warning
appears anyway, and the last two commands still print the same hash, the tag arrived and you
can go on.

### 3.3 Record it

```sh
. work/pin-move/session.sh
cp "$PIN_WORK/new-commit" native/patches/UPSTREAM_COMMIT
cat native/patches/UPSTREAM_COMMIT
git submodule status third_party/llama.cpp
```

`UPSTREAM_COMMIT` now holds the new hash on one line. The status line now begins with `+` and
names the new hash. The `+` means the submodule differs from what the last commit recorded,
which is expected until section 7.

### 3.4 See what upstream changed in the files the project depends on

Every command here only reads:

```sh
. work/pin-move/session.sh
sed -n 's|^+++ b/||p' native/patches/*.patch | sort -u > "$PIN_WORK/patched-files"
git -C third_party/llama.cpp diff --stat "$OLD" "$NEW" -- $(cat "$PIN_WORK/patched-files") \
    | tee "$PIN_WORK/patched-files-changed"
git -C third_party/llama.cpp diff --stat "$OLD" "$NEW" -- \
    src/llama-io.h src/llama-memory.h src/llama-graph.h src/llama-graph.cpp src/llama-model.h \
    src/models/models.h conversion/base.py | tee "$PIN_WORK/interfaces-changed"
```

`patched-files` lists the 13 upstream files that the patches change. The first diff shows
which of them upstream changed between the two commits. A patch whose files are all missing
from `patched-files-changed` will apply unchanged. The second diff covers the interfaces that
section 5 is about. Either diff prints nothing when upstream did not change those files, which
is the best case. Both outputs are saved, and the sections below refer to them.

## 4. Re-applying the patches, one at a time, in order

### 4.1 Why one at a time

The patches apply in name order, and each is written against upstream plus the patches before
it. `make-hook-patch.sh` regenerates one patch as the difference between "upstream plus every
**other** patch in `native/patches/`" and an edited copy. A later patch that does not apply
yet would make that fail. So the patches move to a holding directory and come back one by one,
and each one is made to apply before the next comes back.

```sh
. work/pin-move/session.sh
mv native/patches/*.patch "$PIN_WORK/hold/"
ls native/patches
capped native/tools/prepare-llama-src.sh
```

`ls` shows exactly `README.md` and `UPSTREAM_COMMIT`. The script prints
`prepare-llama-src: wrote <directory> (<first 7 characters of the new hash>, 0 patch(es), 9 added file(s))`.

If it prints `native/llama-ext/<file> would replace an upstream file`, upstream now has a file
at the same path as one of the files the project adds. Stop and ask.

### 4.2 The procedure for one patch

Do this for `0001-architecture-hooks.patch`, then `0002-arm-f16-weights-kernel.patch`,
`0003-mmap-window-for-embedded-models.patch`, `0004-linear-byte-fallback-in-spm-tokenizer.patch`
and `0005-wipe-token-ids-of-a-restored-state.patch`.

**Choose the patch.** Write its full file name down. The session file then sets `NAME` from it
in every block (shown here for 0001):

```sh
. work/pin-move/session.sh
echo 0001-architecture-hooks.patch > "$PIN_WORK/current-patch"
```

**Step A: try it.**

```sh
. work/pin-move/session.sh
cp "$PIN_WORK/hold/$NAME" native/patches/
capped native/tools/prepare-llama-src.sh
```

- If it prints `prepare-llama-src: wrote ... (<hash>, K patch(es), 9 added file(s))`, where K
  is the number of patches now in `native/patches/`, the patch applied. `git apply` accepts a
  hunk whose line number moved, as long as its surrounding lines are unchanged. Go to step F.
- If it prints `error: patch failed: <file>:<line>`, `error: <file>: patch does not apply` and
  `prepare-llama-src: <NAME> does not apply to <hash>`, go to step B.

**Step B: look at the failure.** The failed run left the copy alone, so
`$PROSEPLOW_LLAMA_SRC` still holds upstream plus the patches before this one. Check that, then
show each failing hunk, and where GNU patch would put it:

```sh
. work/pin-move/session.sh
grep -c '^patch ' "$PROSEPLOW_LLAMA_SRC/.proseplow-prepared"
( cd "$PROSEPLOW_LLAMA_SRC" && GIT_CEILING_DIRECTORIES="$(dirname "$PROSEPLOW_LLAMA_SRC")" \
    git apply --check -v -p1 "$REPO/native/patches/$NAME" )
( cd "$PROSEPLOW_LLAMA_SRC" && patch -p1 --dry-run < "$REPO/native/patches/$NAME" )
```

The `grep` prints the number of patches before this one (0 for 0001, 1 for 0002, and so on).
`GIT_CEILING_DIRECTORIES` must be set. Without it, `git apply` run inside a directory that lies
within another git repository (as `work/` does) can silently skip files. The `git apply` output
shows, after `error: while searching for:`, the lines it expected and could not find. The
`patch` output has one line per hunk:

| What `patch --dry-run` says for a hunk | Meaning | Next |
| --- | --- | --- |
| `Hunk #n succeeded at L.` or `... (offset K lines).` | Applies as it is | Nothing for this hunk |
| `Hunk #n succeeded at L with fuzz F.` | Applies only when some surrounding lines are ignored | Step C, then check this hunk's place in step D |
| `Hunk #n FAILED at L.` | The lines it removes or its anchor are not there | Step C, then redo this hunk by hand in step D |

**Step C: apply what applies.** This writes into the copy, never into the submodule, and saves
the hunks that failed to `$PIN_WORK/$NAME.rej`:

```sh
. work/pin-move/session.sh
( cd "$PROSEPLOW_LLAMA_SRC" && patch -p1 --no-backup-if-mismatch -r "$PIN_WORK/$NAME.rej" < "$REPO/native/patches/$NAME" )
```

It prints `patching file <file>` for each file and repeats the hunk lines of step B. When a hunk
failed, it ends with `saving rejects to file .../<NAME>.rej`.

**Step D: decide, hunk by hunk.** For each hunk that needed fuzz or failed, open the upstream
file in the copy (`work/pin-move/build/llama-src/<file>`) at the line that `patch` named. Then
use the subsection of this patch (4.3 to 4.7), which says where each hunk belongs and what it
must achieve. Apply these rules in order:

1. **A removed line is gone.** A hunk removes the lines that start with a single `-`. If such a
   line cannot be found in the same function, unchanged or with nothing added to it but more
   items of the same list, upstream changed the code the patch changes. Stop and ask. Search
   with `grep -n -F -- '<the line without its leading ->' work/pin-move/build/llama-src/<file>`.
2. **The function is gone or different.** The text after the second `@@` of a hunk header names
   the function the hunk is in. If that function no longer exists, was renamed, was split up, or
   the subsection's "must still hold" list no longer matches the code, stop and ask.
3. **Only the neighbours changed.** The function is there, the removed lines are there, and only
   lines around the change differ. For example, upstream added a new architecture to the same
   table, so the line next to where ours goes is a different one. That is a harmless shift.
   - For a hunk with fuzz: confirm that `patch` put the added lines exactly where the subsection
     says they belong. If they are anywhere else, move them there by hand.
   - For a failed hunk: make the change by hand. The `.rej` file shows it: lines that start
     with `-` are removed, lines that start with `+` are added, and lines that start with a
     space are the old neighbours. Put the `+` lines where the subsection says they belong.
     Copy them character for character, with the same indentation, and change nothing else.
4. If you cannot say which of the three rules applies, stop and ask.

**Step E: regenerate the patch from the copy, and compare it with the old one.**

```sh
. work/pin-move/session.sh
capped native/tools/make-hook-patch.sh "$NAME"
diff "$PIN_WORK/original-patches/$NAME" "native/patches/$NAME" \
    | grep -E '^[<>] [-+]' | grep -v -E '^[<>] (\+\+\+|---) '
```

`make-hook-patch.sh` prints `make-hook-patch: wrote native/patches/<NAME>: F file(s), +A -R
line(s)`. These numbers count non-blank lines only, and they must equal the ones in the table
below unless the subsection allows a difference:

| Patch | Files | Added | Removed |
| --- | --- | --- | --- |
| `0001-architecture-hooks.patch` | 7 | 68 | 2 |
| `0002-arm-f16-weights-kernel.patch` | 2 | 23 | 3 |
| `0003-mmap-window-for-embedded-models.patch` | 3 | 50 | 9 |
| `0004-linear-byte-fallback-in-spm-tokenizer.patch` | 1 | 2 | 1 |
| `0005-wipe-token-ids-of-a-restored-state.patch` | 1 | 20 | 0 |

If it also prints a line with `note:`, the copy holds a file that is not upstream's and not
the project's (usually a leftover `.orig` or `.rej`). Delete that file in
`work/pin-move/build/llama-src` and run the block again.

The `diff` compares the new patch with the old one, ignoring line numbers and neighbours:

- If it prints nothing, only line numbers and neighbouring lines changed. The patch does
  exactly what it did before. This is a harmless shift.
- If it prints lines, each one is a line that the patch adds or removes and that changed.
  Every such line must be one of the changes that the subsection of this patch allows, which
  are always one of the edits you made under rule 3. Any other line means stop and ask.

Then check that the regenerated patch applies on its own:

```sh
. work/pin-move/session.sh
capped native/tools/prepare-llama-src.sh
```

It prints `wrote ... K patch(es) ...`, as in step A.

**Step F: check whether upstream made the patch unnecessary, or changed what it relies on.**
Run the check in the subsection of this patch, even when the patch applied without help.
Upstream can change a function without touching the lines a patch changes, and some patches
depend on more than their own lines. The subsection says what to do with each result. Never
delete a patch yourself. Even when upstream made one unnecessary, removing it is the project
owner's decision, so stop and ask.

Then go on to the next patch. After 0005:

```sh
. work/pin-move/session.sh
ls native/patches
for f in "$PIN_WORK"/original-patches/*.patch; do cmp -s "$f" "native/patches/$(basename "$f")" && echo "unchanged $(basename "$f")" || echo "CHANGED $(basename "$f")"; done
```

`native/patches` holds exactly seven entries: the five patches, `README.md` and
`UPSTREAM_COMMIT`. Every patch is listed either as `unchanged` or as `CHANGED`, and each
`CHANGED` one went through step E. The copies in `$PIN_WORK/hold` stay there, which is fine.

### 4.3 Patch 0001: architecture hooks

**What it is for.** llama.cpp only knows an architecture that is listed in its tables. This
patch adds three architectures that the project ports, `peacebell`, `boris2` and `shard2`, to
every table in C++ and Python. Their actual code is in separate files under `native/llama-ext/`.

**What it changes** (in each table, our entries go after the last upstream entry, unless a
line below says otherwise):

| File | Where | What is added or changed |
| --- | --- | --- |
| `conversion/__init__.py` | `TEXT_MODEL_MAP` | `"Boris2ForCausalLM": "boris2"`, `"GPTForCausalLM": "peacebell"`, `"Shard2ForCausalLM": "shard2"`, each in the alphabetical place of its key (this table is sorted, so they do not go at the end) |
| `gguf-py/gguf/constants.py` | `class MODEL_ARCH`, `MODEL_ARCH_NAMES`, `MODEL_TENSORS` | `PEACEBELL`, `BORIS2`, `SHARD2` at the end of each, with a tensor list per architecture |
| `src/llama-arch.h` | `enum llm_arch` | `LLM_ARCH_PEACEBELL`, `LLM_ARCH_BORIS2`, `LLM_ARCH_SHARD2`, directly before `LLM_ARCH_UNKNOWN` |
| `src/llama-arch.cpp` | `LLM_ARCH_NAMES` | Three names, directly before `{ LLM_ARCH_UNKNOWN, "(unknown)" }` |
| `src/llama-arch.cpp` | `llm_arch_is_hybrid` | `case LLM_ARCH_BORIS2:`, at the end of the cases that `return true;` |
| `src/llama-arch.cpp` | `llama_arch_supports_save` | `case LLM_ARCH_BORIS2:` with its comment, at the end of the cases that `return false;` |
| `src/llama-kv-cache.cpp` | `llama_kv_cache::has_cell_ext` | `\|\| model.arch == LLM_ARCH_BORIS2` at the end of the returned condition (one of the two removed lines) |
| `src/llama-model.cpp` | `llama_model_mapping` | Three `case` lines with `return new llama_model_<arch>(params);`, directly before `default:` |
| `src/llama-model.cpp` | `llama_model::create_memory` | `\|\| arch == LLM_ARCH_BORIS2` at the end of the condition of the branch whose body sets both `filter_attn` and `filter_recr` to `[&](uint32_t) { return true; }` (the other removed line) |
| `src/llama-model.cpp` | `llama_model_rope_type` | `case LLM_ARCH_SHARD2:` at the end of the cases that `return LLAMA_ROPE_TYPE_NORM;`, and `case LLM_ARCH_PEACEBELL:` and `case LLM_ARCH_BORIS2:` at the end of the cases that `return LLAMA_ROPE_TYPE_NEOX;` |
| `src/models/models.h` | End of the file | `#include "peacebell.h"`, `#include "boris2.h"`, `#include "shard2.h"` after a blank line |

**What is most likely to happen.** Upstream adds architectures to these same tables in almost
every release. The neighbouring lines then change, and some hunks fail or need fuzz. That is
rule 3 of step D. Put our lines after the new last upstream entry, as the table above says.

**Allowed differences in step E.** When upstream itself extended one of the two conditions
that this patch extends (the condition in `has_cell_ext`, or the list of architectures in the
`create_memory` branch), the changed `-` line and `+` line in the comparison are allowed. The
`+` line must be upstream's new line with only our addition at its end. No other difference
is allowed.

**Must still hold (step F).** Run:

```sh
. work/pin-move/session.sh
git -C third_party/llama.cpp grep -n -i -E 'peacebell|boris2|shard2|"GPTForCausalLM"' HEAD -- src gguf-py conversion
git -C third_party/llama.cpp grep -n -E '^\s+(PER_LAYER_TOKEN_EMBD|PER_LAYER_MODEL_PROJ|SHORTCONV_CONV|ATTN_QKV|ATTN_Q_NORM|ATTN_K_NORM|ATTN_POST_NORM|FFN_POST_NORM)\s+= auto\(\)' HEAD -- gguf-py/gguf/constants.py
git -C third_party/llama.cpp diff "$OLD" "$NEW" -- src/llama-model.h src/models/models.h src/llama-arch.h | head -n 200
```

- The first command must print nothing. If upstream now has an architecture, a file or a class
  name of the same name, stop and ask.
- The second command must print exactly eight lines, one for each tensor name that our lists
  use. Fewer means upstream renamed or removed one: stop and ask.
- Read the third command's output. Stop and ask if it shows that any of these is no longer true:
  architectures are classes deriving from `llama_model_base` and created in
  `llama_model_mapping`; an architecture's memory is chosen in `llama_model::create_memory`
  through `llm_arch_is_hybrid` and the filter branches; `has_cell_ext` decides whether a KV
  cell records its token id; the Python side registers architectures in `MODEL_ARCH`,
  `MODEL_ARCH_NAMES`, `MODEL_TENSORS` and `TEXT_MODEL_MAP`.
- This patch never becomes unnecessary, because upstream does not have these architectures.

### 4.4 Patch 0002: an ARM kernel for 16-bit float weights

**What it is for.** All the models ship as 16-bit float files. On the phone, upstream leaves
tinyBLAS out of the CPU variants that have SVE or the int8 matrix instructions, and in the
variants with half-float arithmetic it compiles out the one tinyBLAS kernel that multiplies
16-bit weights by 32-bit activations. This patch keeps tinyBLAS for exactly that pair of types
and puts the kernel back, which makes a prompt about three times as fast on the phone.

**What it changes:**

| File | Where | What |
| --- | --- | --- |
| `ggml/src/ggml-cpu/ggml-cpu.c` | Near the top, `#if defined(__ARM_FEATURE_SVE) \|\| defined(__ARM_FEATURE_MATMUL_INT8)` | Replaces `#undef GGML_USE_LLAMAFILE` with the definition of `GGML_LLAMAFILE_F16_ONLY` (and a comment above the `#if`) |
| `ggml/src/ggml-cpu/ggml-cpu.c` | `ggml_compute_forward_mul_mat`, the block that calls `llamafile_sgemm` | Adds `llamafile_takes` (F16 weights times F32 activations only, under `GGML_LLAMAFILE_F16_ONLY`) to its condition `if (!params->use_ref && src1_cont)` |
| `ggml/src/ggml-cpu/ggml-cpu.c` | Same function, the second `#if GGML_USE_LLAMAFILE` | Becomes `#if GGML_USE_LLAMAFILE && !defined(GGML_LLAMAFILE_F16_ONLY)` |
| `ggml/src/ggml-cpu/llamafile/sgemm.cpp` | `llamafile_sgemm`, `case GGML_TYPE_F16:`, the branch `#elif defined(__ARM_FEATURE_FP16_VECTOR_ARITHMETIC) && !defined(_MSC_VER)` | Adds an `if (Btype == GGML_TYPE_F32)` block with `tinyBLAS<4, float32x4_t, float32x4_t, ggml_fp16_t, float, float>` as the first thing in that branch, before `if (n < 8)` |

Its three removed lines are `#undef GGML_USE_LLAMAFILE`, `if (!params->use_ref && src1_cont) {`
and `#if GGML_USE_LLAMAFILE`. If one of them is gone (rule 1), stop and ask.

**Allowed differences in step E.** None.

**Must still hold, and unnecessary? (step F).** The second command prints the `Btype` cases of
upstream's half-float branch and nothing else: it starts after the branch's `#elif` line and
stops at the next `#elif`, `#else` or `#endif`. The branch that follows (`__ARM_NEON`) has an
F32 case of its own, which must not be counted, and this command leaves it out.

```sh
. work/pin-move/session.sh
git -C third_party/llama.cpp grep -n -B2 -A1 -F '#undef GGML_USE_LLAMAFILE' HEAD -- ggml/src/ggml-cpu/ggml-cpu.c
git -C third_party/llama.cpp show HEAD:ggml/src/ggml-cpu/llamafile/sgemm.cpp \
    | awk '/^#elif defined\(__ARM_FEATURE_FP16_VECTOR_ARITHMETIC\) && !defined\(_MSC_VER\)$/ {p = 1; next} p && /^#(elif|else|endif)/ {exit} p' \
    | grep -n 'Btype =='
```

- Expected: the first command prints four lines: an empty one, then
  `#if defined(__ARM_FEATURE_SVE) || defined(__ARM_FEATURE_MATMUL_INT8)`,
  `#undef GGML_USE_LLAMAFILE` and `#endif` (each after a file name and line number). The second
  prints exactly one line, `if (Btype == GGML_TYPE_F16) {`. Together they mean the patch is
  still needed. Go on.
- If the first prints nothing **and** the second also prints a line with
  `Btype == GGML_TYPE_F32`, upstream may have made this patch unnecessary. Stop and ask.
- Any other result means upstream changed this code. Stop and ask.
- No host test can check this patch, because it is ARM code that does nothing on x86. Only
  the phone checks in section 6.6 prove it.

### 4.5 Patch 0003: a memory-mapping window for models embedded in a file

**What it is for.** The app opens each model inside its APK, as a byte range of a larger file.
Upstream maps the whole containing file from its first byte and asks for all of it to be read
ahead. This patch confines the mapping, the read-ahead and every later unmapping to the model's
own tensor data, while tensors are still addressed by their offset in the file. It also frees a
context that upstream leaks when it refuses to map a model at an unaligned offset.

**What it changes:**

| File | Where | What |
| --- | --- | --- |
| `src/llama-mmap.h` | `struct llama_file` | Declares `set_mmap_window(size_t first, size_t last)` and `mmap_window()` |
| `src/llama-mmap.cpp` | `struct llama_file::impl` (the POSIX one) | Two members `window_first`, `window_last` after `owns_fp` |
| `src/llama-mmap.cpp` | After `llama_file::has_direct_io` | Definitions of the two functions |
| `src/llama-mmap.cpp` | `struct llama_mmap::impl`, the `_POSIX_MAPPED_FILES` part: the constructor and `unmap_fragment` | `map_first`/`map_last`, and the mapping, `posix_fadvise`, the `advise` lambda, the NUMA `posix_madvise` and `mapped_fragments` limited to them, with `addr` kept as the address of file offset 0; `unmap_fragment` clamped to them |
| `src/llama-model-loader.cpp` | `llama_model_loader::llama_model_loader`, the branch `} else if (file != nullptr) {` (a model loaded from an open file) | `ggml_free(ctx)` before the throw about an unaligned data section; then the end of the last tensor computed while the tensors are listed, and `files.back()->set_mmap_window(...)` after that loop |

Its nine removed lines are all in `llama-mmap.cpp` (eight) and in the tensor loop of the loader
(one: `weights_map.emplace(tensor_name, llama_tensor_weight(files.back().get(), 0, metadata, cur));`).

**What a harmless shift looks like here.** Upstream rewords a log message, or adds an
unrelated member or flag next to one of these lines. A changed `mmap(` call, a different way
of computing the mapped range, a new caller of `munmap` or `posix_madvise` in that `impl`, or
a loader branch that no longer goes through `llama_tensor_weight` is a change in behaviour:
stop and ask.

**Allowed differences in step E.** None.

**Must still hold, and unnecessary? (step F).**

```sh
. work/pin-move/session.sh
git -C third_party/llama.cpp grep -n -E 'mmap_window|mmap\(NULL, file->size\(\)' HEAD -- src
git -C third_party/llama.cpp diff -W "$OLD" "$NEW" -- src/llama-mmap.cpp src/llama-model-loader.cpp | grep -n -E 'llama_mmap::impl|unmap_fragment|llama_model_loader::llama_model_loader'
grep -n -E 'mmap\(NULL, file->size\(\)' "$PROSEPLOW_LLAMA_SRC/src/llama-mmap.cpp"
```

- The first command must print exactly one line, the upstream `addr = mmap(NULL, file->size(), ...)`.
  If it prints a line with `mmap_window`, or none at all, upstream changed how it maps. That
  may make this patch unnecessary or wrong. Stop and ask.
- The second command must print nothing. If it prints lines, upstream changed one of these
  functions. Read that diff (the same command without the `| grep ...` part) and check the
  "harmless shift" paragraph above. If you are not certain that it is harmless, stop and ask.
- The third command searches the patched copy and must print nothing: no whole-file mapping is
  left after the patch.

### 4.6 Patch 0004: a linear byte fallback in the SentencePiece tokeniser

**What it is for.** When a character is not in a SentencePiece vocabulary, the tokeniser emits
its bytes, and upstream first reserves room for exactly those bytes. Reserving the exact size
every time makes the output vector copy itself every time, so a text made of such characters
took time with the square of its length (4 seconds for 256 KiB of line breaks). The patch
removes that one line and leaves a two-line comment in its place.

**What it changes:** `src/llama-vocab.cpp`, `struct llm_tokenizer_spm_session`, function
`resegment`, the branch `if (p == rev_merge.end())`. It removes
`output.reserve(output.size() + cur.n);` and adds the comment. No other patch touches this file.

**Allowed differences in step E.** None.

**Must still hold, and unnecessary? (step F).**

```sh
. work/pin-move/session.sh
git -C third_party/llama.cpp grep -n -F 'output.reserve(output.size() + cur.n);' HEAD -- src/llama-vocab.cpp
git -C third_party/llama.cpp diff -W "$OLD" "$NEW" -- src/llama-vocab.cpp | grep -n -E 'resegment|llm_tokenizer_spm_session'
```

- Expected: the first command prints one line, which is the line this patch removes, and the
  second prints nothing. Go on.
- If the first prints nothing, the patch will not have applied (step A failed). Upstream
  removed or changed the line. If `resegment` no longer reserves anything per symbol, upstream
  may have fixed the problem itself, which would make this patch unnecessary. Either way, stop
  and ask.
- If the second prints lines, upstream changed the SentencePiece tokeniser. Stop and ask.

### 4.7 Patch 0005: wiping the token ids of a restored state

**What it is for.** When llama.cpp reads the state of a sequence back, it collects the cells to
restore in a batch and in a list of what each cell recorded. For models whose state carries
every token id (Qwen3.5, Boris-2), both hold the ids of the whole conversation, and upstream
frees them as they are. The patch adds one object to that function whose destructor zeroes both
lists, through a volatile pointer, on every way out of the function.

**What it changes:** `src/llama-kv-cache.cpp`, `llama_kv_cache::state_read_meta`, the
single-sequence branch (`if (dest_seq_id != -1)`). It adds the `struct wipe_token_ids` and its
instance `wipe_token_ids_on_return` after `exts` is declared and resized, and before the loop
`for (uint32_t i = 0; i < cell_count; ++i)` that reads the cells. It removes nothing.

**Must still hold.** This patch relies on the shape of that function, and **it can still apply
when upstream has made it incomplete**. Check every point below, whatever step A printed:

1. In that branch, `llama_ubatch ubatch = balloc.ubatch_reserve(cell_count, 1);` exists, and
   the token ids go into `ubatch.token`.
2. `std::vector<llama_kv_cell_ext> exts;` exists, is sized with `exts.resize(cell_count)`, and
   is filled in the loop.
3. The function holds no other container of token ids (`llama_token`) and no other copy of
   `exts`. A new one would be freed without being wiped.
4. The object is declared after both lists and before the loop, so its destructor runs after
   the last use of either.

```sh
. work/pin-move/session.sh
git -C third_party/llama.cpp grep -n -E 'balloc.ubatch_reserve\(cell_count, 1\)|std::vector<llama_kv_cell_ext> exts;|exts.resize\(cell_count\)' HEAD -- src/llama-kv-cache.cpp
git -C third_party/llama.cpp diff -W "$OLD" "$NEW" -- src/llama-kv-cache.cpp | grep -n -E 'state_read_meta|has_cell_ext|llama_kv_cell_ext'
sed -n '/^bool llama_kv_cache::state_read_meta/,/^}/p' "$PROSEPLOW_LLAMA_SRC/src/llama-kv-cache.cpp" | grep -n -E 'std::vector|llama_token'
```

- The first command must print three lines, all inside `state_read_meta`. Otherwise stop and ask.
- The second must print nothing. If it prints lines, upstream changed this function or the cell
  records. Read that diff against points 1 to 4. Unless all four plainly still hold, stop and ask.
- The third command searches the whole function in the patched copy. It must print exactly
  these three lines, in this order (the numbers in front differ):
  1. `std::vector<llama_kv_cell_ext> exts;`: upstream's list of what each cell recorded.
  2. `std::vector<llama_kv_cell_ext> & exts;`: a member of the patch's own `struct wipe_token_ids`,
     which refers to the list above. It contains the same identifier, and it is expected.
  3. `zero(ubatch.token, (size_t) n * sizeof(llama_token));`: the patch's destructor wiping the batch.

  Any further line is a container or a use of token ids that upstream added and that the patch
  does not wipe: stop and ask.
- **Allowed differences in step E.** None.
- This patch only becomes unnecessary if upstream itself overwrites both lists before freeing
  them, and in that case point 2 or 3 will have told you. Stop and ask.

The host tests `qwen3.5-0.8b.residue` and `boris-2-instruct.residue` prove this patch
(section 6). They are the only tests that do.

## 5. The parts that are not patches, but break when the pin moves

Two kinds of project code compile against llama.cpp without being patches. Upstream can break
them without touching a single line that a patch touches.

### 5.1 The added files in `native/llama-ext/`

`prepare-llama-src.sh` copies these files into the copy at the same paths. They are the code of
the three architectures (`src/models/peacebell.*`, `boris2.*`, `shard2.*`) and their converters
(`conversion/peacebell.py`, `boris2.py`, `shard2.py`). They use upstream's internal interfaces:
the `llama_model_base` class, `llm_graph_context` and its helpers (`build_norm`, `build_qkv`,
`build_rope`, `build_attn`, `build_ffn`, `build_inp_embd`, `build_inp_pos`,
`build_attn_inp_kv`, `build_attn_inp_kv_iswa`, `build_inp_out_ids`, `build_cvec`,
`build_lora_mm`, and for boris2 `build_conv_state` and the hybrid memory), and on the Python
side `ModelBase`, `TextModel` and `LazyTorchTensor` from `conversion/base.py`.

Symptoms, all in the host build (section 6.2) or the conversion (6.3):

- A compiler error in `src/models/peacebell.cpp`, `boris2.cpp`, `shard2.cpp` or their
  headers, such as `no member named ...`, `no matching function for call to 'build_...'`,
  `... marked 'override', but does not override`, or `cannot declare variable ... to be of
  abstract type`.
- A Python traceback from `conversion/peacebell.py`, `boris2.py` or `shard2.py` during the
  conversion, such as `ImportError` or `AttributeError` on something from `.base`.

What to do:

1. See how upstream changed that interface:

   ```sh
   . work/pin-move/session.sh
   cat "$PIN_WORK/interfaces-changed"
   git -C third_party/llama.cpp diff "$OLD" "$NEW" -- src/llama-graph.h src/llama-model.h conversion/base.py
   ```

2. It is a **harmless change** only when all of these are true: upstream renamed a function,
   parameter or type, or added a parameter with a default value. Upstream made the same
   mechanical change in its own model files, which you can see with
   `git -C third_party/llama.cpp diff "$OLD" "$NEW" -- src/models/ | grep -n '<the old name>'`
   (after sourcing the session file). And the fix in our file is that same mechanical change,
   nothing more. Make it in `native/llama-ext/...` (never in the copy: `prepare-llama-src.sh`
   would overwrite it), and run `capped native/tools/prepare-llama-src.sh` and the build again.
3. Anything else means stop and ask: a helper that now computes something different, a new pure
   virtual function in `llama_model_base`, a changed memory class, or a converter base class
   that works differently.

### 5.2 `native/session/src/state_stream.cpp` and llama.cpp's internal headers

This file, and no other, includes two internal headers of llama.cpp: `src/llama-io.h` (the
interfaces `llama_io_write_i` and `llama_io_read_i`) and `src/llama-memory.h` (the virtual
functions `state_write` and `state_read` of `llama_memory_i`). It implements the first two so
that a conversation's memory can leave and enter a context in pieces.

Symptoms:

- CMake stops with `The prepared llama.cpp has no src/llama-io.h or src/llama-memory.h`:
  upstream moved or removed a header.
- A compiler error in `native/session/src/state_stream.cpp`, such as `cannot declare variable
  ... to be of abstract type` or `... marked 'final' ... overrides nothing`: one of the
  interfaces gained, lost or changed a member.
- The host test `<model>.memory` fails, while the file compiles.

What to do: show the change with

```sh
. work/pin-move/session.sh
git -C third_party/llama.cpp diff "$OLD" "$NEW" -- src/llama-io.h src/llama-memory.h
```

**Stop and ask in every case.** None of these is a renaming you may fix yourself: a member
added to these interfaces changes what a saved state contains. `native/README.md` ("Moving the
upstream pin", step 3) describes what the code relies on and what remains if the interfaces
disappear.

## 6. Proving the result

Run these checks in this order, cheapest first. Stop at the first one that fails. Section 6.7
says which failure points at which patch. From 6.2 on, you need the virtualenv and the
checkpoints (2.1), and both lines of the session file must name them.

### 6.1 Prepare the copy from the patches alone

```sh
. work/pin-move/session.sh
capped native/tools/prepare-llama-src.sh --force
```

It prints `prepare-llama-src: wrote ... (<new 7 characters>, 5 patch(es), 9 added file(s))`.
`--force` rebuilds the copy from the patches, so that no hand edit left in it can reach a build.

### 6.2 Build for the host

```sh
. work/pin-move/session.sh
capped cmake -S native -B "$PROSEPLOW_BUILD/host" -G Ninja -DCMAKE_BUILD_TYPE=Release
capped cmake --build "$PROSEPLOW_BUILD/host" -j "$PROSEPLOW_JOBS"; echo "exit status $?"
```

The configuration ends with `-- Build files have been written to: ...`. A line `fatal: not a
git repository` is expected. The build prints no line containing `error:`, and the last line is
`exit status 0`. If CMake stops with `The common target exists`, upstream changed its build
options: stop and ask. For compiler errors, see section 5.

### 6.3 Convert every model

```sh
. work/pin-move/session.sh
capped python3 models/convert_models.py --also-f32
```

Without `--available`, this converts all five models and fails when a checkpoint is missing. It
prints one line per file, `<id>: wrote <file>, <bytes> bytes, sha256 <hash>`, and ends without
an error.

If it stops with `<file> has SHA-256 <x>, the manifest pins <y>`, the converted F16 file is no
longer byte for byte the file that ships. The cause is upstream's GGUF writer or converter code,
which changes the file that every user gets. Stop and ask: the pinned hashes in
`models/manifest.json` are changed only on purpose, by the project owner. For a Python
traceback, see section 5.1.

### 6.4 The host test suite

```sh
. work/pin-move/session.sh
capped ctest --test-dir "$PROSEPLOW_BUILD/host" --output-on-failure
```

It ends with `100% tests passed, 0 tests failed out of N`. The only test that may be listed
under "The following tests did not run" is `peacebell-bpe-packages`, as `(Skipped)`, because it
needs checkpoints that are not published. Any other skipped test means a checkpoint is missing:
fix that and run the suite again.

Each test prints a line per check that begins with `ok` or `FAIL`, and ends with
`PASSED: <n> check(s), 0 failed`. To run one test again and see every line:

```sh
. work/pin-move/session.sh
capped ctest --test-dir "$PROSEPLOW_BUILD/host" -R '^boris-2-instruct\.residue$' -V
```

A failing test means stop and ask. Use the table in 6.7 to say which patch or part it points
at, and include the `FAIL` lines. One failure comes from the text of upstream's log rather than
from behaviour: when `<model>.context` fails and says that the form of llama.cpp's report of a
context's buffers changed, `native/README.md` ("Moving the upstream pin", step 3) explains what
the session reads there. Report it the same way, with the old and the new form of those lines
(`git -C third_party/llama.cpp diff "$OLD" "$NEW" -- src | grep -n 'buffer size'`, after
sourcing the session file).

### 6.5 The Android build

First update the notices (section 7.2): `ThirdPartyNoticesTest`, which is part of `check`,
compares them with the new commit and fails until they name it. Then:

```sh
. work/pin-move/session.sh
rm -rf llama/.cxx llama/build
capped ./gradlew --no-daemon check
capped ./gradlew --no-daemon :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/arm64-v8a/' | tee "$PIN_WORK/apk-libraries.txt"
```

`llama/.cxx` and `llama/build` hold only build output (git ignores them). Deleting them makes
the native build start from nothing. Both Gradle commands end with `BUILD SUCCESSFUL`. The
`unzip` listing shows thirteen libraries: the twelve in the table of `native/README.md` ("What
is built") and `libandroidx.graphics.path.so`. Their sizes will differ, and you record them in
section 7. If a build fails because the APK holds a native library with an unknown name,
upstream changed its set of CPU variants. Stop and ask.

### 6.6 The phone

These checks need the phone's processor. They are the only proof of patch 0002, and the proof
on the device of 0003 and 0004. Connect the phone (`adb devices` lists it as `device`), keep it
awake and unlocked for the whole run, and follow `native/README.md` ("On the phone", "The
tests"):

```sh
. work/pin-move/session.sh
capped ./gradlew --no-daemon :llama:assembleDebugAndroidTest -Pproseplow.models=all -Pproseplow.modelDir="$PROSEPLOW_MODEL_OUT"
adb install -r llama/build/outputs/apk/androidTest/debug/llama-debug-androidTest.apk
PACKAGE=us.theworkmans.proseplow.llama.test
adb shell "run-as $PACKAGE sh -c 'umask 077 && mkdir -p files/models'"
adb exec-in "run-as $PACKAGE sh -c 'umask 077 && cat > files/models/shard-2.gguf'" < "$PROSEPLOW_MODEL_OUT/shard-2/shard-2-f16.gguf"
adb shell am instrument -r -w $PACKAGE/androidx.test.runner.AndroidJUnitRunner > "$PIN_WORK/phone-run.txt"
grep -E 'PROSEPLOW|OK \(|FAILURES' "$PIN_WORK/phone-run.txt"
adb uninstall $PACKAGE
```

The `run-as` lines place Shard-2, which no APK carries, as described under "A model without
rebuilding the test APK". The run must contain `OK (` with the number of tests, and no
`FAILURES!!!`. Then compare the `PROSEPLOW` lines with the table in `native/README.md` ("What was
measured"). That table was measured on one particular phone. On another phone, run these same
commands once at the old pin first (before section 3, or after section 8), and compare with
that run instead.

- **Patch 0002:** peacebell's prompt speed with four pinned threads must be at least two thirds
  of the reference figure (183 to 190 tokens per second on the phone of the table, so at least
  122). Without the kernel it is about a third of the reference. If it is lower, stop and ask.
- Every other figure is reported, not required. A large change in one of them (more than about
  twenty per cent) belongs in your report under section 7. It is not a reason to stop.

### 6.7 Which test proves which patch

| Patch or part | Host test (section 6.4) | Phone test (section 6.6) | What a failure there means |
| --- | --- | --- | --- |
| 0001 | Every test of `peacebell-v1-291m`, `peacebell-v1-148m`, `boris-2-instruct` and `shard-2`, and `peacebell-bpe-random`: above all `.gguf-f32`, `.logits-f32` and `.logits-f16` | `PeacebellDeviceTest`, `BorisDeviceTest`, `ShardDeviceTest` | An architecture is registered wrongly or incompletely: a missing table entry, the wrong RoPE type, or, for `boris-2-instruct.logits-f32` and `.chat-*`, the hybrid memory or the token id in each cell |
| 0002 | None (ARM only) | The prompt speeds in the `PROSEPLOW` lines; the greedy replies of the device tests | Slow prompts mean the kernel is not used. Wrong replies mean the kernel computes something else |
| 0003 | `<model>.session`: the checks `the mapping covers ... bytes of the file for a model of ... bytes, not the whole file` and `embedded at the unaligned offset, mapped: LOAD_FAILED` | `RuntimeDeviceTest`, `QwenDeviceTest` (load and memory, mapped) | The mapping again covers more than the model, or a model inside an APK no longer loads |
| 0004 | `<model>.session`: the check `the longest text takes time in proportion to its length ...` | `JniBoundaryDeviceTest`, `theLongestTextIsTokenisedWithoutAStall` | Tokenising has become quadratic again |
| 0005 | `qwen3.5-0.8b.residue`, `boris-2-instruct.residue` | None | Token ids of a restored conversation stay in freed memory |
| `native/llama-ext/` | The same tests as 0001, and the conversions of section 6.3 | As for 0001 | Upstream changed an interface or a helper that the added architectures use |
| `state_stream.cpp` | `<model>.memory` for all four models | `JniBoundaryDeviceTest` | A saved memory is no longer the state that llama.cpp itself writes |
| Everything else in the session | `<model>.session`, `.penalties`, `.context`, `.memory`, `session.state-never-to-file` | All of them | Upstream changed behaviour that `native/README.md` ("Moving the upstream pin", step 3) lists |

## 7. Recording the move

### 7.1 The pin and the patches

Stage the submodule, `UPSTREAM_COMMIT`, and only those patch files that changed. Never stage
the whole `native/patches/` directory:

```sh
. work/pin-move/session.sh
git add third_party/llama.cpp native/patches/UPSTREAM_COMMIT
for f in "$PIN_WORK"/original-patches/*.patch; do
    n="$(basename "$f")"
    cmp -s "$f" "native/patches/$n" || git add "native/patches/$n"
done
git status --short
```

The list holds `M  native/patches/UPSTREAM_COMMIT`, `M  third_party/llama.cpp`, and one
`M  native/patches/<NAME>` line for each patch that section 4 listed as `CHANGED`. Nothing else
starts with a letter in its first column.

### 7.2 Files that name the commit

Replace the full hash and its seven-character short form in the files of the first table in
section 1:

```sh
. work/pin-move/session.sh
sed -i "s/$OLD/$NEW/g; s/as in llama.cpp ${OLD:0:7}/as in llama.cpp ${NEW:0:7}/g" \
    README.md app/notices/notices.json app/notices/NOTICES.txt
git grep -n -I -e "$OLD" -e "${OLD:0:7}" -- .
```

The last command prints nothing.

The notices also have to be correct for the new code, not just name it. Run:

```sh
. work/pin-move/session.sh
git -C third_party/llama.cpp diff --stat "$OLD" "$NEW" -- LICENSE
git -C third_party/llama.cpp diff "$OLD" "$NEW" -- src include ggml/include ggml/src/ggml-cpu ':(glob)ggml/src/*' \
    | grep -n -i -E '^[-+].*(copyright|licen[cs]e|spdx|adapted from|borrowed|ported from|based on)'
```

Both commands must print nothing. If either prints something, upstream changed its licence or
the code it carries from others. Stop and ask: the root `README.md` ("Keeping the notices
right") describes the review, and it is not part of this runbook.

### 7.3 Counts and descriptions of the patches

If no patch was added or removed and no patch changed its purpose, the texts of the second
table in section 1 stay as they are. The line counts in `native/README.md` ("The submodule is
never edited") are the ones `make-hook-patch.sh` prints. If step E printed other numbers for a
patch, correct that patch's counts there.

### 7.4 Measured numbers

These numbers were measured with the old pin. Each row says how to find the place with one
search (the quoted text occurs once in that file), and what to do. You either measure again
and replace the numbers, or you keep them and say that they are carried over. `<old 7>` and
`<new 7>` are the first seven characters of `$OLD` and `$NEW`.

| File | Search for this text | If you measured again | If you did not |
| --- | --- | --- | --- |
| `README.md` | `They were measured for a build of` | Put the sizes of `$PIN_WORK/apk-libraries.txt` into the table after that sentence, and into the sentence that begins `All thirteen libraries of that build together were` (the total of all thirteen, and of the twelve without `libandroidx.graphics.path.so`). In the sentence you searched for, replace ``commit `<the seven characters it names>` `` with ``the commit that moved llama.cpp to `<new 7>` `` | Leave it: the sentence already names the commit it was measured at |
| `native/README.md` | `measured for a build of` | The same for the table that follows, its `all twelve` row, the total of thirteen in the same paragraph, and the same replacement of the commit | Leave it, for the same reason |
| `native/README.md` | `For orientation, the values measured when this was written (6 threads, a desktop CPU with AVX2):` | Put the values from the `-V` output of the host tests into the table that follows and the two paragraphs after it | Replace `when this was written` in that line with ``with llama.cpp at `<old 7>` `` |
| `native/README.md` | `### What was measured` | Put the values from `$PIN_WORK/phone-run.txt` into the table of that section | Add one line after the heading, with a blank line before and after it: ``Measured with llama.cpp at `<old 7>`; not measured again after the move to `<new 7>`.`` |
| `native/README.md` | `That was verified by building three times from nothing` | Repeat the three builds that the paragraph describes and keep the paragraph | After the word `again.` that ends that sentence, add: `` That was at llama.cpp `<old 7>`, and it was not verified again after the move to `<new 7>`.`` |

### 7.5 Check and commit

```sh
. work/pin-move/session.sh
git add README.md native/README.md app/notices
git status --short
git diff --cached -- . ':!native/patches/README.md' | grep -n -E '^\+.*(/home/|/Users/)' || echo "no personal paths"
```

`git status --short` lists only staged files (a letter in the first column) and nothing that
starts with `??`. The last command prints exactly `no personal paths`. If it prints lines
instead, an added line names a directory in someone's home: remove it from that file, stage
again, and run the block again. This runbook is left out of the check because its own text
contains the pattern.

Commit with a message of one sentence: imperative, sentence case, no prefix tag, no full stop
at the end, no trailers or footer lines. Add a body only to explain a patch that changed in
substance. For example:

```sh
git commit -m "Move llama.cpp to release bNNNNN"
```

Pushing is not part of this job. The project owner decides when anything is published.

## 8. Going back

Use this when the move is abandoned at any point before the commit. You started from a clean
tree (2.2), so every change to a tracked file is part of the attempt:

```sh
. work/pin-move/session.sh
git restore --staged --worktree -- .
cp "$PIN_WORK/original-patches/"* native/patches/
git submodule update --init third_party/llama.cpp
git status --porcelain
git submodule status third_party/llama.cpp
cat native/patches/UPSTREAM_COMMIT
```

`git status --porcelain` prints nothing: everything the attempt wrote is under `work/`, which
git ignores. The submodule line begins with a space and names the hash in
`$PIN_WORK/old-commit`, which is the same hash that `UPSTREAM_COMMIT` holds. Your normal build
directory was never touched. `work/pin-move` can be deleted once you no longer need its notes,
and `llama/.cxx` and `llama/build` rebuild themselves at the next Gradle build. The fetched tag
stays in the submodule's local store, where it does no harm. Do not delete it.

If the move was already committed and has to be undone, make a new commit that reverses it,
then put the submodule back:

```sh
git revert --no-edit <the commit of the move>
git submodule update --init third_party/llama.cpp
```

## 9. Checklist

- [ ] 2.2 On `main`, `git status --porcelain` empty, submodule at `UPSTREAM_COMMIT`, `core.autocrlf` not `true`, git, patch and systemd-run present
- [ ] 2.3 `work/pin-move/session.sh` written, its two paths set (or left for later, see 2.1), the check prints the three paths, `capped is a function` and `ignored by git`
- [ ] 2.4 The five patches, `UPSTREAM_COMMIT`, the old commit and the old tag saved in `$PIN_WORK`
- [ ] 2.5 Host check passes at the old pin, only `peacebell-bpe-packages` skipped
- [ ] 3.1 A release tag chosen and written to `$PIN_WORK/new-tag`
- [ ] 3.2 Tag fetched (with `--depth 1` only in a shallow submodule), submodule checked out at it, `new-commit` written
- [ ] 3.3 `UPSTREAM_COMMIT` holds the new hash
- [ ] 3.4 `patched-files-changed` and `interfaces-changed` written
- [ ] 4.1 Patches moved to `$PIN_WORK/hold`, `native/patches` shows `README.md` and `UPSTREAM_COMMIT`, the copy prepared with 0 patches
- [ ] 4.3 0001 applies (steps A to E as needed), step F checks done
- [ ] 4.4 0002 applies, step F checks done
- [ ] 4.5 0003 applies, step F checks done
- [ ] 4.6 0004 applies, step F checks done
- [ ] 4.7 0005 applies, all four "must still hold" points checked, the third check prints exactly its three lines
- [ ] 4.2 `native/patches` holds seven entries, each patch `unchanged`, or `CHANGED` with only allowed differences
- [ ] 6.1 `prepare-llama-src.sh --force` writes the copy with 5 patches
- [ ] 6.2 Host build succeeds (section 5 if not)
- [ ] 6.3 All five models convert to their pinned hashes
- [ ] 6.4 `ctest`: 100% passed, only `peacebell-bpe-packages` skipped
- [ ] 7.2 Commit replaced in `README.md` and the notices, `git grep` for the old hash is empty, licence and copyright diff is empty
- [ ] 6.5 `./gradlew --no-daemon check` and `:app:assembleDebug` succeed, thirteen libraries listed
- [ ] 6.6 Phone: `OK (...)`, no failures, 0002's prompt speed holds
- [ ] 7.1 Submodule, `UPSTREAM_COMMIT` and only the changed patches staged
- [ ] 7.3 Patch counts and line counts correct
- [ ] 7.4 Each measured number re-measured or marked as carried over, at the place its search finds
- [ ] 7.5 `no personal paths`, nothing untracked, committed with a one-sentence message, not pushed
- [ ] 8 Only if abandoned: restored, submodule back at the old commit, `git status --porcelain` empty
