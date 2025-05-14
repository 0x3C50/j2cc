package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Modifier;

public class VarInsnNodeHandler implements InsnHandler<VarInsnNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<VarInsnNode> compilerContext) {

		VarInsnNode insn = compilerContext.instruction();
		int stackNow = compilerContext.frames()[compilerContext.instructions().indexOf(insn)].getStackSize();
		Type[] argTypes = Type.getArgumentTypes(compilerContext.methodNode().desc);
		int baseArgSizes = (Type.getArgumentsAndReturnSizes(compilerContext.methodNode().desc) >> 2) - 1;
		Type[] mappedArgSlots = new Type[baseArgSizes];
		int idx = 0;
		for (Type argType : argTypes) {
			mappedArgSlots[idx] = argType;
			idx += argType.getSize();
		}
		int nrP = baseArgSizes + (Modifier.isStatic(compilerContext.methodNode().access) ? 0 : 1);
		boolean isParam = insn.var < nrP;
		switch (insn.getOpcode()) {
			case ILOAD, FLOAD, DLOAD, LLOAD, ALOAD -> {
				char c = "ijfdl".charAt(insn.getOpcode() - ILOAD);
				if (isParam) {
					compilerContext.compileTo().addStatement("stack[$l].$l = param$l", stackNow, c, insn.var);
				} else compilerContext.compileTo().addStatement("stack[$l].$l = locals[$l].$l", stackNow, c, insn.var, c);
			}
			case ISTORE, FSTORE, DSTORE, LSTORE, ASTORE -> {
				char c = "ijfdl".charAt(insn.getOpcode() - ISTORE);
				if (isParam) {
					int idx1 = insn.var - (Modifier.isStatic(compilerContext.methodNode().access) ? 0 : 1);

					Type argumentType = mappedArgSlots[idx1];
					String s = DefaultCompiler.jTypeMap.getOrDefault(argumentType, argumentType.getSort() == Type.ARRAY ? "jobjectArray" : "jobject");
					compilerContext.compileTo().addStatement("param$l = ($l) stack[$l].$l", insn.var, s, stackNow - 1, c);
				} else
					compilerContext.compileTo().addStatement("locals[$l].$l = stack[$l].$l", insn.var, c, stackNow - 1, c);
			}
			default -> throw Util.unimplemented(insn.getOpcode());
		}
	}
}
