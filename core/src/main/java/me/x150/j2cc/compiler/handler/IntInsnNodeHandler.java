package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.IntInsnNode;

public class IntInsnNodeHandler implements InsnHandler<IntInsnNode> {

	private static final String[] ARRAY_TYPE_NAME = {
			"Boolean", "Char", "Float", "Double", "Byte", "Short", "Int", "Long"
	};

	@Override
	public void compileInsn(Context context, CompilerContext<IntInsnNode> compilerContext) {
		IntInsnNode insn = compilerContext.instruction();
		int stack = compilerContext.frames()[compilerContext.instructions().indexOf(insn)].getStackSize();
		final Method m = compilerContext.compileTo();
		switch (insn.getOpcode()) {
			case BIPUSH, SIPUSH -> m.addStatement("stack[$l].i = $l", stack, insn.operand);
			case NEWARRAY -> {
				String t = ARRAY_TYPE_NAME[insn.operand - T_BOOLEAN];
				m.beginScope("if (stack[$l].i < 0)", stack - 1);
				String negArraySize = compilerContext.cache().getOrCreateClassResolve(Type.getInternalName(NegativeArraySizeException.class), 0);
				m.addStatement("env->ThrowNew($l, std::to_string(stack[$l].i).c_str())", negArraySize, stack - 1);
				compilerContext.exceptionCheck(true);
				m.endScope();

				m.addStatement("stack[$l].l = env->New$lArray(stack[$l].i)", stack - 1, t, stack - 1);
			}
			default -> throw Util.unimplemented(insn.getOpcode());
		}
	}
}
