package me.x150.j2cc;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlWriter;
import j2cc.Exclude;
import j2cc.Nativeify;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.compilerExec.GccCompiler;
import me.x150.j2cc.compilerExec.ZigCompiler;
import me.x150.j2cc.conf.Configuration;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.conf.javaconf.ConfigurationManager;
import me.x150.j2cc.input.DirectoryInputProvider;
import me.x150.j2cc.input.InputProvider;
import me.x150.j2cc.input.JarInputProvider;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.output.DirectoryOutputSink;
import me.x150.j2cc.output.JarOutputSink;
import me.x150.j2cc.output.OutputSink;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.tree.resolver.DirectoryResolver;
import me.x150.j2cc.tree.resolver.FsResolver;
import me.x150.j2cc.tree.resolver.Resolver;
import me.x150.j2cc.tree.resolver.UnionResolver;
import me.x150.j2cc.util.ClassFilter;
import me.x150.j2cc.util.MappingSet;
import me.x150.j2cc.util.MemberFilter;
import me.x150.j2cc.util.Util;
import org.jetbrains.annotations.Contract;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

//@Nativeify
@Log4j2
public class Entry {
	private static final String DEFAULT_ZIG_HOME = "zig-compiler";
	private static final String DEFAULT_UTIL = "util";

	@Contract("_ -> fail")
	private static void fail(String msg) {
		log.error(msg);
		System.exit(1);
		throw new RuntimeException("exit(1) continued execution");
	}

	private static void logo() throws IOException {
		try (InputStream resourceAsStream = Entry.class.getClassLoader().getResourceAsStream("logo.txt")) {
			Objects.requireNonNull(resourceAsStream).transferTo(System.err);
		}
	}

	public static boolean globMatchesRoughly(Path p, PathMatcher matcher) {
		int names = p.getNameCount();
		for (int i = names - 1; i >= 0; i--) {
			Path n = p.subpath(i, names);
			if (matcher.matches(n)) return true;
		}
		return false;
	}

//	@Nativeify

	public static void main(String[] args) {
		try {
			logo();
			if (args.length == 0) {
				log.error("Syntax: j2cc <command>");
				System.exit(1);
			}
			String cmd = args[0];
			switch (cmd) {
				case "obfuscate" -> obf(args);
				case "remap" -> remap(args);
				case "getSchema" -> getSchema();
				default ->
						throw new IllegalArgumentException("Unknown command " + cmd + ". Valid commands: obfuscate, remap, getSchema");
			}
		} catch (Throwable t) {
			log.error("", t);
		}
	}

	private static void getSchema() {
		Obfuscator obf = new Obfuscator();

		Configuration configuration = new Configuration(obf);

		System.out.println("""
				# AUTOGENERATED EXAMPLE CONFIGURATION
				# Please go through ALL the config keys, make sure they make sense, before deciding it doesn't work
				# This is an example configuration! It is intended to document what is possible, not what makes sense!
				# Each array contains at least one element with all the keys filled, to document what can go there.
				# If you don't need the array element, remove it!
				# Also recommended: Go through the toml spec @ https://toml.io/
				""");
		CommentedConfig exampleConfiguration = new ConfigurationManager(configuration).getExampleConfiguration();
		TomlWriter tw = TomlFormat.instance().createWriter();
		tw.setIndent("\t");
		tw.write(exampleConfiguration, System.out);
	}

