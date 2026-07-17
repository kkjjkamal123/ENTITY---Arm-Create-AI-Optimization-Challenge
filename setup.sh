#!/usr/bin/env bash
# Lay out the ENTITY app inside a fresh llama.cpp checkout so it can build.
set -e
here="$(cd "$(dirname "$0")" && pwd)"

echo "Downloading llama.cpp master..."
curl -sL -o /tmp/llama.tar.gz https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.tar.gz
tar xzf /tmp/llama.tar.gz -C "$here"

echo "Placing entity.android into examples/..."
cp -r "$here/app/entity.android" "$here/llama.cpp-master/examples/entity.android"

echo
echo "Done. Now:"
echo "  export ANDROID_HOME=/path/to/Android/sdk"
echo "  cd $here/llama.cpp-master/examples/entity.android"
echo "  ./gradlew :app:assembleDebug"
