package me.x150.j2cc.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import j2cc.Exclude;
import j2cc.internal.Loader;
import j2cc.internal.Platform;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.compiler.CacheSlotManager;
import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.compiler.DefaultCompiler;
import me.x150.j2cc.compiler.MemberCache;
import me.x150.j2cc.conf.Configuration;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.util.Printer;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

//@Nativeify
@Log4j2
public class Util implements Opcodes {
	public static final Map<Class<?>, String> simpleNameMap = new HashMap<>() {{
		put(Integer.class, "jint");
		put(Byte.class, "jbyte");
		put(Character.class, "jchar");
		put(Short.class, "jshort");
		put(Boolean.class, "jboolean");
		put(Float.class, "jfloat");
		put(Long.class, "jlong");
		put(Double.class, "jdouble");
	}};
	private static final String LOADER_CLASS = Type.getInternalName(Loader.class);
	private static final Type INTERNAL_UTIL = Type.getObjectType("j2cc/internal/InternalUtil");
	public static final Type OBJECT_TYPE = Type.getType(Object.class);
	public static final org.objectweb.asm.commons.Method internalUtilInvokeMh = new org.objectweb.asm.commons.Method("invokeMethodHandle",
			OBJECT_TYPE,
			new Type[]{
					Type.getType(MethodHandle.class),
					Type.getType(Object[].class)
			});
	private static final Object2IntMap<String> mp = new Object2IntOpenHashMap<>();

	private static final MethodHandle MH_STRING_FORMAT;

	private static final MethodHandle MH_METHOD_FORMAT;

	private static final MethodHandle MH_LITERAL_FORMAT;

	private static final MethodHandles.Lookup lookup = MethodHandles.lookup();
	private static final Map<String, MethodHandle> M_FMT_CACHE = new ConcurrentHashMap<>();

