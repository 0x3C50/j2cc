package me.x150.j2cc.compiler.handler;

import me.x150.j2cc.compiler.CompilerContext;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.cppwriter.Printable;
import me.x150.j2cc.cppwriter.SwitchCaseCodeSegment;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

public class TableSwitchInsnHandler implements InsnHandler<TableSwitchInsnNode> {
	@Override
	public void compileInsn(Context context, CompilerContext<TableSwitchInsnNode> compilerContext) {
		Method method = compilerContext.compileTo();
		TableSwitchInsnNode instruction = compilerContext.instruction();
		Frame<BasicValue> frame = compilerContext.frames()[compilerContext.instructions().indexOf(instruction)];
		int stackHeight = frame.getStackSize();
		SwitchCaseCodeSegment s = new SwitchCaseCodeSegment(Printable.formatted("stack[$l].i", stackHeight - 1));
		for (int i = instruction.min; i <= instruction.max; i++) {
			s.newCase("$l", i);
			s.addStmt("goto $l", compilerContext.labels().get(instruction.labels.get(i - instruction.min)));
		}
		s.dflt();
		s.addStmt("goto $l", compilerContext.labels().get(instruction.dflt));
		method.addP(s);
	}
}
