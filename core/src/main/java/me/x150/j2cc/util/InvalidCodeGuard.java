package me.x150.j2cc.util;

import lombok.extern.log4j.Log4j2;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Optional;

@Log4j2
public class InvalidCodeGuard {
	private ClassNode originalClass = new ClassNode();
	private static final StackWalker sw = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	public void init(ClassNode mn) {
		originalClass = new ClassNode();
		mn.accept(originalClass);
	}

	public boolean checkAndRestoreIfNeeded(ClassNode cn, boolean log) {
		boolean any = false;
		for (MethodNode origOkNode : originalClass.methods) {
			CodeSizeEvaluator cse = new CodeSizeEvaluator(null);
			Optional<MethodNode> any1 = cn.methods.stream()
					.filter(f -> f.name.equals(origOkNode.name) && f.desc.equals(origOkNode.desc)).findAny();
			if (any1.isEmpty()) continue; // method was deleted? ok sure
			MethodNode methodNode = any1.get();
			methodNode.accept(cse);
			if (cse.getMaxSize() >= 0xFFFF) {
//				origOkNode.accept(methodNode);
				MethodNode copy = Util.emptyCopyOf(methodNode);
				origOkNode.accept(copy);
				cn.methods.set(cn.methods.indexOf(methodNode), copy);
				if (log) {
					Class<?> caller = sw.getCallerClass();
					InvalidCodeGuard.log.warn("{} generated too much code for method {}.{}{} ({} bytes). Rolling back...", caller.getSimpleName(), cn.name, methodNode.name, methodNode.desc, cse.getMaxSize());
					CodeSizeEvaluator cs = new CodeSizeEvaluator(null);
					copy.accept(cs);
					InvalidCodeGuard.log.info(cs.getMaxSize());
				}
				any = true;
			}
		}
		return any;
	}
}
