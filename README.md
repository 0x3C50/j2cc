# j2cc

now public!

(i havent maintained it in like 4 months)

A java to C++ transpiler

## What does this do?

This project aims to transpile existing, valid, runnable Java Bytecode into C++ using JNI, effectively replacing the
input bytecode with a native version. The main reason for this is to obfuscate sensitive code, as to make it harder for
people to bypass, for example, license checks.

## DISCLAIMER

**Every obfuscation can be broken**. **There is no exception to this rule**, and this project does
**not claim to be one**.

While code obfuscated with this project may keep newcomers out, **people with enough knowledge can reverse engineer the
output of this program**.

Any project claiming to be "100% irreversible" is selling snake oil. **A full, reverse engineering proof program, which
still runs, CAN NOT exist**.

## What this program can and cannot do

This program can:

- Transpile existing java bytecode, which runs on a normal, JVMS compliant JVM without `-noverify`, to C++ code using JNI
	- Compile the resulting C++ to various targets (x86_64 linux, x86_64 windows, etc., Using the Zig C++ drop-in compiler)
- Automatically detect the system it's running on, and load the appropriate native library from the transpiled jar's
  resources (assuming it exists, and was compiled by the author)
- Support almost everything of the modern JVM spec (including ConstantDynamic)
- Handle obfuscated java bytecode
- Slightly obfuscate and optimize the input code, even if it isn't being compiled

This program CANNOT:

- Produce a fully irreversible native library (this does not and CAN NOT exist)
- Handle older versions of the JVM spec
	- For example: JSR and RET are not supported

## Limitations

Methods transpiled by this program are often slower than their java version. This is because a java downcall to native
code takes a (surprisingly) long time, and because the transpiled C++ code is just slower in general. Use transpilation
lightly, do not go overboard with it.

Transpiled methods also may not share the exact same JVM-side semantics as the java code may have. For example, certain
exception messages are slightly different. This is due to a lot of factors, but mainly because this program has to implement
its own handlers for ALL JVMS bytecode instructions, and a perfect 100% parity is close to impossible.
All handlers are still JVMS compliant tho, just small details might differ from common implementations (they're basically UB).

Redefining classes that get referenced by native code **directly** via agents or other mechanisms will likely cause
undefined behaviour, since classes are cached. This only affects direct references, not references via `Class.forName`
or similar. **THIS HAS NOT BEEN VERIFIED**, but it's likely to be the case.

Invokedynamic is also implemented slightly differently, and has a few quirks in being stricter than the JVM counterpart.

- If you use an invokedynamic and have BSM args that may not have the exact same primitive type as the argument slot in
  the BSM method, this may throw a ClassCastException at runtime.
	- Eg: providing an int where a boolean is expected will cause a ClassCastException
	- Side note: the official JVM spec indirectly causes this issue. This is technically not an issue with the
	  transpiler.

## Usage

### Preparing the environment
The compiler needs to have access to certain runtime functions of the util lib. To get these, look at `util/build_comptime_library.sh`.

You can find our your target triple with: `zig targets | jq -r '[.native.cpu.name,.native.os,.native.abi] | join("-")'`

You can get all supported targets with: `zig targets | jq '.libc'`

Generate the relevant native library with:
- `build_comptime_library.sh your_target_triple libraryNameFornativeUtils.extension`
- example for amd64 windows: `build_comptime_library.sh x86_64-windows-gnu nativeUtils.dll`
- example for amd64 macos: `build_comptime_library x86_64-macos-gnu libnativeUtils.dylib`

j2cc should be able to find the proper native now.

### Building a release
1. Generate natives as shown above
2. `mvn clean package`
3. `./createDist.sh`
4. You can now run `./dist_package/start.sh` or `./dist_package/start.ps1`

### Preparing the jar

Before obfuscation can be applied, you need to prepare your jar to be transpiled. First, add
the `me.x150.j2cc:annotations` dependency to your project, to be able to use the `@j2cc.Nativeify` annotation.
Next, annotate any methods to be transpiled with `@j2cc.Nativeify`. Alternatively, you can annotate a class
with `@j2cc.Nativeify`, to transpile any methods within (even synthetic ones).

### Configuring

Configuring is done via a toml file, you can generate an example mockup with `j2cc getSchema`