package me.x150.j2cc.obfuscator.optim;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import me.x150.j2cc.optimizer.*;
import me.x150.j2cc.util.InvalidCodeGuard;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collection;

@Log4j2
public class OptimizerPass extends ObfuscatorPass implements Opcodes {
	private static final Pass[] optimizerPasses = new Pass[]{
			new RemoveRedundantLabelsPass(),
			new ValueInliner(),
			new LocalPropagator(),
			new RemoveUnusedVars(),
			new PopInliner()
	};

	@SneakyThrows
	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		if (context.skipOptimization()) {
			log.info("Skipping all optimization steps");
			return;
		}
		InvalidCodeGuard i = new InvalidCodeGuard();
		long s = classes.stream().mapToLong(it -> it.info().node().methods.size()).sum();
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode node = aClass.info().node();
			if (Obfuscator.skip(context.workspace(), context, node)) continue;
			i.init(node);
			for (MethodNode method : node.methods) {
				if (Obfuscator.skip(context, node.name, method)) continue;
				long n = s--;
				if (n % 1000 == 0) log.debug("Optimizer: {} methods remaining", n);
				for (Pass optimizerPass : optimizerPasses) {
					optimizerPass.optimize(node, method, context.workspace());
				}
			}
			i.checkAndRestoreIfNeeded(node, true);
		}
	}

	@Override
	public boolean hasConfiguration() {
		return false;
	}

	@Override
	public boolean shouldRun() {
		return true;
	}

}
