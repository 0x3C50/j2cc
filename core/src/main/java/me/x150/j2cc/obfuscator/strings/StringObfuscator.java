package me.x150.j2cc.obfuscator.strings;

import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

@Log4j2
public class StringObfuscator extends ObfuscatorPass {

	private static final Random random = new Random();
	private static final String STR_DECODE_INDEX_NAME = "idx";
	private static final Type STR_DECODE_INDEX_DESC = Type.getMethodType("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;J)Ljava/lang/String;");
	private static final String STR_DECODE_CONST_NAME = "key";
	private static final Type STR_DECODE_CONST_DESC = Type.getMethodType("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode node = aClass.info().node();

			if (Obfuscator.skip(context.workspace(), context, node)) continue;
			boolean isAncient = node.version < Opcodes.V11;
			if (isAncient) {
				log.warn("Class {} is too old to support ConstantDynamic (< version 11), mocking functionality (WITHOUT CACHING!)", node.name);
			}
			List<MethodNode> methods = node.methods;
			for (MethodNode method : methods) {
				boolean willGetTranspiled = J2CC.willCompileMethod(context, node, method);

				if (willGetTranspiled || Obfuscator.skip(context, node.name, method))
					continue;
				processMethod(method, isAncient);
			}
		}
	}

	private void processMethod(MethodNode cp, boolean isAncient) {
		List<LdcInsnNode> insns = new ArrayList<>();
		for (AbstractInsnNode instruction : cp.instructions) {
			if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
				if (s.isEmpty()) continue; // nothing to do
				insns.add(ldc);
			}
		}
		int codeSizeCurrently = Util.guessCodeSize(cp.instructions);
		for (LdcInsnNode insn : insns) {
			String cst = (String) insn.cst;
			InsnList apply = new InsnList();
			int toUse = random.nextInt(0, 2);
			switch (toUse) {
				case 0 -> {
					long seed = random.nextLong();
					String t = StringDecoder.idx(null, null, String.class, cst, seed);
					if (!isAncient) {
						Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "rt/Str", STR_DECODE_INDEX_NAME,
								STR_DECODE_INDEX_DESC.getDescriptor(),
								false);
						ConstantDynamic condy = new ConstantDynamic("a", Type.getDescriptor(String.class), bsm, t, seed);
						apply.add(new LdcInsnNode(condy));
					} else {
						apply.add(new InsnNode(Opcodes.ACONST_NULL));
						apply.add(new InsnNode(Opcodes.ACONST_NULL));
						apply.add(new LdcInsnNode(Type.getType(String.class)));
						apply.add(new LdcInsnNode(t));
						apply.add(new LdcInsnNode(seed));
						apply.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "rt/Str", STR_DECODE_INDEX_NAME, STR_DECODE_INDEX_DESC.getDescriptor(), false));
					}
				}
				case 1 -> {
					int le = cst.length();
					byte[] key = new byte[le * 2];
					char[] mapped = new char[le];

					random.nextBytes(key);

					for (int i = 0; i < key.length; i += 2) {
						mapped[i / 2] = (char) (key[i] << 8 | key[i + 1]);
					}

					String k = new String(mapped);
					String t = StringDecoder.key(null, null, String.class, k, cst);
					if (!isAncient) {
						Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "rt/Str", STR_DECODE_CONST_NAME,
								STR_DECODE_CONST_DESC.getDescriptor(),
								false);
						ConstantDynamic condy = new ConstantDynamic("a", Type.getDescriptor(String.class), bsm, t, k);
						apply.add(new LdcInsnNode(condy));
					} else {
						apply.add(new InsnNode(Opcodes.ACONST_NULL));
						apply.add(new InsnNode(Opcodes.ACONST_NULL));
						apply.add(new LdcInsnNode(Type.getType(String.class)));
						apply.add(new LdcInsnNode(t));
						apply.add(new LdcInsnNode(k));
						apply.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "rt/Str", STR_DECODE_CONST_NAME,
								STR_DECODE_CONST_DESC.getDescriptor(), false));
					}
				}
				default -> throw new IllegalStateException();
			}
			int codeSizeOfThing = Util.guessCodeSize(apply);
			if (codeSizeCurrently + codeSizeOfThing > 65535) {
				log.error("Applying string obf would increase code size to {} bytes. Let's not do that", codeSizeCurrently + codeSizeOfThing);
				break;
			}
			cp.instructions.insertBefore(insn, apply);
			cp.instructions.remove(insn);
			codeSizeCurrently += codeSizeOfThing;
			codeSizeCurrently -= 3; // removed ldc
		}
	}

	@Override
	public Collection<ClassNode> getAdditionalClasses() {
		try {
			ClassReader classReader = new ClassReader(StringDecoder.class.getName());
			ClassNode cn = new ClassNode();
			classReader.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.EXPAND_FRAMES);
			cn.name = "rt/Str";
			cn.version = Opcodes.V1_7;
			return List.of(cn);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
