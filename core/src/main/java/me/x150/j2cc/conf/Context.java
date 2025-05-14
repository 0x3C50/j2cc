package me.x150.j2cc.conf;

import j2cc.Exclude;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import me.x150.j2cc.compiler.CompilerEngine;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.input.InputProvider;
import me.x150.j2cc.output.OutputSink;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.util.ClassFilter;
import me.x150.j2cc.util.MemberFilter;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Builder
public record Context(
		@NonNull Workspace workspace,
		Path specialTempPath,
		@NotNull Path utilPath,
		boolean keepTemp,
		@NotNull InputProvider input,
		@NotNull OutputSink output,
		int pJobs,
		@Singular List<DefaultCompiler.Target> customTargets,
		@Singular List<String> zigArgs,
		@NotNull Configuration.DebugSettings debug,
		@NotNull ObfuscationSettings obfuscationSettings,
		@NotNull CompilerEngine compiler,
		boolean compileAllMethods,

		List<AnnotationOverrides<ClassFilter>> extraClassFilters,
		List<AnnotationOverrides<MemberFilter>> extraMemberFilters,
		List<ClassFilter> classesToInclude,
		List<MemberFilter> methodsToInclude,

		boolean skipOptimization,

		Path[] postCompileCommands
) {

	public record AnnotationOverrides<T>(T[] filters, Exclude.From[] extraExcludeTypes) {
	}

	public record ObfuscationSettings(Configuration.RenamerSettings renamerSettings,
									  boolean vagueExceptions,
									  Configuration.AntiHookSettings antiHook) {

	}

	public ExecutorService parallelExecutorForNThreads() {
		if (pJobs <= 0) return Executors.newCachedThreadPool(); // unlimited
		if (pJobs == 1) return Executors.newSingleThreadExecutor(); // optimize for one thread
		return Executors.newFixedThreadPool(pJobs);
	}
}
