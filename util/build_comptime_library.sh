#!/usr/bin/zsh

cdir=${0:a:h}

if [[ $# == 0 ]]; then
  targets=("x86_64-linux-gnu" "libnativeUtils.so" "x86_64-windows-gnu" "nativeUtils.dll" "x86_64-macos-none" "libnativeUtils.dylib")
else
  targets=("$@")
fi
for ((i = 1; i <= $#targets; i+=2)); do
  nt="${targets[i]}"
  name="${targets[i + 1]}"
  echo "building $nt into $name"
  "$cdir"/../zig-compiler/zig c++ -Xpreprocessor -DCOMPILING_COMPTIME_LIB -shared -O2 -fno-exceptions -o "$cdir/../natives/$name" -I "$cdir" -std=c++23 "-Wl,-S" \
    chacha20.cpp \
    -target "$nt"
done

rm -v -- "$cdir"/../natives/*.lib