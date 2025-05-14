package me.x150.j2cc.obfuscator.optim;

import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Collection;

@Log4j2
public class PushOptimizer extends ObfuscatorPass {
	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		long optimized = 0;
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode no = aClass.info().node();
			if (Obfuscator.skip(context.workspace(), context, no)) continue;
			for (MethodNode method : no.methods) {
				if (Obfuscator.skip(context, no.name, method)) continue;
				boolean did;
				do {
					did = false;
					for (AbstractInsnNode abstractInsnNode : method.instructions) {
						AbstractInsnNode repl = switch (abstractInsnNode) {
							case IntInsnNode ii when ii.getOpcode() == Opcodes.SIPUSH && ii.operand >= Byte.MIN_VALUE && ii.operand <= Byte.MAX_VALUE ->
									new IntInsnNode(Opcodes.BIPUSH, ii.operand);
							case IntInsnNode ii when ii.getOpcode() == Opcodes.BIPUSH && ii.operand <= 5 && ii.operand >= -1 ->
									new InsnNode(Opcodes.ICONST_0 + ii.operand);
							case LdcInsnNode ld when ld.cst instanceof Integer i && i >= Short.MIN_VALUE && i <= Short.MAX_VALUE ->
									new IntInsnNode(Opcodes.SIPUSH, i);
							case LdcInsnNode ld when ld.cst instanceof Long l && (l == 0 || l == 1) ->
									new InsnNode((int) (Opcodes.LCONST_0 + l));
							case LdcInsnNode ld when ld.cst instanceof Float f && (f == 0 || f == 1) ->
									new InsnNode(Opcodes.FCONST_0 + f.intValue());
							case LdcInsnNode ld when ld.cst instanceof Double d && (d == 0 || d == 1) ->
									new InsnNode(Opcodes.DCONST_0 + d.intValue());
							default -> null;
						};
						if (repl != null) {
							did = true;
							method.instructions.set(abstractInsnNode, repl);
							optimized++;
						}
					}
				} while (did);
			}
		}
		log.info("Optimized {} int pushes", optimized);
	}

	@Override
	public boolean shouldRun() {
		return true;
	}

	@Override
	public boolean hasConfiguration() {
		return false;
	}
}
