package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.exc.CompilationFailure;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;

public interface InsnHandler<T extends AbstractInsnNode> extends Opcodes {
	void compileInsn(Context context, CompilerContext<T> compilerContext) throws CompilationFailure;
}
