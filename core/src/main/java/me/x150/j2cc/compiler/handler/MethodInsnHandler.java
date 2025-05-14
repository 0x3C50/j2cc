package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.compiler.MemberCache;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.exc.CompilationFailure;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Printer;

import java.util.List;

public class MethodInsnHandler implements InsnHandler<MethodInsnNode> {
	private static final List<IntrinsicMethod> methodsWeHaveIntrinsicsFor = List.of(
			new IntrinsicMethod(Type.getType(Object.class), "getClass", Type.getMethodType(Type.getType(Class.class))),
			new IntrinsicMethod(Type.getType(String.class), "length", Type.getMethodType(Type.INT_TYPE)),
			new IntrinsicMethod(Type.getType(String.class), "isEmpty", Type.getMethodType(Type.BOOLEAN_TYPE)),
			new IntrinsicMethod(Type.getType(String.class), "equals", Type.getMethodType(Type.BOOLEAN_TYPE, Util.OBJECT_TYPE)),
			new IntrinsicMethod(Type.getType(Integer.class), "valueOf", Type.getMethodType(Type.getType(Integer.class), Type.INT_TYPE)),
			new IntrinsicMethod(Type.getType(Character.class), "valueOf", Type.getMethodType(Type.getType(Character.class), Type.CHAR_TYPE))
	);

	public static String stringifyType(Type t) {
		if (t.getSort() == Type.ARRAY) {
			String base = stringifyType(t.getElementType());
			return base + "[]".repeat(t.getDimensions());
		}
		if (t.getSort() == Type.OBJECT) {
			String clName = t.getClassName();
			if (clName.equals("java.lang.Object")) clName = "Object";
			else if (clName.equals("java.lang.String")) clName = "String";
			return clName;
		}
		return t.getClassName();
	}

