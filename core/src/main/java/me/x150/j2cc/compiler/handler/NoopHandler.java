package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import org.objectweb.asm.tree.AbstractInsnNode;

public class NoopHandler implements InsnHandler<AbstractInsnNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<AbstractInsnNode> compilerContext) {

	}
}
