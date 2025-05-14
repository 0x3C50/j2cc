package me.x150.j2cc.obfuscator;

import j2cc.Exclude;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.etc.*;
import me.x150.j2cc.obfuscator.optim.*;
import me.x150.j2cc.obfuscator.refs.MhCallRef;
import me.x150.j2cc.obfuscator.strings.StringObfuscator;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.jar.Manifest;

@Log4j2
public class Obfuscator {
	public final ObfuscatorPass[] passes = {
			new RemoveDebugInfo(),
			new OptimizerPass(),
			new ExtractConstructors(),
			new Inliner(),
//			new MethodSplitter(),
//			new FlowFlatten(),
//			new MethodCombiner(),
//			new FlowExc(),
//			new FlowReorder(),
//			new ClassSplitter(),
//			new SplitTrapRanges(),
			new MhCallRef(),
//			new StringObfuscator(),
//			new ConstantObfuscator(),
			new PushOptimizer(),
			new BranchInliner(),
			new EliminateDeadCode()
//			new Real()
	};

	public static boolean skip(Workspace wsp, Context context, ClassNode cn) {
		String name = cn.name;
		while (name.contains("/")) {
			name = name.substring(0, name.lastIndexOf('/'));
			Workspace.ClassInfo pInf = wsp.get(name + "/package-info");
			if (pInf != null) {
				if (Util.shouldIgnore(context, pInf.node(), Exclude.From.OBFUSCATION)) return true;
			}
		}
		return skip(context, cn);
	}

	public static boolean skip(Context context, ClassNode cn) {
		return Util.shouldIgnore(context, cn, Exclude.From.OBFUSCATION);
	}

	public static boolean skip(Context context, String owner, MethodNode mn) {
		return Util.shouldIgnore(context, owner, mn, Exclude.From.OBFUSCATION);
	}

	public static boolean skip(Context context, String name, FieldNode fn) {
		return Util.shouldIgnore(context, name, fn, Exclude.From.OBFUSCATION);
	}

	public List<Workspace.ClassInfo> obfuscate(ObfuscationContext ctx, Context context, List<J2CC.ClassEntry> classes) {
		List<J2CC.ClassEntry> theEntries = new ArrayList<>(classes);
		List<Workspace.ClassInfo> ci = new ArrayList<>();
		for (ObfuscatorPass pass : passes) {
			if (pass.shouldRun()) {
				log.debug("Running pass {}", pass.getClass().getSimpleName());
				pass.obfuscate(ctx, context, theEntries);
				Collection<ClassNode> additionalClasses = pass.getAdditionalClasses();
				Collection<Workspace.ClassInfo> newClasses = context.workspace().registerAndMapExternal(additionalClasses);
				ci.addAll(newClasses);
				theEntries.addAll(newClasses.stream().map(it -> new J2CC.ClassEntry(it, "")).toList());
			}
		}
		return ci;
	}

	public void ensureConfValid() {
		for (ObfuscatorPass pass : passes) {
			Collection<Class<? extends ObfuscatorPass>> requires = pass.requires();
			if (!pass.shouldRun()) continue;
			if (requires == null) continue;
			for (Class<? extends ObfuscatorPass> require : requires) {
				ObfuscatorPass obfuscatorPass = Arrays.stream(passes).filter(f -> f.getClass() == require).findFirst().orElseThrow();
				if (!obfuscatorPass.shouldRun())
					throw new IllegalStateException("Transformer " + pass.getClass().getSimpleName() + " requires pass " + require.getSimpleName() + " to be enabled, but it isn't.");
			}
		}
	}

	public void transformManifest(Context ctx, Manifest mf) {
		for (ObfuscatorPass pass : passes) {
			if (pass.shouldRun()) {
				pass.modifyManifest(ctx, mf);
			}
		}
	}
}
