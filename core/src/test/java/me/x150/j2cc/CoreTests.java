package me.x150.j2cc;

import com.google.gson.Gson;
import lombok.SneakyThrows;
import me.darknet.assembler.compile.JavaClassRepresentation;
import me.darknet.assembler.compile.JvmCompiler;
import me.darknet.assembler.compile.JvmCompilerOptions;
import me.darknet.assembler.helper.Processor;
import me.darknet.assembler.parser.BytecodeFormat;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.compilerExec.ZigCompiler;
import me.x150.j2cc.conf.Configuration;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.input.DirectoryInputProvider;
import me.x150.j2cc.input.InputProvider;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.output.DirectoryOutputSink;
import me.x150.j2cc.output.OutputSink;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.tree.resolver.Resolver;
import me.x150.j2cc.tree.resolver.UnionResolver;
import me.x150.j2cc.util.Util;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.opentest4j.AssertionFailedError;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Execution(value = ExecutionMode.CONCURRENT, reason = "Stateless tests")
public class CoreTests {
	private static final Gson GSON = new Gson();
	private static final JavaCompiler JAVA_COMPILER = ToolProvider.getSystemJavaCompiler();
	private static final Pattern EXT_CLASS_NAME = Pattern.compile("(public)? +class +(.+?) *\\{");
	private static final Path JAVA_EXECUTABLE;

	private static final ZigCompiler ZIG_COMPILER = ZigCompiler.locate(Path.of("zig-compiler"));
	private static final Pattern ESCAPE_SEQUENCE = Pattern.compile("^\\\\(\\\\|u[0-9a-fA-F]{4}|\\?)");

	@TempDir(cleanup = CleanupMode.NEVER)
	public static Path tempDir;

