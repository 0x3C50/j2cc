package me.x150.j2cc.optimizer;

import me.x150.j2cc.tree.Workspace;
import org.objectweb.asm.tree.*;

import java.util.stream.StreamSupport;

public class RemoveRedundantLabelsPass implements Pass {
	@Override
	public void optimize(ClassNode owner, MethodNode methodNode, Workspace wsp) {
		for (AbstractInsnNode instruction : methodNode.instructions) {
			if (instruction instanceof LabelNode ln) {
				if (StreamSupport.stream(methodNode.instructions.spliterator(), true)
						.noneMatch(s -> {
							if (s instanceof JumpInsnNode ji && ji.label == ln) return true;
							if (s instanceof TableSwitchInsnNode ts && (ts.dflt == ln || ts.labels.contains(ln)))
								return true;
							if (s instanceof LineNumberNode lnn && lnn.start == ln) return true;
							return s instanceof LookupSwitchInsnNode ls && (ls.dflt == ln || ls.labels.contains(ln));
						})
						&& (methodNode.tryCatchBlocks == null || methodNode.tryCatchBlocks.stream().noneMatch(s -> s.start == ln || s.end == ln || s.handler == ln))
						&& (methodNode.localVariables == null || methodNode.localVariables.stream().noneMatch(it -> it.start == ln || it.end == ln))) {
					// redundant label
					methodNode.instructions.remove(ln);
				}
			}
		}
	}
}
