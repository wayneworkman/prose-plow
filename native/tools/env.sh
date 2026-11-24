# Source this file (". native/tools/env.sh") before building or testing the native code on a host.
# It only changes the current shell.
#
# The tools (cmake, ninja, the Python packages of native/requirements.txt) come from a virtualenv,
# PROSEPLOW_VENV, by default ~/proseplow/venv: the one the README creates and the Gradle build
# converts the models with. When neither exists the tools are taken from PATH as it is, which is
# fine when the right environment is already active.

if [ -z "${PROSEPLOW_VENV:-}" ] && [ -x "${HOME}/proseplow/venv/bin/python" ]; then
    export PROSEPLOW_VENV="${HOME}/proseplow/venv"
fi
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

# Everything that is generated goes under one scratch directory outside the source directories,
# PROSEPLOW_BUILD, by default ~/proseplow/build. The other locations default to places inside it,
# and the checkpoints to ~/proseplow/checkpoints: the same defaults as the Gradle build's.
export PROSEPLOW_BUILD="${PROSEPLOW_BUILD:-${HOME}/proseplow/build}"
export PROSEPLOW_LLAMA_SRC="${PROSEPLOW_LLAMA_SRC:-${PROSEPLOW_BUILD}/llama-src}"
export PROSEPLOW_MODEL_OUT="${PROSEPLOW_MODEL_OUT:-${PROSEPLOW_BUILD}/models}"
export PROSEPLOW_MODEL_SRC="${PROSEPLOW_MODEL_SRC:-${HOME}/proseplow/checkpoints}"

# The host build and the converters are CPU only; hiding the GPUs keeps PyTorch from touching one.
export CUDA_VISIBLE_DEVICES=""
# The converters and tests import Python modules from the patched llama.cpp copy and from this
# repository; neither should be littered with bytecode caches.
export PYTHONDONTWRITEBYTECODE=1
