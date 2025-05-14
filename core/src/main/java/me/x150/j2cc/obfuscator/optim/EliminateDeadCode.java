package me.x150.j2cc.obfuscator.optim;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.Collection;

@Log4j2
public class EliminateDeadCode extends ObfuscatorPass {
	@Override
	public boolean shouldRun() {
		return true;
	}

	@Override
	public boolean hasConfiguration() {
		return false;
	}

	@Override
	@SneakyThrows
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		Analyzer<BasicValue> bv = new Analyzer<>(new BasicInterpreter());
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode node = aClass.info().node();
			for (MethodNode method : node.methods) {
				Frame<BasicValue>[] analyze = bv.analyzeAndComputeMaxs(node.name, method);
				int offset = 0;
				for (AbstractInsnNode instruction : method.instructions) {
					int index = method.instructions.indexOf(instruction);
					if (instruction instanceof LabelNode) continue;
					if (analyze[index+offset] == null) {
						offset++;
						method.instructions.remove(instruction);
					}
				}
				if (offset > 0) log.info("[{}.{}] Removed {} dead instructions", node.name, method.name, offset);
			}
		}
	}
}
