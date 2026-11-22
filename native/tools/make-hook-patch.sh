#!/usr/bin/env bash
# The reverse of prepare-llama-src.sh: turns edits made in a prepared copy back into a hook patch.
#
#   native/tools/make-hook-patch.sh <patch-name> [edited-copy]
#
# patch-name is a file name under native/patches/ (for example 0001-peacebell-hooks.patch); it is
# created or replaced. edited-copy defaults to $PROSEPLOW_LLAMA_SRC.
#
# The patch is the difference between "the pinned commit plus every OTHER patch" and the edited
# copy, restricted to files that exist upstream. Files that exist only in the copy are never put
# into a patch: new sources belong under native/llama-ext/, and the script lists any that are not
# there yet so nothing is lost silently. Keep one patch per architecture and keep them
# independent, so that each can be regenerated this way.
#
# Like the prepare script, this only reads the submodule.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../.." && pwd)"
submodule="${repo}/third_party/llama.cpp"
patches_dir="${repo}/native/patches"
ext_dir="${repo}/native/llama-ext"

die() { echo "make-hook-patch: $*" >&2; exit 1; }

[ $# -ge 1 ] || die "usage: make-hook-patch.sh <patch-name> [edited-copy]"
name="$(basename "$1")"
case "${name}" in *.patch) ;; *) die "the patch name must end in .patch" ;; esac
copy="${2:-${PROSEPLOW_LLAMA_SRC:-}}"
[ -n "${copy}" ] || die "no edited copy: pass its directory or set PROSEPLOW_LLAMA_SRC"
[ -d "${copy}" ] || die "${copy} is not a directory (run prepare-llama-src.sh first)"

commit="$(git -C "${submodule}" rev-parse HEAD)"
expected="$(tr -d '[:space:]' < "${patches_dir}/UPSTREAM_COMMIT")"
[ "${commit}" = "${expected}" ] || die "the submodule is at ${commit} but native/patches/UPSTREAM_COMMIT says ${expected}"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/proseplow-hook.XXXXXX")"
trap 'rm -rf "${tmp}"' EXIT
mkdir "${tmp}/a" "${tmp}/b" "${tmp}/base"

# The base tree: upstream plus the other patches.
git -C "${submodule}" archive --format=tar "${commit}" | tar -x -C "${tmp}/base"
while IFS= read -r p; do
    [ "$(basename "${p}")" != "${name}" ] || continue
    ( cd "${tmp}/base" && GIT_CEILING_DIRECTORIES="${tmp}" git apply --whitespace=error-all -p1 "${p}" ) \
        || die "$(basename "${p}") does not apply to ${commit:0:7}"
done < <(find "${patches_dir}" -maxdepth 1 -name '*.patch' | LC_ALL=C sort)

n_changed=0
while IFS= read -r f; do
    [ -f "${copy}/${f}" ] || die "${f} is missing from the copy; hook patches never delete upstream files"
    if ! cmp -s "${tmp}/base/${f}" "${copy}/${f}"; then
        mkdir -p "$(dirname "${tmp}/a/${f}")" "$(dirname "${tmp}/b/${f}")"
        cp "${tmp}/base/${f}" "${tmp}/a/${f}"
        cp "${copy}/${f}" "${tmp}/b/${f}"
        n_changed=$((n_changed + 1))
    fi
done < <(git -C "${submodule}" ls-tree -r --name-only "${commit}")

[ "${n_changed}" -gt 0 ] || die "the copy does not differ from upstream plus the other patches; nothing to write"

# With directories literally named a and b and no extra prefix, the headers come out as the
# usual a/<path> and b/<path>. Exit status 1 only means "there are differences".
( cd "${tmp}" && git diff --no-index --no-prefix --no-color --no-renames a b > hook.patch ) || [ $? -eq 1 ]
# Drop the "index <hash>..<hash> <mode>" lines: they add nothing to a plain patch and would make
# the file change whenever an unrelated part of the same upstream file moves.
sed -i '/^index [0-9a-f]\{7,\}\.\.[0-9a-f]\{7,\}/d' "${tmp}/hook.patch"
cp "${tmp}/hook.patch" "${patches_dir}/${name}"

added="$(grep -c '^+[^+]' "${patches_dir}/${name}" || true)"
removed="$(grep -c '^-[^-]' "${patches_dir}/${name}" || true)"
echo "make-hook-patch: wrote native/patches/${name}: ${n_changed} file(s), +${added} -${removed} line(s)"

# Anything else in the copy that upstream does not have and the extension tree does not provide.
( cd "${copy}" && find . -type f ! -name '.proseplow-prepared' ! -path '*/__pycache__/*' | sed 's|^\./||' | LC_ALL=C sort ) > "${tmp}/copy.lst"
git -C "${submodule}" ls-tree -r --name-only "${commit}" | LC_ALL=C sort > "${tmp}/upstream.lst"
LC_ALL=C comm -23 "${tmp}/copy.lst" "${tmp}/upstream.lst" | while IFS= read -r f; do
    if [ ! -f "${ext_dir}/${f}" ]; then
        echo "make-hook-patch: note: ${f} exists only in the copy; move it to native/llama-ext/${f} to keep it"
    elif ! cmp -s "${ext_dir}/${f}" "${copy}/${f}"; then
        echo "make-hook-patch: note: ${f} differs from native/llama-ext/${f}; copy your edit back to keep it"
    fi
done
