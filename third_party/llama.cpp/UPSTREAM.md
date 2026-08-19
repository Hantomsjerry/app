# Upstream Provenance

- Repository: https://github.com/ggml-org/llama.cpp
- Official tag: `b9637`
- Full commit SHA: `aedb2a5e9ca3d4064148bbb919e0ddc0c1b70ab3`
- Retrieved: 2026-07-16
- Retrieval: shallow official Git checkout of tag `b9637`; `HEAD`, exact tag, and a clean source status were verified before nested Git metadata was removed.
- License: MIT; see `LICENSE` in this directory.

## Local Changes

No upstream source files are modified. `UPSTREAM.md` is the only locally added file in this directory.

## Android Build

The app builds the CPU backend only for `arm64-v8a` and Android API 29. The integration sets these CMake cache flags before adding llama.cpp:

- `BUILD_SHARED_LIBS=OFF`
- `GGML_BACKEND_DL=OFF`
- `GGML_CCACHE=OFF`
- `GGML_CPU=ON`
- `GGML_CPU_KLEIDIAI=OFF`
- `GGML_LLAMAFILE=OFF`
- `GGML_NATIVE=OFF`
- `GGML_OPENMP=OFF`
- `GGML_RPC=OFF`
- `LLAMA_BUILD_APP=OFF`
- `LLAMA_BUILD_COMMON=OFF`
- `LLAMA_BUILD_EXAMPLES=OFF`
- `LLAMA_BUILD_SERVER=OFF`
- `LLAMA_BUILD_TESTS=OFF`
- `LLAMA_BUILD_TOOLS=OFF`
- `LLAMA_BUILD_UI=OFF`
- `LLAMA_OPENSSL=OFF`
- `LLAMA_TESTS_INSTALL=OFF`
- `LLAMA_TOOLS_INSTALL=OFF`
- `LLAMA_USE_PREBUILT_UI=OFF`

The JNI library uses memory mapping, a 1024-token context, prompt batches capped at 256 tokens, and the greedy sampler (temperature-zero behavior). No network, server, OpenSSL, dynamic backend, test, example, app, UI, or tool targets are built.

