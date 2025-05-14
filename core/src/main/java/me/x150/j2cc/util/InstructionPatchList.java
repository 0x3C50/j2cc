package me.x150.j2cc.util;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;

import java.util.HashMap;
import java.util.Map;

public class InstructionPatchList {
	private final Map<AbstractInsnNode, AbstractInsnNode> clonedInsns;
	private final Map<AbstractInsnNode, AbstractInsnNode> inverseCloned;
	private final InsnList clonedIl;
	private final InsnList originalList;

	public InstructionPatchList(InsnList originalList) {
		Map<LabelNode, LabelNode> labelMap = Util.cloneLabels(originalList);
		clonedInsns = new HashMap<>();
		clonedIl = new InsnList();
		inverseCloned = new HashMap<>();
		this.originalList = originalList;
		for (AbstractInsnNode abstractInsnNode : originalList) {
			AbstractInsnNode cloned = abstractInsnNode.clone(labelMap);
			clonedInsns.put(abstractInsnNode, cloned);
			inverseCloned.put(cloned, abstractInsnNode);
			clonedIl.add(cloned);
		}
	}


	public void insertBefore(AbstractInsnNode insn, AbstractInsnNode toInsert) {
		clonedIl.insertBefore(clonedInsns.getOrDefault(insn, insn), toInsert);
	}


	public void insertBefore(AbstractInsnNode insn, InsnList toInsert) {
		clonedIl.insertBefore(clonedInsns.getOrDefault(insn, insn), toInsert);
	}


	public AbstractInsnNode get(int index) {
		AbstractInsnNode cl = clonedIl.get(index);
		return inverseCloned.getOrDefault(cl, cl);
	}


	public int size() {
		return clonedIl.size();
	}


	public AbstractInsnNode getFirst() {
		AbstractInsnNode first = clonedIl.getFirst();
		return inverseCloned.getOrDefault(first, first);
	}


	public AbstractInsnNode getLast() {
		AbstractInsnNode first = clonedIl.getLast();
		return inverseCloned.getOrDefault(first, first);
	}


	public boolean contains(AbstractInsnNode insnNode) {
		return clonedIl.contains(clonedInsns.getOrDefault(insnNode, insnNode));
	}


	public int indexOf(AbstractInsnNode insnNode) {
		return clonedIl.indexOf(clonedInsns.getOrDefault(insnNode, insnNode));
	}


	public void set(AbstractInsnNode oldInsnNode, AbstractInsnNode newInsnNode) {
		clonedIl.set(clonedInsns.getOrDefault(oldInsnNode, oldInsnNode), newInsnNode);
	}

	public void set(AbstractInsnNode insn, InsnList replacement) {
		AbstractInsnNode d = clonedInsns.getOrDefault(insn, insn);
		clonedIl.insertBefore(d, replacement);
		clonedIl.remove(d);
	}


	public void add(InsnList insnList) {
		clonedIl.add(insnList);
	}


	public void add(AbstractInsnNode insnNode) {
		clonedIl.add(insnNode);
	}


	public void insert(InsnList insnList) {
		clonedIl.insert(insnList);
	}


	public void insert(AbstractInsnNode insnNode) {
		clonedIl.insert(insnNode);
	}


	public void insert(AbstractInsnNode oldInsnNode, InsnList insnList) {
		clonedIl.insert(clonedInsns.getOrDefault(oldInsnNode, oldInsnNode), insnList);
	}


	public void insert(AbstractInsnNode oldInsnNode, AbstractInsnNode insnNode) {
		clonedIl.insert(clonedInsns.getOrDefault(oldInsnNode, oldInsnNode), insnNode);
	}


	public void remove(AbstractInsnNode oldInsnNode) {
		clonedIl.remove(clonedInsns.getOrDefault(oldInsnNode, oldInsnNode));
	}


	public void clear() {
		clonedIl.clear();
		inverseCloned.clear();
		clonedInsns.clear();
	}

	public void apply() {
		this.originalList.clear();
		for (AbstractInsnNode abstractInsnNode : clonedIl) {
			// either we have a mapping from the cloned insn to the original,
			// or this insn was inserted manually after construction
			AbstractInsnNode originalNode = inverseCloned.getOrDefault(abstractInsnNode, abstractInsnNode);
			this.originalList.add(originalNode);
		}
	}
}
