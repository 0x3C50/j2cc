package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;

public class MultiANewArrayInsnHandler implements InsnHandler<MultiANewArrayInsnNode> {

	private static final String[] ARRAY_TYPE_NAMES = {null, "Boolean", "Char", "Byte", "Short", "Int", "Float", "Long", "Double", null, null};

	@Override
	public void compileInsn(Context context, CompilerContext<MultiANewArrayInsnNode> compilerContext) {
		Method m = compilerContext.compileTo();
		MultiANewArrayInsnNode insn = compilerContext.instruction();
		int stackSN = compilerContext.frames()[compilerContext.instructions().indexOf(insn)].getStackSize();
		int nDims = insn.dims;
		Type arrayType = Type.getType(insn.desc);
		Type actualFinalArrayType = Type.getType(arrayType.getDescriptor().substring(nDims));
		int sort = actualFinalArrayType.getSort();
		String suffix = "c";
		String arrayTypeName = ARRAY_TYPE_NAMES[sort];
		assert arrayTypeName != null;
		String negArr = compilerContext.cache().getOrCreateClassResolve(Type.getInternalName(NegativeArraySizeException.class), 0);
		for (int i = 0; i < nDims; i++) {
			m.beginScope("if (stack[$l].i < 0)", stackSN - nDims + i);
			m.addStatement("env->ThrowNew($l, std::to_string(stack[$l].i).c_str())", negArr, stackSN - nDims + i);
			compilerContext.exceptionCheck(true);
			m.endScope();
		}

		for (int i = 0; i < nDims - 1; i++) {
			Type type = Type.getType(arrayType.getDescriptor().substring(i + 1));
			String clName = type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
			String className = compilerContext.cache().getOrCreateClassResolve(clName, 0);

			m.local("jobjectArray", "arr" + i + suffix).initStmt("env->NewObjectArray(stack[$l].i, $l, nullptr)", stackSN - nDims + i, className);
			m.beginScope("for (int i$l = 0; i$.0l < stack[$l].i; i$.0l++)", i, stackSN - nDims + i);
		}

		if (sort != Type.OBJECT && sort != Type.ARRAY) {
			m.local("j" + arrayTypeName.toLowerCase() + "Array", "arr" + (nDims - 1) + suffix).initStmt("env->New$lArray(stack[$l].i)", arrayTypeName, stackSN - 1);
		} else {
			String n = sort == Type.ARRAY ? actualFinalArrayType.getDescriptor() : actualFinalArrayType.getInternalName();
			String resolvedName = compilerContext.cache().getOrCreateClassResolve(n, 0);
			m.local("jobjectArray", "arr" + (nDims - 1) + suffix).initStmt("env->NewObjectArray(stack[$l].i, $l, nullptr)", stackSN - 1, resolvedName);
		}
		m.addStatement("env->SetObjectArrayElement(arr$l$l, i$.0l, arr$l$.1l)", nDims - 2, suffix, nDims - 1);
		for (int i = nDims - 1; i > 0; i--) {
			m.endScope();
			if (i >= 2) m.addStatement("env->SetObjectArrayElement(arr$l$l, i$.0l, arr$l$.1l)", i - 2, suffix, i - 1);
		}
		m.addStatement("stack[$l].l = arr0$l", stackSN - nDims, suffix);
	}
}
