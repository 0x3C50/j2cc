package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.IincInsnNode;

import java.lang.reflect.Modifier;

public class IincInsnNodeHandler implements InsnHandler<IincInsnNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<IincInsnNode> compilerContext) {
		IincInsnNode insn = compilerContext.instruction();
		Method method = compilerContext.compileTo();
		int baseArgSizes = (Type.getArgumentsAndReturnSizes(compilerContext.methodNode().desc) >> 2) - 1;
		int nrP = baseArgSizes + (Modifier.isStatic(compilerContext.methodNode().access) ? 0 : 1);
		boolean isParam = insn.var < nrP;
		if (!isParam) {
			method.addStatement("locals[$l].i = locals[$l].i + $l", insn.var, insn.var, insn.incr);
		} else {
			method.addStatement("param$l = param$l + $l", insn.var, insn.var, insn.incr);
		}
	}
}
