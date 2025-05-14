package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import org.objectweb.asm.tree.LabelNode;

import java.util.Map;

public class LabelNodeHandler implements InsnHandler<LabelNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<LabelNode> compilerContext) {
		Map<LabelNode, String> labels = compilerContext.labels();
		if (!labels.containsKey(compilerContext.instruction()))
			labels.put(compilerContext.instruction(), "lab" + labels.size());
		compilerContext.compileTo().add("$l:", labels.get(compilerContext.instruction()));
		// we have a label where we could technically jump into
		// since we dont know from *where* we jump, or if there's multiple jump points,
		// all variables at this point are M(source1, source2, source..., sourceN)
		// we cant do much with that information, and im too lazy to actually figure out which are M(sourceX) (=sourceX)
		// so just assume all of them are hot now.
		compilerContext.compileTo().clearScopes();
	}
}
