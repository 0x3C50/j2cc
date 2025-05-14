package me.x150.j2cc.util.simulation;

import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.input.DirectoryInputProvider;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.tree.resolver.Resolver;
import me.x150.j2cc.tree.resolver.UnionResolver;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.Printer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

@Log4j2
public class SimulatorInterpreter extends Interpreter<SimulatorValue> implements Opcodes {
	private static final Type I = Type.INT_TYPE;
	private static final Type D = Type.DOUBLE_TYPE;
	private static final Type F = Type.FLOAT_TYPE;
	private static final Type L = Type.LONG_TYPE;
	private final Workspace wsp;
	private final boolean simulateMethods;
	private final Object2BooleanMap<MethodIdentifier> purenessCache = new Object2BooleanOpenHashMap<>();
	private final Object2ObjectMap<MethodSimulatorFrame, MethodSimulatorReturnValue> methodSimulateResults = new Object2ObjectOpenHashMap<>();
	private final Deque<MethodSimulatorFrame> methodSimulateStack = new ArrayDeque<>();
	private int simulatorScoreRemaining;
	private boolean inSimulation = false;

	public SimulatorInterpreter(Workspace wsp, boolean simulateMethods) {
		super(Opcodes.ASM9);
		this.wsp = wsp;
		this.simulateMethods = simulateMethods;
	}

	private static SimulatorValue x2y(AbstractInsnNode insn, SimulatorValue val, Type x, Type y, Function<Object, Object> ifKnown) throws AnalyzerException {
		if (val.valueKnown() && !val.type().equals(x))
			throw new AnalyzerException(insn, x + " to " + y + " type isn't " + x + ": " + val.type());
		if (val.valueKnown()) return new SimulatorValue(y, true, ifKnown.apply(val.value()));
		else return new SimulatorValue(y, false, null);
	}

	private static int computeMaxLocals(final MethodNode method) {
		int maxLocals = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
		if ((method.access & Opcodes.ACC_STATIC) != 0) {
			maxLocals -= 1;
		}
		for (AbstractInsnNode insnNode : method.instructions) {
			if (insnNode instanceof VarInsnNode) {
				int local = ((VarInsnNode) insnNode).var;
				int size =
						(insnNode.getOpcode() == Opcodes.LLOAD
								|| insnNode.getOpcode() == Opcodes.DLOAD
								|| insnNode.getOpcode() == Opcodes.LSTORE
								|| insnNode.getOpcode() == Opcodes.DSTORE)
								? 2
								: 1;
				maxLocals = Math.max(maxLocals, local + size);
			} else if (insnNode instanceof IincInsnNode) {
				int local = ((IincInsnNode) insnNode).var;
				maxLocals = Math.max(maxLocals, local + 1);
			}
		}
		return maxLocals;
	}

	private static boolean canSimulateMethod0(Workspace wsp, MethodNode mn, Set<MethodNode> dejaVu) {
		Type retType = Type.getReturnType(mn.desc);
		if (!(retType.getSort() >= Type.BOOLEAN && retType.getSort() <= Type.DOUBLE || retType.equals(Type.getType(String.class)))) {
			return false; // cant produce return value
		}
		if (Modifier.isNative(mn.access)) return false; // well
		if (!dejaVu.add(mn))
			return true; // we've seen this method before. recursion alone is not a reason to disqualify this method tho
		for (AbstractInsnNode instruction : mn.instructions) {
			if (instruction instanceof FieldInsnNode) return false; // any field access is not pure
			if (instruction instanceof MethodInsnNode min) {
				if (min.getOpcode() != Opcodes.INVOKESTATIC) return false; // any method calls that are not static
				Workspace.ClassInfo ownerCl = wsp.get(min.owner);
				if (ownerCl == null) return false; // we dont know who this method belongs to so we cant check it
				Optional<MethodNode> any = ownerCl.node().methods.stream()
						.filter(f -> f.name.equals(min.name) && f.desc.equals(min.desc)).findAny();
				if (any.isEmpty()) return false; // we cant resolve the method so we cant check it
				MethodNode theMethod = any.get();
				if (!canSimulateMethod0(wsp, theMethod, dejaVu)) return false;
			}
			// really anything else flies
		}
		return true; // no previous guard failed, so we can say yeah
	}

	@Override
	public SimulatorValue newValue(Type type) {
		if (type == null) return SimulatorValue.TWO_WORD_2ND;
		return switch (type.getSort()) {
			case Type.VOID -> null;
			case Type.INT, Type.SHORT, Type.CHAR, Type.BYTE, Type.BOOLEAN -> new SimulatorValue(I, false, null);
			case Type.LONG -> new SimulatorValue(L, false, null);
			case Type.DOUBLE -> new SimulatorValue(D, false, null);
			case Type.FLOAT -> new SimulatorValue(F, false, null);
			case Type.ARRAY, Type.OBJECT -> new SimulatorValue(type, false, null);
			default -> throw new IllegalStateException("Unexpected value: " + type.getSort());
		};
	}

