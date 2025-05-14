package me.x150.j2cc.compiler.handler;

import lombok.SneakyThrows;
import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.compiler.MemberCache;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.lang.invoke.MethodHandle;

public class InvokeDynamicHandler implements InsnHandler<InvokeDynamicInsnNode> {

	@SneakyThrows
	@Override

	public void compileInsn(Context context, CompilerContext<InvokeDynamicInsnNode> compilerContext) {
		InvokeDynamicInsnNode instruction = compilerContext.instruction();
		Frame<BasicValue> frame = compilerContext.frames()[compilerContext.instructions().indexOf(instruction)];
		int sH = frame.getStackSize();
		Handle bsm = instruction.bsm;
		Object[] bsmArgs = instruction.bsmArgs;
		String name = instruction.name;
		Method m = compilerContext.compileTo();

		String returnedCallSite = Util.generateInvokedynamic(context, compilerContext, m, bsmArgs, bsm, name, instruction.desc, "indyCallSite");

		Type[] argumentTypes = Type.getArgumentTypes(instruction.desc);
		int argc = argumentTypes.length;
		int stackMin = sH - argc;
		String argsArray = "indyArgsArray";
		String objectClass = compilerContext.cache().getOrCreateClassResolve("java/lang/Object", 0);
		m.local("jobjectArray", argsArray).initStmt("env->NewObjectArray($l, $l, nullptr)", argc, objectClass);
		for (int i = 0; i < argc; i++) {
			int stackIndex = stackMin + i;
			Type t = argumentTypes[i];
			if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
				m.addStatement("env->SetObjectArrayElement($l, $l, stack[$l].l)", argsArray, i, stackIndex);
			} else {
				Type theWrapper = Util.boxType(t);
				MemberCache.Descriptor desc = new MemberCache.Descriptor(theWrapper.getInternalName(),
						"valueOf", Type.getMethodDescriptor(theWrapper, t));
				String owningClass = compilerContext.cache().getOrCreateClassResolve(desc.owner(), 0);
				String meth = compilerContext.cache().getOrCreateStaticMethodFind(desc, 0);
				char c = Util.typeToTypeChar(t);
				m.addStatement("env->SetObjectArrayElement($l, $l, env->CallStaticObjectMethod($l, $l, stack[$l].$l))", argsArray, i, owningClass, meth, stackIndex, c);
				compilerContext.exceptionCheck();
			}
		}
		String mhToInvoke = "indyMhResult";
		String callSiteGetTarget = compilerContext.cache().getOrCreateNonstaticMethodFind(new MemberCache.Descriptor("java/lang/invoke/CallSite", "getTarget", "()Ljava/lang/invoke/MethodHandle;"), 0);
		m.local("jobject", mhToInvoke).initStmt("env->CallObjectMethod($l, $l)", returnedCallSite, callSiteGetTarget);
		compilerContext.exceptionCheck();

		// normalize call site
		String asFixedArity = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(MethodHandle.class.getMethod("asFixedArity")), 0);
		m.addStatement("$l = env->CallObjectMethod($.0l, $l)", mhToInvoke, asFixedArity);
		compilerContext.exceptionCheck();

		String ret = "indyResult";
		String internalUtilName = Util.getInternalUtilClassName(context.obfuscationSettings().renamerSettings()).getInternalName();
		String internalUtil = compilerContext.cache().getOrCreateClassResolve(internalUtilName, 0);
		String internalUtilInvokeHandle = compilerContext.cache().getOrCreateStaticMethodFind(new MemberCache.Descriptor(internalUtilName, Util.internalUtilInvokeMh.getName(), Util.internalUtilInvokeMh.getDescriptor()), 0);
		m.local("jobject", ret).initStmt("env->CallStaticObjectMethod($l, $l, $l, $l)", internalUtil, internalUtilInvokeHandle, mhToInvoke, argsArray);
		compilerContext.exceptionCheck();
		Type retTy = Type.getReturnType(instruction.desc);
		switch (retTy.getSort()) {
			case Type.BOOLEAN -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Boolean.class.getMethod("booleanValue")), 0);
				m.addStatement("stack[$l].i = env->CallBooleanMethod($l, $l)", stackMin, ret, s);
			}
			case Type.CHAR -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Character.class.getMethod("charValue")), 0);
				m.addStatement("stack[$l].i = env->CallCharMethod($l, $l)", stackMin, ret, s);
			}
			case Type.BYTE -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Byte.class.getMethod("byteValue")), 0);
				m.addStatement("stack[$l].i = env->CallByteMethod($l, $l)", stackMin, ret, s);
			}
			case Type.SHORT -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Short.class.getMethod("shortValue")), 0);
				m.addStatement("stack[$l].i = env->CallShortMethod($l, $l)", stackMin, ret, s);
			}
			case Type.INT -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Integer.class.getMethod("intValue")), 0);
				m.addStatement("stack[$l].i = env->CallIntMethod($l, $l)", stackMin, ret, s);
			}
			case Type.FLOAT -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Float.class.getMethod("floatValue")), 0);
				m.addStatement("stack[$l].f = env->CallFloatMethod($l, $l)", stackMin, ret, s);
			}
			case Type.LONG -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Long.class.getMethod("longValue")), 0);
				m.addStatement("stack[$l].j = env->CallLongMethod($l, $l)", stackMin, ret, s);
			}
			case Type.DOUBLE -> {
				String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Double.class.getMethod("doubleValue")), 0);
				m.addStatement("stack[$l].d = env->CallDoubleMethod($l, $l)", stackMin, ret, s);
			}
			case Type.OBJECT, Type.ARRAY -> m.addStatement("stack[$l].l = $l", stackMin, ret);
		}
		compilerContext.exceptionCheck();
	}

}