	private static void remap(String[] args) throws Exception {
		if (args.length < 4) {
			fail("Syntax: j2cc remap <path to jar> <path to mappings file> <path to output jar>");
		}
		Path toJar = Path.of(args[1]);
		Path toMap = Path.of(args[2]);
		Path outJar = Path.of(args[3]);

		MappingSet ms;
		try (InputStream inputStream = Files.newInputStream(toMap)) {
			ms = MappingSet.readFrom(new DataInputStream(inputStream));
		}
		try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(toJar));
			 ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outJar))) {
			ZipEntry entry;
			byte[] head = new byte[4];
			ByteBuffer buf = ByteBuffer.wrap(head);
			while ((entry = zis.getNextEntry()) != null) {
				if (entry.isDirectory()) continue;
				if (entry.getName().equals("META-INF/MANIFEST.MF")) {
					Manifest mf = new Manifest(zis);

					String mainClass = (String) mf.getMainAttributes().get(Attributes.Name.MAIN_CLASS);
					if (mainClass != null) {
						String mappedName = ms.map(mainClass.replace('.', '/'));
						mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mappedName.replace('/', '.'));
					}

					zos.putNextEntry(entry);
					mf.write(zos);
					zos.closeEntry();
					continue;
				}
				buf.clear();
				int read = zis.read(head);
				if (read == 4 && buf.getInt() == 0xCAFEBABE) {
					ClassReader r = new ClassReader(zis.readAllBytes(), -4, 0);
					ClassNode node = new ClassNode();
					r.accept(node, ClassReader.EXPAND_FRAMES);
					log.debug("Class: {} ({})", node.name, entry.getName());
					ClassWriter cw = new ClassWriter(0);
					ClassRemapper cr = new ClassRemapper(cw, ms);
					node.accept(cr);
					byte[] byteArray = cw.toByteArray();
					zos.putNextEntry(new ZipEntry(ms.map(node.name) + ".class"));
					zos.write(byteArray);
				} else {
					zos.putNextEntry(entry);
					zos.write(head, 0, read);
					if (read == 4) {
						zis.transferTo(zos);
					}
				}
				zos.closeEntry();
			}
		}
	}

	@Nativeify
	private static void obf(String[] args) throws Exception {
		if (args.length < 2) {
			fail("Syntax: j2cc obfuscate <path to config.toml>");
		}


		Path confPath = Path.of(args[1]);
		if (!Files.isReadable(confPath)) {
			fail("Cannot read configuration at '" + confPath.toAbsolutePath().normalize() + "'");
		}

		Obfuscator obf = new Obfuscator();

		Configuration configuration = new Configuration(obf);


		try (FileConfig fileConfig = FileConfig.of(confPath, TomlFormat.instance())) {
			fileConfig.load();
			ConfigurationManager cfgm = new ConfigurationManager(configuration);
			cfgm.fromLbConfig1(fileConfig);
			Set<String> ss = new HashSet<>();
			cfgm.validatePathsFilled(ss);
			if (!ss.isEmpty()) {
				fail("There are missing configuration items, make sure the following items are filled:\n" + ss.stream().map(it -> "  - " + it).collect(Collectors.joining("\n")));
			}
			obf.ensureConfValid();
		}

//		Configuration configuration = ConfigDeserializer.deserialize(confPath);

		Path utilPath = Optional
				.ofNullable(configuration.getPaths().getUtilPath())
				.map(Path::of)
				.orElse(Path.of(DEFAULT_UTIL));
		Path zigHome = Optional.ofNullable(configuration.getPaths().getZigPath())
				.map(Path::of)
				.orElse(Path.of(DEFAULT_ZIG_HOME));

		CompilerType compType = configuration.getCompilerType();

		Path resolvedUtilPath = utilPath, resolvedZigPath = zigHome;
		if (!utilPath.isAbsolute())
			resolvedUtilPath = Objects.requireNonNullElse(Util.findInSearch(pathStream -> pathStream.map(it -> it.resolve(utilPath)).filter(Files::isDirectory)), utilPath);
		if (!zigHome.isAbsolute())
			resolvedZigPath = Objects.requireNonNullElse(Util.findInSearch(pathStream -> pathStream.map(it -> it.resolve(zigHome)).filter(Files::isDirectory)), zigHome);

		if (!Files.isDirectory(resolvedUtilPath)) {
			fail("Cannot find util path, or util path isn't directory (" + resolvedUtilPath.toAbsolutePath().normalize() + ")");
		}

		if (!Files.exists(resolvedZigPath)) {
			fail("Cannot find compiler (" + resolvedZigPath.toAbsolutePath().normalize() + ")");
		}

		Path input = Path.of(configuration.getPaths().getInputPath());
		Path output = Path.of(configuration.getPaths().getOutputPath());
		if (!Files.exists(input)) {
			fail("Input file " + input + " does not exist");
		}

		InputProvider provider = Files.isDirectory(input) ? new DirectoryInputProvider(input) : new JarInputProvider(FileSystems.newFileSystem(input));
		boolean outShouldBeDir =
				Files.isDirectory(output) // output directory already exists and is a directory
						|| !output.getFileName().toString().contains("."); // assuming any extension means we want a zip
		OutputSink outputSink = outShouldBeDir ? new DirectoryOutputSink(output) : new JarOutputSink(output);
		if (outShouldBeDir) {
			log.info("Assuming output is supposed to be a folder");
		}

		List<Resolver> jarResolvers = new ArrayList<>();

		jarResolvers.add(provider.toResolver());

		for (String library1 : configuration.getPaths().getLibraries()) {
			Path library = Path.of(library1);
			if (!Files.exists(library)) {
				log.error("Library {} does not exist", library.getFileName());
				System.exit(1);
			}
			log.debug("Library: {}", library);
			jarResolvers.add(Files.isDirectory(library) ? new DirectoryResolver(library) : new FsResolver(FileSystems.newFileSystem(library)));
		}

		for (Configuration.LibraryMatcher libraryMatcher : configuration.getPaths().getLibraryMatchers()) {
			String base = libraryMatcher.getBasePath();
			String match = libraryMatcher.getGlobPattern();
			Path bp = Path.of(base).toAbsolutePath().normalize();
			if (!Files.isDirectory(bp)) {
				log.error("{} is not a directory", bp);
				System.exit(1);
			}
			PathMatcher matcher = bp.getFileSystem().getPathMatcher("glob:" + match);
			try (Stream<Path> walk = Files.walk(bp, FileVisitOption.FOLLOW_LINKS)) {
				List<Path> list = walk.filter(path -> globMatchesRoughly(path, matcher)).toList();
				for (Path library : list) {
					log.debug("Library (via glob {} {}): {}", bp, match, library);
					jarResolvers.add(Files.isDirectory(library) ? new DirectoryResolver(library) : new FsResolver(FileSystems.newFileSystem(library)));
				}
			}
		}

		List<DefaultCompiler.Target> targets = new ArrayList<>();

		for (String target : configuration.getTargets()) {
			String[] split = target.split("-", 3);
			if (split.length != 3)
				throw new IllegalArgumentException("Invalid custom target " + target + ": Should follow the format of <arch>-<platform>-<abi>. See zig manual");
			targets.add(new DefaultCompiler.Target(target, split[0] + "-" + split[1]));
		}

		jarResolvers.add(Resolver.stdlibResolver());

		Workspace wsp = new Workspace(new UnionResolver(jarResolvers.toArray(Resolver[]::new)));

		List<String> extraZigs = List.of(configuration.getZigArgs());

		Context.ContextBuilder contextBuilder = Context.builder();
		contextBuilder.workspace(wsp);
		contextBuilder.utilPath(resolvedUtilPath);
		contextBuilder.keepTemp(configuration.isKeepTempDir());
		contextBuilder.input(provider);
		contextBuilder.output(outputSink);
		contextBuilder.pJobs(Math.toIntExact(configuration.getParallelJobs()));
		contextBuilder.customTargets(targets);
		contextBuilder.zigArgs(extraZigs);
		contextBuilder.skipOptimization(configuration.isSkipOptimizations());
		contextBuilder.compiler(new DefaultCompiler());
//		contextBuilder.license(license);
		contextBuilder.compileAllMethods(configuration.isCompileAllMethods());
		contextBuilder.debug(configuration.getDebugSettings());
		contextBuilder.obfuscationSettings(new Context.ObfuscationSettings(
				configuration.getRenamerSettings(),
				configuration.isVagueExceptions(),
				configuration.getAntiHook()
		));
		if (configuration.getPaths().getTempPath() != null) {
			Path specialTempPath = Path.of(configuration.getPaths().getTempPath());
			if (!Files.exists(specialTempPath)) Files.createDirectories(specialTempPath);
			if (!Files.isDirectory(specialTempPath))
				throw new IllegalArgumentException("Temp path " + specialTempPath + " is not a directory");
			contextBuilder.specialTempPath(specialTempPath.toAbsolutePath());
		}

		List<Path> pcc = new ArrayList<>();
		for (String s : configuration.getPostCompile()) {
			Path path = Path.of(s);
			if (!Files.isRegularFile(path) || !Files.isExecutable(path))
				throw new IllegalArgumentException("Post-compile hook " + path + " isn't executable");
			pcc.add(path);
		}
		contextBuilder.postCompileCommands(pcc.toArray(Path[]::new));

		contextBuilder.extraClassFilters(Arrays.stream(configuration.annotOverridesC).map(it -> new Context.AnnotationOverrides<>(
				Arrays.stream(it.classes).map(ClassFilter::fromString).toArray(ClassFilter[]::new),
				Arrays.stream(it.excludeFrom).map(vv -> Exclude.From.valueOf(vv.toUpperCase())).toArray(Exclude.From[]::new)
		)).toList());

		contextBuilder.extraMemberFilters(Arrays.stream(configuration.annotOverridesM).map(it -> new Context.AnnotationOverrides<>(
				Arrays.stream(it.members).map(MemberFilter::fromFilter).toArray(MemberFilter[]::new),
				Arrays.stream(it.excludeFrom).map(vv -> Exclude.From.valueOf(vv.toUpperCase())).toArray(Exclude.From[]::new)
		)).toList());

		contextBuilder.classesToInclude(Arrays.stream(configuration.getCompileClasses()).map(ClassFilter::fromString).toList());
		contextBuilder.methodsToInclude(Arrays.stream(configuration.getCompileMethods()).map(MemberFilter::fromFilter).toList());

		Context ctx = contextBuilder.build();
		log.debug("Context: {}", ctx);
		J2CC.doObfuscate(ctx, switch (compType) {
			case GCC -> new GccCompiler(resolvedZigPath);
			case ZIG -> ZigCompiler.locate(resolvedZigPath);
		}, obf);
	}


	public enum CompilerType {
		ZIG, GCC
	}
}
