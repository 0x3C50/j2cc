package me.x150.j2cc.optimizer;

import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.util.Util;
import me.x150.j2cc.util.simulation.SimulatorInterpreter;
import me.x150.j2cc.util.simulation.SimulatorValue;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

@Log4j2
public class LocalPropagator implements Pass {


	@Override
	public void optimize(ClassNode owner, MethodNode method, Workspace wsp) throws AnalyzerException {
		boolean isStatic = Modifier.isStatic(method.access);

		Frame<SimulatorValue>[] frames;

		try {
			frames = new Analyzer<>(new SimulatorInterpreter(wsp, false)).analyzeAndComputeMaxs(owner.name, method);
		} catch (AnalyzerException ase) {
			log.debug("Failed to analyse instruction {}: ", Util.stringifyInstruction(ase.node, Map.of()), ase);
			throw ase;
		}

		Map<VarInsnNode, SimulatorValue> replacements = new HashMap<>();

		InsnList instructions = method.instructions;
		for (AbstractInsnNode instruction : instructions) {
			int i = instructions.indexOf(instruction);

			Frame<SimulatorValue> frame = frames[i];
			if (frame == null) continue;

			if (instruction instanceof VarInsnNode vi && vi.getOpcode() >= Opcodes.ILOAD && vi.getOpcode() <= Opcodes.ALOAD) {
				int v = vi.var;
				if (v == 0 && !isStatic) {
					continue;
				}

				SimulatorValue theLocal = frame.getLocal(v);

				if (!theLocal.valueKnown() || theLocal.type().getSort() >= Type.ARRAY) continue;
				replacements.put(vi, theLocal);
			}
		}
		replacements.forEach((varInsnNode, ldcInsnNode) -> {
			Object cst = ldcInsnNode.value();
			AbstractInsnNode producer = switch (cst) {
				case null -> new InsnNode(Opcodes.ACONST_NULL);
				case Integer i when i >= -1 && i <= 5 -> new InsnNode(Opcodes.ICONST_0 + i);
				default -> new LdcInsnNode(cst);
			};
			method.instructions.insertBefore(varInsnNode, producer);
			method.instructions.remove(varInsnNode);
		});
		if (!replacements.isEmpty())
			log.info("[{}] Optimized {} local loads", owner.name, replacements.size());

	}

}
