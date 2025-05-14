package me.x150.j2cc.optimizer;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.tree.Workspace;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Log4j2
public class PopInliner implements Pass, Opcodes {
	private static final Int2IntMap im = new Int2IntOpenHashMap();

	static {
		loadOffsets(
				IALOAD, 2,
				LALOAD, 2,
				FALOAD, 2,
				DALOAD, 2,
				AALOAD, 2,
				BALOAD, 2,
				CALOAD, 2,
				SALOAD, 2,

				ISTORE, 1,
				LSTORE, 2,
				FSTORE, 1,
				DSTORE, 2,
				ASTORE, 1,
				LASTORE, 4,
				FASTORE, 3,
				DASTORE, 4,
				AASTORE, 3,
				BASTORE, 3,
				CASTORE, 3,
				SASTORE, 3,
				POP, 1,
				POP2, 2,
				DUP, 1,
				DUP_X1, 1+1, // a b c -> a -> a c b c
				DUP_X2, 1+2, // a b c d -> a -> a d b c d
				DUP2, 2,
				DUP2_X1, 2+1, // see above
				DUP2_X2, 2+2, // see above
				SWAP, 2,

				IADD, 2,
				LADD, 4,
				FADD, 2,
				DADD, 4,

				ISUB, 2,
				LSUB, 4,
				FSUB, 2,
				DSUB, 4,

				IMUL, 2,
				LMUL, 4,
				FMUL, 2,
				DMUL, 4,

				IDIV, 2,
				LDIV, 4,
				FDIV, 2,
				DDIV, 4,

				IREM, 2,
				LREM, 4,
				FREM, 2,
				DREM, 4,

				INEG, 1,
				LNEG, 2,
				FNEG, 1,
				DNEG, 2,

				ISHL, 2,
				LSHL, 3,

				ISHR, 2,
				LSHR, 3,

				IUSHR, 2,
				LUSHR, 3,

				IAND, 2,
				LAND, 4,
				IOR, 2,
				LOR, 4,
				IXOR, 2,
				LXOR, 4,

				IINC, 1,

				I2L, 1,
				I2F, 1,
				I2D, 1,
				I2B, 1,
				I2C, 1,
				I2S, 1,

				F2L, 1,
				F2I, 1,
				F2D, 1,

				L2I, 2,
				L2F, 2,
				L2D, 2,

				D2I, 2,
				D2F, 2,
				D2L, 2,

				LCMP, 4,
				FCMPL, 2,
				FCMPG, 2,
				DCMPG, 4,
				DCMPL, 4,

				IFEQ, 1,
				IFNE, 1,
				IFLT, 1,
				IFGE, 1,
				IFGT, 1,
				IFLE, 1,

				IF_ICMPEQ, 2,
				IF_ICMPNE, 2,
				IF_ICMPLT, 2,
				IF_ICMPGE, 2,
				IF_ICMPGT, 2,
				IF_ICMPLE, 2,

				IF_ACMPEQ, 2,
				IF_ACMPNE, 2,

				TABLESWITCH, 1,
				LOOKUPSWITCH, 1,
				IRETURN, 1,
				FRETURN, 1,
				ARETURN, 1,
				DRETURN, 2,
				LRETURN, 2,
				GETFIELD, 1,
				NEWARRAY, 1,
				ANEWARRAY, 1,
				ARRAYLENGTH, 1,
				ATHROW, 1,
				CHECKCAST, 1,
				INSTANCEOF, 1,
				MONITORENTER, 1,
				MONITOREXIT, 1,
				IFNULL, 1,
				IFNONNULL, 1
		);
	}

	private static void loadOffsets(int... from) {
		int length = from.length;
		for (int i = 0; i < length; i += 2) {
			im.put(from[i], from[i + 1]);
		}
	}

	private static boolean canRemove(AbstractInsnNode ins) {
		int op = ins.getOpcode();
		boolean isCompletelyStateless = (op >= Opcodes.ACONST_NULL && op <= Opcodes.SIPUSH) || op == Opcodes.DUP;
		if (isCompletelyStateless) return true;
		if (ins instanceof LdcInsnNode li) {
			// this *could* have side effects so dont do anything
			return !(li.cst instanceof ConstantDynamic) && !(li.cst instanceof Handle) && !(li.cst instanceof Type);
		}
		return false;
	}

	private static int getNSC(AbstractInsnNode ain) {
		int opcode = ain.getOpcode();
		if (im.containsKey(opcode)) return im.get(opcode);
		return switch (opcode) {
			case PUTSTATIC, PUTFIELD ->
					Type.getType(((FieldInsnNode) ain).desc).getSize() + (opcode == PUTFIELD ? 1 : 0);
			case INVOKEINTERFACE, INVOKESTATIC, INVOKEVIRTUAL, INVOKESPECIAL ->
					(Type.getArgumentsAndReturnSizes(((MethodInsnNode) ain).desc) >> 2) - (opcode == INVOKESTATIC ? 1 : 0);
			case INVOKEDYNAMIC -> (Type.getArgumentsAndReturnSizes(((InvokeDynamicInsnNode) ain).desc) >> 2) - 1;
			case MULTIANEWARRAY -> ((MultiANewArrayInsnNode) ain).dims;
			default -> 0;
		};
	}

	SourceValue[] getConsumedElements(Frame<SourceValue> frame, int words) {
		int ow = words;
		int numberElements = frame.getStackSize();
		List<SourceValue> sv = new ArrayList<>();
		while (words > 0) {
			SourceValue topStack = frame.getStack(--numberElements);
			words -= topStack.size;
			sv.addFirst(topStack);
		}
		if (words != 0) {
			throw new IllegalStateException("unclean stack: expected " + ow + " words to be consumed, but stack contained " + sv.stream().map(sourceValue -> "" + sourceValue.size).collect(Collectors.joining(", ")));
		}
		return sv.toArray(SourceValue[]::new);
	}

