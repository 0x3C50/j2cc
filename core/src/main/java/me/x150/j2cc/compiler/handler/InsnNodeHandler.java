package me.x150.j2cc.compiler.handler;

import j2cc.Nativeify;
import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

@Nativeify
public class InsnNodeHandler implements InsnHandler<InsnNode>, Opcodes {

	private static final String[] ARRAY_TYPES = {
			"jint", "jlong", "jfloat", "jdouble", null, "jbyte", "jchar", "jshort"
	};
	private static final String[] ARRAY_TYPES_NAMES = {
			"Int", "Long", "Float", "Double", null, "Byte", "Char", "Short"
	};
	private static final String[] EXCEPTION_TYPE_NAMES = {"int", "long", "float", "double", null, "byte/boolean", "char", "short"};

	@Override
//	@Nativeify
	public void compileInsn(Context context, CompilerContext<InsnNode> compilerContext) {
		boolean detailed = !context.obfuscationSettings().vagueExceptions();
		int opcode = compilerContext.instruction().getOpcode();
		int leIndex = compilerContext.instructions().indexOf(compilerContext.instruction());
		Frame<BasicValue> frame = compilerContext.frames()[leIndex];
		Frame<SourceValue> sourceValueFrame = compilerContext.sourceFrames()[leIndex];
		int stack = frame.getStackSize();
		Method m = compilerContext.compileTo();

		if (opcode >= ICONST_M1 && opcode <= ICONST_5) {
			m.addStatement("stack[$l].i = $l", stack, opcode - ICONST_0);
		} else if (opcode >= LCONST_0 && opcode <= LCONST_1) {
			m.addStatement("stack[$l].j = $l", stack, opcode - LCONST_0);
		} else if (opcode >= FCONST_0 && opcode <= FCONST_2) {
			m.addStatement("stack[$l].f = $l", stack, (float) (opcode - FCONST_0));
		} else {
			switch (opcode) {
				case NOP, POP, POP2 -> {
				}
				case ACONST_NULL -> m.addStatement("stack[$l].l = nullptr", stack);
				/* ICONST_* */
				/* LCONST_* */
				/* FCONST_* */
				case DCONST_0 -> m.addStatement("stack[$l].d = 0.0", stack);
				case DCONST_1 -> m.addStatement("stack[$l].d = 1.0", stack);

				case AALOAD -> {
					boolean isNu = Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), sourceValueFrame.getStack(stack - 2));
					String s = "jObjectArr0";
					String lenS = "arrlen0";
					// null check
					m.localInitialValue("jobjectArray", s, "nullptr").initStmt("(jobjectArray) stack[$l].l", stack - 2);
					if (isNu) {
						m.beginScope("if (!$l)", s);
						String npeClass = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
						m.addStatement("env->ThrowNew($l, $s)", npeClass, "Cannot load from object array");
						compilerContext.exceptionCheck(true);
						m.endScope();
					}

					// array bounds check
					m.local("jsize", lenS).initStmt("env->GetArrayLength($l)", s);
					m.beginScope("if (stack[$l].i >= $l || stack[$.0l].i < 0)", stack - 1, lenS);
					String aioobClass = compilerContext.cache().getOrCreateClassResolve("java/lang/ArrayIndexOutOfBoundsException", 0);
					if (detailed)
						m.addStatement("env->ThrowNew($l, (std::string{$s} + std::to_string(stack[$l].i) + $s + std::to_string($l)).c_str())", aioobClass, "Index ", stack - 1, " out of bounds for length ", lenS);
					else m.addStatement("env->ThrowNew($l, $s)", aioobClass, "");
					compilerContext.exceptionCheck(true);
					m.endScope();

					m.addStatement("stack[$l].l = env->GetObjectArrayElement($l, stack[$l].i)", stack - 2, s, stack - 1);
				}
				case IALOAD, LALOAD, FALOAD, DALOAD, BALOAD, CALOAD, SALOAD -> {
					int typeOrdinal = opcode - IALOAD;
					String type = ARRAY_TYPES_NAMES[typeOrdinal];
					String typeNames = EXCEPTION_TYPE_NAMES[typeOrdinal];
					char op = "ijfd_iii".charAt(typeOrdinal);
					String arrayVar = "j"+type+"Arr0";
					String lenS = "arrlen0";
					// null check
					m.localInitialValue("j" + type.toLowerCase() + "Array", arrayVar, "nullptr").initStmt("(j$lArray) stack[$l].l", type.toLowerCase(), stack - 2);
					boolean isNu = Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), sourceValueFrame.getStack(stack - 2));
					if (isNu) {
						m.beginScope("if (!$l)", arrayVar);
						String npeClass = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
						m.addStatement("env->ThrowNew($l, $s)", npeClass, detailed ? "Cannot load from " + typeNames + " array" : "Cannot load from array");
						compilerContext.exceptionCheck(true);
						m.endScope();
					}

