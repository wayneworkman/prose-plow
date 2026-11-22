#!/usr/bin/env bash
# The whole host check in one command: patched llama.cpp source, build, conversions, tests.
#
#   PROSEPLOW_BUILD=/path/to/scratch [PROSEPLOW_VENV=...] [PROSEPLOW_MODEL_SRC=...] native/tools/host-check.sh
#
# It runs exactly the commands native/README.md lists one by one, and stops at the first that
# fails. Tests that need a checkpoint which is not on this machine are reported as skipped.
#
#   PROSEPLOW_JOBS      parallel compile jobs (default: all processors)
#   PROSEPLOW_THREADS   inference threads in the tests (default: 4)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../.." && pwd)"

# shellcheck source=native/tools/env.sh
. "${here}/env.sh"
[ -n "${PROSEPLOW_BUILD:-}" ] || exit 1

jobs="${PROSEPLOW_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
build="${PROSEPLOW_BUILD}/host"

"${here}/prepare-llama-src.sh"
cmake -S "${repo}/native" -B "${build}" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "${build}" -j "${jobs}"
if [ -n "${PROSEPLOW_MODEL_SRC:-}" ]; then
    python3 "${repo}/models/convert_models.py" --also-f32 --available
fi
ctest --test-dir "${build}" --output-on-failure