	static {
		try {
			String javaHome = System.getProperty("java.home");
			if (javaHome == null) throw new IllegalStateException("java.home not set; got null when reading property");
			Path javaBin = Path.of(javaHome).resolve("bin");
			if (!Files.exists(javaBin))
				throw new IllegalStateException("java.home corrupted; " + javaBin + " does not exist");
			Optional<Path> first;
			try (Stream<Path> list = Files.list(javaBin)) {
				first = list.filter(f -> f.getFileName().toString().matches("java(\\.exe)?")).findFirst();
			}
			if (first.isEmpty())
				throw new IllegalStateException("java.home corrupted; could not find java(.exe) in " + javaBin);
			JAVA_EXECUTABLE = first.get();
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private static void appendQuoted(StringBuilder to, StringBuilder from) {
		if (!from.isEmpty()) {
			to.append(Pattern.quote(from.toString()));
			from.delete(0, from.length());
		}
	}

	private static Pattern buildPatternForExpectedOutput(String expectedS) {
		expectedS = expectedS.replace("\r\n", "\n");
		Matcher escapeMatcher = ESCAPE_SEQUENCE.matcher(expectedS);
		StringBuilder pattern = new StringBuilder();
		StringBuilder currentQuoteSequence = new StringBuilder();
		char[] chars = expectedS.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			char currentC = chars[i];
			escapeMatcher.reset();
			escapeMatcher.region(i, expectedS.length());
			if (escapeMatcher.find()) {
				appendQuoted(pattern, currentQuoteSequence);
				String matched = escapeMatcher.group();
				String replacement;
				if (matched.equals("\\\\")) replacement = "\\";
				else if (matched.equals("\\?")) replacement = ".*?";
				else {
					int d = Integer.parseInt(escapeMatcher.group(1).substring(1), 16);
					replacement = String.valueOf((char) d);
				}
				pattern.append(replacement);
				i += matched.length() - 1;
			} else {
				if (currentC == '\n') {
					currentQuoteSequence.append(System.lineSeparator());
				} else {
					currentQuoteSequence.append(currentC);
				}
			}
		}
		appendQuoted(pattern, currentQuoteSequence);
		return Pattern.compile(pattern.toString());
	}

	@TestFactory
	public Iterable<DynamicTest> getTests() throws URISyntaxException, IOException {
		URL testsDir = getClass().getClassLoader().getResource("tests");
		Objects.requireNonNull(testsDir);
		Path p = Path.of(testsDir.toURI());
		// since this stream is consumed by the caller, we can't just close the source
		// sorry idea
		//noinspection resource
		try (Stream<DynamicTest> dynamicTestStream = Files.walk(p)
				.filter(it -> it.getFileName().toString().endsWith(".test.json"))
				.map(this::createTestFor)) {
			return dynamicTestStream.toList();
		}
	}

	private DynamicTest createTestFor(Path testSpec) {
		try {
			String baseName = testSpec.toString();
			baseName = baseName.substring(0, baseName.length() - ".test.json".length()); // strip ".test.json"
			Path basePath = testSpec.getParent();
			Path expectedOutput = basePath.resolve(baseName + ".output");
			TestMetadata meta;

			try (BufferedReader is = Files.newBufferedReader(testSpec)) {
				meta = GSON.fromJson(is, TestMetadata.class);
			}

			String theShit = Files.readString(expectedOutput);
			Pattern finalExpectedOutputS = buildPatternForExpectedOutput(theShit);
			String finalBaseName = baseName;
			return DynamicTest.dynamicTest(meta.name,
					() -> runTest(Files.createTempDirectory(tempDir, "test"), meta, finalExpectedOutputS, theShit, basePath, finalBaseName));
		} catch (Throwable t) {
			System.out.printf("Failed to create test for %s%n", testSpec);
			throw new RuntimeException(t);
		}
	}

	protected byte[] compileJavaSource(Path tempDir, String outerClassName, @Language("JAVA") String source) throws IOException {
		Path compiledDir = tempDir.resolve("javacOutput");
		Path sourceFile = tempDir.resolve("javacSource").resolve(outerClassName + ".java");
		Files.createDirectories(sourceFile.getParent());
		Files.createDirectories(compiledDir);

		try (OutputStream outputStream = Files.newOutputStream(sourceFile)) {
			outputStream.write(source.getBytes(StandardCharsets.UTF_8));
		}
		StandardJavaFileManager standardFileManager = JAVA_COMPILER.getStandardFileManager(null, null, null);
		Iterable<? extends JavaFileObject> javaFileObjectsFromPaths = standardFileManager.getJavaFileObjectsFromPaths(
				List.of(sourceFile)
		);
		System.out.printf("Compiling %s -> %s%n", sourceFile.toAbsolutePath(), compiledDir.toAbsolutePath());
		List<String> compilerArgs = new ArrayList<>();
		compilerArgs.addAll(List.of("-d", compiledDir.toAbsolutePath().toString()));
		compilerArgs.addAll(List.of("-cp", "annotations/target/classes"));
		compilerArgs.add("-proc:none"); // don't bok the lom
		boolean aBoolean = JAVA_COMPILER.getTask(null, standardFileManager, null, compilerArgs, null,
				javaFileObjectsFromPaths).call();
		if (!aBoolean) {
			return null;
		}
		try (Stream<Path> p = Files.walk(compiledDir)) {
			Stream<Path> pathStream = p.filter(Files::isRegularFile);
			List<Path> list = pathStream.toList();
			if (list.size() != 1)
				throw new IllegalStateException("Compilation resulted in " + list.size() + " class files, not 1");
			Path f = list.getFirst();
			return Files.readAllBytes(f);
		}
	}

	@SneakyThrows
	private void runTest(Path testDir, TestMetadata meta, Pattern expectedOutput, String expectedOutputS, Path basePath, String baseName) {

		AtomicReference<byte[]> sourceClass = new AtomicReference<>();
		if (meta.type == TestType.ASSEMBLY) {
			Path path = basePath.resolve(baseName + ".jasm");
			String src = Files.readString(path);
			JvmCompiler jvmCompiler = new JvmCompiler();
			JvmCompilerOptions opts = new JvmCompilerOptions();
			opts.version(21);


			Processor.processSource(src, path.toAbsolutePath().normalize().toString(),
					astElements -> jvmCompiler.compile(astElements, opts).ifErr((javaCompileResult, errors) -> {
						System.err.println("Couldn't compile source file:");
						errors.forEach(System.err::println);
					}).ifOk(javaCompileResult -> {
						JavaClassRepresentation repr = javaCompileResult.representation();
						sourceClass.set(Objects.requireNonNull(repr).classFile());
					}), errors -> {
						System.err.println("Failed to parse source file:");
						errors.forEach(System.err::println);
					}, BytecodeFormat.JVM);
		} else {
			@Language("JAVA") String sourceString = Files.readString(basePath.resolve(baseName + ".java"), StandardCharsets.UTF_8);
			Matcher matcher = EXT_CLASS_NAME.matcher(sourceString);
			if (!matcher.find()) throw new IllegalStateException("Cannot find outer class declaration");
			String className = matcher.group(2);
			sourceClass.set(compileJavaSource(testDir, className, sourceString));
		}

		byte[] cf = sourceClass.get();
		if (cf == null) {
			throw new IllegalStateException("Could not compile");
		}

		ClassReader cr = new ClassReader(cf);
		ClassNode cn = new ClassNode();
		cr.accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		String name = cn.name;
		String strippedName = name.substring(name.lastIndexOf('/') + 1);
		Path sourceDir = testDir.resolve("transpilerSource");
		Path outPath = testDir.resolve("transpilerOutput");
		Files.createDirectories(sourceDir);
		Files.createDirectories(outPath);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(cw);

		Files.write(sourceDir.resolve(strippedName + ".class"), cw.toByteArray());

		InputProvider prov = new DirectoryInputProvider(sourceDir);
		OutputSink sink = new DirectoryOutputSink(outPath);
		Workspace wsp = new Workspace(new UnionResolver(
				prov.toResolver(),
				Resolver.stdlibResolver()
		));
		Context context = Context.builder()
				.workspace(wsp)
				.specialTempPath(testDir)
				.utilPath(Path.of("util").toAbsolutePath().normalize())
				.keepTemp(true)
				.input(prov)
				.output(sink)
				.pJobs(1)
				.skipOptimization(true)
				.debug(new Configuration.DebugSettings())
				.obfuscationSettings(new Context.ObfuscationSettings(new Configuration.RenamerSettings(), false, new Configuration.AntiHookSettings()))
				.compiler(new DefaultCompiler())
				.customTarget(Util.getTargetTripleForCurrentOs())
				.extraClassFilters(List.of())
				.extraMemberFilters(List.of())
				.build();
		// TODO 10 Sept. 2024 12:06: configure obfuscation properly
		J2CC.doObfuscate(context, ZIG_COMPILER, new Obfuscator());

		Path runDir = testDir.resolve("run");
		Files.createDirectories(runDir);
		Path processOutput = runDir.resolve("processOutput.txt");

		ProcessBuilder pb = new ProcessBuilder();
		List<String> call = new ArrayList<>();
		call.add(JAVA_EXECUTABLE.toAbsolutePath().toString());
		call.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+ShowHiddenFrames"));
		if (meta.jvmArgs != null) call.addAll(List.of(meta.jvmArgs));
		call.addAll(List.of("-cp", outPath.toAbsolutePath().toString()));
		call.add(name.replace('/', '.'));
		if (meta.programArgs != null) call.addAll(List.of(meta.programArgs));
		pb.command(call);
		pb.directory(runDir.toFile());
		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.to(processOutput.toFile()));
		Process start = pb.start();
		int i = start.waitFor();
		if (i != meta.expectedProgramExit) {
			String s = Files.readString(processOutput);
			System.err.println(s);
			Assertions.fail("Process exited with code " + i + ", not " + meta.expectedProgramExit);
		}
		String output;
		try (InputStream inputStream = Files.newInputStream(processOutput)) {
			output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		while (true) {
			int currentLineEnd = output.indexOf('\n');
			String currentLine = output.substring(0, currentLineEnd);
			if (currentLine.matches("^Picked up JAVA_TOOL_OPTIONS: .*$")) {
				output = output.substring(currentLineEnd+1);
			} else break;
		}
		boolean isVAlid = expectedOutput.matcher(output).matches();
		if (!isVAlid) throw new AssertionFailedError("Failed to match pattern vs output:\nPattern:\n" + expectedOutputS + "\nOutput:\n" + output + "\nCompiled pattern:\n" + expectedOutput, expectedOutputS, output);
	}

	@SuppressWarnings("unused")
	enum TestType {
		ASSEMBLY, SOURCE
	}

	record TestMetadata(TestType type, String name, int expectedProgramExit, String[] programArgs, String[] jvmArgs) {
	}
}
