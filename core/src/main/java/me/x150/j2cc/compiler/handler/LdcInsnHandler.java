package me.x150.j2cc.compiler.handler;

import lombok.SneakyThrows;
import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.compiler.MemberCache;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.LdcInsnNode;

import java.lang.invoke.MethodHandle;

public class LdcInsnHandler implements InsnHandler<LdcInsnNode> {
	@SneakyThrows
	@Override
	public void compileInsn(Context context, CompilerContext<LdcInsnNode> compilerContext) {
		LdcInsnNode instruction = compilerContext.instruction();
		Object cst = instruction.cst;
		Method m = compilerContext.compileTo();
		String prefix = "stack[$l].";
		int sH = compilerContext.frames()[compilerContext.instructions().indexOf(instruction)].getStackSize();
		switch (cst) {
			case Integer i -> m.addStatement(prefix + "i = $l", sH, i);

			case Float v -> m.addStatement(prefix + "i = $l", sH, Float.floatToRawIntBits(v));
			case Double v -> m.addStatement(prefix + "j = $l", sH, Double.doubleToRawLongBits(v));

			case Long l -> m.addStatement(prefix + "j = $lLL", sH, l);
			case String s -> m.addStatement("stack[$l].l = env->NewStringUTF(STRING_CP($l))", sH, compilerContext.stringCollector().reserveString(s));
			case Type t -> {
				if (t.getSort() == Type.METHOD) {
					String s1 = Util.generateMethodTypeFromType(t, m, compilerContext, "methodType");
					m.addStatement("stack[$l].l = $l", sH, s1);
				} else {
					String d = t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName();
					String orGenerateClassFind = compilerContext.cache().getOrCreateClassResolve(d, 0);
					m.addStatement(prefix + "l = $l", sH, orGenerateClassFind);
				}
			}
			case ConstantDynamic cd -> {
				Object[] bsmArgs = new Object[cd.getBootstrapMethodArgumentCount()];
				for (int i = 0; i < bsmArgs.length; i++) {
					bsmArgs[i] = cd.getBootstrapMethodArgument(i);
				}
				String ret = Util.generateConstantDynamic(context, compilerContext, m, bsmArgs, cd.getBootstrapMethod(), cd.getName(), cd.getDescriptor(), "condyResult");
				Type retTy = Type.getType(cd.getDescriptor());
				switch (retTy.getSort()) {
					case Type.BOOLEAN -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Boolean.class.getMethod("booleanValue")), 0);
						m.addStatement("stack[$l].i = env->CallBooleanMethod($l, $l)", sH, ret, s);
					}
					case Type.CHAR -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Character.class.getMethod("charValue")), 0);
						m.addStatement("stack[$l].i = env->CallCharMethod($l, $l)", sH, ret, s);
					}
					case Type.BYTE -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Byte.class.getMethod("byteValue")), 0);
						m.addStatement("stack[$l].i = env->CallByteMethod($l, $l)", sH, ret, s);
					}
					case Type.SHORT -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Short.class.getMethod("shortValue")), 0);
						m.addStatement("stack[$l].i = env->CallShortMethod($l, $l)", sH, ret, s);
					}
					case Type.INT -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Integer.class.getMethod("intValue")), 0);
						m.addStatement("stack[$l].i = env->CallIntMethod($l, $l)", sH, ret, s);
					}
					case Type.FLOAT -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Float.class.getMethod("floatValue")), 0);
						m.addStatement("stack[$l].f = env->CallFloatMethod($l, $l)", sH, ret, s);
					}
					case Type.LONG -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Long.class.getMethod("longValue")), 0);
						m.addStatement("stack[$l].j = env->CallLongMethod($l, $l)", sH, ret, s);
					}
					case Type.DOUBLE -> {
						String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Double.class.getMethod("doubleValue")), 0);
						m.addStatement("stack[$l].d = env->CallDoubleMethod($l, $l)", sH, ret, s);
					}
					case Type.OBJECT, Type.ARRAY -> m.addStatement("stack[$l].l = $l", sH, ret);
				}
			}
			case Handle h -> {
				String owner = h.getOwner();
				String name = h.getName();
				String desc = h.getDesc();
				String ownerClR = compilerContext.cache().getOrCreateClassResolve(owner, 0);
				String mhn = "java/lang/invoke/MethodHandleNatives";
				String mhnClazz = compilerContext.cache().getOrCreateClassResolve(mhn, 1);
				String linkMethodHandleConstantMethod = compilerContext.cache().getOrCreateStaticMethodFind(new MemberCache.Descriptor(
						mhn, "linkMethodHandleConstant",
						Type.getMethodDescriptor(Type.getType(MethodHandle.class), Type.getType(Class.class), Type.INT_TYPE,
								Type.getType(Class.class), Type.getType(String.class), Util.OBJECT_TYPE)
				), 0);
				String theFinalArg = switch (h.getTag()) {
					case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC ->
							Util.resolveTypeToJclassAndStoreIt(Type.getType(desc), m, compilerContext, "otherType");
					case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESTATIC,
						 Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL ->
							Util.generateMethodTypeFromType(Type.getMethodType(desc), m, compilerContext, "methodType");
					default -> throw new IllegalStateException();
				};
				String currentClazz = compilerContext.cache().getOrCreateClassResolve(compilerContext.methodOwner().name, 2);
				m.addStatement("stack[$l].l = env->CallStaticObjectMethod($l, $l, $l, $l, $l, env->NewStringUTF($s), $l)", sH, mhnClazz, linkMethodHandleConstantMethod, currentClazz, h.getTag(), ownerClR, name, theFinalArg);
				compilerContext.exceptionCheck();
			}
			case null, default -> throw new IllegalStateException("Unimplemented");
		}
	}
}
