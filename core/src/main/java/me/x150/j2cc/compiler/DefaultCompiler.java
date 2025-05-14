package me.x150.j2cc.compiler;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.compiler.handler.*;
import me.x150.j2cc.conf.Configuration;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.cppwriter.SourceBuilder;
import me.x150.j2cc.exc.CompilationFailure;
import me.x150.j2cc.tree.Remapper;
import me.x150.j2cc.tree.SmartClassWriter;
import me.x150.j2cc.util.StringCollector;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Log4j2
public class DefaultCompiler implements Opcodes, CompilerEngine {
	public static final Map<Type, String> typeToName = new HashMap<>() {{
		put(Type.BOOLEAN_TYPE, "Boolean");
		put(Type.BYTE_TYPE, "Byte");
		put(Type.CHAR_TYPE, "Char");
		put(Type.SHORT_TYPE, "Short");
		put(Type.INT_TYPE, "Int");
		put(Type.LONG_TYPE, "Long");
		put(Type.FLOAT_TYPE, "Float");
		put(Type.DOUBLE_TYPE, "Double");
		put(Type.VOID_TYPE, "Void");
	}};
	public static final Map<Type, String> jTypeMap = new HashMap<>() {{
		put(Type.BOOLEAN_TYPE, "jboolean");
		put(Type.BYTE_TYPE, "jbyte");
		put(Type.CHAR_TYPE, "jchar");
		put(Type.SHORT_TYPE, "jshort");
		put(Type.INT_TYPE, "jint");
		put(Type.LONG_TYPE, "jlong");
		put(Type.FLOAT_TYPE, "jfloat");
		put(Type.DOUBLE_TYPE, "jdouble");
		put(Type.VOID_TYPE, "void");
		put(Type.getObjectType("java/lang/Class"), "jclass");
		put(Type.getObjectType("java/lang/String"), "jstring");
		put(Type.getObjectType("java/lang/Throwable"), "jthrowable");
		put(Type.getType("[Z"), "jbooleanArray");
		put(Type.getType("[B"), "jbyteArray");
		put(Type.getType("[C"), "jcharArray");
		put(Type.getType("[S"), "jshortArray");
		put(Type.getType("[I"), "jintArray");
		put(Type.getType("[J"), "jlongArray");
		put(Type.getType("[F"), "jfloatArray");
		put(Type.getType("[D"), "jdoubleArray");
	}};
	public static final Map<Class<? extends AbstractInsnNode>, InsnHandler<?>> insnHandlers = new HashMap<>() {{
		put(FieldInsnNode.class, new FieldInsnNodeHandler());
		put(FrameNode.class, new NoopHandler());
		put(IincInsnNode.class, new IincInsnNodeHandler());
		put(InsnNode.class, new InsnNodeHandler());
		put(IntInsnNode.class, new IntInsnNodeHandler());
		put(InvokeDynamicInsnNode.class, new InvokeDynamicHandler());
		put(JumpInsnNode.class, new JumpInsnNodeHandler());
		put(LabelNode.class, new LabelNodeHandler());
		put(LdcInsnNode.class, new LdcInsnHandler());
		put(LineNumberNode.class, new NoopHandler());
		put(LookupSwitchInsnNode.class, new LookupSwitchInsnHandler());
		put(MethodInsnNode.class, new MethodInsnHandler());
		put(MultiANewArrayInsnNode.class, new MultiANewArrayInsnHandler());
		put(TableSwitchInsnNode.class, new TableSwitchInsnHandler());
		put(TypeInsnNode.class, new TypeInsnNodeHandler());
		put(VarInsnNode.class, new VarInsnNodeHandler());
	}};

	private static final Path dump = Path.of("dump");

