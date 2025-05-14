package me.x150.j2cc.tree;

import dev.xdark.jlinker.FieldModel;
import org.objectweb.asm.tree.FieldNode;

public record AsmFieldInfo(AsmClassInfo owner, FieldNode me) implements FieldModel {
	@Override
	public int accessFlags() {
		return me.access;
	}
}