	@Override
	public SimulatorValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
		return switch (type.getSort()) {
			case Type.VOID -> null;
			case Type.INT, Type.SHORT, Type.CHAR, Type.BYTE, Type.BOOLEAN -> new SimulatorValue(I, false, null);
			case Type.LONG -> new SimulatorValue(L, false, null);
			case Type.DOUBLE -> new SimulatorValue(D, false, null);
			case Type.FLOAT -> new SimulatorValue(F, false, null);
			case Type.ARRAY, Type.OBJECT -> new SimulatorValue(type, false, null);
			default -> throw new IllegalStateException("Unexpected value: " + type.getSort());
		};
	}

	@Override
	public SimulatorValue newEmptyValue(int local) {
		return SimulatorValue.UNINITIALIZED;
	}

	@Override
	public SimulatorValue newOperation(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		return switch (op) {
			case ACONST_NULL -> SimulatorValue.NULL;

			case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 ->
					new SimulatorValue(Type.INT_TYPE, true, op - ICONST_0);
			case LCONST_0, LCONST_1 -> new SimulatorValue(Type.LONG_TYPE, true, (long) (op - LCONST_0));
			case FCONST_0, FCONST_1, FCONST_2 -> new SimulatorValue(Type.FLOAT_TYPE, true, (float) (op - FCONST_0));
			case DCONST_0, DCONST_1 -> new SimulatorValue(Type.DOUBLE_TYPE, true, (double) (op - DCONST_0));

			case BIPUSH, SIPUSH -> new SimulatorValue(Type.INT_TYPE, true, ((IntInsnNode) insn).operand);
			case LDC -> {
				LdcInsnNode li = (LdcInsnNode) insn;
				Object cst = li.cst;
				yield switch (cst) {
					case Integer i -> new SimulatorValue(Type.INT_TYPE, true, i);
					case Float i -> new SimulatorValue(Type.FLOAT_TYPE, true, i);
					case Long i -> new SimulatorValue(Type.LONG_TYPE, true, i);
					case Double i -> new SimulatorValue(Type.DOUBLE_TYPE, true, i);
					case String i -> new SimulatorValue(Type.getType(String.class), true, i);
					case Type i when i.getSort() == Type.OBJECT || i.getSort() == Type.ARRAY ->
							new SimulatorValue(Type.getType(Class.class), true, i); // A.class, A[].class
					case Type i when i.getSort() == Type.METHOD ->
							new SimulatorValue(Type.getType(MethodType.class), true, i);
					case Handle h -> new SimulatorValue(Type.getType(MethodHandle.class), true, h);
					case ConstantDynamic condy -> new SimulatorValue(Type.getType(condy.getDescriptor()), false, null);
					default -> throw new IllegalStateException();
				};
			}
			case GETSTATIC -> new SimulatorValue(Type.getType(((FieldInsnNode) insn).desc), false, null);
			case NEW -> new SimulatorValue(Type.getObjectType(((TypeInsnNode) insn).desc), false, null);
			default -> throw new IllegalStateException("Unimplemented: " + op);
		};
	}

	@Override
	public SimulatorValue copyOperation(AbstractInsnNode insn, SimulatorValue value) {
		return value;
	}

	@SneakyThrows
	@Override
	public SimulatorValue unaryOperation(AbstractInsnNode insn, SimulatorValue value) {
		int op = insn.getOpcode();
		return switch (op) {
			case INEG -> {
				if (value.valueKnown() && value.type() != Type.INT_TYPE)
					throw new AnalyzerException(insn, "INEG type isn't int: " + value.type());
				if (value.valueKnown()) yield new SimulatorValue(value.type(), true, -((int) value.value()));
				else yield new SimulatorValue(value.type(), false, null);
			}
			case IINC -> {
				if (value.valueKnown() && value.type() != Type.INT_TYPE)
					throw new AnalyzerException(insn, "IINC type isn't int: " + value.type());
				if (value.valueKnown())
					yield new SimulatorValue(value.type(), true, ((int) value.value()) + ((IincInsnNode) insn).incr);
				else yield new SimulatorValue(value.type(), false, null);
			}
			case LNEG -> {
				if (value.valueKnown() && value.type() != Type.LONG_TYPE)
					throw new AnalyzerException(insn, "LNEG type isn't long: " + value.type());
				if (value.valueKnown()) yield new SimulatorValue(value.type(), true, -((long) value.value()));
				else yield new SimulatorValue(value.type(), false, null);
			}
			case FNEG -> {
				if (value.valueKnown() && value.type() != Type.FLOAT_TYPE)
					throw new AnalyzerException(insn, "FNEG type isn't float: " + value.type());
				if (value.valueKnown()) yield new SimulatorValue(value.type(), true, -((float) value.value()));
				else yield new SimulatorValue(value.type(), false, null);
			}
			case DNEG -> {
				if (value.valueKnown() && value.type() != Type.DOUBLE_TYPE)
					throw new AnalyzerException(insn, "DNEG type isn't double: " + value.type());
				if (value.valueKnown()) yield new SimulatorValue(value.type(), true, -((double) value.value()));
				else yield new SimulatorValue(value.type(), false, null);
			}
			case I2L -> x2y(insn, value, I, L, x -> ((Integer) x).longValue());
			case I2F -> x2y(insn, value, I, F, x -> ((Integer) x).floatValue());
			case I2D -> x2y(insn, value, I, D, x -> ((Integer) x).doubleValue());

			case L2I -> x2y(insn, value, L, I, x -> ((Long) x).intValue());
			case L2F -> x2y(insn, value, L, F, x -> ((Long) x).floatValue());
			case L2D -> x2y(insn, value, L, D, x -> ((Long) x).doubleValue());

			case F2I -> x2y(insn, value, F, I, x -> ((Float) x).intValue());
			case F2L -> x2y(insn, value, F, L, x -> ((Float) x).longValue());
			case F2D -> x2y(insn, value, F, D, x -> ((Float) x).doubleValue());

			case D2I -> x2y(insn, value, D, I, x -> ((Double) x).intValue());
			case D2L -> x2y(insn, value, D, L, x -> ((Double) x).longValue());
			case D2F -> x2y(insn, value, D, F, x -> ((Double) x).floatValue());

			case I2B, I2C, I2S -> x2y(insn, value, I, I, x -> x);

			case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, TABLESWITCH, LOOKUPSWITCH, IRETURN, LRETURN, FRETURN, DRETURN,
				 ARETURN, PUTSTATIC, ATHROW, MONITORENTER, MONITOREXIT, IFNULL, IFNONNULL -> null;

			case GETFIELD -> new SimulatorValue(Type.getType(((FieldInsnNode) insn).desc), false, null);

			case NEWARRAY -> {
				int type = ((IntInsnNode) insn).operand;
				Type arrayType = switch (type) {
					case 4 -> Type.BOOLEAN_TYPE;
					case 5 -> Type.CHAR_TYPE;
					case 6 -> F;
					case 7 -> D;
					case 8 -> Type.BYTE_TYPE;
					case 9 -> Type.SHORT_TYPE;
					case 10 -> I;
					case 11 -> L;
					default -> throw new AnalyzerException(insn, "Invalid NEWARRAY type: " + type);
				};
				if (value.valueKnown()) {
					int dims = (int) value.value();
					if (dims >= 0) {
						yield new SimulatorValue(Type.getType("[" + arrayType.getDescriptor()), true, dims);
					}
				}
				yield new SimulatorValue(Type.getType("[" + arrayType.getDescriptor()), false, null);
			}

			case ANEWARRAY -> {
				Type t = Type.getObjectType(((TypeInsnNode) insn).desc);
				if (value.valueKnown()) {
					int dims = (int) value.value();
					if (dims >= 0) {
						yield new SimulatorValue(Type.getType("[" + t.getDescriptor()), true, dims);
					}
				}
				yield new SimulatorValue(Type.getType("[" + t.getDescriptor()), false, null);
			}

			case ARRAYLENGTH -> {
				if (value.valueKnown() && value != SimulatorValue.NULL) {
					assert value.type().getSort() == Type.ARRAY;
					int theValue = value.valueAs(insn, Integer.class);
					yield new SimulatorValue(I, true, theValue);
				}
				yield new SimulatorValue(I, false, null);
			}
			case CHECKCAST ->
					new SimulatorValue(Type.getObjectType(((TypeInsnNode) insn).desc), value.valueKnown(), value.value());
			case INSTANCEOF -> new SimulatorValue(Type.BOOLEAN_TYPE, false, null);
			default -> throw new IllegalStateException();
		};
	}

	@Override
	public SimulatorValue binaryOperation(AbstractInsnNode insn, SimulatorValue value1, SimulatorValue value2) throws AnalyzerException {
		int op = insn.getOpcode();
		return switch (op) {
			case AALOAD ->
					value1.type() != null && value1.type().getSort() == Type.ARRAY ? new SimulatorValue(Type.getType(value1.type().getDescriptor().substring(1)), false, null) :
							new SimulatorValue(Util.OBJECT_TYPE, false, null);
			case IALOAD, LALOAD, FALOAD, DALOAD, BALOAD, CALOAD, SALOAD ->
					new SimulatorValue(new Type[]{I, L, F, D, null, I, I, I}[op - IALOAD], false, null);
			case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(I, false, null);
				if (value1.type() != I || value2.type() != I)
					throw new AnalyzerException(insn, "expected 2 ints, got " + value1.type() + ", " + value2.type());
				int v1 = (int) value1.value();
				int v2 = (int) value2.value();
				if (op == IDIV && v2 == 0) yield new SimulatorValue(I, false, null);
				int val = switch (op) {
					case IXOR -> v2 ^ v1;
					case ISHL -> v1 << v2;
					case ISHR -> v1 >> v2;
					case IUSHR -> v1 >>> v2;
					case IADD -> v1 + v2;
					case IMUL -> v1 * v2;
					case IDIV -> v1 / v2;
					case IREM -> v1 % v2;
					case IAND -> v1 & v2;
					case IOR -> v1 | v2;
					case ISUB -> v1 - v2;
					default -> throw new RuntimeException();
				};
				yield new SimulatorValue(I, true, val);
			}

			case DADD, DSUB, DMUL, DDIV, DREM -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(D, false, null);
				if (value1.type() != D || value2.type() != D)
					throw new AnalyzerException(insn, "expected 2 doubles, got " + value1.type() + ", " + value2.type());
				double v1 = (double) value1.value();
				double v2 = (double) value2.value();
				double val = switch (op) {
					case DADD -> v1 + v2;
					case DMUL -> v1 * v2;
					case DDIV -> v1 / v2;
					case DREM -> v1 % v2;
					case DSUB -> v1 - v2;
					default -> throw new RuntimeException();
				};
				yield new SimulatorValue(D, true, val);
			}
			case FADD, FSUB, FMUL, FDIV, FREM -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(F, false, null);
				if (value1.type() != F || value2.type() != F)
					throw new AnalyzerException(insn, "expected 2 floats, got " + value1.type() + ", " + value2.type());
				float v1 = (float) value1.value();
				float v2 = (float) value2.value();
				float val = switch (op) {
					case FADD -> v1 + v2;
					case FMUL -> v1 * v2;
					case FDIV -> v1 / v2;
					case FREM -> v1 % v2;
					case FSUB -> v1 - v2;
					default -> throw new RuntimeException();
				};
				yield new SimulatorValue(F, true, val);
			}

			case LSHL, LSHR, LUSHR -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(L, false, null);
				if (value1.type() != L || value2.type() != I)
					throw new AnalyzerException(insn, "expected long, int but got " + value1.type() + ", " + value2.type());
				long v1 = (long) value1.value();
				int v2 = (int) value2.value();
				long val = switch (op) {
					case LSHL -> v1 << v2;
					case LSHR -> v1 >> v2;
					case LUSHR -> v1 >>> v2;
					default -> throw new RuntimeException();
				};
				yield new SimulatorValue(L, true, val);
			}
			case LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(L, false, null);
				if (value1.type() != L || value2.type() != L)
					throw new AnalyzerException(insn, "expected 2 longs, got " + value1.type() + ", " + value2.type());
				long v1 = (long) value1.value();
				long v2 = (long) value2.value();
				long val = switch (op) {
					case LXOR -> v2 ^ v1;
					case LADD -> v1 + v2;
					case LMUL -> v1 * v2;
					case LDIV -> v1 / v2;
					case LREM -> v1 % v2;
					case LAND -> v1 & v2;
					case LOR -> v1 | v2;
					case LSUB -> v1 - v2;
					default -> throw new RuntimeException();
				};
				yield new SimulatorValue(L, true, val);
			}
			case LCMP -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(I, false, null);
				if (value1.type() != L || value2.type() != L)
					throw new AnalyzerException(insn, "expected 2 longs, got " + value1.type() + ", " + value2.type());
				long v1 = (long) value1.value();
				long v2 = (long) value2.value();
				yield new SimulatorValue(I, true, Long.compare(v1, v2));
			}
			case FCMPG, FCMPL -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(I, false, null);
				if (value1.type() != F || value2.type() != F)
					throw new AnalyzerException(insn, "expected 2 floats, got " + value1.type() + ", " + value2.type());
				float v1 = (float) value1.value();
				float v2 = (float) value2.value();
				int res;
				if (v1 > v2) res = 1;
				else if (v1 == v2) res = 0;
				else if (v1 < v2) res = -1;
				else res = (op == FCMPG ? 1 : -1); // at least one NaN
				yield new SimulatorValue(I, true, res);
			}
			case DCMPG, DCMPL -> {
				if (!value1.valueKnown() || !value2.valueKnown()) yield new SimulatorValue(I, false, null);
				if (value1.type() != D || value2.type() != D)
					throw new AnalyzerException(insn, "expected 2 doubles, got " + value1.type() + ", " + value2.type());
				double v1 = (double) value1.value();
				double v2 = (double) value2.value();
				int res;
				if (v1 > v2) res = 1;
				else if (v1 == v2) res = 0;
				else if (v1 < v2) res = -1;
				else res = (op == DCMPG ? 1 : -1); // at least one NaN
				yield new SimulatorValue(I, true, res);
			}
			case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE, PUTFIELD ->
					null;
			default -> throw new IllegalStateException();
		};
	}

	@Override
	public SimulatorValue ternaryOperation(AbstractInsnNode insn, SimulatorValue value1, SimulatorValue value2, SimulatorValue value3) {
		// asm DOESNT FUCKING TRACK the array changes we make here so THIS IS ALL FOR FUCKING NOTHING
		// WHAT THE FUCK????????????????????????????????????
		return null;
//		if (!value1.valueKnown() || value2.valueKnown() || value3.valueKnown()) return null; // cant do shit
//		int op = insn.getOpcode();
//		int theIndex = value2.valueAs(insn, Integer.class);
//		if (theIndex < 0) return null;
//		return switch (op) {
//			case IASTORE -> {
//				int[] theArrayInst = value1.valueAs(insn, int[].class).clone();
//				if (theIndex >= theArrayInst.length) yield null;
//				int theValue = value3.valueAs(insn, Integer.class);
//				theArrayInst[theIndex] = theValue;
//				yield new SimulatorValue(Type.getType("[I"), true, theArrayInst);
//			}
//			case LASTORE -> {
//				long[] theArrayInst = value1.valueAs(insn, long[].class).clone();
//				if (theIndex >= theArrayInst.length) yield null;
//				long theValue = value3.valueAs(insn, Long.class);
//				theArrayInst[theIndex] = theValue;
//				yield new SimulatorValue(Type.getType("[J"), true, theArrayInst);
//			}
//			case FASTORE -> {
//				float[] theArrayInst = value1.valueAs(insn, float[].class).clone();
//				if (theIndex >= theArrayInst.length) yield null;
//				float theValue = value3.valueAs(insn, Float.class);
//				theArrayInst[theIndex] = theValue;
//				yield new SimulatorValue(Type.getType("[F"), true, theArrayInst);
//			}
//			case DASTORE -> {
//				double[] theArrayInst = value1.valueAs(insn, double[].class).clone();
//				if (theIndex >= theArrayInst.length) yield null;
//				double theValue = value3.valueAs(insn, Double.class);
//				theArrayInst[theIndex] = theValue;
//				yield new SimulatorValue(Type.getType("[D"), true, theArrayInst);
//			}
//			case BASTORE -> {
//				// boolean OR byte, i will fucking kill the retards who made this instruction
//				if (value3.type().equals(Type.BOOLEAN_TYPE)) {
//					boolean[] theArrayInst = value1.valueAs(insn, boolean[].class).clone();
//					if (theIndex >= theArrayInst.length) yield null;
//					boolean theValue = value3.valueAs(insn, Integer.class) != 0;
//					theArrayInst[theIndex] = theValue;
//					yield new SimulatorValue(Type.getType("[Z"), true, theArrayInst);
//				} else {
//					byte[] theArrayInst = value1.valueAs(insn, byte[].class).clone();
//					if (theIndex >= theArrayInst.length) yield null;
//					byte theValue = value3.valueAs(insn, Integer.class).byteValue();
//					theArrayInst[theIndex] = theValue;
//					yield new SimulatorValue(Type.getType("[B"), true, theArrayInst);
//				}
//			}
//			case CASTORE ->{
//				char[] theArrayInst = value1.valueAs(insn, char[].class).clone();
//				if (theIndex >= theArrayInst.length) yield null;
//				char theValue = ((char) value3.valueAs(insn, Integer.class).intValue());
//				theArrayInst[theIndex] = theValue;
//				yield new SimulatorValue(Type.getType("[C"), true, theArrayInst);
//			}
//			case SASTORE -> {
//				short[] theArrayInst = value1.valueAs(insn, short[].class).clone();
//				if (theIndex >= theArrayInst.length) yield null;
//				short theValue = value3.valueAs(insn, Integer.class).shortValue();
//				theArrayInst[theIndex] = theValue;
//				yield new SimulatorValue(Type.getType("[S"), true, theArrayInst);
//			}
//			default -> null; // includes astore
//		};
	}

	@Override
	public SimulatorValue naryOperation(AbstractInsnNode insn, List<? extends SimulatorValue> values) throws AnalyzerException {
		int op = insn.getOpcode();
		return switch (op) {
			case INVOKESTATIC -> {
				MethodInsnNode min = (MethodInsnNode) insn;
				Workspace.ClassInfo w;
				MethodNode targetNode;
				// this is the most ingenious yet shittiest guard i have ever written
				if (!simulateMethods
						|| (w = wsp.get(min.owner)) == null
						|| (targetNode = w.node().methods.stream().filter(f -> f.name.equals(min.name) && f.desc.equals(min.desc)).findAny().orElse(null)) == null
						|| !canSimulateMethod(min.owner, targetNode)) {
					// cant simulate
					yield new SimulatorValue(Type.getReturnType(min.desc), false, null);
				}
				Object[] theArgs = values.stream().map(SimulatorValue::value).toArray();
				Boolean[] dogshitArray = values.stream().map(SimulatorValue::valueKnown).toArray(Boolean[]::new);
				boolean[] argKnown = new boolean[dogshitArray.length];
				for (int i = 0; i < dogshitArray.length; i++) {
					argKnown[i] = dogshitArray[i];
				}
				// prepare simulator and clear previous state, if any
				boolean areWeTheOuterFrame = !inSimulation;
				if (areWeTheOuterFrame)
					enterSimulateMethod(1000); // only reset state if we're the outmost frame that is about to call the simulation in
				MethodSimulatorFrame msf = new MethodSimulatorFrame(new MethodIdentifier(min.owner, min.name, min.desc), argKnown, theArgs);
				MethodSimulatorReturnValue simulatedValue;
				if (methodSimulateStack.contains(msf)) {
					// this exact call has appeared before. this means that this is infinite recursion
					// we can immediately exit, we cant simulate this and it wont change
//					log.debug("Recursion detected:\n{}", methodSimulateStack.stream().map(MethodSimulatorFrame::toString).collect(Collectors.joining("\n")));
					simulatorScoreRemaining = 0;
					simulatedValue = MethodSimulatorReturnValue.UNK;
				} else {
					methodSimulateStack.push(msf); // enter simulation
					simulatedValue = cachedSimRun(w, targetNode, argKnown, theArgs);
					methodSimulateStack.pop(); // exit simulation of this method
				}
				if (areWeTheOuterFrame) exitSimulation(); // and only exit the simulation if we called it in
				if (simulatedValue.t != MethodSimulatorReturnValue.Type.RETURNED) {
					// this method completes abnormally, such as throwing an exception, or we cant compute the value
					yield new SimulatorValue(Type.getReturnType(min.desc), false, null);
				}
				Object actualValue = simulatedValue.value;
				yield new SimulatorValue(Type.getReturnType(min.desc), true, actualValue);
			}
			case INVOKEVIRTUAL, INVOKESPECIAL, INVOKEINTERFACE -> {
				MethodInsnNode min = (MethodInsnNode) insn;
				yield new SimulatorValue(Type.getReturnType(min.desc), false, null);
			}
			case INVOKEDYNAMIC ->
					new SimulatorValue(Type.getReturnType(((InvokeDynamicInsnNode) insn).desc), false, null);
			case MULTIANEWARRAY -> new SimulatorValue(Type.getType(((MultiANewArrayInsnNode) insn).desc), false, null);
			default -> throw new IllegalStateException();
		};
	}

	private MethodSimulatorReturnValue cachedSimRun(Workspace.ClassInfo w, MethodNode targetNode, boolean[] argsKnown, Object[] theArgs) throws AnalyzerException {
		MethodSimulatorFrame frame = new MethodSimulatorFrame(new MethodIdentifier(w.node().name, targetNode.name, targetNode.desc), argsKnown, theArgs);
		if (methodSimulateResults.containsKey(frame)) {

			MethodSimulatorReturnValue methodSimulatorReturnValue = methodSimulateResults.get(frame);
//			log.debug("Using cached result for {}: {}", frame, methodSimulatorReturnValue);
			return methodSimulatorReturnValue;
		}
		MethodSimulatorReturnValue res = simulatorRun(w, targetNode, argsKnown, theArgs);
		log.debug("Uncached {}: {}", frame, res);
		methodSimulateResults.put(frame, res);
		return res;
	}

	private MethodSimulatorReturnValue simulatorRun(Workspace.ClassInfo w, MethodNode targetNode, boolean[] argsKnown, Object[] theArgs) throws AnalyzerException {
//		log.trace("Entering simulate {}.{}{}", w.node().name, targetNode.name, targetNode.desc);
//		MethodSimulatorFrame msf = new MethodSimulatorFrame(new MethodIdentifier(w.node().name, targetNode.name, targetNode.desc),
//				argsKnown, theArgs);
		// assumptions at this point:
		// - method is pure
		// - method is able to be simulated
		// - method is static
		Frame<SimulatorValue> currentFrame = new Frame<>(computeMaxLocals(targetNode), -1);
		int nArgs = Type.getArgumentCount(targetNode.desc);
		Type[] tArgs = Type.getArgumentTypes(targetNode.desc);
		for (int localPtr = 0, i = 0; i < nArgs; i++) {
			Type type = tArgs[i];
			currentFrame.setLocal(localPtr, new SimulatorValue(type, argsKnown[i], theArgs[i]));
			localPtr += type.getSize();
		}
		int ip = 0;
		while (true) {
			if (ip >= targetNode.instructions.size()) {
				log.trace("IP {}: Overflew instruction list with size {}", ip, targetNode.instructions.size());
			}
			AbstractInsnNode currentInsn = targetNode.instructions.get(ip++);
			final int op = currentInsn.getOpcode();
			if (op == -1) {
				log.trace("IP {}: {} (skip)", ip - 1, currentInsn.getClass().getSimpleName());
				continue; // synthetic node (something from asm), we can skip
			}
			if (simulatorScoreRemaining <= 0) {
				// we're done here
				log.trace("Complexity limit reached, popping frame");
				return MethodSimulatorReturnValue.UNK;
			}
			simulatorScoreRemaining--;
			switch (currentInsn) {
				case JumpInsnNode ji -> {
					int targetIp = targetNode.instructions.indexOf(ji.label) + 1;
					log.trace("IP {}: {} -> {}", ip - 1, Printer.OPCODES[op], targetIp);
					boolean doJump;
					switch (op) {
						case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
							SimulatorValue top = currentFrame.pop();
							if (!top.valueKnown())
								return MethodSimulatorReturnValue.UNK; // we cant figure out this jump
							assert top.type().getSort() == Type.INT : "top " + top + " on IFXX is not an int";
							int x = (int) top.value();
							doJump = switch (op) {
								case IFEQ -> x == 0;
								case IFNE -> x != 0;
								case IFLT -> x < 0;
								case IFGT -> x > 0;
								case IFLE -> x <= 0;
								case IFGE -> x >= 0;
								// stupid fucking javac, op is final and the only possible values are listed above
								default -> throw new IllegalStateException("Unexpected value: " + op);
							};
						}
						case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
							SimulatorValue top = currentFrame.pop();
							SimulatorValue bot = currentFrame.pop();
							if (!top.valueKnown() || !bot.valueKnown())
								return MethodSimulatorReturnValue.UNK; // we cant figure out this jump
							assert top.type().getSort() == Type.INT : "top " + top + " on IF_ICMPXX is not an int";
							assert bot.type().getSort() == Type.INT : "top_2nd " + bot + " on IF_ICMPXX is not an int";
							int value2 = (int) top.value();
							int value1 = (int) bot.value();
							doJump = switch (op) {
								case IF_ICMPEQ -> value1 == value2;
								case IF_ICMPNE -> value1 != value2;
								case IF_ICMPLT -> value1 < value2;
								case IF_ICMPGT -> value1 > value2;
								case IF_ICMPLE -> value1 <= value2;
								case IF_ICMPGE -> value1 >= value2;
								// stupid fucking javac, op is final and the only possible values are listed above
								default -> throw new IllegalStateException("Unexpected value: " + op);
							};
						}
						case IF_ACMPEQ, IF_ACMPNE -> {
							return MethodSimulatorReturnValue.UNK; // uncrackable jump
						}
						case GOTO -> doJump = true;
						case JSR -> throw new UnsupportedOperationException();
						case IFNULL, IFNONNULL -> {
							SimulatorValue top = currentFrame.pop();
							if (!top.valueKnown()) return MethodSimulatorReturnValue.UNK; // cant crack
							assert top.type().getSort() >= Type.ARRAY : "top " + top + " on IF(NON)NULL not assignable to java.lang.Object";
							// if its null (true) and we have IFNULL (true) -> true
							// if its not null (false) and we have non-IFNULL (false) -> true
							// everything else -> false
							doJump = (op == IFNULL) == (top.value() == null);
						}
						default -> throw Util.unimplemented(op);
					}
					if (doJump) {
						// we go
						ip = targetIp;
					}
					// alright we processed this insn and either jumped or didnt, skip default handler
					continue;
				}
				case InsnNode ignored when op >= Opcodes.IRETURN && op <= Opcodes.ARETURN -> {
					log.trace("IP {}: {}", ip - 1, Util.stringifyInstruction(currentInsn, Map.of()));
					SimulatorValue retValue = currentFrame.pop();
					if (!retValue.valueKnown()) return MethodSimulatorReturnValue.UNK; // oh well
					return new MethodSimulatorReturnValue(MethodSimulatorReturnValue.Type.RETURNED, retValue.value());
				}
				case InsnNode ignored when op == Opcodes.ATHROW -> {
					log.trace("IP {}: {}", ip - 1, Util.stringifyInstruction(currentInsn, Map.of()));
					return new MethodSimulatorReturnValue(MethodSimulatorReturnValue.Type.THROWN, null); // throws
				}
				case LookupSwitchInsnNode lks -> {
					SimulatorValue top = currentFrame.pop();
					if (!top.valueKnown()) return MethodSimulatorReturnValue.UNK; // we cant figure out this jump
					assert top.type().getSort() == Type.INT : "top " + top + " on LKSWITCH is not an int";
					log.trace("IP {}: {}", ip - 1, Util.stringifyInstruction(currentInsn, Map.of()));
					int x = (int) top.value();
					int indexLabel = lks.keys.indexOf(x);
					ip = targetNode.instructions.indexOf(indexLabel == -1 ? lks.dflt : lks.labels.get(indexLabel));
				}
				case TableSwitchInsnNode tbs -> {
					SimulatorValue top = currentFrame.pop();
					if (!top.valueKnown()) return MethodSimulatorReturnValue.UNK; // we cant figure out this jump
					assert top.type().getSort() == Type.INT : "top " + top + " on TBSWITCH is not an int";
					log.trace("IP {}: {}", ip - 1, Util.stringifyInstruction(currentInsn, Map.of()));
					int x = (int) top.value();
					if (x < tbs.min || x > tbs.max) ip = targetNode.instructions.indexOf(tbs.dflt);
					else {
						int indexLabel = x - tbs.min;
						ip = targetNode.instructions.indexOf(tbs.labels.get(indexLabel));
					}
				}
				default -> {
				}
			}
			log.trace("IP {}: {}", ip - 1, Util.stringifyInstruction(currentInsn, Map.of()));
			currentFrame.execute(currentInsn, this);
		}
	}

	private void enterSimulateMethod(int maxScore) {
		this.simulatorScoreRemaining = maxScore;
		this.methodSimulateStack.clear();
		inSimulation = true; // we're live
	}

	private void exitSimulation() {
		inSimulation = false;
	}

	private boolean canSimulateMethod(String owner, MethodNode mn) {
		return purenessCache.computeIfAbsent(new MethodIdentifier(owner, mn.name, mn.desc), _ -> canSimulateMethod0(wsp, mn, new HashSet<>()));
	}

	@Override
	public void returnOperation(AbstractInsnNode insn, SimulatorValue value, SimulatorValue expected) {
		// noop
	}

	@Override
	public SimulatorValue merge(SimulatorValue value1, SimulatorValue value2) {
		return value1.merge(wsp, value2);
	}

	record MethodIdentifier(String owner, String name, String desc) {
		@Override
		public String toString() {
			return owner + "." + name + desc;
		}
	}

	record MethodSimulatorFrame(MethodIdentifier who, boolean[] knownArgs, Object[] args) {
		@Override
		public String toString() {
			StringBuilder f = new StringBuilder("Frame ");
			f.append(this.who).append("(");
			for (int i = 0; i < this.knownArgs.length; i++) {
				f.append(this.knownArgs[i] ? this.args[i] : "?").append(", ");
			}
			f.delete(f.length() - 2, f.length());
			f.append(")");
			return f.toString();
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof MethodSimulatorFrame(MethodIdentifier who1, boolean[] knownArgs1, Object[] args1))) return false;
			if (!Objects.equals(this.who, who1)) return false;
			if (!Arrays.equals(this.knownArgs, knownArgs1)) return false;
			for (int i = 0; i < knownArgs.length; i++) {
				if (knownArgs[i] && !Objects.equals(this.args[i], args1[i])) return false;
			}
			return true;
		}

		@Override
		public int hashCode() {
			int result = who.hashCode();
			result = 31 * result + Arrays.hashCode(knownArgs);
			result = 31 * result + Arrays.hashCode(args);
			return result;
		}
	}

	record MethodSimulatorReturnValue(Type t, Object value) {
		public static MethodSimulatorReturnValue UNK = new MethodSimulatorReturnValue(Type.CANT_SIMULATE, null);

		enum Type {
			RETURNED, THROWN, CANT_SIMULATE
		}
	}
}