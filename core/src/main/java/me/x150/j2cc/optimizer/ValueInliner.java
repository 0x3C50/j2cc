package me.x150.j2cc.optimizer;

import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.util.InstructionPatchList;
import me.x150.j2cc.util.Util;
import me.x150.j2cc.util.simulation.SimulatorInterpreter;
import me.x150.j2cc.util.simulation.SimulatorValue;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.util.Printer;

@Log4j2
public class ValueInliner implements Pass, Opcodes {

	private static Frame<SimulatorValue> simulate(AbstractInsnNode ai, SimulatorInterpreter si, Frame<SimulatorValue> input, Type... expectedKnownStackTypes) throws AnalyzerException {
		Frame<SimulatorValue> nf = new Frame<>(input);
		int nV = expectedKnownStackTypes.length;
		if (nf.getStackSize() < nV)
			throw new IllegalStateException("Expected " + nV + " stack elements, found " + nf.getStackSize());
		for (int i = expectedKnownStackTypes.length - 1; i >= 0; i--) {
			int offsetFromTop = expectedKnownStackTypes.length - i;
			SimulatorValue stack = nf.getStack(nf.getStackSize() - offsetFromTop);
			if (!stack.valueKnown()) return null; // cant simulate
			Type type = stack.type();
			Type expected = expectedKnownStackTypes[i];
			if (expected != null && !expected.equals(type))
				throw new IllegalStateException("Stack " + (nf.getStackSize() - offsetFromTop) + ": expected type " + expected + ", got " + type);
		}
		nf.execute(ai, si);
		return nf;
	}

