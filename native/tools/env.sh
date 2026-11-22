# Source this file (". native/tools/env.sh") before building or testing the native code on a host.
# It only changes the current shell.
#
# The tools (cmake, ninja, the Python packages of native/requirements.txt) come from a virtualenv
# that you create wherever you keep such things and name in PROSEPLOW_VENV. Without that
# variable the tools are taken from PATH as it is, which is fine when the right environment is
# already active.

if [ -n "${PROSEPLOW_VENV:-}" ]; then
    if [ -x "${PROSEPLOW_VENV}/bin/python" ]; then
        case ":${PATH}:" in
            *":${PROSEPLOW_VENV}/bin:"*) ;;
            *) PATH="${PROSEPLOW_VENV}/bin:${PATH}" ;;
        esac
        export PATH
    else
        echo "native/tools/env.sh: PROSEPLOW_VENV=${PROSEPLOW_VENV} has no bin/python (see native/README.md)" >&2
    fi
fi

# Everything that is generated goes under one scratch directory of your choice, outside the
# source directories. The other locations default to places inside it.
if [ -n "${PROSEPLOW_BUILD:-}" ]; then
    export PROSEPLOW_LLAMA_SRC="${PROSEPLOW_LLAMA_SRC:-${PROSEPLOW_BUILD}/llama-src}"
    export PROSEPLOW_MODEL_OUT="${PROSEPLOW_MODEL_OUT:-${PROSEPLOW_BUILD}/models}"
else
    echo "native/tools/env.sh: set PROSEPLOW_BUILD to a scratch directory first (see native/README.md)" >&2
fi

# The host build and the converters are CPU only; hiding the GPUs keeps PyTorch from touching one.
export CUDA_VISIBLE_DEVICES=""
# The converters and tests import Python modules from the patched llama.cpp copy and from this
# repository; neither should be littered with bytecode caches.
export PYTHONDONTWRITEBYTECODE=1
