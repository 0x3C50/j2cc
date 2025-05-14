package me.x150.j2cc.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class InstructionPatchListTest {

	@Test
	public void testFunctionality() {
		InsnList originalList = new InsnList();
		LdcInsnNode theActualLdc = new LdcInsnNode("hi");
		originalList.add(theActualLdc);

		InstructionPatchList ipl = new InstructionPatchList(originalList);
		AbstractInsnNode theLdc = ipl.get(0);
		assertSame(theActualLdc, theLdc);

		InsnNode ic = new InsnNode(Opcodes.ICONST_0);
		ipl.insertBefore(theActualLdc, ic);

		assertSame(ic, ipl.get(0));

		assertFalse(originalList.contains(ic));

		assertTrue(ipl.contains(theActualLdc));

		ipl.apply();

		assertTrue(originalList.contains(ic));
	}

}