	private static void nullptrCheck(CompilerContext<MethodInsnNode> compilerContext, Method m, int stackIdxToCheck, String message) {
		m.beginScope("if (!stack[$l].l)", stackIdxToCheck);
		String cc = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 1);
		m.addStatement("env->ThrowNew($l, $s)", cc, message);
		compilerContext.exceptionCheck(true);
		m.endScope();
	}

	@Override
	public void compileInsn(Context context, CompilerContext<MethodInsnNode> compilerContext) throws CompilationFailure {
		boolean detailed = !context.obfuscationSettings().vagueExceptions();
		MethodInsnNode insn = compilerContext.instruction();
		int stackSN = compilerContext.frames()[compilerContext.instructions().indexOf(insn)].getStackSize();
		final Method m = compilerContext.compileTo();
		String ownerClassUnmapped = compilerContext.remapper().unmapClassName(insn.owner);
		String ownerClass = insn.owner;
		String name = insn.name;
		String dsc = insn.desc;
		Workspace.ClassInfo ownerClassRf = context.workspace().get(ownerClassUnmapped);
		if (ownerClassRf != null) {
			List<MethodNode> possibleMethods = ownerClassRf.node().methods.stream().filter(s -> s.name.equals(name)).toList();
			// if there's multiple methods with the same name, the method cant be polymorphic
			if (possibleMethods.size() == 1) {
				List<AnnotationNode> visibleAnnotations = possibleMethods.getFirst().visibleAnnotations;
				if (visibleAnnotations != null && visibleAnnotations.stream().anyMatch(e -> e.desc.equals("Ljava/lang/invoke/MethodHandle$PolymorphicSignature;"))) {
					// POLYMORPHIC SIGNATURE! We can't transpile this!
					throw new CompilationFailure(
							String.format("Method %s.%s%s calls polymorphic method %s.%s%s. Polymorphic method refs cannot be transpiled.", compilerContext.methodOwner().name, compilerContext.methodNode().name, compilerContext.methodNode().desc,
									ownerClassUnmapped, name, dsc)
					);
				}
			}
		}

		Type[] argumentTypes = Type.getArgumentTypes(dsc);
		int argcount = argumentTypes.length;

		int opcode = insn.getOpcode();
		Type ret = Type.getReturnType(dsc);
		if (methodsWeHaveIntrinsicsFor.stream().anyMatch(it -> it.owner.getInternalName().equals(ownerClass) && it.name.equals(name) && it.desc.getDescriptor().equals(dsc))) {
			// this method can be intrinsified
			StringBuilder theArgs = new StringBuilder("env");
			boolean hasInstance = opcode != Opcodes.INVOKESTATIC;
			Type[] theArgTypes;
			if (hasInstance) {
				theArgTypes = new Type[argcount + 1];
				System.arraycopy(argumentTypes, 0, theArgTypes, 1, argcount);
				theArgTypes[0] = Util.OBJECT_TYPE;
			} else {
				theArgTypes = argumentTypes;
			}
			int stackSize = stackSN - theArgTypes.length;
			for (Type fullArgType : theArgTypes) {
				int stackIndex = stackSize++;
				theArgs.append(", stack[").append(stackIndex).append("]").append(".").append(Util.typeToTypeChar(fullArgType));
			}
			if (ret.getSort() != Type.VOID) {
				m.addStatement("stack[$l].$l = j2cc_intrinsic_$l_$l($l)", stackSN - theArgTypes.length, Util.typeToTypeChar(ret), Util.turnIntoIdentifier(ownerClass), name, theArgs.toString());
			} else {
				m.addStatement("j2cc_intrinsic_$l_$l($l)", Util.turnIntoIdentifier(ownerClass), name, theArgs.toString());
			}
			compilerContext.exceptionCheck();
			return;
		}

		StringBuilder methodRepresentation = new StringBuilder(stringifyType(Type.getObjectType(ownerClass)));
		methodRepresentation.append(".").append(name).append("(");
		for (int i = 0; i < argcount; i++) {
			Type argumentType = argumentTypes[i];
			methodRepresentation.append(stringifyType(argumentType));
			if (i != argcount - 1) methodRepresentation.append(", ");
		}
		methodRepresentation.append(")");

		String invokeType = DefaultCompiler.typeToName.getOrDefault(ret, "Object");
		if (context.debug().isPrintMethodCalls()) {
			m.addStatement("DBG($s)", String.format("upcall: %s %s.%s%s", Printer.OPCODES[opcode], ownerClass, name, dsc));
		}
		MemberCache.Descriptor meth = new MemberCache.Descriptor(ownerClass, name, dsc);
		switch (opcode) {
			case INVOKEVIRTUAL, INVOKEINTERFACE -> {
				String storedMethName = compilerContext.cache().getOrCreateNonstaticMethodFind(meth, 0);
				nullptrCheck(compilerContext, m, stackSN - argcount - 1, detailed ? "Cannot invoke " + methodRepresentation : "");
				if (ret != Type.VOID_TYPE) {
					m.addStatement("stack[$l].$l = env->Call$lMethodA(stack[$.0l].l, $l, stack+$l)",
							stackSN - argcount - 1,
							Util.typeToTypeChar(ret),
							invokeType,
							storedMethName,
							stackSN - argcount
							);
				} else {
					m.addStatement("env->CallVoidMethodA(stack[$l].l, $l, stack+$l)",
							stackSN - argcount - 1,
							storedMethName,
							stackSN - argcount
							);
				}
				compilerContext.exceptionCheck();
			}
			case INVOKESPECIAL -> {
				String storedClassName = compilerContext.cache().getOrCreateClassResolve(ownerClass, 0);
				String storedMethName = compilerContext.cache().getOrCreateNonstaticMethodFind(meth, 0);
				nullptrCheck(compilerContext, m, stackSN - argcount - 1, detailed ? "Cannot invoke " + methodRepresentation : "");

				if (ret != Type.VOID_TYPE) {
					m.addStatement("stack[$l].$l = env->CallNonvirtual$lMethodA(stack[$.0l].l, $l, $l, stack+$l)",
							stackSN - argcount - 1,
							Util.typeToTypeChar(ret),
							invokeType,
							storedClassName,
							storedMethName,
							stackSN - argcount);
				} else {
					m.addStatement("env->CallNonvirtualVoidMethodA(stack[$l].l, $l, $l, stack+$l)",
							stackSN - argcount - 1,
							storedClassName,
							storedMethName,
							stackSN - argcount);
				}
				compilerContext.exceptionCheck();
			}
			case INVOKESTATIC -> {
				String storedClassName = compilerContext.cache().getOrCreateClassResolve(ownerClass, 0);
				String storedMethName = compilerContext.cache().getOrCreateStaticMethodFind(meth, 0);


				if (ret != Type.VOID_TYPE) {
					m.addStatement("stack[$l].$l = env->CallStatic$lMethodA($l, $l, stack+$l)",
							stackSN - argcount,
							Util.typeToTypeChar(ret),
							invokeType,
							storedClassName,
							storedMethName,
							stackSN - argcount);
				} else {
					m.addStatement("env->CallStaticVoidMethodA($l, $l, stack+$l)",
							storedClassName,
							storedMethName,
							stackSN - argcount);
				}
				compilerContext.exceptionCheck();
			}
			default -> throw Util.unimplemented(opcode);
		}
	}

	record IntrinsicMethod(Type owner, String name, Type desc) {
	}
}