	@Override
	public void optimize(ClassNode owner, MethodNode method, Workspace wsp) throws AnalyzerException {
		Frame<SourceValue>[] sv = new Analyzer<>(new SourceInterpreter()).analyzeAndComputeMaxs(owner.name, method);
		Frame<BasicValue>[] bv = new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);

		Map<AbstractInsnNode, List<AbstractInsnNode>> producerNodeToConsumerNode = new HashMap<>();

		for (int j = 0; j < sv.length; j++) {
			Frame<SourceValue> sourceValueFrame = sv[j];
			if (sourceValueFrame == null) continue;
			AbstractInsnNode currentInsn = method.instructions.get(j);
			int wordsConsumed = getNSC(currentInsn);
			SourceValue[] consumedElements = getConsumedElements(sourceValueFrame, wordsConsumed);
			for (SourceValue consumedElement : consumedElements) {
				for (AbstractInsnNode insn : consumedElement.insns) {
					producerNodeToConsumerNode.computeIfAbsent(insn, _ -> new ArrayList<>()).add(currentInsn);
				}
			}
		}

		// all insns that have more than one consumer
		/*
		ldc 123  ◄───┐  ◄───────────────────────────────────┐ This instruction has two consumers, dup and pop
					 │                                      │ Thus we can't remove it
		dup  ────────┘  ◄────────────────────────────────┐  │
														 │  │
		invokestatic doSomething(Ljava/lang/String;)V  ──┘  │
															│
		pop  ───────────────────────────────────────────────┘
		 */
		List<AbstractInsnNode> insnsThatProduceForMoreThanOneInsn = producerNodeToConsumerNode.entrySet().stream().filter(g -> g.getValue().size() > 1).map(Map.Entry::getKey).toList();

		producerNodeToConsumerNode.clear();

		long nRemoved = 0;

		List<AbstractInsnNode> toRemove = new ArrayList<>();
		Map<AbstractInsnNode, AbstractInsnNode> toReplace = new HashMap<>();

		for (AbstractInsnNode instruction : method.instructions) {
			int op = instruction.getOpcode();
			int idx = method.instructions.indexOf(instruction);
			Frame<SourceValue> frame = sv[idx];
			if (frame == null) continue;
			Frame<BasicValue> frameB = bv[idx];

			if (op == Opcodes.POP) {
				// one stack element
				SourceValue top = frame.getStack(frame.getStackSize() - 1);
				if (top.insns.isEmpty()) continue; // catch block most likely
				boolean b = top.insns.stream().allMatch(PopInliner::canRemove);
				if (b && top.insns.stream().noneMatch(insnsThatProduceForMoreThanOneInsn::contains)) {
					toRemove.add(instruction);
					toRemove.addAll(top.insns);
					nRemoved += top.insns.size() + 1;
				}
			} else if (op == Opcodes.POP2) {
				BasicValue topS = frameB.getStack(frameB.getStackSize() - 1);
				if (topS.getSize() == 2) {
					// pop2 removes one element, it's this one
					SourceValue top = frame.getStack(frame.getStackSize() - 1);
					// this shouldn't ever happen
					if (top.insns.isEmpty())
						throw new AssertionError("instruction source list for double word stack element is empty; shouldn't be");
					if (top.insns.stream().allMatch(PopInliner::canRemove) && top.insns.stream().noneMatch(insnsThatProduceForMoreThanOneInsn::contains)) {

						toRemove.add(instruction);
						toRemove.addAll(top.insns);

						nRemoved += top.insns.size() + 1;
					}
				} else {
					BasicValue bottomS = frameB.getStack(frameB.getStackSize() - 2);
					if (bottomS.getSize() != 1)
						throw new AssertionError("pop2: top stack size is " + topS.getSize() + ", bottom is " + bottomS.getSize() + ". would pop unclean");
					SourceValue top = frame.getStack(frame.getStackSize() - 1);
					SourceValue bottom = frame.getStack(frame.getStackSize() - 2);
					// we have a 2nd value below this one, and exception handlers clear the stack before pushing the exception. this should be impossible
					if (top.insns.isEmpty())
						throw new AssertionError("insn source list for top stack element is empty, but can't come from an exception handler");
					boolean canRemoveTop = top.insns.stream().allMatch(PopInliner::canRemove);
					boolean canRemoveBottom = !bottom.insns.isEmpty() && bottom.insns.stream().allMatch(PopInliner::canRemove);
					if (top.insns.stream().noneMatch(insnsThatProduceForMoreThanOneInsn::contains)
							&& bottom.insns.stream().noneMatch(insnsThatProduceForMoreThanOneInsn::contains)) {
						if (canRemoveTop && canRemoveBottom) {
							// perfect, just nuke the whole instruction
							toRemove.add(instruction);
							toRemove.addAll(top.insns);
							toRemove.addAll(bottom.insns);
							nRemoved += top.insns.size() + bottom.insns.size() + 1;
						} else if (canRemoveTop || canRemoveBottom) {
							// okay, we can remove one element but not the other one. just remove the one element and set pop2 to pop1
							toRemove.addAll((canRemoveTop ? top : bottom).insns);
							nRemoved += (canRemoveTop ? top : bottom).insns.size();
							toReplace.put(instruction, new InsnNode(Opcodes.POP));
						}
					}
				}

			}
		}
		for (AbstractInsnNode abstractInsnNode : toRemove) {
			method.instructions.remove(abstractInsnNode);
		}
		toReplace.forEach(method.instructions::set);
		if (nRemoved > 0) log.debug("Removed {} insns", nRemoved);
	}

}
