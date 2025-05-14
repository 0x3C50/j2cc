package me.x150.j2cc;

import lombok.SneakyThrows;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.compilerExec.ZigCompiler;
import me.x150.j2cc.conf.ConfigDeserializer;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.confgen.Configuration;
import me.x150.j2cc.input.DirectoryInputProvider;
import me.x150.j2cc.input.InputProvider;
import me.x150.j2cc.output.DirectoryOutputSink;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.tree.resolver.FsResolver;
import me.x150.j2cc.tree.resolver.Resolver;
import me.x150.j2cc.tree.resolver.UnionResolver;
import me.x150.j2cc.util.ClassFilter;
import me.x150.j2cc.util.MemberFilter;
import me.x150.j2cc.util.Util;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.COMPILE,
		requiresDependencyResolution = ResolutionScope.RUNTIME)
@SuppressWarnings("unused") // used by maven
public class ObfuscateMojo extends AbstractMojo {
	private static final String DEFAULT_ZIG_HOME = "zig-compiler";
	private static final String DEFAULT_UTIL = "util";
	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	MavenSession session;
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	MavenProject project;
	@Parameter(property = "j2cc.configFile", required = true)
	String configFile;

	private static void fail(String s) throws MojoFailureException {
		throw new MojoFailureException(s);
	}

	@SneakyThrows
	@Override
	public void execute() {
		Path confPath = Path.of(configFile);
		if (!Files.isReadable(confPath)) {
			throw new MojoFailureException("Cannot read configuration at " + confPath + " (" + confPath.toAbsolutePath().normalize() + ")");
		}
		Configuration configuration = ConfigDeserializer.deserialize(confPath);

		Path utilPath = Optional
				.ofNullable(configuration.getUtilPath())
				.map(Path::of)
				.orElse(Path.of(DEFAULT_UTIL));
		Path zigHome = Optional.ofNullable(configuration.getZigPath())
				.map(Path::of)
				.orElse(Path.of(DEFAULT_ZIG_HOME));

		Entry.CompilerType compType = switch (configuration.getCompilerType()) {
			case "gcc" -> Entry.CompilerType.GCC;
			case "zig" -> Entry.CompilerType.ZIG;
			default -> throw new IllegalStateException("Unexpected value: " + configuration.getCompilerType());
		};

		Path resolvedUtilPath = utilPath, resolvedZigPath = zigHome;
		if (!utilPath.isAbsolute())
			resolvedUtilPath = Objects.requireNonNullElse(Util.findInSearch(pathStream -> pathStream.map(it -> it.resolve(utilPath)).filter(Files::isDirectory)), utilPath);
		if (!zigHome.isAbsolute())
			resolvedZigPath = Objects.requireNonNullElse(Util.findInSearch(pathStream -> pathStream.map(it -> it.resolve(zigHome)).filter(Files::isDirectory)), zigHome);

		if (!Files.isDirectory(resolvedUtilPath)) {
			throw new MojoFailureException("Cannot find util path, or util path isn't directory (" + resolvedUtilPath.toAbsolutePath().normalize() + ")");
		}

		if (!Files.isDirectory(resolvedZigPath)) {
			throw new MojoFailureException("Cannot find zig path, or zig path isn't directory (" + resolvedZigPath.toAbsolutePath().normalize() + ")");
		}

		List<DefaultCompiler.Target> targets = new ArrayList<>();

		for (String target : configuration.getTargets().getTarget()) {
			String[] split = target.split("-", 3);
			if (split.length != 3)
				throw new IllegalArgumentException("Invalid custom target " + target + ": Should follow the format of <arch>-<platform>-<abi>. See zig manual");
			targets.add(new DefaultCompiler.Target(target, split[0] + "-" + split[1]));
		}

		Artifact artifact = session.getCurrentProject().getArtifact();

		File file = artifact.getFile();
		Path path = file.toPath();
		InputProvider provider = new DirectoryInputProvider(path);
		List<Resolver> libResolvers = new ArrayList<>();
		libResolvers.add(provider.toResolver());
		//noinspection unchecked
		for (Artifact projectArtifact : (Set<Artifact>) project.getArtifacts()) {
			FileSystem nfs = FileSystems.newFileSystem(projectArtifact.getFile().toPath());
			libResolvers.add(new FsResolver(nfs));
		}
		libResolvers.add(Resolver.stdlibResolver());

		Workspace workspace = new Workspace(new UnionResolver(libResolvers.toArray(Resolver[]::new)));

		Context.ContextBuilder contextBuilder = Context.builder()
				.workspace(workspace)
				.utilPath(resolvedUtilPath)
				.keepTemp(Objects.requireNonNullElse(configuration.isKeepTempDir(), false))
				.input(provider)
				.output(new DirectoryOutputSink(path))
				.pJobs(Math.toIntExact(configuration.getParallelJobs()))
				.customTargets(targets)
				.zigArgs(configuration.getZigArgs())
				.compiler(new DefaultCompiler())
				.compileAllMethods(configuration.isCompileAllMethods())
				.debug(configuration.getDebugSettings())
				.obfuscationSettings(configuration.getObfuscationSettings());

		if (configuration.getCompileExclude() != null) {
			contextBuilder.classesToIgnore(configuration.getCompileExclude().getClazz().stream().map(ClassFilter::fromString).toList());
			contextBuilder.methodsToIgnore(configuration.getCompileInclude().getMethod().stream().map(MemberFilter::fromFilter).toList());
		}
		if (configuration.getCompileInclude() != null) {
			contextBuilder.classesToInclude(configuration.getCompileInclude().getClazz().stream().map(ClassFilter::fromString).toList());
			contextBuilder.methodsToInclude(configuration.getCompileInclude().getMethod().stream().map(MemberFilter::fromFilter).toList());
		}

		Context context = contextBuilder.build();
		J2CC.doObfuscate(context, ZigCompiler.locate(resolvedZigPath));

		workspace.close();
	}
}
