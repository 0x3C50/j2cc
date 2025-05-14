package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.tree.TypeInsnNode;

public class TypeInsnNodeHandler implements InsnHandler<TypeInsnNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<TypeInsnNode> compilerContext) {
		TypeInsnNode insn = compilerContext.instruction();
		int stack = compilerContext.frames()[compilerContext.instructions().indexOf(insn)].getStackSize();
		Method m = compilerContext.compileTo();
		switch (insn.getOpcode()) {
			case NEW -> {
				String desc = insn.desc;
				String className = compilerContext.cache().getOrCreateClassResolve(desc, 0);
				m.addStatement("stack[$l].l = env->AllocObject($l)", stack, className);
			}
			case CHECKCAST -> {
				String desc = insn.desc;
				String className = compilerContext.cache().getOrCreateClassResolve(desc, 0);
				m.beginScope("if (!env->IsInstanceOf(stack[$l].l, $l))", stack - 1, className);
				String orGenerateClassFind = compilerContext.cache().getOrCreateClassResolve("java/lang/ClassCastException", 0);
				m.addStatement("env->ThrowNew($l, $s)", orGenerateClassFind, "");
				compilerContext.exceptionCheck(true);
				m.endScope();
			}
			case ANEWARRAY -> {
				String orGenerateClassFind = compilerContext.cache().getOrCreateClassResolve(insn.desc, 0);
				m.addStatement("stack[$l].l = env->NewObjectArray(stack[$l].i, $l, nullptr)", stack - 1, stack - 1, orGenerateClassFind);
			}
			case INSTANCEOF -> {
				String real = compilerContext.cache().getOrCreateClassResolve(insn.desc, 0);
				m.addStatement("stack[$l].i = (stack[$l].l != nullptr && env->IsInstanceOf(stack[$l].l, $l)) ? 1 : 0", stack - 1, stack - 1, stack - 1, real);
			}
			default -> throw Util.unimplemented(insn.getOpcode());
		}
	}
}
