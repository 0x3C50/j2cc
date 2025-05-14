package me.x150.j2cc.obfuscator.optim;

import me.x150.j2cc.J2CC;
import me.x150.j2cc.analysis.Block;
import me.x150.j2cc.analysis.BlockList;
import me.x150.j2cc.analysis.UniqueList;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import me.x150.j2cc.obfuscator.etc.RemoveDebugInfo;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.stream.Collectors;

public class BranchInliner extends ObfuscatorPass {
	@Override
	public Collection<Class<? extends ObfuscatorPass>> requires() {
		return List.of(RemoveDebugInfo.class);
	}

	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		for (J2CC.ClassEntry aClass : classes) {
			List<MethodNode> methods = aClass.info().node().methods;
			for (MethodNode method : methods) {
				boolean didAnything;
				do {
					didAnything = false;
					BlockList blocks = BlockList.createFrom(method);
					blocks.optimize();
					for (Block block : blocks.getBlocks()) {
						if (block.getNodes().getLast() instanceof JumpInsnNode ji && ji.getOpcode() == Opcodes.GOTO) {
							UniqueList<Block> jumpTargets = block.jumpsTo;
							assert jumpTargets.size() == 1;
							Block theTarget = jumpTargets.getFirst();
							if (theTarget == block) continue; // dont inline self
							long amountOfRealInsns = theTarget.getNodes().stream().filter(it -> it.getOpcode() != -1).count();
							if (amountOfRealInsns > 0 && amountOfRealInsns <= 2) {
								// inline
								System.out.println("inline in "+aClass.info().node().name+" "+method.name);
								inline(method, block, ji, theTarget);
								didAnything = true;
							}
						}
					}
				} while (didAnything);
			}
		}
	}

	public static Map<LabelNode, LabelNode> cloneLabels(List<AbstractInsnNode> insns) {
		HashMap<LabelNode, LabelNode> labelMap = new HashMap<>();
		for (AbstractInsnNode insn : insns) {
			if (insn.getType() == 8) {
				labelMap.put((LabelNode) insn, new LabelNode());
			}
		}
		return labelMap;
	}

	public static InsnList cloneInsnList(List<AbstractInsnNode> insns) {
		return cloneInsnList(cloneLabels(insns), insns);
	}

	public static InsnList cloneInsnList(Map<LabelNode, LabelNode> labelMap, List<AbstractInsnNode> insns) {
		InsnList clone = new InsnList();
		for (AbstractInsnNode insn : insns) {
			clone.add(insn.clone(labelMap));
		}

		return clone;
	}

	private void inline(MethodNode mth, Block jumpingBlock, JumpInsnNode replace, Block replaceWith) {
		Map<LabelNode, LabelNode> clonedLabels = cloneLabels(replaceWith.getNodes());
		LabelNode startOfTheClonedRange = new LabelNode();
		LabelNode endOfTheClonedRange = new LabelNode();
		Map<LabelNode, LabelNode> allLabelsAndCloned = new HashMap<>(clonedLabels);
		for (AbstractInsnNode instruction : mth.instructions) {
			if (instruction instanceof LabelNode ln && !allLabelsAndCloned.containsKey(ln)) {
				allLabelsAndCloned.put(ln, ln);
			}
		}
		InsnList clonedInsns = cloneInsnList(allLabelsAndCloned, replaceWith.getNodes());
		Map<LabelNode, String> labelNames = allLabelsAndCloned.values().stream()
				.collect(Collectors.toMap(labelNode -> labelNode, labelNode -> String.valueOf(labelNode.hashCode())));
		for (AbstractInsnNode clonedInsn : clonedInsns) {
			System.out.println(Util.stringifyInstruction(clonedInsn, labelNames));
		}
		// after these new instructions finish executing, we need to jump to the block that the inlined block would've flown into
		Block flowTargetBlock = replaceWith.flowsTo;
		if (flowTargetBlock != null) {
			LabelNode target;
			if (flowTargetBlock.nodes.getFirst() instanceof LabelNode ln) {
				target = ln;
			} else {
				// need to add a label here
				target = new LabelNode();
				mth.instructions.insertBefore(flowTargetBlock.nodes.getFirst(), target);
				flowTargetBlock.nodes.addFirst(target);
				allLabelsAndCloned.put(target, target);
			}
			clonedInsns.add(new JumpInsnNode(Opcodes.GOTO, target));
		}
		clonedInsns.insert(startOfTheClonedRange);
		clonedInsns.add(endOfTheClonedRange);
		// if the other block had any exception handlers, we need to copy them to the new range
		Set<TryCatchBlockNode> weNeedToCopy = new HashSet<>();
		for (AbstractInsnNode node : replaceWith.nodes) {
			int indexOfNode = mth.instructions.indexOf(node);
			for (TryCatchBlockNode tryCatchBlock : (mth.tryCatchBlocks)) {
				int thatNodeStart = mth.instructions.indexOf(tryCatchBlock.start);
				int thatNodeEnd = mth.instructions.indexOf(tryCatchBlock.end);
				if (indexOfNode >= thatNodeStart && indexOfNode <= thatNodeEnd) {
					// this handler covers this instruction. we need to copy it
					weNeedToCopy.add(tryCatchBlock);
				}
			}
		}
		for (TryCatchBlockNode tryCatchBlock : weNeedToCopy) {
			// if the label of this tcb is in our label map, it starts or ends in the middle of our cloned section
			// we can then just use our label mappings to find the equivalent label
			// if it starts or ends outside the cloned range, we need to still copy it, but fit it to the cloned range
			LabelNode newStart = clonedLabels.getOrDefault(tryCatchBlock.start, startOfTheClonedRange);
			LabelNode newEnd = clonedLabels.getOrDefault(tryCatchBlock.end, endOfTheClonedRange);
			mth.tryCatchBlocks.add(new TryCatchBlockNode(newStart, newEnd, tryCatchBlock.handler, tryCatchBlock.type));
		}
		mth.instructions.insert(replace, clonedInsns);
		mth.instructions.remove(replace);
	}
}
