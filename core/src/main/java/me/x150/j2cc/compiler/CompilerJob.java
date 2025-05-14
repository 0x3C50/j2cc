package me.x150.j2cc.compiler;

import j2cc.Nativeify;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.cppwriter.SourceBuilder;
import me.x150.j2cc.exc.CompilationFailure;
import me.x150.j2cc.tree.Remapper;
import me.x150.j2cc.util.StringCollector;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

//	@Nativeify
@Log4j2
public record CompilerJob(ClassNode owner, MethodNode[] toCompile, SourceBuilder header, SourceBuilder[] sources) {
	static final String nativeifyDesc = Type.getDescriptor(Nativeify.class);

	public static boolean hasNativeifyAnnotation(ClassNode cn, MethodNode f) {
		return Stream.of(f.invisibleAnnotations, f.visibleAnnotations, cn.invisibleAnnotations, cn.visibleAnnotations)
				.filter(Objects::nonNull).flatMap(Collection::stream)
				.anyMatch(e -> e.desc.equals(nativeifyDesc));
	}

	@Nativeify
	public List<CompletableFuture<DefaultCompiler.CompiledMethod>> compile(Remapper remapper, Context context, ExecutorService service, CacheSlotManager indyCache, StringCollector stringCollector) {
		List<CompletableFuture<DefaultCompiler.CompiledMethod>> methodJobs = new ArrayList<>();
		for (int i = 0; i < toCompile.length; i++) {
			MethodNode methodNode = toCompile[i];
			SourceBuilder source = sources[i];
			methodJobs.add(CompletableFuture.supplyAsync(() -> {
				String fmted = Util.formatMethod(owner.name, new org.objectweb.asm.commons.Method(methodNode.name, methodNode.desc));
				log.info("Compiling method {}...", fmted);
				String methodName = "j2cc_%s_%s_%d"
						.formatted(Util.turnIntoIdentifier(owner.name), methodNode.name.replaceAll("[^a-zA-Z0-9_\\-]", "_"),
								owner.methods.indexOf(methodNode));

				Method compile;
				try {
					compile = context.compiler().compile(context, owner, methodNode, source, methodName, remapper, indyCache, stringCollector);
					header.method(compile.getFlags(), compile.getReturns(), compile.name, compile.getParams());
				} catch (CompilationFailure cf) {
					// pass up
					throw cf;
				} catch (Throwable e) {
					log.error("FUCK: {}.{}{}", owner.name, methodNode.name, methodNode.desc);
					throw new RuntimeException(e);
				}
				methodNode.instructions.clear();
				methodNode.access = methodNode.access | Opcodes.ACC_NATIVE;
				if (methodNode.localVariables != null) methodNode.localVariables.clear();
				return new DefaultCompiler.CompiledMethod(owner.name, methodNode.name, methodNode.desc, methodName, compile);
			}, service));
		}

		Util.addLoaderInitToClinit(owner, remapper);
		return methodJobs;
	}

}
