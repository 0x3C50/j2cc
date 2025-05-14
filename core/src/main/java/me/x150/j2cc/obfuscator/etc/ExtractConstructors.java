package me.x150.j2cc.obfuscator.etc;

import lombok.SneakyThrows;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class ExtractConstructors extends ObfuscatorPass {
	private static final HexFormat hf = HexFormat.ofDelimiter("").withLowerCase();
	private static final Logger log = LogManager.getLogger(ExtractConstructors.class);

	@SneakyThrows
	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		byte[] mName = new byte[16];
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode node = aClass.info().node();
			if (Obfuscator.skip(context, node)) continue;
			if (Modifier.isInterface(node.access) && node.fields.stream().anyMatch(it -> Modifier.isFinal(it.access))) {
				log.warn("Can't extract constructors from {}: Is interface, and final fields are present (cannot remove final modifier from fields in interface)", node.name);
				continue;
			}
			List<MethodNode> newMethods = new ArrayList<>();
			for (MethodNode method : node.methods) {
				if (!method.name.startsWith("<") || Obfuscator.skip(context, node.name, method)) continue;
				if (method.name.equals("<init>")) {
					// we cant do <init>s
					continue;
				}
				ThreadLocalRandom.current().nextBytes(mName);
				// found a clinit
				String extractedName = "_" + hf.formatHex(mName);

				MethodNode newConstructor = new MethodNode(method.access, method.name, method.desc, method.signature, method.exceptions.toArray(String[]::new));
				method.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
				method.name = extractedName;
				method.desc = "()V";
				method.signature = null;
				method.exceptions = null;
				newConstructor.visitMethodInsn(Opcodes.INVOKESTATIC, node.name, method.name, "()V", false);
				newConstructor.visitInsn(Opcodes.RETURN);
				newMethods.add(newConstructor);
			}
			node.methods.addAll(newMethods);
			if (!newMethods.isEmpty()) {
				// make any static final fields non-final, since they're now being accessed from an external method
				for (FieldNode field : node.fields) {
					if (Modifier.isStatic(field.access)) field.access = field.access & ~Modifier.FINAL;
				}
			}
		}
	}

}
