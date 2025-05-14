package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.compiler.MemberCache;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

public class FieldInsnNodeHandler implements InsnHandler<FieldInsnNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<FieldInsnNode> compilerContext) {
		boolean detailed = !context.obfuscationSettings().vagueExceptions();
		FieldInsnNode insn = compilerContext.instruction();
		int leIndex = compilerContext.instructions().indexOf(insn);
		int stackSN = compilerContext.frames()[leIndex].getStackSize();
		Frame<SourceValue> sourceValueFrame = compilerContext.sourceFrames()[leIndex];
		Method m = compilerContext.compileTo();
		String ownerClass = insn.owner;
		String name = insn.name;
		Type fieldType = Type.getType(insn.desc);
		String typeName = DefaultCompiler.typeToName.getOrDefault(fieldType, "Object");
		char op = Util.typeToTypeChar(fieldType);
		switch (insn.getOpcode()) {
			case GETSTATIC -> {
				String storedClassName = compilerContext.cache().getOrCreateClassResolve(ownerClass, 0);
				MemberCache.Descriptor descriptor = new MemberCache.Descriptor(ownerClass, name, insn.desc);
				String storedFieldName = compilerContext.cache().getOrCreateStaticFieldFind(descriptor, 0);
				m.addStatement("stack[$l].$l = env->GetStatic$lField($l, $l)", stackSN, op, typeName, storedClassName, storedFieldName);
				compilerContext.exceptionCheck();
			}
			case GETFIELD -> {
				SourceValue inst = sourceValueFrame.getStack(stackSN - 1);
				if (Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), inst)) {
					m.beginScope("if (!stack[$l].l)", stackSN - 1);
					String cc = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
					m.addStatement("env->ThrowNew($l, $s)", cc, detailed ? "Cannot read field \"" + name + "\"" : "");
					compilerContext.exceptionCheck();
					m.endScope();
				}

				MemberCache.Descriptor descriptor = new MemberCache.Descriptor(ownerClass, name, insn.desc);
				String storedFieldName = compilerContext.cache().getOrCreateNonstaticFieldFind(descriptor, 0);
				m.addStatement("stack[$l].$l = env->Get$lField(stack[$l].l, $l)", stackSN - 1, op, typeName, stackSN - 1, storedFieldName);
				compilerContext.exceptionCheck();
			}
			case PUTFIELD -> {
				SourceValue inst = sourceValueFrame.getStack(stackSN - 2);
				if (Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), inst)) {
					m.beginScope("if (!stack[$l].l)", stackSN - 2);
					String cc = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
					m.addStatement("env->ThrowNew($l, $s)", cc, detailed ? "Cannot assign field \"" + name + "\"" : "");
					compilerContext.exceptionCheck();
					m.endScope();
				}

				MemberCache.Descriptor desc = new MemberCache.Descriptor(ownerClass, name, insn.desc);
				String orCreateNonstaticFieldFind = compilerContext.cache().getOrCreateNonstaticFieldFind(desc, 0);
				m.addStatement("env->Set$lField(stack[$l].l, $l, stack[$l].$l)", typeName, stackSN - 2, orCreateNonstaticFieldFind, stackSN - 1, op);
				compilerContext.exceptionCheck();
			}
			case PUTSTATIC -> {
				String orGenerateClassFind = compilerContext.cache().getOrCreateClassResolve(ownerClass, 0);
				MemberCache.Descriptor desc = new MemberCache.Descriptor(ownerClass, name, insn.desc);
				String orCreateStaticFieldFind = compilerContext.cache().getOrCreateStaticFieldFind(desc, 0);
				m.addStatement("env->SetStatic$lField($l, $l, stack[$l].$l)", typeName, orGenerateClassFind, orCreateStaticFieldFind, stackSN - 1, op);
				compilerContext.exceptionCheck();
			}
			default -> throw Util.unimplemented(insn.getOpcode());
		}
	}
}
