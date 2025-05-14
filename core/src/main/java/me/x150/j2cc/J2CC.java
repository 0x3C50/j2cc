package me.x150.j2cc;

import j2cc.Exclude;
import j2cc.Nativeify;
import j2cc.internal.Debug;
import j2cc.internal.Loader;
import j2cc.internal.Platform;
import lombok.Cleanup;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.compiler.CacheSlotManager;
import me.x150.j2cc.compiler.CompilerJob;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.compilerExec.Compiler;
import me.x150.j2cc.conf.Configuration;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.cppwriter.Printable;
import me.x150.j2cc.cppwriter.SourceBuilder;
import me.x150.j2cc.exc.CompilationFailure;
import me.x150.j2cc.input.InputProvider;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.optim.OptimizerPass;
import me.x150.j2cc.output.OutputSink;
import me.x150.j2cc.tree.Remapper;
import me.x150.j2cc.tree.SmartClassWriter;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.tree.resolver.Resolver;
import me.x150.j2cc.util.Util;
import me.x150.j2cc.util.*;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.*;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class J2CC {

	/**
	 * Runs the compiler with the specified {@link Context configuration}.
	 *
	 * @param context  Configuration to run
	 * @param compiler The C++ compiler to call for natives generation
	 * @throws CompilationFailure In case compilation fails in any section, and compilation could not complete successfully.
	 */
	@Nativeify
	public static void doObfuscate(Context context, Compiler compiler, Obfuscator obfuscatorInst) throws CompilationFailure {
		if (!compiler.supportsCrossComp()) {
			String currentTarget = Platform.RESOURCE_PREFIX;
			if (context.customTargets().stream().anyMatch(it -> !it.resourcePrefix().equals(currentTarget))) {
				throw new IllegalArgumentException("Selected compiler " + compiler + " does not support cross compilation. Current platform: " + currentTarget + ". Context configured to compile to: " + context.customTargets().stream().map(DefaultCompiler.Target::resourcePrefix).collect(Collectors.joining(", ")));
			}
		}

		Path utilPath = context.utilPath();

		String mainClass = null;

		try (InputProvider inputProv = context.input(); OutputSink outputSink = context.output()) {
			log.debug("Input: {}, Output: {}", inputProv, outputSink);

			Path rootPath = inputProv.getFile("");

			log.debug("Loading classes...");
			List<ClassEntry> classesToProcess = loadInitialClasses(context, rootPath, outputSink);

			// add internal util
			ClassNode internalUtilClass = Util.generateInternalUtilClass(context);
			context.workspace().registerAndMapExternal(Collections.singleton(internalUtilClass));

			Manifest mf = null;
			Path manifestPath = rootPath.resolve("META-INF").resolve("MANIFEST.MF");
			if (Files.exists(manifestPath)) {
				mf = new Manifest(Files.newInputStream(manifestPath));

				obfuscatorInst.transformManifest(context, mf);

				String mainClassBinaryName = mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
				if (mainClassBinaryName != null) {
					mainClass = mainClassBinaryName.replace('.', '/');
				}
			} else {
				obfuscatorInst.transformManifest(context, null);
			}

			Remapper remapper = new Remapper(context.workspace(), classesToProcess.stream().map(ClassEntry::info).toList());
			Configuration.RenamerSettings renamerSettings = context.obfuscationSettings().renamerSettings();
			if (renamerSettings != null) {
				String packageName = renamerSettings.internalClassesPackageName;
				remapper.mapClass("j2cc/internal/Loader", packageName+"/Loader");
				remapper.mapClass("j2cc/internal/Platform", packageName+"/Platform");

				if (renamerSettings.isEnable()) {
					log.debug("Generating mappings...");
					renameMembers(context, context.workspace(), classesToProcess, remapper);
					if (mf != null && mainClass != null) {
						String mappedClassName = remapper.map(mainClass);
						mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mappedClassName);
					}
					remapper.print();
					if (!renamerSettings.getExportPath().isBlank()) {
						MappingSet ms = MappingSet.fromRemapper(remapper);
						try (OutputStream outputStream = Files.newOutputStream(Path.of(renamerSettings.getExportPath()));
							 DataOutputStream dos = new DataOutputStream(outputStream)) {
							ms.exportTo(dos);
						}
					}
				}
			}

			List<ClassNode> loaderFiles = writeLoaderFiles(remapper, context, outputSink);
			context.workspace().registerAndMapExternal(loaderFiles);

			log.debug("Obfuscating...");
			classesToProcess.addAll(obfuscatorInst.obfuscate(new ObfuscationContext(mainClass, remapper), context, classesToProcess)
					.stream().map(info -> new ClassEntry(info, "")).toList());

			if (mf != null) {
				try (OutputStream outputStream = outputSink.openFile("META-INF/MANIFEST.MF")) {
					mf.write(outputStream);
				}
			}

			StringCollector stringCollector = new StringCollector();

			List<DefaultCompiler.CSourceFileEntry> sourceFiles = new ArrayList<>();
			List<DefaultCompiler.CompiledMethod> compiledMethods = new ArrayList<>();

			Map<Pair<ClassNode, String>, List<CompletableFuture<DefaultCompiler.CompiledMethod>>> runningJobs = new HashMap<>();

			CacheSlotManager indyCache = new CacheSlotManager();
			try (ExecutorService threadPool = context.parallelExecutorForNThreads()) {
				for (ClassEntry classToProcess : classesToProcess) {
					ClassNode clazz = classToProcess.info().node();
					ClassNode remappedClazz = new ClassNode();
					clazz.accept(new ClassRemapper(remappedClazz, remapper));
//					clazz.accept(remappedClazz);
					SourceBuilder headerFile = new SourceBuilder();

					String name = "j2cc_" + clazz.name.replaceAll("[^a-zA-Z0-9_\\-]", "_");

					CompilerJob compilerJob = createCompilerJob(context, clazz, remappedClazz, headerFile, () -> {
						SourceBuilder sourceFile = new SourceBuilder();
						sourceFile.include("antiHook.h", true);
						sourceFile.include(name + ".h", true);
						return sourceFile;
					});
					if (compilerJob == null) {
						// nothing to do, do standard processing and write
						stripj2ccAnnotations(classToProcess.info.node());
						ClassWriter cw11 = new SmartClassWriter(ClassWriter.COMPUTE_FRAMES, context.workspace(), remapper);
						try {
							remappedClazz.accept(cw11);
						} catch (RuntimeException t) {
							log.error("Failed to write class {} (was: {})", remappedClazz.name, clazz.name, t);
							throw t;
						}
						synchronized (outputSink) {
							String actualName = remappedClazz.name + ".class";
							if (!classToProcess.namePrefix.isEmpty())
								actualName = classToProcess.namePrefix + "/" + actualName;
							log.debug("Writing class {} (was: {}) to {}", remappedClazz.name, clazz.name, actualName);
							@Cleanup OutputStream outputStream1 = outputSink.openFile(actualName);
							outputStream1.write(cw11.toByteArray());
						}
						continue;
					}

					headerFile.include("jni.h", false);
					headerFile.include("cmath", false);
					headerFile.include("util.h", true);

					List<CompletableFuture<DefaultCompiler.CompiledMethod>> compile = compilerJob.compile(remapper, context, threadPool, indyCache, stringCollector);
					sourceFiles.add(new DefaultCompiler.CSourceFileEntry(false, headerFile, name));
					SourceBuilder[] sources = compilerJob.sources();
					for (int i = 0; i < sources.length; i++) {
						SourceBuilder source = sources[i];
						sourceFiles.add(new DefaultCompiler.CSourceFileEntry(true, source, name + "_" + i));
					}
					runningJobs.computeIfAbsent(new Pair<>(remappedClazz, classToProcess.namePrefix), _ -> new ArrayList<>())
							.addAll(compile);
				}

				for (Pair<ClassNode, String> classNode : runningJobs.keySet()) {
					log.trace("Assembling class {}", classNode.getA().name);
					List<CompletableFuture<DefaultCompiler.CompiledMethod>> theJobs = runningJobs.get(classNode);
					for (CompletableFuture<DefaultCompiler.CompiledMethod> theJob : theJobs) {
						try {
							DefaultCompiler.CompiledMethod theValue = theJob.get();
							compiledMethods.add(theValue);
						} catch (ExecutionException ce) {
							// failed to build
							throw ce.getCause();
						}
					}

					stripj2ccAnnotations(classNode.getA());

					String actualName = classNode.getA().name + ".class";
					if (!classNode.getB().isEmpty()) actualName = classNode.getB() + "/" + actualName;

					log.debug("Writing partially compiled class {} to {}", classNode.getA().name, actualName);
					ClassWriter cw1 = new SmartClassWriter(ClassWriter.COMPUTE_FRAMES, context.workspace(), remapper);
					classNode.getA().accept(new CheckClassAdapter(cw1));
					@Cleanup OutputStream outputStream = outputSink.openFile(actualName);
					outputStream.write(cw1.toByteArray());
				}
			}

			Path tmpDir = context.specialTempPath() != null ? Files.createTempDirectory(context.specialTempPath(), "j2cc-compilation") : Files.createTempDirectory("j2cc-compilation");

			boolean skipNativesGeneration = compiledMethods.isEmpty() && classesToProcess.stream().noneMatch(it -> it.info.node().name.equals("rt/Constants"));

			writeCppSources(context, sourceFiles, tmpDir, compiledMethods, remapper, internalUtilClass);

			String javaHome = System.getProperty("java.home");
			Path javaHomeInclude = Path.of(javaHome).resolve("include");
			if (skipNativesGeneration) {
				log.warn("We can probably skip native generation, since no native features were used. NOT COMPILING NATIVES!");
			} else {
				compileAndWriteNatives(context, compiler, tmpDir, sourceFiles, utilPath, javaHomeInclude, indyCache, outputSink, stringCollector);
			}

			if (!context.keepTemp()) Util.rmRf(tmpDir);
			else {
				log.info("Would delete temp directory at this point, but --keepTempDir is enabled; Temp directory: {}", tmpDir.toAbsolutePath());
			}

		} catch (CompilationFailure cf) {
			throw cf; // pass up
		} catch (Throwable t) {
			throw new CompilationFailure(t);
		}
	}

	public static boolean willCompileMethod(Context ctx, ClassNode cn, MethodNode mn) {
		CompilerJob compilerJob = createCompilerJob(ctx, cn, cn /* doesnt matter in this case */, null, () -> null);
		if (compilerJob == null) return false; // no methods will be compiled
		for (MethodNode methodNode : compilerJob.toCompile()) {
			if (methodNode == mn) return true; // yep, compile
		}
		return false; // no method was like mn
	}

	public static boolean shouldIgnoreMethod(Context cn, String own, MethodNode mn) {
		return Modifier.isAbstract(mn.access)
				|| Modifier.isNative(mn.access)
				|| mn.name.startsWith("<")
				|| Util.shouldIgnore(cn, own, mn, Exclude.From.COMPILATION);
	}

	public static @Nullable CompilerJob createCompilerJob(Context context, ClassNode clazz, ClassNode remappedClazz, SourceBuilder headerFile, Supplier<SourceBuilder> sourceFileGenerator) {
		if (Modifier.isInterface(clazz.access)) return null; // nothing can be done

//		boolean ignoredClassesHasThisClass = context.classesToIgnore() != null && context.classesToIgnore().stream().anyMatch(cf -> cf.matches(clazz.name));
		if (Util.shouldIgnore(context, clazz, Exclude.From.COMPILATION)) return null; // do not compile at all
		if (context.compileAllMethods()) {
			// compile all methods except for the ones ignored
			List<MethodNode> list = remappedClazz.methods
					.stream()
					.filter(f -> !shouldIgnoreMethod(context, clazz.name, f))
					.toList();
			if (list.isEmpty()) return null; // nothing to do
			SourceBuilder[] sbs = new SourceBuilder[list.size()];
			for (int i = 0; i < sbs.length; i++) {
				sbs[i] = sourceFileGenerator.get();
			}
			return new CompilerJob(remappedClazz, list.toArray(MethodNode[]::new), headerFile, sbs);
		}
		// compile all methods annotated or in include list, excluding ignored
		boolean theClassIsIncluded = context.classesToInclude() != null && context.classesToInclude().stream().anyMatch(it -> it.matches(clazz.name));
		List<MethodNode> list = remappedClazz.methods
				.stream()
				.filter(f -> !shouldIgnoreMethod(context, clazz.name, f))
				.filter(f -> {
							boolean real = theClassIsIncluded
									|| CompilerJob.hasNativeifyAnnotation(clazz, f);
							if (context.methodsToInclude() != null)
								real |= context.methodsToInclude().stream().anyMatch(it -> it.matches(clazz.name, f.name, Type.getMethodType(f.desc)));
							return real;
						}
				)
				.toList();
		if (list.isEmpty()) return null; // nothing to do
		SourceBuilder[] sbs = new SourceBuilder[list.size()];
		for (int i = 0; i < sbs.length; i++) {
			sbs[i] = sourceFileGenerator.get();
		}
		return new CompilerJob(remappedClazz, list.toArray(MethodNode[]::new), headerFile, sbs);
	}

	private static void compileAndWriteNatives(Context context, Compiler zigCompiler, Path tmpDir, List<DefaultCompiler.CSourceFileEntry> sourceFiles, Path utilPath, Path javaHomeInclude, CacheSlotManager indyCache, OutputSink outputSink, StringCollector stringCollector) throws IOException {
		Path[] postCompileCommands = context.postCompileCommands();
		List<DefaultCompiler.Target> allTargets = context.customTargets();
		if (allTargets.isEmpty())
			throw new CompilationFailure("No targets to build. Add at least one target in the targets array.");
		ByteArrayOutputStream tempRelocTable = new ByteArrayOutputStream();
		try (OutputStream nativesFile = outputSink.openFile("j2cc/natives.bin");
			 DataOutputStream dos = new DataOutputStream(tempRelocTable)) {
			long positionCounter = 0L;

			for (DefaultCompiler.Target target : allTargets) {
				log.info("Compiling target {} ({})...", target.resourcePrefix(), target.id());
				Path outputBinary = tmpDir.resolve(target.resourcePrefix());

				byte[] constantskey = new byte[48];
				ThreadLocalRandom.current().nextBytes(constantskey);

				Path generatedFile = generateConstantsStub(stringCollector, constantskey, tmpDir);


				List<String> argumentes = new ArrayList<>(List.of(
						"-Wl,-s", "-shared", "-O2", "-fno-exceptions", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
						"-o", outputBinary.toString(),
						"-I", javaHomeInclude.toAbsolutePath().toString(),
						"-I", javaHomeInclude.resolve(Platform.JNI_INCLUDE).toAbsolutePath().toString(),
						"-I", utilPath.toString(),
						"-std=c++23",
						"-Xpreprocessor", "-DDYN_CACHE_SIZE=" + indyCache.getIndyAmount(),
						"-Xpreprocessor", "-DCLAZZ_CACHE_SIZE=" + indyCache.getClassAmount(),
						"-Xpreprocessor", "-DFIELD_CACHE_SIZE=" + indyCache.getFieldAmount(),
						"-Xpreprocessor", "-DMETHOD_CACHE_SIZE=" + indyCache.getMethodAmount()
				));
				Configuration.AntiHookSettings ahSettings = context.obfuscationSettings().antiHook();
				if (ahSettings.isEnableAntiHook()) {
					int mode = ahSettings.action.ordinal();
					argumentes.addAll(List.of("-Xpreprocessor", "-DHOOK_ACTION="+mode));
					if (ahSettings.action == Configuration.AntiHookSettings.Action.CALL_METHOD) {
						String the = Objects.requireNonNull(ahSettings.callMethodOnHookDetected);
						String[] parts = the.split("\\.", 2);
						String owner = parts[0];
						String method = parts[1];
						argumentes.addAll(List.of("-Xpreprocessor", "-DCALL_TARGET="+owner));
						argumentes.addAll(List.of("-Xpreprocessor", "-DCALL_TARGET_M="+method));
					}
				}
				if (context.debug().isVerboseRuntime()) argumentes.addAll(List.of("-Xpreprocessor", "-DJ2CC_DBG"));
				if (context.zigArgs() != null) argumentes.addAll(context.zigArgs());

				Path argfile = context.specialTempPath() != null ? Files.createTempFile(context.specialTempPath(), "j2cc-argfile", ".txt") : Files.createTempFile("j2cc-argfile", ".txt");
				List<String> filesToCompile = new ArrayList<>();
				for (DefaultCompiler.CSourceFileEntry sourceFile : sourceFiles) {
					if (sourceFile.isObject()) filesToCompile.add(sourceFile.name() + ".cpp");
				}
				filesToCompile.add("main.cpp");
				filesToCompile.add(utilPath.resolve("util.cpp").toString());
				filesToCompile.add(utilPath.resolve("antiHook.cpp").toString());
				filesToCompile.add(utilPath.resolve("chacha20.cpp").toString());
				filesToCompile.add(generatedFile.toAbsolutePath().normalize().toString());
				filesToCompile.replaceAll(J2CC::escapeArgument);
				Files.write(argfile, filesToCompile);

				argumentes.add("@" + argfile.toAbsolutePath().normalize());
				log.info("[compiler] cc {}", String.join(" ", argumentes));
				try {
					Process invoke = zigCompiler.invoke(tmpDir, target.id(), argumentes.toArray(String[]::new));
					int i = invoke.waitFor();
					if (i != 0) throw new IllegalStateException("Non-zero return code from compiler: " + i);
					Files.delete(argfile);
					if (postCompileCommands != null) {
						for (Path postCompileCommand : postCompileCommands) {
							Path normPath = postCompileCommand.toAbsolutePath().normalize();
							log.info("Running post-compile command: {} {}", normPath, outputBinary);
							Process pb = new ProcessBuilder(normPath.toString(), outputBinary.toString())
									.inheritIO()
									.start();
							int i1 = pb.waitFor();
							if (i1 != 0)
								throw new IllegalStateException("Post-compile hook " + normPath + " returned code " + i1);
						}
					}
					try (InputStream inputStream = Files.newInputStream(outputBinary)) {
						long nWritten = inputStream.transferTo(nativesFile);
						log.debug("Wrote native {} to offset {} (length {})", target.resourcePrefix(), positionCounter, nWritten);
						dos.writeUTF(target.resourcePrefix());
						dos.writeLong(positionCounter);
						dos.writeLong(nWritten);
						dos.write(constantskey);
						positionCounter += nWritten;
					}
				} catch (Throwable t) {
					log.error("Failed to compile target {} ({})", target.resourcePrefix(), target.id());
					log.error("Stacktrace: ", t);
					throw new CompilationFailure("Couldn't compile target " + target.resourcePrefix() + " (" + target.id() + ")", t);
				}
			}
		}
		try (OutputStream outputStream = outputSink.openFile("j2cc/relocationInfo.dat")) {
			outputStream.write(tempRelocTable.toByteArray());
		}
	}

	private static Path generateConstantsStub(StringCollector stringCollector, byte[] constantskey, Path tempDir) throws IOException {
		SourceBuilder sb = new SourceBuilder();
		sb.include("util.h", true);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		stringCollector.writeEncryptedPoolTo(baos, constantskey);
		StringBuilder ss = new StringBuilder();
		byte[] byteArray = baos.toByteArray();
		for (byte b : byteArray) {
			ss.append(b).append(",");
		}

		sb.addTop(Printable.formatted("static char j2c_constants[] = { $l };", ss));
		sb.addTop(Printable.formatted("static StringCpInfo j2c_constantInfo = { .ptr = j2c_constants, .n = $l };", stringCollector.size()));
		sb.addTop(Printable.constant("StringCpInfo* stringConstantPool = &j2c_constantInfo;"));

		Path constants = Files.createTempFile(tempDir, "constants", ".cpp");
		Files.writeString(constants, sb.stringify());
		return constants;
	}

	private static String escapeArgument(String s) {
		// we love regex, replace \ with \\
		s = s.replaceAll("\\\\", "\\\\\\\\");
		// we love regex again, replace " with \"
		s = s.replaceAll("\"", "\\\\\"");
		return "\"" + s + "\"";
	}

	private static void writeCppSources(Context context, List<DefaultCompiler.CSourceFileEntry> sourceFiles, Path tmpDir, List<DefaultCompiler.CompiledMethod> compiledMethods, Remapper remapper, ClassNode internalUtilClass) throws IOException {
		SourceBuilder mainBuilder = new SourceBuilder();
		mainBuilder.include("utility", false);
		for (DefaultCompiler.CSourceFileEntry csfe : sourceFiles) {
			Path writeTo = tmpDir.resolve(csfe.name() + (csfe.isObject() ? ".cpp" : ".h"));
			Files.writeString(writeTo, csfe.sb().stringify());
			if (!csfe.isObject()) mainBuilder.include(tmpDir.relativize(writeTo).toString(), true);
		}
		mainBuilder.include("util.h", true);

		Method method = mainBuilder.method(null, "int", "main", "int argc", "char** argp");
		method.addStatement("puts($s)", "Don't run me directly you idiot, I'm a library!");
		method.addStatement("return 0");


		Map<String, List<DefaultCompiler.CompiledMethod>> collect = compiledMethods.stream().collect(Collectors.groupingBy(DefaultCompiler.CompiledMethod::owner));

		String mappedLoaderName = Util.loaderClassName(remapper).replace('/', '_');


		Method ensureClazzInitialized = mainBuilder.method("__attribute__((visibility(\"default\"))) EXPORT JNIEXPORT", "void", "JNICALL Java_"+mappedLoaderName+"_initClass",
				"JNIEnv* env", "jclass lc", "jclass clazz");

		ensureClazzInitialized.local("bool", "clNameResolvedOk");
		ensureClazzInitialized.local("JNINativeMethod", "methods[" + collect.values().stream().mapToInt(List::size).max().orElse(0) + "]");
		ensureClazzInitialized.local("std::string", "clName").initStmt("cache::clazzName(env, clazz, &clNameResolvedOk)");
		ensureClazzInitialized.beginScope("if (!clNameResolvedOk)");
		ensureClazzInitialized.addStatement("return"); // exception is already set, will be thrown when execution resumes in jvm land
		ensureClazzInitialized.endScope();
		boolean e = false;
		for (String clName : collect.keySet()) {
			// clName is std::string
			ensureClazzInitialized.beginScope((e ? "else " : "") + "if (clName == $s)", clName.replace('/', '.'));
			e = true;

			List<DefaultCompiler.CompiledMethod> cmpMethods = collect.get(clName);
			for (int i = 0; i < cmpMethods.size(); i++) {
				DefaultCompiler.CompiledMethod compiledMethod1 = cmpMethods.get(i);
				if (context.debug().isPrintMethodLink()) {
					ensureClazzInitialized.addStatement("puts($s)", "[j2cc] Linking native method " + compiledMethod1.mn() + " to java method " + compiledMethod1.owner() + "." + compiledMethod1.name() + compiledMethod1.desc());
				}
				ensureClazzInitialized.addStatement("methods[$l] = { (char*) $s, (char*) $s, (void*) &$l }", i, compiledMethod1.name(), compiledMethod1.desc(), compiledMethod1.mn());
			}

			ensureClazzInitialized.addStatement("env->RegisterNatives(clazz, methods, $l)", cmpMethods.size());

			ensureClazzInitialized.endScope();
		}
		if (!collect.isEmpty()) {
			ensureClazzInitialized.beginScope("else");
		}
//		ensureClazzInitialized.addStatement("fprintf(stderr, $s, clName.c_str())", "[j2cc] Unknown native compiled class %s. This is most likely a problem with natives generation.\n");
//		ensureClazzInitialized.addStatement("fflush(stderr)");
		ensureClazzInitialized.addStatement("std::unreachable()");
		if (!collect.isEmpty()) {
			ensureClazzInitialized.endScope();
		}

		Method bootstrapMethodC = mainBuilder.method("__attribute__((visibility(\"default\"))) EXPORT JNIEXPORT", "void", "JNICALL Java_"+mappedLoaderName+"_bootstrap",
				"JNIEnv* env", "jclass loaderClazz", "jbyteArray key");

		SmartClassWriter internalUtilCw = new SmartClassWriter(SmartClassWriter.COMPUTE_FRAMES, context.workspace(), remapper);
		internalUtilClass.accept(internalUtilCw);
		byte[] internalUtil = internalUtilCw.toByteArray();
		int[] bytes = new int[internalUtil.length];
		for (int i = 0; i < internalUtil.length; i++) {
			bytes[i] = internalUtil[i];
		}
		bootstrapMethodC.beginScope("");
		bootstrapMethodC.addStatement("jbyte internalUtilBuffer[] = { $l }", Arrays.stream(bytes).mapToObj(String::valueOf).collect(Collectors.joining(", ")));
		bootstrapMethodC.addStatement("env->DefineClass(nullptr, nullptr, internalUtilBuffer, $l)", internalUtil.length);
		bootstrapMethodC.addStatement("initChachaTable(env, key)");
		bootstrapMethodC.endScope();

		Files.writeString(tmpDir.resolve("main.cpp"), mainBuilder.stringify());
	}

	private static String normalizePath(String pth) {
		return pth.replace('\\', '/');
	}

	private static List<ClassEntry> loadInitialClasses(Context context, Path rootPath, OutputSink outputSink) throws IOException {
		List<ClassEntry> classes = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(rootPath)) {
			Iterator<Path> pathIter = walk.iterator();

			ByteBuffer sig = ByteBuffer.allocate(4);
			while (pathIter.hasNext()) {
				Path currentPath = pathIter.next();
				if (currentPath.endsWith(rootPath.getFileSystem().getPath("META-INF", "MANIFEST.MF"))) {
					continue; // we'll do this later
				}
				if (Files.isDirectory(currentPath)) continue;
				@Cleanup SeekableByteChannel seekableByteChannel = Files.newByteChannel(currentPath);
				sig.clear();
				int readBytes = seekableByteChannel.read(sig);
				Path relativePathP = rootPath.relativize(currentPath);
				String relativeName = normalizePath(relativePathP.toString());
				sig.flip();
				if (readBytes < 4) {
					log.debug("Found resource (< 4 bytes) {} ({})", currentPath, relativeName);
					@Cleanup OutputStream outputStream = outputSink.openFile(relativeName);
					if (readBytes > 0) {
						byte[] o = new byte[readBytes];
						sig.get(o);
						outputStream.write(o, 0, readBytes);
					}
					continue;
				}
				if (sig.getInt() != 0xCAFEBABE) {
					sig.rewind();
					log.debug("Found resource (not a class) {} ({})", currentPath, relativeName);
					@Cleanup OutputStream outputStream = outputSink.openFile(relativeName);
					byte[] o = new byte[readBytes];
					sig.get(o);
					outputStream.write(o, 0, readBytes);
					int r;
					byte[] buffer = new byte[1024 * 8];
					ByteBuffer bf = ByteBuffer.wrap(buffer);
					while ((r = seekableByteChannel.read(bf)) != -1) {
						outputStream.write(buffer, 0, r);
						bf.position(0);
					}
					continue;
				}
				byte[] bytes = new byte[(int) (seekableByteChannel.size() - seekableByteChannel.position())];
				seekableByteChannel.read(ByteBuffer.wrap(bytes));
				// kekw
				// the CAFEBABE signature is already read and doesn't get checked by asm, also isn't in the buffer
				// troll asm to offset 4 bytes left to take account for the missing magic
				// classFileLength isn't used, and i can't be bothered to figure out what it should be, -> 0
				ClassReader cr = new ClassReader(bytes, -4, 0);
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
				log.debug("Found class {} @ {} ({})", cn.name, currentPath, relativeName);
				String[] parts = cn.name.split("/");
				int to = relativePathP.getNameCount() - parts.length;
				String namePrefix = to == 0 ? "" : relativePathP.subpath(0, to).toString();
				if (parts[0].equals("j2cc") && parts[1].equals("internal")) continue;
				Workspace.ClassInfo cachedClass = context.workspace().get(relativeName.substring(0, relativeName.length() - ".class".length()));
				classes.add(new ClassEntry(cachedClass, namePrefix));
			}
		}
		return classes;
	}

	private static void renameMembers(Context ctx, Workspace wsp, List<ClassEntry> classesToProc, Remapper remapper) {
		List<ClassEntry> classesToProcess = new ArrayList<>(classesToProc);
		Set<ClassEntry> classesToRemove = new HashSet<>();
		Configuration.RenamerSettings.FilterSet[] fs1 = ctx.obfuscationSettings().renamerSettings().getFilterSets();
		for (Configuration.RenamerSettings.FilterSet filterSet : fs1) {
			String[] classes = filterSet.getClazz();
			String type = filterSet.getFilterType();
			for (String s : classes) {
				ClassFilter cf = ClassFilter.fromString(s);
				if (type.equals("exclude")) {
					classesToProcess.stream()
							.filter(f -> cf.matches(f.info.node().name))
							.forEach(classesToRemove::add);
				} else {
					classesToRemove.removeIf(f -> cf.matches(f.info.node().name));
				}
			}
		}
		for (ClassEntry classEntry : classesToRemove) {
			classesToProcess.remove(classEntry);
		}
		Map<String, NameGenerator> generatorPerPackage = new HashMap<>();
		NameGenerator methodGen = new NameGenerator(NameGenerator.ALPH_LOWER_UPPER);
		for (ClassEntry toProcess : classesToProcess) {
			// can remap this class
			NameGenerator nameGenerator = new NameGenerator(NameGenerator.ALPH_LOWER_UPPER);
			ClassNode targetNode = toProcess.info.node();
			if (Util.shouldIgnore(ctx, targetNode, Exclude.From.RENAMING)) continue;
			if (targetNode.name.startsWith("j2cc")) continue; // nuh uh
			String packageName;
			if (ctx.obfuscationSettings().renamerSettings().isMergePackages()) packageName = "classes";
			else
				packageName = targetNode.name.contains("/") ? targetNode.name.substring(0, targetNode.name.lastIndexOf('/')) : null;
			NameGenerator classNameGen = generatorPerPackage.computeIfAbsent(packageName == null ? "" : packageName, _ -> new NameGenerator(NameGenerator.ALPH_LOWER_UPPER));
			remapper.mapClass(targetNode.name, (packageName != null ? packageName + "/" : "") + classNameGen.nextName());
			for (FieldNode field : targetNode.fields) {
				if (Util.shouldIgnore(ctx, targetNode.name, field, Exclude.From.RENAMING)) {
					continue;
				}
				remapper.mapField(new Remapper.MemberID(targetNode.name, field.name, Type.getType(field.desc)), nameGenerator.nextName());
			}
			for (MethodNode method : targetNode.methods) {
				String name = method.name;
				if (name.startsWith("<") || name.equals("main") ||
						name.equals("values") && toProcess.info.hierarchyParents().stream().anyMatch(it -> it.name.equals(Type.getInternalName(Enum.class))))
					continue;
				if (Util.shouldIgnore(ctx, targetNode.name, method, Exclude.From.RENAMING)) {
					continue;
				}
				Remapper.MemberID h = new Remapper.MemberID(targetNode.name, name, Type.getMethodType(method.desc));
				if (remapper.hasMethodMapping(h)) continue;
				Set<Remapper.MemberID> relatedImpls = remapper.getRelatedMethodImplementations(h);
				// if all related classes implementing our method
				// - are in the classesToProcess list
				// - DON'T have the annotation to exclude from remapping
				// then remap
				boolean shouldMap = relatedImpls
						.stream()
						.map(it -> new Pair<>(wsp.get(it.owner()), it))
						.allMatch(it -> {
							Workspace.ClassInfo theThing = it.getA();
							Optional<ClassEntry> owningClass = classesToProcess.stream()
									.filter(v -> v.info == theThing)
									.findAny();
							ClassNode theThingamabob = theThing.node();
							MethodNode theMthod = theThingamabob.methods.stream().filter(f -> f.name.equals(it.getB().name()) && Type.getMethodType(f.desc).equals(it.getB().type())).findAny().orElseThrow();
							return owningClass.isPresent() // can we modify the class at all?
									&& !Util.shouldIgnore(ctx, theThingamabob, Exclude.From.RENAMING) // can we rename the class?
									&& !Util.shouldIgnore(ctx, theThingamabob.name, theMthod, Exclude.From.RENAMING) // can we rename the method?
									;
						});
				if (shouldMap) {
					// we're ok to continue
					String newName;
					boolean chainHasFunctionalInterface = relatedImpls
							.stream()
							.map(it -> wsp.get(it.owner()).node())
							.filter(it -> Modifier.isInterface(it.access))
							.anyMatch(it -> it.methods.stream().filter(f -> Modifier.isAbstract(f.access)).count() == 1);
					if (chainHasFunctionalInterface) {
						// we cant map this, the invokedynamic regarding this lambda uses the old name as a string.
						// no automatic remapping
						// shitty hack to be fast ("renaming" this method makes previous checks skip for overriding types), but it works
						newName = h.name();
					} else {
						// we can map this method
						newName = methodGen.nextName();
					}
					remapper.mapMethod(h, newName);
				}
			}
		}
	}

	private static void stripj2ccAnnotations(ClassNode cn) {
		Stream.concat(Stream.concat(Stream.of(
								cn.invisibleAnnotations,
								cn.visibleAnnotations,
								cn.invisibleTypeAnnotations,
								cn.visibleTypeAnnotations
						), cn.methods
								.stream()
								.mapMulti((it, cons) -> {
									cons.accept(it.visibleAnnotations);
									cons.accept(it.invisibleAnnotations);
									cons.accept(it.visibleTypeAnnotations);
									cons.accept(it.invisibleTypeAnnotations);
									if (it.visibleParameterAnnotations != null)
										for (List<AnnotationNode> visibleParameterAnnotation : it.visibleParameterAnnotations) {
											cons.accept(visibleParameterAnnotation);
										}
									if (it.invisibleParameterAnnotations != null)
										for (List<AnnotationNode> invisibleParameterAnnotation : it.invisibleParameterAnnotations) {
											cons.accept(invisibleParameterAnnotation);
										}
									cons.accept(it.visibleLocalVariableAnnotations);
									cons.accept(it.invisibleLocalVariableAnnotations);
								})),
						cn.fields.stream().flatMap(it -> Stream.of(it.visibleAnnotations, it.invisibleAnnotations, it.visibleTypeAnnotations, it.invisibleTypeAnnotations)))
				.filter(Objects::nonNull)
				.forEach(c -> c.removeIf(J2CC::isJ2ccAnnotation));
	}

	private static boolean isJ2ccAnnotation(AnnotationNode an) {
		return Type.getType(an.desc).getInternalName().startsWith("j2cc/");
	}

	private static void transformLoader(Context context, List<ClassNode> loaderClasses) {
		Predicate<AnnotationNode> matchDebugAnnot = it -> it.desc.equals(Type.getDescriptor(Debug.class));
		List<Pair<ClassNode, MethodNode>> methodsToNuke = new ArrayList<>();
		List<ClassNode> classesToNuke = new ArrayList<>();
		for (ClassNode loaderClass : loaderClasses) {
			if (!context.debug().isVerboseLoader() && loaderClass.invisibleAnnotations != null && loaderClass.invisibleAnnotations.stream().anyMatch(matchDebugAnnot)) {
				// class has @debug
				classesToNuke.add(loaderClass);
				continue; // we dont need to bother
			}
			if (loaderClass.invisibleAnnotations != null) loaderClass.invisibleAnnotations.removeIf(matchDebugAnnot);
			for (MethodNode method : loaderClass.methods) {
				if (!context.debug().isVerboseLoader() && method.invisibleAnnotations != null && method.invisibleAnnotations.stream().anyMatch(matchDebugAnnot)) {
					methodsToNuke.add(new Pair<>(loaderClass, method));
				}
				if (method.invisibleAnnotations != null) {
					method.invisibleAnnotations.removeIf(matchDebugAnnot);
				}
			}
			loaderClass.methods.removeAll(methodsToNuke.stream().map(Pair::getB).toList());
		}
		for (ClassNode loaderClass : loaderClasses) {
			for (MethodNode method : loaderClass.methods) {
				InsnList insns = method.instructions;
				for (AbstractInsnNode instruction : insns) {
					if (instruction instanceof MethodInsnNode min &&
							(
									methodsToNuke.stream().anyMatch(e -> e.getA().name.equals(min.owner) && e.getB().name.equals(min.name) && e.getB().desc.equals(min.desc))
									|| classesToNuke.stream().anyMatch(it -> it.name.equals(min.owner))
							)
					) {
						// method to be removed - remove call as well. pop any arguments, push appropriate null value
						InsnList replacement = new InsnList();
						Type[] argumentTypes = Type.getArgumentTypes(min.desc);
						Type ret = Type.getReturnType(min.desc);
						for (int i = argumentTypes.length - 1; i >= 0; i--) {
							Type argumentType = argumentTypes[i];
							// POP for size 1, POP2 for size 2
							replacement.add(new InsnNode(Opcodes.POP + argumentType.getSize() - 1));
						}
						int sort = ret.getSort();
						if (sort != Type.VOID) {
							replacement.add(new InsnNode(switch (sort) {
								case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.ICONST_0;
								case Type.FLOAT -> Opcodes.FCONST_0;
								case Type.LONG -> Opcodes.LCONST_0;
								case Type.DOUBLE -> Opcodes.DCONST_0;
								case Type.ARRAY, Type.OBJECT -> Opcodes.ACONST_NULL;
								default -> throw new IllegalStateException("Unexpected value: " + sort);
							}));
						}
						insns.insertBefore(min, replacement);
						insns.remove(min);
					}
				}
			}
		}
		loaderClasses.removeAll(classesToNuke);
		ObfuscationContext ocx = new ObfuscationContext(null, null);
		new OptimizerPass().obfuscate(ocx, context, loaderClasses.stream().map(it ->
				new ClassEntry(new Workspace.ClassInfo(context.workspace(), it, List.of()), "")).toList());
	}

	private static List<ClassNode> writeLoaderFiles(Remapper remap, Context context, OutputSink to) throws URISyntaxException, IOException {
		Path path = Path.of(Loader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path path1;
		FileSystem fileSystem = null;
		if (Files.isDirectory(path)) {
			// running in debug
			path1 = path.resolve("j2cc").resolve("internal");
		} else {
			fileSystem = FileSystems.newFileSystem(path);
			path1 = fileSystem.getPath("j2cc", "internal");
		}
		List<ClassNode> loaderClasses;
		try (Stream<Path> walk = Files.walk(path1)) {
			loaderClasses = new ArrayList<>(walk
					.filter(Files::isRegularFile)
					.map(path2 -> {
						try (InputStream inputStream = Files.newInputStream(path2)) {
							ClassNode cn = new ClassNode();
							ClassReader reader = new ClassReader(inputStream.readAllBytes());
							reader.accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
							return cn;
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					})
					.toList());
		}
		Workspace loaderWorkspace = new Workspace(loaderClasses.toArray(ClassNode[]::new), Resolver.stdlibResolver());
		transformLoader(context, loaderClasses);
		for (ClassNode loaderClass : loaderClasses) {
			ClassWriter cw = new SmartClassWriter(ClassWriter.COMPUTE_FRAMES, loaderWorkspace, remap);
			loaderClass.accept(new ClassRemapper(cw, remap));
			byte[] byteArray = cw.toByteArray();
			@Cleanup OutputStream outputStream = to.openFile(remap.map(loaderClass.name) + ".class");
			outputStream.write(byteArray);
		}
		if (fileSystem != null) fileSystem.close();
		return loaderClasses;
	}

	public record ClassEntry(Workspace.ClassInfo info, String namePrefix) {
	}
}