	@Override
	public void optimize(ClassNode owner, MethodNode method, Workspace wsp) throws AnalyzerException {
		SimulatorInterpreter simint = new SimulatorInterpreter(wsp, true);
		Analyzer<SimulatorValue> sv = new Analyzer<>(simint);
		Frame<SimulatorValue>[] frames = sv.analyzeAndComputeMaxs(owner.name, method);

		InsnList instructions = method.instructions;

		InstructionPatchList ipl = new InstructionPatchList(instructions);

		for (AbstractInsnNode instruction : instructions) {
			int i = instructions.indexOf(instruction);

			Frame<SimulatorValue> frame = frames[i];
			if (frame == null) continue;

			try {
				if (instruction instanceof LookupSwitchInsnNode || instruction instanceof TableSwitchInsnNode) {
					SimulatorValue theIntValue = frame.getStack(frame.getStackSize() - 1);
					if (!theIntValue.valueKnown()) continue;

					int switchC = (int) theIntValue.value();

					LabelNode real;
					if (instruction instanceof LookupSwitchInsnNode ji) {
						int theIndex = ji.keys.indexOf(switchC);
						if (theIndex == -1) real = ji.dflt;
						else real = ji.labels.get(theIndex);
					} else {
						TableSwitchInsnNode ji = (TableSwitchInsnNode) instruction;
						if (switchC > ji.max) real = ji.dflt;
						else real = ji.labels.get(switchC - ji.min);
					}
					ipl.set(instruction, Util.makeIL(il -> {
						il.add(new InsnNode(Opcodes.POP));
						il.add(new JumpInsnNode(Opcodes.GOTO, real));
					}));
					log.trace("[{}.{}] Inlined switch {} to key {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()], switchC);
				} else if (instruction instanceof JumpInsnNode ji) {
					int opc = ji.getOpcode();
					switch (opc) {
						case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
							SimulatorValue theIntValue = frame.getStack(frame.getStackSize() - 1);
							if (!theIntValue.valueKnown()) continue;

							int value = (int) theIntValue.value();

							boolean c = switch (opc) {
								case IFEQ -> value == 0;
								case IFNE -> value != 0;
								case IFLT -> value < 0;
								case IFLE -> value <= 0;
								case IFGT -> value > 0;
								case IFGE -> value >= 0;
								default -> throw new IllegalStateException("Unexpected value: " + opc);
							};
							InsnList gg = new InsnList();
							gg.add(new InsnNode(Opcodes.POP));
							if (c) {
								// branch succeeds
								log.trace("[{}.{}] Inlined conditional {} {} to goto", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()], value);
								gg.add(new JumpInsnNode(Opcodes.GOTO, ji.label));
							} else {
								log.trace("[{}.{}] Inlined conditional {} {} to nop", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()], value);
							}
							ipl.set(ji, gg);
						}
						case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
							SimulatorValue tp = frame.getStack(frame.getStackSize() - 1);
							SimulatorValue bt = frame.getStack(frame.getStackSize() - 2);
							if (!tp.valueKnown() || !bt.valueKnown()) continue;
							int value1 = ((int) bt.value());
							int value2 = (int) tp.value();

							boolean c = switch (opc) {
								case IF_ICMPEQ -> value1 == (value2);
								case IF_ICMPNE -> value1 != (value2);
								case IF_ICMPLT -> value1 < value2;
								case IF_ICMPLE -> value1 <= value2;
								case IF_ICMPGT -> value1 > value2;
								case IF_ICMPGE -> value1 >= value2;
								default -> throw new IllegalStateException("Unexpected value: " + opc);
							};
							InsnList gg = new InsnList();
							gg.add(new InsnNode(Opcodes.POP2));
							if (c) {
								log.trace("[{}.{}] Inlined conditional {} {} {} to goto", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()], value1, value2);
								// branch succeeds
								gg.add(new JumpInsnNode(Opcodes.GOTO, ji.label));
							} else {
								log.trace("[{}.{}] Inlined conditional {} {} {} to nop", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()], value1, value2);
							}
							ipl.set(ji, gg);
						}
					}
				} else if (instruction instanceof InsnNode ii) {
					int op = ii.getOpcode();
					switch (op) {
						case IXOR, ISHL, ISHR, IADD, IMUL, IDIV, IREM, IUSHR, IAND, IOR, ISUB -> {
							Frame<SimulatorValue> result = simulate(ii, simint, frame, Type.INT_TYPE, Type.INT_TYPE);
							if (result == null) continue;
							SimulatorValue top = result.getStack(result.getStackSize() - 1);
							// since this is a known insn and both the stack values are known (simulate), we can safely assume the result is known
							// EXCEPT FOR: int division x/0
							if (!top.valueKnown()) continue;
							int val = ((int) top.value());
							ipl.set(instruction, Util.makeIL(il -> {
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new LdcInsnNode(val));
							}));
							log.trace("[{}.{}] Inlined unary {} {} to {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()], frame.getStack(frame.getStackSize() - 1).value(), val);
						}
						case LSHL, LSHR, LUSHR -> {
							Frame<SimulatorValue> result = simulate(ii, simint, frame, Type.LONG_TYPE, Type.INT_TYPE);
							if (result == null) continue;
							long val = ((long) result.getStack(result.getStackSize() - 1).value());

							ipl.set(instruction, Util.makeIL(il -> {
								il.add(new InsnNode(Opcodes.POP));
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new LdcInsnNode(val));
							}));
							log.trace("[{}.{}] Inlined binary {} {} {} to {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()],
									frame.getStack(frame.getStackSize() - 2).value(),
									frame.getStack(frame.getStackSize() - 1).value(),
									val);
						}
						case LXOR, LADD, LMUL, LSUB, LDIV, LREM, LAND, LOR, LCMP -> {
							Frame<SimulatorValue> result = simulate(ii, simint, frame, Type.LONG_TYPE, Type.LONG_TYPE);
							if (result == null) continue;
							long val = ((long) result.getStack(result.getStackSize() - 1).value());
							ipl.set(instruction, Util.makeIL(il -> {
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new LdcInsnNode(val));
							}));

							log.trace("[{}.{}] Inlined binary {} {} {} to {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()],
									frame.getStack(frame.getStackSize() - 2).value(),
									frame.getStack(frame.getStackSize() - 1).value(),
									val);
						}
						case DADD, DSUB, DMUL, DDIV, DREM -> {
							Frame<SimulatorValue> result = simulate(ii, simint, frame, Type.DOUBLE_TYPE, Type.DOUBLE_TYPE);
							if (result == null) continue;
							double val = ((double) result.getStack(result.getStackSize() - 1).value());
							ipl.set(instruction, Util.makeIL(il -> {
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new LdcInsnNode(val));
							}));
							log.trace("[{}.{}] Inlined binary {} {} {} to {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()],
									frame.getStack(frame.getStackSize() - 2).value(),
									frame.getStack(frame.getStackSize() - 1).value(),
									val);
						}
						case DCMPG, DCMPL -> {
							Frame<SimulatorValue> result = simulate(ii, simint, frame, Type.DOUBLE_TYPE, Type.DOUBLE_TYPE);
							if (result == null) continue;
							int val = ((int) result.getStack(result.getStackSize() - 1).value());
							ipl.set(instruction, Util.makeIL(il -> {
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new LdcInsnNode(val));
							}));
							log.trace("[{}.{}] Inlined binary {} {} {} to {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()],
									frame.getStack(frame.getStackSize() - 2).value(),
									frame.getStack(frame.getStackSize() - 1).value(),
									val);
						}
						case FCMPG, FCMPL -> {
							Frame<SimulatorValue> result = simulate(ii, simint, frame, Type.FLOAT_TYPE, Type.FLOAT_TYPE);
							if (result == null) continue;
							int val = ((int) result.getStack(result.getStackSize() - 1).value());
							ipl.set(instruction, Util.makeIL(il -> {
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new LdcInsnNode(val));
							}));
							log.trace("[{}.{}] Inlined binary {} {} {} to {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()],
									frame.getStack(frame.getStackSize() - 2).value(),
									frame.getStack(frame.getStackSize() - 1).value(),
									val);
						}
						case FADD, FSUB, FMUL, FDIV, FREM -> {
							Frame<SimulatorValue> result = simulate(ii, simint, frame, Type.FLOAT_TYPE, Type.FLOAT_TYPE);
							if (result == null) continue;
							float val = ((float) result.getStack(result.getStackSize() - 1).value());
							ipl.set(instruction, Util.makeIL(il -> {
								il.add(new InsnNode(Opcodes.POP2));
								il.add(new LdcInsnNode(val));
							}));
							log.trace("[{}.{}] Inlined binary {} {} {} to {}", owner.name, method.name, Printer.OPCODES[instruction.getOpcode()],
									frame.getStack(frame.getStackSize() - 2).value(),
									frame.getStack(frame.getStackSize() - 1).value(),
									val);
						}
						case I2L, I2F, I2D,
							 L2I, L2F, L2D,
							 F2I, F2L, F2D,
							 D2I, D2L, D2F,
							 I2B, I2C, I2S -> {
							Frame<SimulatorValue> simulate = simulate(ii, simint, frame, (Type) null);
							if (simulate == null) continue;
							SimulatorValue stack = simulate.getStack(simulate.getStackSize() - 1);
							Object v = stack.value();
							ipl.set(instruction, Util.makeIL(e -> {
								e.add(new InsnNode(Opcodes.POP + frame.getStack(frame.getStackSize() - 1).getSize() - 1));
								e.add(new LdcInsnNode(v));
							}));
						}
					}
				} else if (instruction instanceof MethodInsnNode mi) {
					int op = mi.getOpcode();
					boolean isStatic = op == Opcodes.INVOKESTATIC;
					if (isStatic)
						if (mi.owner.equals("java/lang/Integer") && mi.name.equals("parseInt") && mi.desc.equals("(Ljava/lang/String;I)I")) {
							SimulatorValue radix = frame.getStack(frame.getStackSize() - 1);
							SimulatorValue encoded = frame.getStack(frame.getStackSize() - 2);
							if (radix.valueKnown() && encoded.valueKnown()) {
								int rad = (Integer) radix.value();
								String enc = ((String) encoded.value());
								int value;
								try {
									value = Integer.parseInt(enc, rad);
								} catch (NumberFormatException nfe) {
									// alright this is an invalid integer
									// it'll throw at runtime so we just leave it
									continue;
								}
								ipl.set(mi, Util.makeIL(abstractInsnNodes -> {
									abstractInsnNodes.add(new InsnNode(Opcodes.POP2));
									abstractInsnNodes.add(new LdcInsnNode(value));
								}));
							}
						} else {
							Type ret = Type.getReturnType(mi.desc);
							if ((ret.getSort() < Type.BOOLEAN || ret.getSort() > Type.DOUBLE) && !ret.equals(Type.getType(String.class)))
								continue;
							Type[] oaE = new Type[Type.getArgumentCount(mi.desc)];
							Frame<SimulatorValue> resultingFrame = simulate(mi, simint, frame, oaE);
							if (resultingFrame == null)
								continue; // :/ unknown value on stack, which would technically be possible to simulate. TODO?
							SimulatorValue top = resultingFrame.getStack(resultingFrame.getStackSize() - 1);
							if (!top.valueKnown()) continue;
							Object val = top.value();
							Object mapped = switch (val) {
								case Boolean b -> b ? 1 : 0;
								case Byte b -> b.intValue();
								case Character c -> (int) c;
								case Short s -> (int) s;
								case null, default -> val;
							};
							log.info("Able to inline {}.{}{} -> {}", mi.owner, mi.name, mi.desc, mapped);
							ipl.set(mi, Util.makeIL(abstractInsnNodes -> {
								for (Type argumentType : Type.getArgumentTypes(mi.desc)) {
									abstractInsnNodes.add(new InsnNode(Opcodes.POP + (argumentType.getSize() - 1)));
								}
								abstractInsnNodes.add(mapped == null ? new InsnNode(Opcodes.ACONST_NULL) : new LdcInsnNode(mapped));
							}));
						}
				}
			} catch (Throwable t) {
				log.error("At instruction {}, '{}'", i, Util.stringifyInstruction(instruction, null));
				throw t;
			}
		}
		ipl.apply();
	}

}
