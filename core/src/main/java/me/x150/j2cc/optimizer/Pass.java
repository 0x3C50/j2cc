package me.x150.j2cc.optimizer;

import me.x150.j2cc.tree.Workspace;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

public interface Pass {
	void optimize(ClassNode owner, MethodNode method, Workspace wsp) throws AnalyzerException;
}
