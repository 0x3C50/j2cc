package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

public class JumpInsnNodeHandler implements InsnHandler<JumpInsnNode> {

	// this is the correct arrangement
	private static final String[] C_NUMBER_OPS = {
			"==", "!=", "<", ">=", ">", "<="
	};

	@Override
	public void compileInsn(Context context, CompilerContext<JumpInsnNode> compilerContext) {
		JumpInsnNode instruction = compilerContext.instruction();
		Frame<BasicValue> frame = compilerContext.frames()[compilerContext.instructions().indexOf(instruction)];
		String labelName = compilerContext.labels().get(instruction.label);
		Method m = compilerContext.compileTo();
		int stack = frame.getStackSize();
		switch (instruction.getOpcode()) {
			case GOTO -> m.addStatement("goto $l", labelName);
			case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
				String op = C_NUMBER_OPS[instruction.getOpcode() - IFEQ];
				m.beginScope("if (stack[$l].i $l 0)", stack - 1, op);
				m.addStatement("goto $l", labelName);
				m.endScope();
			}
			case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
				String op = C_NUMBER_OPS[instruction.getOpcode() - IF_ICMPEQ];
				m.beginScope("if (stack[$l].i $l stack[$l].i)", stack - 2, op, stack - 1);
				m.addStatement("goto $l", labelName);
				m.endScope();
			}
			case IF_ACMPEQ, IF_ACMPNE -> {
				m.beginScope("if ($lenv->IsSameObject(stack[$l].l, stack[$l].l))", instruction.getOpcode() == IF_ACMPNE ? "!" : "", stack - 2, stack - 1);
				m.addStatement("goto $l", labelName);
				m.endScope();
			}
			case IFNULL, IFNONNULL -> {
				m.beginScope("if (stack[$l].l $l= nullptr)", stack - 1, instruction.getOpcode() == IFNULL ? "=" : "!");
				m.addStatement("goto $l", labelName);
				m.endScope();
			}
			default -> throw Util.unimplemented(instruction.getOpcode());
		}
	}
}
