#!/usr/bin/env bash
# Builds the patched copy of llama.cpp that everything else compiles and imports.
#
#   native/tools/prepare-llama-src.sh [--force] [out-dir]
#
# out-dir defaults to $PROSEPLOW_LLAMA_SRC; one of the two must be given, and it must lie
# outside the source directories of this repository. The result is
#   the tracked files of the pinned submodule commit
#   + every native/patches/*.patch, applied in name order
#   + every file under native/llama-ext/, copied to the same relative path.
#
# Invariants this script keeps, because the host build, the converters and the Android build
# all start here:
# - The submodule is only read (git archive of a commit), never written, and its working tree
#   state does not matter: untracked or edited files there cannot leak into a build.
# - The output is a function of the pinned commit, the patches and the extension files. A
#   stamp records that key; when it matches, the copy is left alone, so edits made in the copy
#   while developing a hook survive until --force or until the inputs change.
# - A patch that does not apply exactly (no fuzz, no partial application) stops the script, and
#   so does an extension file that would replace an upstream file: changes to upstream files
#   belong in a patch where they can be reviewed.
# - Files whose content did not change keep their modification time across a re-run, so an
#   incremental build recompiles only what really changed. When the pinned commit changes,
#   every file gets a fresh time: exported files otherwise carry the commit date, which is
#   older than existing build outputs and would hide the change from the build tool.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../.." && pwd)"
submodule="${repo}/third_party/llama.cpp"
patches_dir="${repo}/native/patches"
ext_dir="${repo}/native/llama-ext"

force=0
out=""
for arg in "$@"; do
    case "${arg}" in
        --force) force=1 ;;
        -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "prepare-llama-src: unknown option ${arg}" >&2; exit 2 ;;
        *) out="${arg}" ;;
    esac
done
die() { echo "prepare-llama-src: $*" >&2; exit 1; }

out="${out:-${PROSEPLOW_LLAMA_SRC:-}}"
[ -n "${out}" ] || die "no output directory: pass one or set PROSEPLOW_LLAMA_SRC (see native/README.md)"
mkdir -p "$(dirname "${out}")"
out="$(cd "$(dirname "${out}")" && pwd)/$(basename "${out}")"
# The copy is deleted and rewritten, so it must never be a directory that holds sources.
case "${out}/" in
    "${repo}/native/"*|"${repo}/models/"*|"${repo}/third_party/"*|"${repo}/") die "${out} is inside the source tree; choose a scratch directory" ;;
esac

[ -e "${submodule}/.git" ] || die "third_party/llama.cpp is not checked out (run: git submodule update --init third_party/llama.cpp)"

expected="$(tr -d '[:space:]' < "${patches_dir}/UPSTREAM_COMMIT")"
commit="$(git -C "${submodule}" rev-parse HEAD)"
[ "${commit}" = "${expected}" ] || die "the submodule is at ${commit} but the patches are written against ${expected} (native/patches/UPSTREAM_COMMIT); see 'Moving the upstream pin' in native/README.md"

# The key of the output: the commit plus the name and content of every input file.
mapfile -t patches < <(find "${patches_dir}" -maxdepth 1 -name '*.patch' | LC_ALL=C sort)
mapfile -t ext_files < <(cd "${ext_dir}" && find . -type f ! -name '*.md' | sed 's|^\./||' | LC_ALL=C sort)
key="$(
    {
        echo "format 1"
        echo "commit ${commit}"
        for p in "${patches[@]}"; do echo "patch $(basename "${p}") $(git hash-object --no-filters "${p}")"; done
        for f in "${ext_files[@]}"; do echo "ext ${f} $(git hash-object --no-filters "${ext_dir}/${f}")"; done
    }
)"
stamp="${out}/.proseplow-prepared"

# The copy is replaced wholesale below. Refuse to do that to a directory this script did not
# write: a mistyped path must not cost anyone their files.
if [ -e "${out}" ] && [ ! -f "${stamp}" ] && [ -n "$(ls -A "${out}" 2>/dev/null)" ]; then
    die "${out} exists and was not written by this script; remove it or choose another directory"
fi

if [ "${force}" -eq 0 ] && [ -f "${stamp}" ] && [ "$(cat "${stamp}")" = "${key}" ]; then
    echo "prepare-llama-src: ${out} is up to date (${commit:0:7}, ${#patches[@]} patch(es), ${#ext_files[@]} added file(s))"
    exit 0
fi

tmp="${out}.tmp.$$"
rm -rf "${tmp}"
mkdir "${tmp}"
trap 'rm -rf "${tmp}"' EXIT

git -C "${submodule}" archive --format=tar "${commit}" | tar -x -C "${tmp}"

# git apply is used as a plain patch tool: the ceiling stops it from discovering the enclosing
# repository, where it would silently skip paths under an ignored directory.
changed=()
for p in "${patches[@]}"; do
    ( cd "${tmp}" && GIT_CEILING_DIRECTORIES="$(dirname "${tmp}")" git apply --whitespace=error-all -p1 "${p}" ) \
        || die "$(basename "${p}") does not apply to ${commit:0:7}; see 'Moving the upstream pin' in native/README.md"
    while IFS= read -r f; do changed+=("${f}"); done < <(sed -n 's|^+++ b/||p' "${p}")
done

for f in "${ext_files[@]}"; do
    [ ! -e "${tmp}/${f}" ] || die "native/llama-ext/${f} would replace an upstream file; put the change in a patch instead"
    mkdir -p "$(dirname "${tmp}/${f}")"
    cp "${ext_dir}/${f}" "${tmp}/${f}"
    changed+=("${f}")
done

old_commit=""
if [ -f "${stamp}" ]; then
    old_commit="$(sed -n 's/^commit //p' "${stamp}")"
fi
if [ "${old_commit}" = "${commit}" ]; then
    # Same upstream tree: only patched and added files can differ from the previous copy.
    for f in "${changed[@]}"; do
        if [ -f "${out}/${f}" ] && cmp -s "${out}/${f}" "${tmp}/${f}"; then
            touch -r "${out}/${f}" "${tmp}/${f}"
        fi
    done
else
    find "${tmp}" -type f -exec touch {} +
fi

printf '%s\n' "${key}" > "${tmp}/.proseplow-prepared"
rm -rf "${out}"
mv "${tmp}" "${out}"
trap - EXIT

echo "prepare-llama-src: wrote ${out} (${commit:0:7}, ${#patches[@]} patch(es), ${#ext_files[@]} added file(s))"