	static {
		try {
			MH_STRING_FORMAT = lookup.findStatic(Util.class, "formatString", MethodType.methodType(String.class, String.class));

			MH_METHOD_FORMAT = Util.lookup.findGetter(Method.class, "name", String.class);

			MH_LITERAL_FORMAT = Util.lookup.findVirtual(Object.class, "toString", MethodType.methodType(String.class));
		} catch (NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

	public static String loaderClassName(Remapper rmp) {
		return rmp.map(LOADER_CLASS);
	}

	public static byte[] encodeMUTF(String str) {
		final int strlen = str.length();
		int utflen = strlen; // optimized for ASCII

		for (int i = 0; i < strlen; i++) {
			int c = str.charAt(i);
			if (c >= 0x80 || c == 0)
				utflen += (c >= 0x800) ? 2 : 1; // null char or >=0x80 chars are encoded with 2 bytes, >=0x800 with 3 bytes
		}

		byte[] bytearr = new byte[utflen];

		int stringI;
		int byArrI = 0;
		// initial run, copy all simple bytes over as far as we can
		for (stringI = 0; stringI < strlen; stringI++) {
			int c = str.charAt(stringI);
			if (c >= 0x80 || c == 0) break;
			bytearr[byArrI++] = (byte) c;
		}

		// actual encoding run
		for (; stringI < strlen; stringI++) {
			int c = str.charAt(stringI);
			if (c < 0x80 && c != 0) {
				// simple byte
				bytearr[byArrI++] = (byte) c;
			} else if (c >= 0x800) {
				// tri-byte
				bytearr[byArrI++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
				bytearr[byArrI++] = (byte) (0x80 | ((c >>  6) & 0x3F));
				bytearr[byArrI++] = (byte) (0x80 | ((c >>  0) & 0x3F));
			} else {
				// double-byte
				bytearr[byArrI++] = (byte) (0xC0 | ((c >>  6) & 0x1F));
				bytearr[byArrI++] = (byte) (0x80 | ((c >>  0) & 0x3F));
			}
		}

		return bytearr;
	}

	@SneakyThrows
	private static String formatString(String s) {
		byte[] encoded = encodeMUTF(s);
		char[] convertedChars = new char[encoded.length];
		for (int i = 0; i < encoded.length; i++) {
			convertedChars[i] = (char) Byte.toUnsignedInt(encoded[i]);
		}
		return "\"" + escapeString(convertedChars) + "\"";
	}

	public static Exclude.From[] getExcludeTypes(List<AnnotationNode> cn) {
		if (cn == null) return new Exclude.From[0];
		Optional<AnnotationNode> any = cn.stream().filter(f -> f.desc.equals(Type.getDescriptor(Exclude.class))).findAny();
		if (any.isEmpty()) return new Exclude.From[0];
		AnnotationNode anno = any.get();
		int valueIdx;
		if (anno.values == null || (valueIdx = anno.values.indexOf("value")) == -1) {
			// should never happen, value does not have a default
			return new Exclude.From[0];
		}
		// arrays are represented as lists
		// enums are represented as string arrays, index 0 being the type desc of the enum and 1 being the enum value name
		//noinspection unchecked
		return ((List<String[]>) anno.values.get(valueIdx + 1)).stream()
				.map(it -> Exclude.From.valueOf(it[1])).toArray(Exclude.From[]::new);
	}

	public static boolean shouldIgnore(Context ctx, ClassNode cn, Exclude.From from) {
		List<AnnotationNode> invisibleAnnotations = cn.invisibleAnnotations;
		Exclude.From[] excludeTypes = getExcludeTypes(invisibleAnnotations);
		for (Exclude.From excludeType : excludeTypes) {
			if (excludeType == from) return true;
		}
		return ctx.extraClassFilters()
				.stream()
				.anyMatch(it -> Arrays.asList(it.extraExcludeTypes()).contains(from) && Arrays.stream(it.filters()).anyMatch(v -> v.matches(cn.name)));
	}

	public static boolean shouldIgnore(Context ctx, String owner, MethodNode mn, Exclude.From from) {
		List<AnnotationNode> invisibleAnnotations = mn.invisibleAnnotations;
		Exclude.From[] excludeTypes = getExcludeTypes(invisibleAnnotations);
		for (Exclude.From excludeType : excludeTypes) {
			if (excludeType == from) return true;
		}
		Type type = Type.getType(mn.desc);
		return ctx.extraMemberFilters()
				.stream()
				.anyMatch(it -> Arrays.asList(it.extraExcludeTypes()).contains(from) && Arrays.stream(it.filters()).anyMatch(v -> v.matches(owner, mn.name, type)));
	}

	public static boolean shouldIgnore(Context ctx, String owner, FieldNode mn, Exclude.From from) {
		List<AnnotationNode> invisibleAnnotations = mn.invisibleAnnotations;
		Exclude.From[] excludeTypes = getExcludeTypes(invisibleAnnotations);
		for (Exclude.From excludeType : excludeTypes) {
			if (excludeType == from) return true;
		}
		Type type = Type.getType(mn.desc);
		return ctx.extraMemberFilters()
				.stream()
				.anyMatch(it -> Arrays.asList(it.extraExcludeTypes()).contains(from) && Arrays.stream(it.filters()).anyMatch(v -> v.matches(owner, mn.name, type)));
	}

	private static String toHex(char c) {
		String hex = Integer.toHexString(c);
		// WARNING! C++XX RETARD ALERT!
		// \ u____ generates 2 bytes REGARDLESS OF THE FUCKING CONTENT!!! AND UTF8 ENCODES IT!!!
		// \x__ generates 1 byte
		// -> replicate the fucking java behavior in c++
		// WARNING! C++XX RETARD ALERT!
		// \x__ is not the max. you can go \x_+
		// \x01 generates 0x01
		// a\x01Abc generates a\xbc and a compiler warning
		// I FUCKING HATE THIS LANGUAGE
		return "\\x{" + hex+"}";
	}

	public static String escapeString(char[] s) {
		StringBuilder sb = new StringBuilder(s.length);
		for (char c : s) {
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '"' -> sb.append("\\\"");
				default -> {
					if (c >= 32 && c <= 126) {
						sb.append(c);
					} else sb.append(toHex(c));
				}
			}
		}
		return sb.toString();
	}

	public static String turnIntoIdentifier(String s) {
		return s.replaceAll("^[^a-zA-Z]|[^a-zA-Z0-9]", "_");
	}

	@SneakyThrows
	private static MethodHandle compileFormat(String s) {
		char[] ce = s.toCharArray();
		int amountOfFormats = 0;
		for (char c : ce) {
			if (c == '$') amountOfFormats++;
		}
		String[] template = new String[amountOfFormats * 2 + 1];
		MethodHandle[] valueFormatters = new MethodHandle[amountOfFormats];
		int[] valueArrPointers = new int[amountOfFormats];
		int length = ce.length;
		int idx = 0;

		int real = 0;
		int ctr2 = 0;

		int lastEnd = 0;

		for (int i = 0; i < ce.length; i++) {
			char c = ce[i];
			if (c == '$') {
				if (i == length - 1) throw new IllegalArgumentException("Trailing $");
				int start = i;
				char nx = ce[++i];
				if (nx == '.') {
					if (i + 2 >= length) throw new IllegalArgumentException("Incomplete $.");
					int index = ce[++i] - '0';
					char format = ce[++i];
					MethodHandle theFormatter = findFormatter(format);
					valueArrPointers[ctr2] = index;
					valueFormatters[ctr2] = theFormatter;
				} else {
					MethodHandle theFormatter = findFormatter(nx);
					valueArrPointers[ctr2] = idx++;
					valueFormatters[ctr2] = theFormatter;
				}
				ctr2++;
				template[real] = s.substring(lastEnd, start);
				real += 2; // jump to next string slot
				lastEnd = i + 1;
			}
		}
		template[real] = s.substring(lastEnd);
		return makeFormatter(template, valueFormatters, valueArrPointers);
	}


	@SneakyThrows
	public static String fmt(String s, Object... args) {
		MethodHandle formatter = M_FMT_CACHE.computeIfAbsent(s, Util::compileFormat);
		return (String) formatter.invoke(args);
	}

	private static MethodHandle makeFormatter(String[] template, MethodHandle[] formats, int[] mapping) throws NoSuchMethodException, IllegalAccessException {
		MethodHandle doFormat = lookup.findStatic(Util.class, "doFormat", MethodType.methodType(String.class, String[].class, MethodHandle[].class, int[].class, Object[].class));
		return MethodHandles.insertArguments(doFormat, 0, template, formats, mapping);
	}

	private static String doFormat(String[] template, MethodHandle[] formats, int[] mapping, Object[] args) throws Throwable {
		int lenSum = 0;
		for (String s : template) {
			lenSum += s == null ? 5 : s.length();
		}
		StringBuilder sb = new StringBuilder(lenSum);
		int d = 0;
		for (String s : template) {
			if (s == null) s = (String) formats[d].invoke(args[mapping[d++]]);
			sb.append(s);
		}
		return sb.toString();
	}

	private static MethodHandle findFormatter(char c) {
		return switch (c) {
			case 's' -> MH_STRING_FORMAT;
			case 'm' -> MH_METHOD_FORMAT;
			case 'l' -> MH_LITERAL_FORMAT;
			default -> throw new IllegalStateException("Unexpected value: " + c);
		};
	}

	public static String uniquifyName(String name) {
		synchronized (mp) {
			int i = mp.computeIfAbsent(name, _ -> 0);
			mp.put(name, i + 1);
			return name + i;
		}
	}

	public static char typeToTypeChar(Type t) {
		return switch (t.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> 'i';
			case Type.FLOAT -> 'f';
			case Type.DOUBLE -> 'd';
			case Type.LONG -> 'j';
			case Type.OBJECT, Type.ARRAY -> 'l';
			default -> throw new IllegalStateException("Unexpected value: " + t.getSort());
		};
	}

	public static int guessCodeSize(InsnList il) {
		CodeSizeEvaluator cse = new CodeSizeEvaluator(null);
		for (AbstractInsnNode abstractInsnNode : il) {
			abstractInsnNode.accept(cse);
		}
		return cse.getMaxSize();
	}

	@SneakyThrows
	public static String resolveBsmArg(Context c, Object o, Method m, CompilerContext<?> compilerContext, String storeUnder) {
		if (simpleNameMap.containsKey(o.getClass())) {
			String tn = simpleNameMap.get(o.getClass());
			storeUnder += tn;
			m.local(tn, storeUnder).initStmt("$l", o);
		} else if (o instanceof String s) {
			storeUnder += "jstring";
			m.local("jstring", storeUnder).initStmt("env->NewStringUTF($s)", s);
		} else if (o instanceof Handle h) {
			String owner = h.getOwner();
			String name = h.getName();
			String desc = h.getDesc();
			String ownerClR = compilerContext.cache().getOrCreateClassResolve(owner, "rbm_ocl");
			String mhn = "java/lang/invoke/MethodHandleNatives";
			String mhnClazz = compilerContext.cache().getOrCreateClassResolve(mhn, "rbm_mhn");
			String linkMethodHandleConstantMethod = compilerContext.cache().getOrCreateStaticMethodFind(new MemberCache.Descriptor(
					mhn, "linkMethodHandleConstant",
					Type.getMethodDescriptor(Type.getType(MethodHandle.class), Type.getType(Class.class), Type.INT_TYPE,
							Type.getType(Class.class), Type.getType(String.class), Util.OBJECT_TYPE)
			), "rbm_lmhc");
			String theFinalArg = switch (h.getTag()) {
				case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC ->
						Util.resolveTypeToJclassAndStoreIt(Type.getType(desc), m, compilerContext, "otherType");
				case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESTATIC,
					 Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL ->
						Util.generateMethodTypeFromType(Type.getMethodType(desc), m, compilerContext, "methodType");
				default -> throw new IllegalStateException();
			};
			String currentClazz = compilerContext.cache().getOrCreateClassResolve(compilerContext.methodOwner().name, "rbm_cc");
			storeUnder += "jobject";
			m.local("jobject", storeUnder).initStmt("env->CallStaticObjectMethod($l, $l, $l, $l, $l, env->NewStringUTF($s), $l)", mhnClazz, linkMethodHandleConstantMethod, currentClazz, h.getTag(), ownerClR, name, theFinalArg);
			compilerContext.exceptionCheck();
		} else if (o instanceof Type t) {
			if (t.getSort() == Type.METHOD) {
				storeUnder += "jobject";
				storeUnder = generateMethodTypeFromType(t, m, compilerContext, storeUnder);
			} else {
				storeUnder += "jclass";
				String d = t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName();
				storeUnder = compilerContext.cache().getOrCreateClassResolve(d, storeUnder);
			}
		} else if (o instanceof ConstantDynamic cd) {
			Object[] bsmArgs = new Object[cd.getBootstrapMethodArgumentCount()];
			for (int i = 0; i < bsmArgs.length; i++) {
				bsmArgs[i] = cd.getBootstrapMethodArgument(i);
			}
			Type retTy = Type.getType(cd.getDescriptor());
			if (retTy.getSort() >= Type.ARRAY) {
				storeUnder += "jobject";
				// no mapping needed, just do the condy directly into storeUnder
				Util.generateConstantDynamic(c, compilerContext, m, bsmArgs, cd.getBootstrapMethod(), cd.getName(), cd.getDescriptor(), storeUnder);
				return storeUnder;
			}
			String ret = Util.generateConstantDynamic(c, compilerContext, m, bsmArgs, cd.getBootstrapMethod(), cd.getName(), cd.getDescriptor(), storeUnder+"condy");
			switch (retTy.getSort()) {
				case Type.BOOLEAN -> {
					storeUnder += "jboolean";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Boolean.class.getMethod("booleanValue")), "rbm_cv");
					m.local("jboolean", storeUnder).initStmt("env->CallBooleanMethod($l, $l)", ret, s);
				}
				case Type.CHAR -> {
					storeUnder += "jchar";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Character.class.getMethod("charValue")), "rbm_cv");
					m.local("jchar", storeUnder).initStmt("env->CallCharMethod($l, $l)", ret, s);
				}
				case Type.BYTE -> {
					storeUnder += "jbyte";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Byte.class.getMethod("byteValue")), "rbm_cv");
					m.local("jbyte", storeUnder).initStmt("env->CallByteMethod($l, $l)", ret, s);
				}
				case Type.SHORT -> {
					storeUnder += "jshort";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Short.class.getMethod("shortValue")), "rbm_cv");
					m.local("jshort", storeUnder).initStmt("env->CallShortMethod($l, $l)", ret, s);
				}
				case Type.INT -> {
					storeUnder += "jint";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Integer.class.getMethod("intValue")), "rbm_cv");
					m.local("jint", storeUnder).initStmt("env->CallIntMethod($l, $l)", ret, s);
				}
				case Type.FLOAT -> {
					storeUnder += "jfloat";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Float.class.getMethod("floatValue")), "rbm_cv");
					m.local("jfloat", storeUnder).initStmt("env->CallFloatMethod($l, $l)", ret, s);
				}
				case Type.LONG -> {
					storeUnder += "jlong";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Long.class.getMethod("longValue")), "rbm_cv");
					m.local("jlong", storeUnder).initStmt("env->CallLongMethod($l, $l)", ret, s);
				}
				case Type.DOUBLE -> {
					storeUnder += "jdouble";
					String s = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(Double.class.getMethod("doubleValue")), "rbm_cv");
					m.local("jdouble", storeUnder).initStmt("env->CallDoubleMethod($l, $l)", ret, s);
				}
			}
		} else throw new IllegalArgumentException("what the fuck: " + o);
		return storeUnder;
	}

	public static IllegalStateException unimplemented(int opcode) {
		return new IllegalStateException("Unimplemented opcode " + Printer.OPCODES[opcode]);
	}

	@SneakyThrows
	public static String generateMethodTypeFromType(Type t, Method m, CompilerContext<?> compilerContext, String storeIn) {
		// caller can be trusted
		Type[] argumentTypes = t.getArgumentTypes();
		Type returnType = t.getReturnType();
		String returnName = resolveTypeToJclassAndStoreIt(returnType, m, compilerContext, "ttmt_r");
		String theArgs = "mtArgs";

		String javaClass = compilerContext.cache().getOrCreateClassResolve("java/lang/Class", "ttmt_0");
		m.local("jobjectArray", theArgs).initStmt("env->NewObjectArray($l, $l, nullptr)", argumentTypes.length, javaClass);
		for (int i = 0; i < argumentTypes.length; i++) {
			Type argumentType = argumentTypes[i];
			String ttmt1 = resolveTypeToJclassAndStoreIt(argumentType, m, compilerContext, "ttmt_argElement");
			m.addStatement("env->SetObjectArrayElement($l, $l, $l)", theArgs, i, ttmt1);
		}

		org.objectweb.asm.commons.Method method = org.objectweb.asm.commons.Method.getMethod(MethodType.class.getMethod("methodType", Class.class, Class[].class));
		String orGenerateClassFind = compilerContext.cache().getOrCreateClassResolve("java/lang/invoke/MethodType", "ttmt_0");
		MemberCache.Descriptor desc = new MemberCache.Descriptor("java/lang/invoke/MethodType", method.getName(), method.getDescriptor());
		String storedMethName = compilerContext.cache().getOrCreateStaticMethodFind(desc, "ttmt_mtmt");
		m.local("jobject", storeIn).initStmt("env->CallStaticObjectMethod($l, $l, $l, $l)", orGenerateClassFind, storedMethName, returnName, theArgs);
		compilerContext.exceptionCheck();
		return storeIn;
	}

	public static String resolveTypeToJclassAndStoreIt(Type t, Method m, CompilerContext<?> compilerContext, String storeUnder) {
		if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
			String d = t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName();
			return compilerContext.cache().getOrCreateClassResolve(d, storeUnder);
		} else {
			String wpTypeIN = Util.boxType(t).getInternalName();
			String orGenerateClassFind = compilerContext.cache().getOrCreateClassResolve(wpTypeIN, "ttjc_0");
			MemberCache.Descriptor ds = new MemberCache.Descriptor(wpTypeIN, "TYPE", "Ljava/lang/Class;");
			String typeFieldName = compilerContext.cache().getOrCreateStaticFieldFind(ds, 1);
			m.localInitialValue("jclass", storeUnder, "nullptr").initStmt("(jclass) env->GetStaticObjectField($l, $l)", orGenerateClassFind, typeFieldName);
			m.noteClassDef(storeUnder, null);
			return storeUnder;
		}
	}

	public static Class<?> wrapperTypeForPrim(Class<?> cl) {
		return MethodType.methodType(cl).wrap().returnType();
	}

	public static Class<?> primTypeForWrapper(Class<?> cl) {
		return MethodType.methodType(cl).unwrap().returnType();
	}

	public static Class<?> name2type(Type t) {
		return switch (t.getSort()) {
			case Type.VOID -> void.class;
			case Type.BOOLEAN -> boolean.class;
			case Type.CHAR -> char.class;
			case Type.BYTE -> byte.class;
			case Type.SHORT -> short.class;
			case Type.INT -> int.class;
			case Type.FLOAT -> float.class;
			case Type.LONG -> long.class;
			case Type.DOUBLE -> double.class;
			default -> throw new IllegalStateException("Not a primitive: " + t);
		};
	}

	public static Type boxType(Type t) {
		int sort = t.getSort();
		return switch (sort) {
			case Type.BOOLEAN -> Type.getType(Boolean.class);
			case Type.CHAR -> Type.getType(Character.class);
			case Type.BYTE -> Type.getType(Byte.class);
			case Type.SHORT -> Type.getType(Short.class);
			case Type.INT -> Type.getType(Integer.class);
			case Type.FLOAT -> Type.getType(Float.class);
			case Type.LONG -> Type.getType(Long.class);
			case Type.DOUBLE -> Type.getType(Double.class);
			case Type.VOID -> Type.getType(Void.class);
			default -> t;
		};
	}

	public static Type unboxType(Type t) {
		if (t.getSort() != Type.OBJECT) return t;
		String clN = t.getClassName();
		return switch (clN) {
			case "java/lang/Void" -> Type.VOID_TYPE;
			case "java/lang/Boolean" -> Type.BOOLEAN_TYPE;
			case "java/lang/Character" -> Type.CHAR_TYPE;
			case "java/lang/Byte" -> Type.BYTE_TYPE;
			case "java/lang/Short" -> Type.SHORT_TYPE;
			case "java/lang/Integer" -> Type.INT_TYPE;
			case "java/lang/Float" -> Type.FLOAT_TYPE;
			case "java/lang/Long" -> Type.LONG_TYPE;
			case "java/lang/Double" -> Type.DOUBLE_TYPE;
			default -> t;
		};
	}

	@SneakyThrows
	public static void rmRf(Path p) {
		Files.walkFileTree(p, new FileVisitor<>() {
			@Override
			public @NotNull FileVisitResult preVisitDirectory(Path file, @NotNull BasicFileAttributes attrs) {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public @NotNull FileVisitResult visitFileFailed(Path file, @NotNull IOException exc) {
				return FileVisitResult.TERMINATE;
			}

			@Override
			public @NotNull FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public static String stringifyBsmArg1(Object o) {
		if (o instanceof Handle handle) {
			return "handle " + Printer.HANDLE_TAG[handle.getTag()] + " " + handle.getOwner() + "." + handle.getName() + handle.getDesc();
		} else if (o instanceof Type t) {
			return "type " + t.getDescriptor();
		} else {
			return o.getClass().getName() + " " + o;
		}
	}

	public static String stringifyInstruction(AbstractInsnNode node, Map<LabelNode, String> labels) {
		StringBuilder sb = new StringBuilder();
		if (node.getOpcode() > 0) sb.append(Printer.OPCODES[node.getOpcode()]);
		if (node instanceof FieldInsnNode fi) {
			sb.append(" ").append(fi.owner).append(".").append(fi.name).append("(").append(fi.desc).append(")");
		} else if (node instanceof IincInsnNode i) {
			sb.append(" ").append("var").append(i.var).append(" ").append(i.incr > 0 ? "+" : "-").append("=").append(" ").append(Math.abs(i.incr));
		} else if (node instanceof IntInsnNode i) {
			sb.append(" ").append(i.operand);
		} else if (node instanceof InvokeDynamicInsnNode indy) {
			Handle bsm = indy.bsm;
			sb.append("\n")
					.append("  bsm: ").append(Printer.HANDLE_TAG[bsm.getTag()]).append(" ").append(bsm.getOwner()).append(".").append(bsm.getName()).append(bsm.getDesc()).append(" (");
			for (Object bsmArg : indy.bsmArgs) {
				sb.append("\n    ").append(stringifyBsmArg1(bsmArg));
			}
			if (indy.bsmArgs.length != 0) sb.append("\n  )\n");
			else sb.append(")\n");
			sb.append("  -> ").append(indy.name).append(indy.desc);
		} else if (node instanceof JumpInsnNode ji) {
			sb.append(" ").append(labels.get(ji.label));
		} else if (node instanceof LabelNode ln) {
			sb.append("LABEL ").append(labels.get(ln));
		} else if (node instanceof LdcInsnNode l) {
			sb.append(" ").append(l.cst.getClass().getName()).append(" ").append(l.cst);
		} else if (node instanceof LookupSwitchInsnNode ls) {
			sb.append(" {");
			for (int i = 0; i < ls.keys.size(); i++) {
				sb.append("\n  ").append(i).append(" -> ").append(labels.get(ls.labels.get(i))).append(";");
			}
			sb.append("\n  ").append("dflt -> ").append(labels.get(ls.dflt)).append("\n  }");
		} else if (node instanceof MethodInsnNode mi) {
			sb.append(" ").append(mi.owner).append(".").append(mi.name).append(mi.desc);
		} else if (node instanceof MultiANewArrayInsnNode mia) {
			sb.append(" ").append(mia.desc).append("[]".repeat(mia.dims));
		} else if (node instanceof TableSwitchInsnNode ls) {
			sb.append(" {");
			for (int i = 0; i <= ls.max - ls.min; i++) {
				sb.append("\n  ").append(i).append(" -> ").append(labels.get(ls.labels.get(i))).append(";");
			}
			sb.append("\n  ").append("dflt -> ").append(labels.get(ls.dflt)).append("\n  }");
		} else if (node instanceof TypeInsnNode ti) {
			sb.append(" ").append(ti.desc);
		} else if (node instanceof VarInsnNode vi) {
			sb.append(" ").append("var").append(vi.var);
		} else if (node instanceof LineNumberNode lno) {
			sb.append("LINE ").append(lno.line);
		} else if (!(node instanceof InsnNode)) throw new IllegalStateException(node.toString());
		return sb.toString();
	}

	@SneakyThrows
	@NotNull
	public static String generateInvokedynamic(Context ctx, CompilerContext<?> compilerContext, Method compileTo, Object[] bsmArgs, Handle bsm, String name, String desc, String storeUnder) {
		String lookupName = compilerContext.cache().lookupHere();
		compilerContext.exceptionCheck();

		String[] bsmArgsNames = IntStream.range(0, bsmArgs.length)
				.mapToObj(i -> new Pair<>(i, bsmArgs[i]))
				.map(p -> resolveBsmArg(ctx, p.getB(), compileTo, compilerContext, storeUnder+"_bsmArg"+p.a))
				.toArray(String[]::new);
//		String[] bsmArgsNames = Arrays.stream(bsmArgs).map(s -> resolveBsmArg(s, compileTo, compilerContext)).toArray(String[]::new);


		String theBsmMethodHandle = "bsmMh";
		Type bsmMethodType = Type.getMethodType(bsm.getDesc());
		String methodType = generateMethodTypeFromType(bsmMethodType, compileTo, compilerContext, "methodType");
		String resolvedOwnerType = compilerContext.cache().getOrCreateClassResolve(bsm.getOwner(), "indygen_0");

		String findStaticMethod = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(MethodHandles.Lookup.class.getMethod("findStatic", Class.class, String.class, MethodType.class)), "indygen_fs");

		compileTo.local("jobject", theBsmMethodHandle).initStmt("env->CallObjectMethod($l, $l, $l, env->NewStringUTF($s), $l)", lookupName, findStaticMethod, resolvedOwnerType, bsm.getName(), methodType);
		compilerContext.exceptionCheck();

		String theBsmArgs = "bsmArgs";

		String targetMethodType = generateMethodTypeFromType(Type.getMethodType(desc), compileTo, compilerContext, "methodType");

		String objectClass = compilerContext.cache().getOrCreateClassResolve("java/lang/Object", "indygen_0");
		compileTo.local("jobjectArray", theBsmArgs).initStmt("env->NewObjectArray($l, $l, nullptr)", bsmArgsNames.length + 3, objectClass);
		compileTo.addStatement("env->SetObjectArrayElement($l, 0, $l)", theBsmArgs, lookupName);
		compileTo.addStatement("env->SetObjectArrayElement($l, 1, env->NewStringUTF($s))", theBsmArgs, name);
		compileTo.addStatement("env->SetObjectArrayElement($l, 2, $l)", theBsmArgs, targetMethodType);

		for (int i = 0; i < bsmArgsNames.length; i++) {
			Class<?> theWrappedBsmArgType = bsmArgs[i].getClass();
			Type t = Type.getType(Util.primTypeForWrapper(theWrappedBsmArgType));
			if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
				compileTo.addStatement("env->SetObjectArrayElement($l, $l, $l)", theBsmArgs, i + 3, bsmArgsNames[i]);
			} else {
				Type theWrapper = Util.boxType(t);
				MemberCache.Descriptor desc1 = new MemberCache.Descriptor(theWrapper.getInternalName(),
						"valueOf", Type.getMethodDescriptor(theWrapper, t));
				String owningClass = compilerContext.cache().getOrCreateClassResolve(desc1.owner(), "indygen_0");
				String meth = compilerContext.cache().getOrCreateStaticMethodFind(desc1, "indygen_valueof");
				compileTo.addStatement("env->SetObjectArrayElement($l, $l, env->CallStaticObjectMethod($l, $l, $l))", theBsmArgs, i + 3, owningClass, meth, bsmArgsNames[i]);
				compilerContext.exceptionCheck();
			}
		}

		compileTo.local("jobject", storeUnder);


		CacheSlotManager.InvokeDynamicSpec indySpec = new CacheSlotManager.InvokeDynamicSpec(
				bsm, bsmArgs, name, desc
		);
		int slot = compilerContext.indyCache().getOrCreateIndyCacheSlot(indySpec);
		compileTo.beginScope("if (!cache::getCachedValue($l, &$l))", slot, storeUnder);

		String iuName = getInternalUtilClassName(ctx.obfuscationSettings().renamerSettings()).getInternalName();
		String internalUtilClass = compilerContext.cache().getOrCreateClassResolve(iuName, "indygen_0");
		String theInvokeMethod = compilerContext.cache().getOrCreateStaticMethodFind(
				new MemberCache.Descriptor(iuName, internalUtilInvokeMh.getName(), internalUtilInvokeMh.getDescriptor()),
				"indygen_iuimh"
		);
		compileTo.addStatement("$l = env->CallStaticObjectMethod($l, $l, $l, $l)", storeUnder, internalUtilClass, theInvokeMethod, theBsmMethodHandle, theBsmArgs);

		compilerContext.exceptionCheck();

		compileTo.addStatement("cache::putCachedValue($l, env->NewGlobalRef($l))", slot, storeUnder);
		compileTo.endScope();
		return storeUnder;
	}

	@SneakyThrows
	@NotNull
	public static String generateConstantDynamic(Context ctx, CompilerContext<?> compilerContext, Method compileTo, Object[] bsmArgs, Handle bsm, String name, String desc, String storeUnder) {
		String lookupName = compilerContext.cache().lookupHere();
		compilerContext.exceptionCheck();

		String[] bsmArgsNames = IntStream.range(0, bsmArgs.length)
				.mapToObj(i -> new Pair<>(i, bsmArgs[i]))
				.map(p -> resolveBsmArg(ctx, p.getB(), compileTo, compilerContext, storeUnder+"_bsmArg"+p.a))
				.toArray(String[]::new);

		String theBsmMethodHandle = "bsmMh";
		Type bsmMethodType = Type.getMethodType(bsm.getDesc());
		String methodType = generateMethodTypeFromType(bsmMethodType, compileTo, compilerContext, "methodType");
		String resolvedOwnerType = compilerContext.cache().getOrCreateClassResolve(bsm.getOwner(), "indygen_0");

		String findStaticMethod = compilerContext.cache().getOrCreateNonstaticMethodFind(MemberCache.Descriptor.ofMethod(MethodHandles.Lookup.class.getMethod("findStatic", Class.class, String.class, MethodType.class)), "indygen_fs");

		compileTo.local("jobject", theBsmMethodHandle).initStmt("env->CallObjectMethod($l, $l, $l, env->NewStringUTF($s), $l)", lookupName, findStaticMethod, resolvedOwnerType, bsm.getName(), methodType);
		compilerContext.exceptionCheck();

		String objectClass = compilerContext.cache().getOrCreateClassResolve("java/lang/Object", "indygen_0");
		String theBsmArgs = "bsmArgs";

		compileTo.local("jobjectArray", theBsmArgs).initStmt("env->NewObjectArray($l, $l, nullptr)", bsmArgsNames.length + 3, objectClass);
		compileTo.addStatement("env->SetObjectArrayElement($l, 0, $l)", theBsmArgs, lookupName);
		compileTo.addStatement("env->SetObjectArrayElement($l, 1, env->NewStringUTF($s))", theBsmArgs, name);
		String targetType = resolveTypeToJclassAndStoreIt(Type.getType(desc), compileTo, compilerContext, "indygen_targetType");
		compileTo.addStatement("env->SetObjectArrayElement($l, 2, $l)", theBsmArgs, targetType);

		for (int i = 0; i < bsmArgsNames.length; i++) {
			Class<?> theWrappedBsmArgType = bsmArgs[i].getClass();
			Type t = Type.getType(Util.primTypeForWrapper(theWrappedBsmArgType));
			if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
				compileTo.addStatement("env->SetObjectArrayElement($l, $l, $l)", theBsmArgs, i + 3, bsmArgsNames[i]);
			} else {
				Type theWrapper = Util.boxType(t);
				MemberCache.Descriptor desc1 = new MemberCache.Descriptor(theWrapper.getInternalName(),
						"valueOf", Type.getMethodDescriptor(theWrapper, t));
				String owningClass = compilerContext.cache().getOrCreateClassResolve(desc1.owner(), "indygen_0");
				String meth = compilerContext.cache().getOrCreateStaticMethodFind(desc1, "indygen_valueof");
				compileTo.addStatement("env->SetObjectArrayElement($l, $l, env->CallStaticObjectMethod($l, $l, $l))", theBsmArgs, i + 3, owningClass, meth, bsmArgsNames[i]);
				compilerContext.exceptionCheck();
			}
		}

		compileTo.local("jobject", storeUnder);


		CacheSlotManager.InvokeDynamicSpec indySpec = new CacheSlotManager.InvokeDynamicSpec(
				bsm, bsmArgs, name, desc
		);
		int slot = compilerContext.indyCache().getOrCreateIndyCacheSlot(indySpec);
		compileTo.beginScope("if (!cache::getCachedValue($l, &$l))", slot, storeUnder);

		String iuName = getInternalUtilClassName(ctx.obfuscationSettings().renamerSettings()).getInternalName();
		String internalUtilClass = compilerContext.cache().getOrCreateClassResolve(iuName, "indygen_0");
		String theInvokeMethod = compilerContext.cache().getOrCreateStaticMethodFind(
				new MemberCache.Descriptor(iuName, internalUtilInvokeMh.getName(), internalUtilInvokeMh.getDescriptor()),
				"indygen_iuimh"
		);
		compileTo.addStatement("$l = env->CallStaticObjectMethod($l, $l, $l, $l)", storeUnder, internalUtilClass, theInvokeMethod, theBsmMethodHandle, theBsmArgs);

		compilerContext.exceptionCheck();

		compileTo.addStatement("cache::putCachedValue($l, env->NewGlobalRef($l))", slot, storeUnder);
		compileTo.endScope();
		return storeUnder;
	}

	public static MethodInsnNode invoke(int opcode, java.lang.reflect.Method toInvoke) {
		return invoke(opcode, opcode == Opcodes.INVOKEINTERFACE, toInvoke);
	}

	public static MethodInsnNode invoke(int opcode, boolean itf, java.lang.reflect.Method toInvoke) {
		return new MethodInsnNode(opcode, Type.getInternalName(toInvoke.getDeclaringClass()), toInvoke.getName(), Type.getMethodDescriptor(toInvoke), itf);
	}

	@SneakyThrows
	public static ClassNode generateInternalUtilClass(Context ctx) {
		ClassNode scw = new ClassNode();
		scw.visit(V11, ACC_PUBLIC, getInternalUtilClassName(ctx.obfuscationSettings().renamerSettings()).getInternalName(), null, "java/lang/Object", null);
		GeneratorAdapter adapter = new GeneratorAdapter(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, internalUtilInvokeMh, null, null, scw);
		adapter.visitAnnotation("Ljdk/internal/vm/annotation/Hidden;", true).visitEnd();
		adapter.visitCode();

		adapter.loadArg(1);
		adapter.arrayLength();
		invoke(INVOKESTATIC, adapter, MethodType.class.getMethod("genericMethodType", int.class));
		adapter.push(0);
		invoke(INVOKESTATIC, adapter, MethodHandles.class.getMethod("spreadInvoker", MethodType.class, int.class));
		adapter.loadArg(0);
		adapter.loadArg(1);
		// MethodHandle.invokeExact(Object, Object[]) -> Object
		adapter.invokeVirtual(Type.getType(MethodHandle.class),
				new org.objectweb.asm.commons.Method(
						"invoke",
						OBJECT_TYPE,
						new Type[]{
								Type.getType(MethodHandle.class),
								Type.getType(Object[].class)
						}
				));
		adapter.returnValue();
		adapter.visitEnd();
		adapter.visitMaxs(3, 0);
		scw.visitEnd();
		return scw;
	}

	public static String formatMethod(String owner, org.objectweb.asm.commons.Method m) {
		Type reType = m.getReturnType();
		String ret = reType.getClassName();
		StringBuilder sb = new StringBuilder();
		sb.append(ret);
		sb.append(" ");
		sb.append(owner.replace('/', '.'));
		sb.append(".").append(m.getName()).append("(");
		Type[] argumentTypes = m.getArgumentTypes();
		for (int i = 0; i < argumentTypes.length; i++) {
			Type argumentType = argumentTypes[i];
			String a = argumentType.getClassName();
			sb.append(a);
			if (i != argumentTypes.length - 1) sb.append(", ");
		}
		sb.append(")");
		return sb.toString();
	}

	public static void invoke(int opcode, GeneratorAdapter gen, java.lang.reflect.Method method) {
		Type type = Type.getType(method.getDeclaringClass());
		org.objectweb.asm.commons.Method m = org.objectweb.asm.commons.Method.getMethod(method);
		switch (opcode) {
			case INVOKEVIRTUAL -> gen.invokeVirtual(type, m);
			case INVOKESTATIC -> gen.invokeStatic(type, m);
			case INVOKEINTERFACE -> gen.invokeInterface(type, m);
			default -> throw new IllegalArgumentException("Opcode " + Printer.OPCODES[opcode]);
		}
	}

	public static Map<LabelNode, LabelNode> cloneLabels(InsnList insns) {
		HashMap<LabelNode, LabelNode> labelMap = new HashMap<>();
		for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getType() == 8) {
				labelMap.put((LabelNode) insn, new LabelNode());
			}
		}
		return labelMap;
	}

	public static InsnList cloneInsnList(InsnList insns) {
		return cloneInsnList(cloneLabels(insns), insns);
	}

	public static String getPackage(String internalName) {
		int endIndex = internalName.lastIndexOf('/');
		return endIndex > 0 ? internalName.substring(0, endIndex) : internalName;
	}

	public static InsnList cloneInsnList(Map<LabelNode, LabelNode> labelMap, InsnList insns) {
		InsnList clone = new InsnList();
		for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
			clone.add(insn.clone(labelMap));
		}

		return clone;
	}

	@SuppressWarnings("unchecked")
	public static <T, R> CompletableFuture<R>[] parallelize(Executor esv, ThrowingFunction<T, R> processor, T[] values) {
		CompletableFuture<R>[] futures = new CompletableFuture[values.length];
		for (int i = 0; i < futures.length; i++) {
			T value = values[i];
			futures[i] = CompletableFuture.supplyAsync(() -> processor.applyUncheckedExc(value), esv);
		}
		return futures;
	}

	public static InsnList makeIL(Consumer<InsnList> c) {
		InsnList il = new InsnList();
		c.accept(il);
		return il;
	}

	public static void addLoaderInitToClinit(ClassNode owner, Remapper rmp) {
		Optional<MethodNode> existingClinit = owner.methods.stream().filter(s -> s.name.equals("<clinit>")).findAny();
		if (existingClinit.isPresent()) {
			// clinit exists, add our initNatives call to the top of it
			MethodNode methodNode = existingClinit.get();
			methodNode.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, loaderClassName(rmp), "doInit", "(Ljava/lang/Class;)V", false));
			methodNode.instructions.insert(new LdcInsnNode(Type.getObjectType(owner.name)));
		} else {
			// clinit does not exist, make one and add our initNatives call
			MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			clinit.visitCode();
			clinit.visitLdcInsn(Type.getObjectType(owner.name));
			clinit.visitMethodInsn(Opcodes.INVOKESTATIC, loaderClassName(rmp), "doInit", "(Ljava/lang/Class;)V", false);
			clinit.visitInsn(Opcodes.RETURN);
			clinit.visitEnd();
			owner.methods.add(clinit);
		}
	}

	public static Stream<AbstractInsnNode> streamInsnList(InsnList list) {
		AbstractInsnNode[] array = list.toArray();
		return Stream.of(array);
	}

	public static Int2ObjectMap<Type> getAllLocalsWithType(MethodNode mn) {
		Int2ObjectMap<Type> ioe = new Int2ObjectOpenHashMap<>();
		int ctr = 0;
		if (!Modifier.isStatic(mn.access)) {
			ioe.put(0, OBJECT_TYPE);
			ctr += 1;
		}
		Type[] argumentTypes = Type.getArgumentTypes(mn.desc);
		for (Type type : argumentTypes) {
			Type argumentType = switch (type.getSort()) {
				case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Type.INT_TYPE;
				case Type.FLOAT -> Type.FLOAT_TYPE;
				case Type.LONG -> Type.LONG_TYPE;
				case Type.DOUBLE -> Type.DOUBLE_TYPE;
				case Type.ARRAY, Type.OBJECT -> OBJECT_TYPE;
				default -> throw new IllegalStateException("Unexpected value: " + type.getSort());
			};
			ioe.put(ctr, argumentType);
			ctr += argumentType.getSize();
		}
		for (AbstractInsnNode instruction : mn.instructions) {
			if (instruction instanceof VarInsnNode vi) {
				int var = vi.var;
				Type type = switch (vi.getOpcode()) {
					case ISTORE, ILOAD -> Type.INT_TYPE;
					case LLOAD, LSTORE -> Type.LONG_TYPE;
					case FLOAD, FSTORE -> Type.FLOAT_TYPE;
					case DLOAD, DSTORE -> Type.DOUBLE_TYPE;
					case ALOAD, ASTORE -> OBJECT_TYPE;
					default -> throw new IllegalStateException("Unexpected value: " + vi.getOpcode());
				};
				Type type1 = ioe.get(var);
				if (type1 != null && type1.getSort() != type.getSort())
					throw new IllegalStateException("multiple types for one var: old was " + type1 + ", new is " + type);
				ioe.put(var, type);
			}
		}
		return ioe;
	}

	public static List<Pair<Type, Integer>> splitLocals(MethodNode node) {
		Map<Pair<Type, Integer>, Integer> relocation = new HashMap<>();
		AtomicInteger localCounter = new AtomicInteger();
		if (!Modifier.isStatic(node.access)) {
			relocation.put(new Pair<>(OBJECT_TYPE, 0), 0);
			localCounter.getAndIncrement();
		}
		for (Type argumentType : Type.getArgumentTypes(node.desc)) {
			argumentType = switch (argumentType.getSort()) {
				case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Type.INT_TYPE;
				case Type.FLOAT -> Type.FLOAT_TYPE;
				case Type.LONG -> Type.LONG_TYPE;
				case Type.DOUBLE -> Type.DOUBLE_TYPE;
				case Type.ARRAY, Type.OBJECT -> OBJECT_TYPE;
				default -> throw new IllegalStateException("Unexpected value: " + argumentType.getSort());
			};
			relocation.put(new Pair<>(argumentType, localCounter.get()), localCounter.get());
			localCounter.addAndGet(argumentType.getSize());
		}
		streamInsnList(node.instructions)
				.filter(f -> f instanceof VarInsnNode)
				.map(it -> (VarInsnNode) it)
				.forEach(varInsnNode -> {
					int loadOp = varInsnNode.getOpcode();
					Type t = getTypeWithVarStore(loadOp);
					Pair<Type, Integer> typep = new Pair<>(t, varInsnNode.var);
					if (relocation.containsKey(typep)) return;
					relocation.put(typep, localCounter.getAndAdd(t.getSize()));
				});
		streamInsnList(node.instructions)
				.filter(f -> f instanceof VarInsnNode)
				.map(it -> (VarInsnNode) it)
				.forEach(varInsnNode -> {
					Type t = getTypeWithVarStore(varInsnNode.getOpcode());
					Pair<Type, Integer> typep = new Pair<>(t, varInsnNode.var);
					varInsnNode.var = relocation.get(typep);
				});
		streamInsnList(node.instructions)
				.filter(f -> f instanceof IincInsnNode)
				.map(it -> (IincInsnNode) it)
				.forEach(iincInsnNode -> iincInsnNode.var = relocation.get(new Pair<>(Type.INT_TYPE, iincInsnNode.var)));
		return relocation.keySet().stream().map(it -> new Pair<>(it.a, relocation.get(it))).toList();
	}

	public static Type getTypeWithVarStore(int op) {
		return switch (op) {
			case ISTORE, ILOAD -> Type.INT_TYPE;
			case LLOAD, LSTORE -> Type.LONG_TYPE;
			case FLOAD, FSTORE -> Type.FLOAT_TYPE;
			case DLOAD, DSTORE -> Type.DOUBLE_TYPE;
			case ALOAD, ASTORE -> OBJECT_TYPE;
			default -> throw new IllegalArgumentException("opcode " + op);
		};
	}

	public static MethodNode emptyCopyOf(MethodNode mn) {
		return new MethodNode(mn.access, mn.name, mn.desc, mn.signature, mn.exceptions == null ? null : mn.exceptions.toArray(String[]::new));
	}

	/**
	 * <ol>
	 *     <li>{@code ${J2CC_HOME}}, if set</li>
	 *     <li>CWD</li>
	 *     <li>Path of enclosing jar of Util.class, if exists</li>
	 * </ol>
	 *
	 * @return Order of resolution for important files
	 */
	@SneakyThrows
	public static List<Path> determineSearchPathOrder() {
		String j2ccHome = System.getProperty("J2CC_HOME");
		List<Path> order = new ArrayList<>();
		if (j2ccHome != null && !j2ccHome.isBlank()) {
			Path j2h = Path.of(j2ccHome);
			if (Files.exists(j2h) && Files.isDirectory(j2h)) {
				order.add(j2h);
			}
		}
		Path currentPath = Path.of(".").toAbsolutePath().normalize();
		order.add(currentPath);
		URL uLoc = Util.class.getProtectionDomain().getCodeSource().getLocation();
		if (uLoc.getProtocol().equals("file")) {
			Path pp = Path.of(uLoc.toURI());
			order.add(pp);
		}
		log.trace("Path search order: {}", order);
		return order;
	}

	public static DefaultCompiler.Target getTargetTripleForCurrentOs() {
		String resourcePrefix = Platform.RESOURCE_PREFIX;
		return new DefaultCompiler.Target(resourcePrefix + "-gnu", resourcePrefix);
	}

	public static boolean couldBeNull(Frame<SourceValue>[] vals, InsnList il, SourceValue sv) {
		return sv.insns.stream().anyMatch(abstractInsnNode -> couldPushNull(vals, il, abstractInsnNode));
	}

	private static boolean couldPushNull(Frame<SourceValue>[] vals, InsnList il, AbstractInsnNode e) {
		if (e instanceof LdcInsnNode li && li.cst == null) return true;
		int op = e.getOpcode();
		if (op == DUP || op == DUP_X1 || op == DUP_X2) {
			Frame<SourceValue> val = vals[il.indexOf(e)];
			SourceValue topStackToDup = val.getStack(val.getStackSize() - 1);
			return topStackToDup.insns.stream().anyMatch(it -> couldPushNull(vals, il, it));
		}
		int[] dogshitOps = {
				ACONST_NULL,
				ALOAD,
				AALOAD,
				DUP2,
				DUP2_X1,
				DUP2_X2,
				SWAP,
				GETSTATIC,
				GETFIELD,
				INVOKEVIRTUAL,
				INVOKESPECIAL,
				INVOKESTATIC,
				INVOKEINTERFACE,
				INVOKEDYNAMIC
		};
		for (int dogshitOp : dogshitOps) {
			if (dogshitOp == op) return true;
		}
		return false;
	}

	public static Path findInSearch(Function<Stream<Path>, Stream<Path>> pipelineMod) {
		Stream<Path> stream = pipelineMod.apply(determineSearchPathOrder().stream());
		return stream.findFirst().orElse(null);
	}

	public static Type getInternalUtilClassName(Configuration.RenamerSettings rs) {
		if (rs == null) return INTERNAL_UTIL;
		else return Type.getObjectType(rs.internalClassesPackageName+"/InternalUtil");
	}

	public interface ThrowingFunction<I, O> {
		O apply(I value) throws Throwable;

		@SneakyThrows
		default O applyUncheckedExc(I value) {
			return apply(value);
		}
	}

}
