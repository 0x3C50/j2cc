package me.x150.j2cc.util;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MethodInliner implements Opcodes {

	public static InsnList generateInvokeReplacement(ClassNode ownerOfInvoker, MethodNode invoker, ClassNode ownerOfInvoked, MethodNode invokedMethod, MethodInsnNode call, InsnList clonedMethodBody) throws AnalyzerException {
		InsnList rl = new InsnList();
		Label returnTarget = new Label();
		LabelNode returnNode = new LabelNode(returnTarget);

		Analyzer<BasicValue> bv = new Analyzer<>(new BasicInterpreter());
		Frame<BasicValue>[] framesInvoker = bv.analyzeAndComputeMaxs(ownerOfInvoker.name, invoker);
		Frame<BasicValue>[] framesInvokee = bv.analyzeAndComputeMaxs(ownerOfInvoked.name, invokedMethod);

		LocalVariablesSorter lvs = new LocalVariablesSorter(invoker.access, invoker.desc, null);
		invoker.accept(lvs);

		// At this point, we're in place of the invoking method insn node
		// All arguments are on the stack
		// For simplicity, we assume the instance is another argument in case of invokevirtual

		// 1. Figure out the arguments properly
		List<Type> argumentsOfInvokee = new ArrayList<>();
		if (call.getOpcode() != Opcodes.INVOKESTATIC) {
			// We have another "argument"
			argumentsOfInvokee.add(Type.getObjectType(ownerOfInvoked.name));
		}
		argumentsOfInvokee.addAll(List.of(Type.getArgumentTypes(invokedMethod.desc)));

		Map<Integer, Integer> localsMap = new HashMap<>();

		int[] argsLocals = new int[argumentsOfInvokee.size()];
		// 2. Figure out which slots the locals go into
		int localCounter = 0;
		for (int i = 0; i < argumentsOfInvokee.size(); i++) {
			Type type = argumentsOfInvokee.get(i);
			argsLocals[i] = localCounter;
			localCounter += type.getSize();
		}

		for (int i = argumentsOfInvokee.size() - 1; i >= 0; i--) {
			// go through the arguments in reverse, store them in a new local
			Type type = argumentsOfInvokee.get(i);
			int local = lvs.newLocal(type);
			int targetLocal = argsLocals[i];
			rl.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), local));
			localsMap.put(targetLocal, local); // map all occurrences of loads and stores on the target local of this var to the new one
		}

		// 3. Pop rest of stack in case of throwing
		// If the method has a try catch block ANYWHERE, the stack might be cleared in that point
		// we need to restore the stack if that happens
		// to do that, we first need to empty it
		InsnList instructions = invoker.instructions;
		Frame<BasicValue> callStack = framesInvoker[instructions.indexOf(call)];
		int rest = callStack.getStackSize() - argumentsOfInvokee.size();
		// we only need to do this if we have stack elements beyond the call, and we have try catch blocks in the inlinee
		boolean applicable = rest != 0 && invokedMethod.tryCatchBlocks != null && !invokedMethod.tryCatchBlocks.isEmpty();
		int[] restStackVars = new int[rest];
		Type[] stackTypes = new Type[rest];
		if (applicable) {
			for (int i = restStackVars.length - 1; i >= 0; i--) {
				BasicValue stack = callStack.getStack(i);
				Type type = stack.getType();
				stackTypes[i] = type;
				restStackVars[i] = lvs.newLocal(type);
				rl.add(new VarInsnNode(type.getOpcode(ISTORE), restStackVars[i]));
			}
		}

		// 4. Inline the method body, remapping applicable insns
		for (int i = 0; i < clonedMethodBody.size(); i++) {
			AbstractInsnNode abstractInsnNode = clonedMethodBody.get(i);
			switch (abstractInsnNode) {
				case VarInsnNode var -> {
					int opcode = var.getOpcode();
					int targetLocal = var.var;
					Type targetType = switch (opcode) {
						case ISTORE, ILOAD -> Type.INT_TYPE;
						case LSTORE, LLOAD -> Type.LONG_TYPE;
						case FSTORE, FLOAD -> Type.FLOAT_TYPE;
						case DSTORE, DLOAD -> Type.DOUBLE_TYPE;
						case ASTORE, ALOAD -> Util.OBJECT_TYPE;
						default -> throw new IllegalStateException("Unexpected value: " + opcode);
					};
					var.var = localsMap.computeIfAbsent(targetLocal, it -> lvs.newLocal(targetType));
					rl.add(abstractInsnNode);
				}
				case IincInsnNode iinc -> {
					int targetLocal = iinc.var;
					iinc.var = localsMap.computeIfAbsent(targetLocal, it -> lvs.newLocal(Type.INT_TYPE));
					rl.add(abstractInsnNode);
				}
				case InsnNode in -> {
					int opcode = in.getOpcode();
					if (opcode >= IRETURN && opcode <= RETURN) {
						// at this point, the method terminates
						// BUT: there might still be stack elements left over
						// those need to go
						Frame<BasicValue> frame = framesInvokee[i];
						int stackSize = frame == null ? 1 : frame.getStackSize();

						if (applicable || stackSize != 1) {
							Type retType = Type.getReturnType(invokedMethod.desc);
							boolean isVoid = retType.getSort() == Type.VOID;
							int retLocal = 0;
							if (!isVoid) {
								retLocal = lvs.newLocal(retType);
								rl.add(new VarInsnNode(retType.getOpcode(ISTORE), retLocal));
							}

							// we already "popped" one stack element, start at -2 instead of -1
							for (int size = stackSize - 2; size >= 0; size--) {
								BasicValue stackEl = frame.getStack(size);
								int popOpcode = stackEl.getSize() == 1 ? POP : POP2;
								rl.add(new InsnNode(popOpcode));
							}

							// push stack elements back on from step 3
							if (applicable) for (int e = 0; e < restStackVars.length; e++) {
								int restStackVar = restStackVars[e];
								Type t = stackTypes[e];
								rl.add(new VarInsnNode(t.getOpcode(ILOAD), restStackVar));
							}

							if (!isVoid) rl.add(new VarInsnNode(retType.getOpcode(ILOAD), retLocal));
						}
						// return needs to jump to the return label
						rl.add(new JumpInsnNode(GOTO, returnNode));
					} else {
						// nothing to do
						rl.add(abstractInsnNode);
					}
				}
				case null, default -> rl.add(abstractInsnNode);
			}
		}

		rl.add(returnNode);

		return rl;
	}

	public static void inline(ClassNode ownerOfInvokerMethod, MethodNode invokerMethod, ClassNode ownerOfInvokedMethod, MethodNode invokedMethod) throws AnalyzerException {
		List<MethodInsnNode> toReplace = new ArrayList<>();
		for (AbstractInsnNode instruction : invokerMethod.instructions) {
			if (instruction instanceof MethodInsnNode min && min.owner.equals(ownerOfInvokedMethod.name) && min.name.equals(invokedMethod.name) && min.desc.equals(invokedMethod.desc)) {
				toReplace.add(min);
			}
		}
		for (MethodInsnNode methodInsnNode : toReplace) {
			Map<LabelNode, LabelNode> labelNodeLabelNodeMap = Util.cloneLabels(invokedMethod.instructions);
			InsnList abstractInsnNodes1 = Util.cloneInsnList(labelNodeLabelNodeMap, invokedMethod.instructions);
			InsnList abstractInsnNodes = generateInvokeReplacement(ownerOfInvokerMethod, invokerMethod, ownerOfInvokedMethod, invokedMethod, methodInsnNode, abstractInsnNodes1);
			invokerMethod.instructions.insertBefore(methodInsnNode, abstractInsnNodes);
			invokerMethod.instructions.remove(methodInsnNode);
			for (TryCatchBlockNode tryCatchBlock : invokedMethod.tryCatchBlocks) {
				TryCatchBlockNode e = new TryCatchBlockNode(
						labelNodeLabelNodeMap.get(tryCatchBlock.start),
						labelNodeLabelNodeMap.get(tryCatchBlock.end),
						labelNodeLabelNodeMap.get(tryCatchBlock.handler),
						tryCatchBlock.type
				);
				e.visibleTypeAnnotations = tryCatchBlock.visibleTypeAnnotations;
				e.invisibleTypeAnnotations = tryCatchBlock.invisibleTypeAnnotations;
				invokerMethod.tryCatchBlocks.add(e);
			}
		}
	}
}