					// array bounds check
					m.local("jsize", lenS).initStmt("env->GetArrayLength($l)", arrayVar);
					m.beginScope("if (stack[$l].i >= $l || stack[$.0l].i < 0)", stack - 1, lenS);
					String aioobClass = compilerContext.cache().getOrCreateClassResolve("java/lang/ArrayIndexOutOfBoundsException", 0);
					if (detailed)
						m.addStatement("env->ThrowNew($l, (std::string{$s} + std::to_string(stack[$l].i) + $s + std::to_string($l)).c_str())", aioobClass, "Index ", stack - 1, " out of bounds for length ", lenS);
					else m.addStatement("env->ThrowNew($l, $s)", aioobClass, "Index out of bounds");
					compilerContext.exceptionCheck(true);
					m.endScope();

					String carryVar = "j"+type+"ArrayBuffer0";
					String carryType = "j" + type.toLowerCase() + "*";
					m.local(carryType, carryVar).initStmt("($l) env->GetPrimitiveArrayCritical($l, nullptr)", carryType, arrayVar);
					m.addStatement("stack[$l].$l = $l[stack[$l].i]", stack - 2, op, carryVar, stack - 1);
					m.addStatement("env->ReleasePrimitiveArrayCritical($l, $l, JNI_ABORT)", arrayVar, carryVar);
				}

				case IASTORE, LASTORE, FASTORE, DASTORE, BASTORE, CASTORE, SASTORE -> {
					int typeOrdinal = opcode - IASTORE;
					String typeName = EXCEPTION_TYPE_NAMES[typeOrdinal];
					String type = ARRAY_TYPES[typeOrdinal];
					String name = ARRAY_TYPES_NAMES[typeOrdinal];
					char op = "ijfd_bcs".charAt(typeOrdinal);

					m.beginScope("");

					String arrayRef = "j"+name+"Arr0";
					String lenS = "arrlen0";
					m.localInitialValue("j" + name.toLowerCase() + "Array", arrayRef, "nullptr").initStmt("(j$lArray) stack[$l].l", name.toLowerCase(), stack - 3);

					// null check
					boolean isNu = Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), sourceValueFrame.getStack(stack - 3));

					if (isNu) {
						m.beginScope("if (!$l)", arrayRef);
						String npeClass = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
						m.addStatement("env->ThrowNew($l, $s)", npeClass, detailed ? "Cannot store to " + typeName + " array" : "Cannot store to array");
						compilerContext.exceptionCheck(true);
						m.endScope();
					}

					// array bounds check
					m.local("jsize", lenS).initStmt("env->GetArrayLength($l)", arrayRef);
					m.beginScope("if (stack[$l].i >= $l || stack[$.0l].i < 0)", stack - 2, lenS);
					String aioobClass = compilerContext.cache().getOrCreateClassResolve("java/lang/ArrayIndexOutOfBoundsException", 0);
					if (detailed)
						m.addStatement("env->ThrowNew($l, (std::string{$s} + std::to_string(stack[$l].i) + $s + std::to_string($l)).c_str())", aioobClass, "Index ", stack - 2, " out of bounds for length ", lenS);
					else m.addStatement("env->ThrowNew($l, $s)", aioobClass, "Index out of bounds");
					compilerContext.exceptionCheck(true);
					m.endScope();

					m.addStatement("$l newValue = stack[$l].$l", type, stack - 1, op);
					m.addStatement("env->Set$lArrayRegion($l, stack[$l].i, 1, &newValue)", name, arrayRef, stack - 2);
					m.endScope();
				}
				case AASTORE -> {
					String arrayRef = "jObjectArr0";
					String lenS = "arrlen0";
					// null check
					m.localInitialValue("jobjectArray", arrayRef, "nullptr").initStmt("(jobjectArray) stack[$l].l", stack - 3);
					boolean isNu = Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), sourceValueFrame.getStack(stack - 3));
					if (isNu) {
						m.beginScope("if (!$l)", arrayRef);
						String npeClass = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
						m.addStatement("env->ThrowNew($l, $s)", npeClass, "Cannot store to object array");
						compilerContext.exceptionCheck(true);
						m.endScope();
					}
					// array bounds check
					m.local("jsize", lenS).initStmt("env->GetArrayLength($l)", arrayRef);
					m.beginScope("if (stack[$l].i >= $l || stack[$.0l].i < 0)", stack - 2, lenS);
					String aioobClass = compilerContext.cache().getOrCreateClassResolve("java/lang/ArrayIndexOutOfBoundsException", 0);
					if (detailed)
						m.addStatement("env->ThrowNew($l, (std::string{$s} + std::to_string(stack[$l].i) + $s + std::to_string($l)).c_str())", aioobClass, "Index ", stack - 2, " out of bounds for length ", lenS);
					else m.addStatement("env->ThrowNew($l, $s)", aioobClass, "Index out of bounds");
					compilerContext.exceptionCheck(true);
					m.endScope();

					m.addStatement("env->SetObjectArrayElement($l, stack[$l].i, stack[$l].l)", arrayRef, stack - 2, stack - 1);
				}

				case DUP -> {
					m.comment("DUP 1 SKIP 0");
					m.addStatement("stack[$l] = stack[$l]", stack, stack - 1);
				}
				case DUP_X1 -> {
					m.comment("DUP 1 SKIP 1");
					doDup(frame, m, 1, 1);
				}
				case DUP_X2 -> {
					m.comment("DUP 1 SKIP 2");
					doDup(frame, m, 1, 2);
				}

				case DUP2 -> {
					m.comment("DUP 2 SKIP 0");
					BasicValue topStack = frame.getStack(frame.getStackSize() - 1);
					char c = Util.typeToTypeChar(topStack.getType());
					if (topStack.getSize() == 1) {
						BasicValue secondTop = frame.getStack(frame.getStackSize() - 2);
						char cc = Util.typeToTypeChar(secondTop.getType());
						m.addStatement("stack[$l].$l = stack[$l].$l", stack + 1, c, stack - 1, c);
						m.addStatement("stack[$l].$l = stack[$l].$l", stack, cc, stack - 2, cc);
					} else {
						m.addStatement("stack[$l].$l = stack[$l].$l", stack, c, stack - 1, c);
					}
				}
				case DUP2_X1 -> {
					m.comment("DUP 2 SKIP 1");
					doDup(frame, m, 2, 1);
				}

				case DUP2_X2 -> {
					m.comment("DUP 2 SKIP 2");
					doDup(frame, m, 2, 2);
				}

				case SWAP -> m.addStatement("std::swap(stack[$l], stack[$l])", stack - 2, stack - 1);
				case IADD, ISUB, IMUL, IDIV -> {
					char op = "+-*/".charAt((opcode - IADD) / 4);
					if (opcode == IDIV) {
						m.beginScope("if (stack[$l].i == 0)", stack - 1);
						String arithm = compilerContext.cache().getOrCreateClassResolve(Type.getInternalName(ArithmeticException.class), 0);
						m.addStatement("env->ThrowNew($l, $s)", arithm, "/ by zero");
						compilerContext.exceptionCheck();
						m.endScope();
					}
					m.addStatement("stack[$l].i = stack[$l].i $l stack[$l].i", stack - 2, stack - 2, op, stack - 1);
				}
				case LADD, LSUB, LMUL, LDIV -> {
					char op = "+-*/".charAt((opcode - LADD) / 4);
					if (opcode == LDIV) {
						m.beginScope("if (stack[$l].j == 0)", stack - 1);
						String arithm = compilerContext.cache().getOrCreateClassResolve(Type.getInternalName(ArithmeticException.class), 0);
						m.addStatement("env->ThrowNew($l, $s)", arithm, "/ by zero");
						compilerContext.exceptionCheck();
						m.endScope();
					}
					m.addStatement("stack[$l].j = stack[$l].j $l stack[$l].j", stack - 2, stack - 2, op, stack - 1);
				}
				case FADD, FSUB, FMUL, FDIV -> {
					char op = "+-*/".charAt((opcode - FADD) / 4);
					m.addStatement("stack[$l].f = stack[$l].f $l stack[$l].f", stack - 2, stack - 2, op, stack - 1);
				}
				case DADD, DSUB, DMUL, DDIV -> {
					char op = "+-*/".charAt((opcode - DADD) / 4);
					m.addStatement("stack[$l].d = stack[$l].d $l stack[$l].d", stack - 2, stack - 2, op, stack - 1);
				}

				case LSHR, LSHL, ISHR, ISHL -> {
					String op = opcode == LSHR || opcode == ISHR ? ">>" : "<<";
					char v = switch (opcode) {
						case LSHL, LSHR -> 'j';
						case ISHL, ISHR -> 'i';
						default -> throw new IllegalStateException("Unexpected value: " + opcode);
					};
					int mask = opcode == LSHR || opcode == LSHL ? 0x3F : 0x1F;
					m.addStatement("stack[$l].$l = stack[$l].$.1l $l (stack[$l].i & $l)", stack - 2, v, stack - 2, op, stack - 1, mask);
				}

				case LXOR, LOR, LAND, LREM -> {
					String operator = switch (opcode) {
						case LXOR -> "^";
						case LOR -> "|";
						case LAND -> "&";
						case LREM -> "%";
						default -> throw new IllegalStateException("Unexpected value: " + opcode);
					};
					char v = 'j';
					m.addStatement("stack[$l].j = stack[$l].j $l stack[$l].$l", stack - 2, stack - 2, operator, stack - 1, v);
				}
				case IXOR, IOR, IAND, IREM -> {
					String operator = switch (opcode) {
						case IXOR -> "^";
						case IOR -> "|";
						case IAND -> "&";
						case IREM -> "%";
						default -> throw new IllegalStateException("Unexpected value: " + opcode);
					};
					m.addStatement("stack[$l].i = stack[$l].i $l stack[$l].i", stack - 2, stack - 2, operator, stack - 1);
				}

				case RETURN -> {
					if (context.debug().isPrintMethodEntryExit()) {
						m.addStatement("puts($s)", "[j2cc] exit " + compilerContext.methodOwner().name + "." + compilerContext.methodNode().name + compilerContext.methodNode().desc);
					}
					m.addStatement("return");
				}
				case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN -> {
					char c = "ijfdl".charAt(opcode - IRETURN);
					String s = m.getReturns();
					if (context.debug().isPrintMethodEntryExit()) {
						m.addStatement("puts($s)", "[j2cc] exit " + compilerContext.methodOwner().name + "." + compilerContext.methodNode().name + compilerContext.methodNode().desc);
					}
					m.addStatement("return ($l) stack[$l].$l", s, stack - 1, c);
				}

				case ARRAYLENGTH -> {
					String arrayRef = "jarr0";
					// null check
					m.localInitialValue("jarray", arrayRef, "nullptr").initStmt("(jarray) stack[$l].l", stack - 1);
					boolean isNu = Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), sourceValueFrame.getStack(stack - 1));
					if (isNu) {
						m.beginScope("if (!$l)", arrayRef);
						String npeClass = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
						m.addStatement("env->ThrowNew($l, $s)", npeClass, "Cannot read the array length because array is null");
						compilerContext.exceptionCheck(true);
						m.endScope();
					}
					m.addStatement("stack[$l].i = env->GetArrayLength($l)", stack - 1, arrayRef);
				}
				case ATHROW -> {
					boolean isNu = Util.couldBeNull(compilerContext.sourceFrames(), compilerContext.instructions(), sourceValueFrame.getStack(stack - 1));
					if (isNu) {
						m.beginScope("if (!stack[$l].l)", stack - 1);
						m.addStatement("DBG($s)", "athrow handler top is null, throwing npe");
						String npe = compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0);
						m.addStatement("env->ThrowNew($l, $s)", npe, "Cannot throw exception");
						m.scopeElse();
					}
					m.addStatement("DBG($s)", "athrow handler top is nonnull, throwing normal");
					m.addStatement("env->Throw((jthrowable) stack[$l].l)", stack - 1);
					if (isNu) m.endScope();
					compilerContext.exceptionCheck(true);
				}
				case F2D -> m.addStatement("stack[$l].d = (jdouble) stack[$l].f", stack - 1, stack - 1);
				case F2I -> m.addStatement("stack[$l].i = (jint) stack[$l].f", stack - 1, stack - 1);
				case D2I -> m.addStatement("stack[$l].i = (jint) stack[$l].d", stack - 1, stack - 1);
				case DREM -> m.addStatement("stack[$l].d = fmod(stack[$l].d, stack[$l].d)", stack - 2, stack - 2, stack - 1);
				case FREM -> m.addStatement("stack[$l].f = fmod(stack[$l].f, stack[$l].f)", stack - 2, stack - 2, stack - 1);
				case LUSHR ->
						m.addStatement("stack[$l].j = ((unsigned long long) stack[$l].j) >> stack[$l].i", stack - 2, stack - 2, stack - 1);
				case IUSHR ->
						m.addStatement("stack[$l].i = ((unsigned int) stack[$l].i) >> stack[$l].i", stack - 2, stack - 2, stack - 1);
				case LCMP ->
						m.addStatement("stack[$l].i = stack[$l].j > stack[$l].j ? 1 : (stack[$.1l].j < stack[$.2l].j ? -1 : 0)", stack - 2, stack - 2, stack - 1);
				case DCMPL, DCMPG -> {
					int direction = opcode == DCMPL ? -1 : 1;
					m.addStatement("stack[$l].i = stack[$l].d > stack[$l].d ? 1 : stack[$.1l].d < stack[$.2l].d ? -1 : stack[$.1l].d == stack[$.2l].d ? 0 : $l", stack - 2, stack - 2, stack - 1, direction);
				}
				case FCMPL, FCMPG -> {
					int direction = opcode == FCMPL ? -1 : 1;
					m.addStatement("stack[$l].i = stack[$l].f > stack[$l].f ? 1 : stack[$.1l].f < stack[$.2l].f ? -1 : stack[$.1l].f == stack[$.2l].f ? 0 : $l", stack - 2, stack - 2, stack - 1, direction);
				}
				case L2I -> m.addStatement("stack[$l].i = (jint) stack[$l].j", stack - 1, stack - 1);
				case L2D -> m.addStatement("stack[$l].d = (jdouble) stack[$l].j", stack - 1, stack - 1);
				case L2F -> m.addStatement("stack[$l].f = (jfloat) stack[$l].j", stack - 1, stack - 1);
				case F2L -> m.addStatement("stack[$l].j = (jlong) stack[$l].f", stack - 1, stack - 1);
				case D2L -> m.addStatement("stack[$l].j = (jlong) stack[$l].d", stack - 1, stack - 1);
				case D2F -> m.addStatement("stack[$l].f = (jfloat) stack[$l].d", stack - 1, stack - 1);
				case MONITORENTER -> {
					m.beginScope("if (stack[$l].l == nullptr)", stack - 1);
					m.addStatement("env->ThrowNew($l, $s)", compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0), "Cannot enter synchronized block", compilerContext);
					compilerContext.exceptionCheck();
					m.endScope();
					m.addStatement("env->MonitorEnter(stack[$l].l)", stack - 1);
				}
				case MONITOREXIT -> {
					m.beginScope("if (stack[$l].l == nullptr)", stack - 1);
					m.addStatement("env->ThrowNew($l, $s)", compilerContext.cache().getOrCreateClassResolve("java/lang/NullPointerException", 0), "Cannot exit synchronized block (because objectref is null, how did you manage to do this \\?\\?\\?)", compilerContext);
					compilerContext.exceptionCheck();
					m.endScope();
					m.addStatement("env->MonitorExit(stack[$l].l)", stack - 1);
				}
				case I2C -> m.addStatement("stack[$l].i = (jchar) stack[$l].i", stack - 1, stack - 1);
				case I2B -> m.addStatement("stack[$l].i = (jbyte) stack[$l].i", stack - 1, stack - 1);
				case I2S -> m.addStatement("stack[$l].i = (jshort) stack[$l].i", stack - 1, stack - 1);
				case I2L -> m.addStatement("stack[$l].j = (jlong) stack[$l].i", stack - 1, stack - 1);
				case I2F -> m.addStatement("stack[$l].f = (jfloat) stack[$l].i", stack - 1, stack - 1);
				case I2D -> m.addStatement("stack[$l].d = (jdouble) stack[$l].i", stack - 1, stack - 1);

				case INEG, LNEG, FNEG, DNEG -> {
					char FUCK = "ijfd".charAt(opcode - INEG);
					m.addStatement("stack[$l].$l = -stack[$l].$l", stack - 1, FUCK, stack - 1, FUCK);
				}

				default -> throw Util.unimplemented(opcode);
			}
		}
	}

	private static void doDup(Frame<BasicValue> frame, Method m, int dupWords, int skipWords) {
		int dwO = dupWords, swO = skipWords;
		int skipElements = 0, dupElements = 0;
		int originalStackSize = frame.getStackSize();
		int p = originalStackSize -1;
		while (dupWords > 0) {
			int size = frame.getStack(p).getSize();
			dupWords -= size;
			if (dupWords < 0) throw new IllegalStateException("can't dup "+dwO+" words evenly");
			dupElements++;
			p--; // next element
		}
		while (skipWords > 0) {
			int size = frame.getStack(p).getSize();
			skipWords -= size;
			if (skipWords < 0) throw new IllegalStateException("can't skip "+swO+" words evenly");
			skipElements++;
			p--; // next element
		}
		int expandedStackSize = originalStackSize + dupElements;
		int moveElements = dupElements + skipElements;
		/*
		dup 2 words, skip 1
		Start: x y z
		Expand: x y z _ _
		Move: _ _  x y z
		Copy: y z  x y z
		*/

		int moveStartRange = originalStackSize - moveElements;
		int moveEndRange = originalStackSize;
		int moveInto = expandedStackSize - moveElements;
		m.addStatement("std::move(stack+$l, stack+$l, stack+$l)", moveStartRange, moveEndRange, moveInto);
		int copyStartRange = expandedStackSize - dupElements;
		int copyEndRange = expandedStackSize;
		int copyInto = originalStackSize - moveElements;
		m.addStatement("std::copy(stack+$l, stack+$l, stack+$l)", copyStartRange, copyEndRange, copyInto);
	}
}