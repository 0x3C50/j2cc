package me.x150.j2cc.obfuscator.flow;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.analysis.Block;
import me.x150.j2cc.analysis.BlockList;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import me.x150.j2cc.util.Pair;
import me.x150.j2cc.util.Util;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.stream.IntStream;

@Log4j2
public class FlowFlatten extends ObfuscatorPass implements Opcodes {
	public static boolean predAllLocalsAndArrayAccesses(MethodNode node, AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) return true;
		if (insn instanceof VarInsnNode vi) {
			int var = vi.var;
			int argSize = Type.getArgumentsAndReturnSizes(node.desc) >> 2;
			if (Modifier.isStatic(node.access)) argSize--;
			return var >= argSize;
		}
		return false;
	}

	private static void checkStack(SourceValue owner, MethodNode method, String fin, List<Pair<AbstractInsnNode, AbstractInsnNode>> patches, BiPredicate<MethodNode, AbstractInsnNode> isNaughtyINsn) {
		for (AbstractInsnNode insn : owner.insns) {
			if (!isNaughtyINsn.test(method, insn)) continue;
			TypeInsnNode tin = new TypeInsnNode(Opcodes.CHECKCAST, fin);
			patches.add(new Pair<>(insn, tin));
		}
	}

	private static @Nullable MethodNode doProcess(MethodNode methodDONOTUSEME, ClassNode node) throws AnalyzerException, NoSuchMethodException {
		Util.splitLocals(methodDONOTUSEME);
		MethodNode method = Util.emptyCopyOf(methodDONOTUSEME);
		LocalVariablesSorter lvs = new LocalVariablesSorter(methodDONOTUSEME.access, methodDONOTUSEME.desc, method);
		methodDONOTUSEME.accept(lvs);

		int dispatchIndexVar = lvs.newLocal(Type.INT_TYPE);

		Object2IntMap<LabelNode> labelToIdMap = new Object2IntOpenHashMap<>();
		Int2ObjectMap<LabelNode> idToLabelMap = new Int2ObjectOpenHashMap<>();
		LabelNode dispatcherSwitch = new LabelNode();

		Map<JumpInsnNode, InsnList> replacements = new HashMap<>();

		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jin && jin.getOpcode() != Opcodes.GOTO) {
				LabelNode post = new LabelNode();
				LabelNode into = new LabelNode();
				InsnList replacement = new InsnList();
				replacement.add(new JumpInsnNode(Opcodes.GOTO, post));
				replacement.add(into);
				replacement.add(new JumpInsnNode(Opcodes.GOTO, jin.label));
				replacement.add(post);
				jin.label = into;
				method.instructions.insert(jin, replacement);
			}
		}

		BlockList from2 = BlockList.createFrom(method);
		from2.optimize();
		for (Block block : from2.getBlocks()) {
			Block target = block.flowsTo;
			if (target != null) {
				AbstractInsnNode lastInsn = block.nodes.getLast();
				AbstractInsnNode first = target.nodes.getFirst();
				LabelNode ln;
				if (!(first instanceof LabelNode ln1)) {
					method.instructions.insertBefore(first, ln = new LabelNode());
					target.nodes.addFirst(ln);
				} else ln = ln1;
				method.instructions.insert(lastInsn, new JumpInsnNode(Opcodes.GOTO, ln));
			}
		}

		from2 = BlockList.createFrom(method);
		from2.optimize();

		Analyzer<BasicValue> bv = new Analyzer<>(new BasicInterpreter());
		Frame<BasicValue>[] analyze = bv.analyzeAndComputeMaxs(node.name, method);

		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof JumpInsnNode jin) {
				if (!eligForJump(jin, analyze, method)) continue; // dead

				if (jin.getOpcode() == Opcodes.GOTO) {
					int jumpTargetId = labelToIdMap.computeIfAbsent(jin.label, labelNode -> {
						int guess;
						ThreadLocalRandom current = ThreadLocalRandom.current();
						do {
							guess = current.nextInt();
						} while (labelToIdMap.containsValue(guess));
						return guess;
					});
					idToLabelMap.put(jumpTargetId, jin.label);
				}
			}
		}

		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof JumpInsnNode jin) {
				if (!eligForJump(jin, analyze, method)) continue; // dead

				if (jin.getOpcode() == Opcodes.GOTO) {
					int weGoToId = labelToIdMap.getInt(jin.label);
					Block ourBlock = Arrays.stream(from2.getBlocks()).filter(f -> f.nodes.contains(insn)).findAny().orElseThrow();
					if (ourBlock.nodes.getFirst() instanceof LabelNode ourHead) {
						int theIdWeHaveInTheVarRn = labelToIdMap.getInt(ourHead);
						boolean prevBlocksAllGetReplacedByDispatch = ourBlock.comesFrom.stream().allMatch(it -> {
							if (it.flowsTo != null) return false;
							if (!(it.nodes.getLast() instanceof JumpInsnNode ji)) return false;
							return eligForJump(ji, analyze, method);
						});
						if (prevBlocksAllGetReplacedByDispatch) {
							// holy shit we can do it
							InsnList jump = new InsnList();
							int xor = theIdWeHaveInTheVarRn ^ weGoToId;

							jump.add(new VarInsnNode(Opcodes.ILOAD, dispatchIndexVar));
							jump.add(new LdcInsnNode(xor));
							jump.add(new InsnNode(Opcodes.IXOR));
							jump.add(new VarInsnNode(Opcodes.ISTORE, dispatchIndexVar));
							jump.add(new JumpInsnNode(Opcodes.GOTO, dispatcherSwitch));
							replacements.put(jin, jump);
							continue;
						}
					}
					InsnList jump = new InsnList();

					jump.add(new LdcInsnNode(weGoToId));
					jump.add(new VarInsnNode(Opcodes.ISTORE, dispatchIndexVar));
					jump.add(new JumpInsnNode(Opcodes.GOTO, dispatcherSwitch));
					replacements.put(jin, jump);
				}
			}
		}

		if (replacements.isEmpty()) return null;

		// list of the possible keys we have
		ArrayList<Integer> jmpKeys = new ArrayList<>(labelToIdMap.values());
		Collections.sort(jmpKeys);
		// sorted array of the keys
		int[] keys = new int[jmpKeys.size()];
		// array of the corresponding labels to jump to
		LabelNode[] dispatchers = new LabelNode[jmpKeys.size()];
		InsnList dispatcherSegment = new InsnList();
		for (int i = 0; i < keys.length; i++) {
			int jumpKey = jmpKeys.get(i);
			keys[i] = jumpKey;

			LabelNode actualLabelBehindThisKey = idToLabelMap.get(jumpKey);

			dispatchers[i] = actualLabelBehindThisKey;
		}

		LabelNode terminate = new LabelNode();
		dispatcherSegment.add(terminate);
		dispatcherSegment.add(new InsnNode(Opcodes.ACONST_NULL));
		dispatcherSegment.add(new InsnNode(Opcodes.ATHROW));

		LookupSwitchInsnNode lsin = new LookupSwitchInsnNode(terminate, keys, dispatchers);

		dispatcherSegment.insert(Util.makeIL(il -> {
			il.add(dispatcherSwitch);
			il.add(new VarInsnNode(Opcodes.ILOAD, dispatchIndexVar));
			il.add(lsin);
		}));

		replacements.forEach((jumpInsnNode, abstractInsnNodes) -> {
			method.instructions.insertBefore(jumpInsnNode, abstractInsnNodes);
			method.instructions.remove(jumpInsnNode);
		});

		method.instructions.add(dispatcherSegment);

		int argSize = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
		if (Modifier.isStatic(method.access)) argSize--;
		int firstLocal = argSize;
		Int2ObjectMap<Type> allLocals = Util.getAllLocalsWithType(method);
		for (int iff : allLocals.keySet()) {
			if (iff < firstLocal) continue;
			Type t = allLocals.get(iff);
			int slop = switch (t.getSort()) {
				case Type.INT -> Opcodes.ISTORE;
				case Type.LONG -> Opcodes.LSTORE;
				case Type.DOUBLE -> Opcodes.DSTORE;
				case Type.FLOAT -> Opcodes.FSTORE;
				case Type.OBJECT -> Opcodes.ASTORE;
				default -> throw new IllegalStateException("Unexpected value: " + t.getSort());
			};
			int op = switch (slop) {
				case Opcodes.ISTORE -> Opcodes.ICONST_0;
				case Opcodes.LSTORE -> Opcodes.LCONST_0;
				case Opcodes.DSTORE -> Opcodes.DCONST_0;
				case Opcodes.FSTORE -> Opcodes.FCONST_0;
				case Opcodes.ASTORE -> Opcodes.ACONST_NULL;
				default -> throw new IllegalStateException("Unexpected value: " + slop);
			};
			method.instructions.insert(new VarInsnNode(slop, iff));
			method.instructions.insert(new InsnNode(op));
		}

		fixTheStack(node.name, method, FlowFlatten::predAllLocalsAndArrayAccesses);

		int codeS = Util.guessCodeSize(method.instructions);
		if (codeS > Character.MAX_VALUE) {
			log.error("Could not obfuscate {}.{}{}. Code size ({}) exceeded maximum of {}", node.name, method.name, method.desc, codeS, (int) Character.MAX_VALUE);
			return null;
		}
		return method;
	}

	public static void fixTheStack(String node, MethodNode method, BiPredicate<MethodNode, AbstractInsnNode> thisInsnProducesABadValue) throws AnalyzerException, NoSuchMethodException {
		List<Pair<AbstractInsnNode, AbstractInsnNode>> insertionPatches = new ArrayList<>();
		Map<AbstractInsnNode, AbstractInsnNode> replacementPatches = new HashMap<>();

		Analyzer<SourceValue> svs = new Analyzer<>(new SourceInterpreter());
		Frame<SourceValue>[] fuck = svs.analyzeAndComputeMaxs(node, method);
		for (AbstractInsnNode instruction : method.instructions) {
			int idx = method.instructions.indexOf(instruction);
			Frame<SourceValue> fd = fuck[idx];
			if (fd == null) continue; // dead
			int opcode = instruction.getOpcode();
			switch (instruction) {
				case MethodInsnNode min -> {
					Type[] theArgs = Type.getArgumentTypes(min.desc);
					// if its special, we shouldnt check the instance because that has a VERY high chance of already being proper
					// checkcasting an uninitialized object leads to verifier error (thank you jvms!)
					boolean weShouldCheckStackM1 = opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKESPECIAL;
					Type[] stackElements = new Type[theArgs.length + (weShouldCheckStackM1 ? 1 : 0)];
					if (weShouldCheckStackM1) {
						stackElements[0] = Type.getObjectType(min.owner);
						System.arraycopy(theArgs, 0, stackElements, 1, theArgs.length);
					} else {
						System.arraycopy(theArgs, 0, stackElements, 0, theArgs.length);
					}
					for (int i = 0; i < stackElements.length; i++) {
						Type theType = stackElements[i];
						if (theType.getSort() != Type.OBJECT && theType.getSort() != Type.ARRAY) continue;
						SourceValue theArgStackElement = fd.getStack(fd.getStackSize() - stackElements.length + i);
						checkStack(theArgStackElement, method, theType.getInternalName(), insertionPatches, thisInsnProducesABadValue);
					}
				}
				case FieldInsnNode fin when opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC || opcode == Opcodes.GETFIELD -> {
					boolean isPut = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
					if (opcode == Opcodes.PUTFIELD) {
						// check owner in case we have nonstatic field set
						SourceValue owner = fd.getStack(fd.getStackSize() - 2);
						checkStack(owner, method, fin.owner, insertionPatches, thisInsnProducesABadValue);
					}
					Type typeOnStack;
					if (isPut) {
						typeOnStack = Type.getType(fin.desc);
					} else {
						typeOnStack = Type.getObjectType(fin.owner);
					}
					if (typeOnStack.getSort() == Type.OBJECT || typeOnStack.getSort() == Type.ARRAY) {
						SourceValue topStack = fd.getStack(fd.getStackSize() - 1);
						checkStack(topStack, method, typeOnStack.getInternalName(), insertionPatches, thisInsnProducesABadValue);
					}
				}
				case InsnNode ignored -> {
					boolean isALoad = opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD;
					boolean isAStore = opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE;
					if (isALoad || isAStore) {
						int loadInsn = isAStore ? opcode - 33 : opcode;
						Type theType = switch (loadInsn) {
							case Opcodes.IALOAD -> Type.getType("[I");
							case Opcodes.LALOAD -> Type.getType("[J");
							case Opcodes.FALOAD -> Type.getType("[F");
							case Opcodes.DALOAD -> Type.getType("[D");
							case Opcodes.AALOAD -> Type.getType(Object[].class);
							case Opcodes.BALOAD -> Type.getType("[B");
							case Opcodes.CALOAD -> Type.getType("[C");
							case Opcodes.SALOAD -> Type.getType("[S");
							default -> throw new IllegalStateException("Unexpected value: " + loadInsn);
						};
						int offset = isALoad ? 2 : 3;
						SourceValue arr = fd.getStack(fd.getStackSize() - offset);
						checkStack(arr, method, theType.getInternalName(), insertionPatches, thisInsnProducesABadValue);
					} else if (opcode == Opcodes.ATHROW) {
						checkStack(
								fd.getStack(fd.getStackSize() - 1),
								method,
								Type.getType(Throwable.class).getInternalName(), insertionPatches, thisInsnProducesABadValue
						);
					} else if (opcode == Opcodes.ARETURN) {
						checkStack(
								fd.getStack(fd.getStackSize() - 1),
								method,
								Type.getReturnType(method.desc).getInternalName(),
								insertionPatches, thisInsnProducesABadValue
						);
					} else if (opcode == Opcodes.ARRAYLENGTH) {
						// we need to replace this one entirely

						SourceValue owner = fd.getStack(fd.getStackSize() - 1);
						String fin = Type.getInternalName(Object.class);
						for (AbstractInsnNode insn1 : owner.insns) {
							if (!predAllLocalsAndArrayAccesses(method, insn1)) continue;
							TypeInsnNode tin = new TypeInsnNode(Opcodes.CHECKCAST, fin);
							insertionPatches.add(new Pair<>(insn1, tin));
						}
						// since arraylength doesnt have a set instruction for each type of array, we have to replace this one entirely
						// with a type-agnostic method that does what we want it to do
						// (Array.getLength(Object), throws if Object isn't an array (it's always gonna be an array))
						replacementPatches.put(instruction, Util.invoke(Opcodes.INVOKESTATIC, Array.class.getMethod("getLength", Object.class)));
					}
				}
				case InvokeDynamicInsnNode indy -> {
					Type[] stackElements = Type.getArgumentTypes(indy.desc);
					for (int i = 0; i < stackElements.length; i++) {
						Type theType = stackElements[i];
						if (theType.getSort() != Type.OBJECT && theType.getSort() != Type.ARRAY) continue;
						SourceValue theArgStackElement = fd.getStack(fd.getStackSize() - stackElements.length + i);
						checkStack(theArgStackElement, method, theType.getInternalName(), insertionPatches, thisInsnProducesABadValue);
					}
				}
				default -> {
				}
			}
		}

		for (Pair<AbstractInsnNode, AbstractInsnNode> patch : insertionPatches) {
			method.instructions.insert(patch.getA(), patch.getB());
		}

		for (AbstractInsnNode abstractInsnNode : replacementPatches.keySet()) {
			method.instructions.set(abstractInsnNode, replacementPatches.get(abstractInsnNode));
		}
	}

	private static boolean eligForJump(JumpInsnNode jin, Frame<BasicValue>[] analyze, MethodNode method) {
		Frame<BasicValue> lf = analyze[method.instructions.indexOf(jin)];
		return jin.getOpcode() == Opcodes.GOTO && lf != null && lf.getStackSize() == 0;
	}

	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		try (ExecutorService executorService = context.parallelExecutorForNThreads()) {
			for (J2CC.ClassEntry aClass : classes) {
				if (!Obfuscator.skip(context.workspace(), context, aClass.info().node()))
					flatten(context, executorService, aClass);
			}
		}
	}

	//	@SneakyThrows
	private void flatten(Context context, ExecutorService esv, J2CC.ClassEntry clazz) {
		ClassNode node = clazz.info().node();
		final List<MethodNode> meth = node.methods;
		int msi = meth.size();
		//noinspection unchecked
		ObjectIntImmutablePair<MethodNode>[] array = IntStream.range(0, msi)
				.mapToObj(it -> new ObjectIntImmutablePair<>(meth.get(it), it))
				.filter(f -> !Obfuscator.skip(context, node.name, f.left()) && f.left().instructions.size() > 0)
				.toArray(ObjectIntImmutablePair[]::new);
		CompletableFuture<ObjectIntImmutablePair<MethodNode>>[] parallelize = Util.parallelize(esv, item -> {
			MethodNode il = item.left();
			MethodNode methodNode = doProcess(il, node);
			return new ObjectIntImmutablePair<>(methodNode, item.rightInt());
		}, array);
		for (CompletableFuture<ObjectIntImmutablePair<MethodNode>> future : parallelize) {
			future.thenAccept(processed -> {
				MethodNode proc = processed.left();
				if (proc == null) return;
				synchronized (meth) {
					meth.set(processed.rightInt(), proc);
				}
			});
		}
	}


}
