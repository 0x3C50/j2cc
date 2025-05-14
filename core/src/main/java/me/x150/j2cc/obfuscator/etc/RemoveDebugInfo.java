package me.x150.j2cc.obfuscator.etc;

import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import org.objectweb.asm.tree.*;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class RemoveDebugInfo extends ObfuscatorPass {
	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode node = aClass.info().node();
			if (Obfuscator.skip(context.workspace(), context, node)) continue;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof LineNumberNode) method.instructions.remove(instruction);
				}
				Optional.ofNullable(method.localVariables).ifPresent(List::clear);
				Optional.ofNullable(method.visibleLocalVariableAnnotations).ifPresent(List::clear);
				Optional.ofNullable(method.invisibleLocalVariableAnnotations).ifPresent(List::clear);
				method.signature = null;
			}
			for (FieldNode field : node.fields) {
				field.signature = null;
			}
			node.signature = null;
		}
	}
}
