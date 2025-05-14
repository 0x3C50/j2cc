package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.cppwriter.Printable;
import me.x150.j2cc.cppwriter.SwitchCaseCodeSegment;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.List;

public class LookupSwitchInsnHandler implements InsnHandler<LookupSwitchInsnNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<LookupSwitchInsnNode> compilerContext) {
		Method method = compilerContext.compileTo();
		LookupSwitchInsnNode instruction = compilerContext.instruction();
		Frame<BasicValue> frame = compilerContext.frames()[compilerContext.instructions().indexOf(instruction)];
		int stackHeight = frame.getStackSize();
		SwitchCaseCodeSegment s = new SwitchCaseCodeSegment(Printable.formatted("stack[$l].i", stackHeight - 1));
		List<Integer> keys = instruction.keys;
		for (int i = 0; i < keys.size(); i++) {
			Integer key = keys.get(i);
			s.newCase("$l", key);
			s.addStmt("goto $l", compilerContext.labels().get(instruction.labels.get(i)));
		}
		s.dflt();
		s.addStmt("goto $l", compilerContext.labels().get(instruction.dflt));
		method.addP(s);
	}
}