	@SneakyThrows
	private static MethodNode preprocessMethod(Context ctx, MethodNode node) {
		MethodNode repl = Util.emptyCopyOf(node);
		LocalVariablesSorter sorter = new LocalVariablesSorter(node.access, node.desc, repl);
		node.accept(sorter);
		InsnList insns = repl.instructions;
		for (int idx = 0; idx < insns.size(); idx++) {
			AbstractInsnNode instruction = insns.get(idx);
			if (instruction instanceof MethodInsnNode min) {
				String owner = min.owner;
				if (min.getOpcode() == INVOKEVIRTUAL && owner.equals(Type.getInternalName(VarHandle.class))) {
					String desc = min.desc;
					Type descType = Type.getMethodType(desc);
					InsnList replacement = new InsnList();
					String accessModeName = null;
					for (VarHandle.AccessMode value : VarHandle.AccessMode.values()) {
						if (value.methodName().equals(min.name)) {
							accessModeName = value.name();
							break;
						}
					}
					if (accessModeName == null) continue; // this isn't a method we can transpile
					Type[] argTypes = Type.getArgumentTypes(desc);
					int[] argSlots = new int[argTypes.length];
					for (int i = argTypes.length - 1; i >= 0; i--) {
						Type argType = argTypes[i];
						int i1 = sorter.newLocal(argType);
						argSlots[i] = i1;
						replacement.add(new VarInsnNode(argType.getOpcode(ISTORE), i1));
					}
					replacement.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(VarHandle.AccessMode.class), accessModeName, Type.getDescriptor(VarHandle.AccessMode.class)));
					replacement.add(new LdcInsnNode(descType));
					replacement.add(Util.invoke(Opcodes.INVOKESTATIC, MethodHandles.class.getMethod("varHandleInvoker", VarHandle.AccessMode.class, MethodType.class)));
					replacement.add(new InsnNode(SWAP)); // swap handle and original var handle around; saves one insn
					for (int i = 0; i < argTypes.length; i++) {
						int argSlot = argSlots[i];
						replacement.add(new VarInsnNode(argTypes[i].getOpcode(ILOAD), argSlot));
					}
					List<Type> args = new ArrayList<>();
					args.add(Type.getType(VarHandle.class));
					args.addAll(Arrays.asList(argTypes));
					MethodInsnNode f = new MethodInsnNode(
							INVOKEVIRTUAL,
							Type.getInternalName(MethodHandle.class),
							"invoke",
							Type.getMethodDescriptor(Type.getReturnType(desc), args.toArray(Type[]::new))
					);
					replacement.add(f);
					insns.insertBefore(instruction, replacement);
					insns.remove(instruction);
				}
				if (owner.equals("java/lang/invoke/MethodHandle") && (min.name.equals("invoke") || min.name.equals("invokeExact"))) {
					InsnList replacement = new InsnList();
					Type[] args = Type.getArgumentTypes(min.desc);
					int[] mappedArguments = new int[args.length];
					for (int i = args.length - 1; i >= 0; i--) {
						Type arg = args[i];
						mappedArguments[i] = sorter.newLocal(arg);
						replacement.add(new VarInsnNode(arg.getOpcode(ISTORE), mappedArguments[i]));
					}
					replacement.add(new IntInsnNode(SIPUSH, args.length));
					replacement.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
					for (int i = 0; i < args.length; i++) {
						Type arg = args[i];
						int sort = arg.getSort();
						int local = mappedArguments[i];
						replacement.add(new InsnNode(DUP));
						replacement.add(new IntInsnNode(SIPUSH, i));
						if (sort == Type.OBJECT || sort == Type.ARRAY) {
							replacement.add(new VarInsnNode(ALOAD, local));
						} else {
							Type wrapperClass = Util.boxType(arg);
							replacement.add(new VarInsnNode(arg.getOpcode(ILOAD), local));
							// <T> <Wrapper of T> <Wrapper of T>.valueOf(T)
							replacement.add(new MethodInsnNode(INVOKESTATIC, wrapperClass.getInternalName(), "valueOf", Type.getMethodDescriptor(wrapperClass, arg)));
						}
						replacement.add(new InsnNode(AASTORE));
					}
					replacement.add(new MethodInsnNode(
							INVOKESTATIC,
							Util.getInternalUtilClassName(ctx.obfuscationSettings().renamerSettings()).getInternalName(),
							Util.internalUtilInvokeMh.getName(),
							Util.internalUtilInvokeMh.getDescriptor(),
							false
					));
					int retSort = Type.getReturnType(min.desc).getSort();
					if (retSort == Type.VOID) {
						// generic "void" handle invoke always returns null, and regularly isn't expected to return anything
						// -> throw it off the stack once it arrives
						replacement.add(new InsnNode(Opcodes.POP));
					} else {
						if (retSort != Type.OBJECT && retSort != Type.ARRAY) {
							// we need to unbox
							// FUCK
							replacement.add(Util.invoke(INVOKEVIRTUAL, switch (retSort) {
								case Type.BOOLEAN -> Boolean.class.getMethod("booleanValue");
								case Type.CHAR -> Character.class.getMethod("charValue");
								case Type.BYTE -> Byte.class.getMethod("byteValue");
								case Type.SHORT -> Short.class.getMethod("shortValue");
								case Type.INT -> Integer.class.getMethod("intValue");
								case Type.FLOAT -> Float.class.getMethod("floatValue");
								case Type.LONG -> Long.class.getMethod("longValue");
								case Type.DOUBLE -> Double.class.getMethod("doubleValue");
								default -> throw new IllegalStateException("Unexpected value: " + retSort);
							}));
						}
					}
					insns.insertBefore(instruction, replacement);
					insns.remove(instruction);
				}
			}
		}
		return repl;
	}

	@Override
	public Method compile(Context context, ClassNode owner, MethodNode methodNode, SourceBuilder source, String targetSymbol, Remapper remapper, CacheSlotManager indyCache, StringCollector stringCollector) throws AnalyzerException, CompilationFailure {
		List<String> nativeArgs = new ArrayList<>();
		nativeArgs.add("JNIEnv* env");
		if (!Modifier.isStatic(methodNode.access)) nativeArgs.add("jobject param0");
		else nativeArgs.add("jclass owningClass");

		Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
		int localIndex = Modifier.isStatic(methodNode.access) ? 0 : 1;
		for (Type argumentType : argumentTypes) {
			String cType = jTypeMap.getOrDefault(argumentType, argumentType.getSort() == Type.ARRAY ? "jobjectArray" : "jobject");
			nativeArgs.add(String.format("%s param%d", cType, localIndex));
			localIndex += argumentType.getSize();
		}

		Type returnType = Type.getReturnType(methodNode.desc);
		String cReturnType = jTypeMap.getOrDefault(returnType, returnType.getSort() == Type.ARRAY ? "jobjectArray" : "jobject");

		Method method = source.method("", cReturnType, "JNICALL " + targetSymbol, nativeArgs.toArray(String[]::new));

		method.addHead("""
				// Automatically generated method
				// Source method: $l.$l$l
				""".trim(), owner.name, methodNode.name, methodNode.desc);

		Configuration.AntiHookSettings antiHook = context.obfuscationSettings().antiHook();
		if (antiHook.enableAntiHook) {
			method.addStatement("checkForHookedFunctions(env)");
		}

		method.addStatementHead("goto mainBodyEntry");
		method.addStatementHead("exceptionTerminate:");
		method.addStatementHead("DBG($s)", "escalating exception");
//		method.addStatementHead("logThatShit(env)");
		// no try catch, any exception returns
		boolean returnsVoid = method.getReturns().equals("void");
		if (returnsVoid) method.addStatementHead("return");
		else method.addStatementHead("return 0");

		method.addStatementHead("mainBodyEntry:");

		if (context.debug().isPrintMethodEntryExit()) {
			method.addStatementHead("puts($s)", "[j2cc] enter " + owner.name + "." + methodNode.name + methodNode.desc);
		}

		MethodNode processed = preprocessMethod(context, methodNode);


		if (context.debug().isDumpTranspilees()) {
			try {
				Path resolve = dump.resolve(owner.name + "_DUMMY_" + Util.turnIntoIdentifier(processed.name + processed.desc) + ".class");
				Files.createDirectories(resolve.getParent());
				SmartClassWriter scw = new SmartClassWriter(ClassWriter.COMPUTE_MAXS, context.workspace(), remapper);
				ClassNode dummy = new ClassNode();
				owner.accept(dummy);
				dummy.methods.removeIf(e -> !e.name.equals(processed.name) || !e.desc.equals(processed.desc));
				dummy.accept(scw);
				Files.write(resolve, scw.toByteArray());
				log.debug("Dumped partial class {} to {}", owner.name, resolve.toAbsolutePath().normalize());
			} catch (IOException e) {
				log.error("Failed to dump {}", owner.name, e);
			}
		}


		// compute maxs before using it
		Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
		Frame<BasicValue>[] frames = analyzer.analyzeAndComputeMaxs(owner.name, processed);

		Analyzer<SourceValue> sv = new Analyzer<>(new SourceInterpreter());
		Frame<SourceValue>[] svf = sv.analyze(owner.name, processed);

		int nLocals = processed.maxLocals;
		int nStack = processed.maxStack;
		method.addStatement("jvalue locals[$l]", nLocals);
		method.addStatement("jvalue stack[$l]", nStack);

		InsnList instructions = processed.instructions;
		Map<LabelNode, String> labels = new HashMap<>();
		for (AbstractInsnNode instruction : instructions) {
			if (instruction instanceof LabelNode ln) {
				labels.computeIfAbsent(ln, _ -> "lbl" + labels.size());
			}
		}

		MemberCache cache = new MemberCache(indyCache);
		for (AbstractInsnNode instruction : instructions) {
			InsnHandler<?> insnHandler = insnHandlers.get(instruction.getClass());
			if (insnHandler == null) throw new IllegalStateException("No handler for " + instruction.getClass());
			if (context.debug().isPrintBytecode()) {
				method.addStatement("DBG(\"%s\", $s)", Util.stringifyInstruction(instruction, labels));
			}
			Frame<BasicValue> frame = frames[instructions.indexOf(instruction)];
			if (frame == null) {
				if (context.debug().isPrintBytecode()) {
					method.comment("(Unreachable)");
				}
				continue; // unreachable code
			}
			// noinspection rawtypes
			CompilerContext compilerContext = new CompilerContext<>(owner, processed, frames, svf, instructions, instruction, method, labels, source, remapper, cache, indyCache, stringCollector);
			cache.setContext(compilerContext);
			//noinspection unchecked
			insnHandler.compileInsn(context, compilerContext);
		}
		return method;
	}

	public record CSourceFileEntry(boolean isObject, SourceBuilder sb, String name) {
	}

	public record Target(String id, String resourcePrefix) {
	}

	public record CompiledMethod(String owner, String name, String desc, String mn, Method nativeMethod) {
	}
}
