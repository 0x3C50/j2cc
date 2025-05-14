package me.x150.j2cc.optimizer;

import lombok.SneakyThrows;
import me.x150.j2cc.tree.Workspace;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashSet;
import java.util.Set;

public class RemoveUnusedVars implements Pass, Opcodes {
	private static Set<Integer> discoverUnused(MethodNode method) {
		Set<Integer> unusedLocals = new HashSet<>();
		Set<Integer> knownUsedLocals = new HashSet<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode vi) {
				int op = vi.getOpcode();
				if (!knownUsedLocals.contains(vi.var)) {
					if (op >= ISTORE && op <= ASTORE) {
						// not previously seen, add to potentially unused
						unusedLocals.add(vi.var);
					} else {
						// loads the var - this var is used!
						unusedLocals.remove(vi.var);
						knownUsedLocals.add(vi.var);
					}
				}
			} else if (instruction instanceof IincInsnNode ii) {
				if (!knownUsedLocals.contains(ii.var)) {
					unusedLocals.add(ii.var);
				}
			}
		}
		return unusedLocals;
	}

	@Override
	@SneakyThrows
	public void optimize(ClassNode owner, MethodNode method, Workspace wsp) {
		Set<Integer> unusedLocals = discoverUnused(method);
		Analyzer<BasicValue> bv = new Analyzer<>(new BasicInterpreter());
		Frame<BasicValue>[] frames = bv.analyzeAndComputeMaxs(owner.name, method);
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode vi) {
				if (unusedLocals.contains(vi.var)) {
					Frame<BasicValue> frame = frames[method.instructions.indexOf(instruction)];
					if (frame == null) continue; // unreachable code, preserve
					BasicValue top = frame.getStack(frame.getStackSize() - 1);
					int size = top.getType().getSize();
					method.instructions.set(instruction, new InsnNode(size == 2 ? Opcodes.POP2 : Opcodes.POP));
				}
			} else if (instruction instanceof IincInsnNode ii) {
				if (unusedLocals.contains(ii.var)) {
					method.instructions.set(instruction, new InsnNode(Opcodes.NOP));
				}
			}
		}
	}
}
