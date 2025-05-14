package me.x150.j2cc.obfuscator.refs;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import me.x150.j2cc.util.InvalidCodeGuard;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.*;

@Log4j2
public class MhCallRef extends ObfuscatorPass {

	private static Type[] getTypesConsumed(MethodInsnNode min) {
		Type[] argTypes = Type.getArgumentTypes(min.desc);
		if (min.getOpcode() != Opcodes.INVOKESTATIC && !min.name.equals("<init>") /* gets special care */) {
			// add `this` to first slot
			Type[] actual = new Type[argTypes.length + 1];
			actual[0] = Type.getObjectType(min.owner);
			System.arraycopy(argTypes, 0, actual, 1, argTypes.length);
			return actual;
		} else {
			return argTypes;
		}
	}


	@SneakyThrows
	@Override
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		InvalidCodeGuard iff = new InvalidCodeGuard();
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode cn = aClass.info().node();
			if (Obfuscator.skip(context.workspace(), context, cn)) continue;
			int majVer = cn.version & 0xFFFF;
			if (majVer < 51) {
				log.warn("Class {} (major version {}) does not support invokedynamic, skipping", cn.name, majVer);
				continue;
			}
			if (Modifier.isInterface(cn.access)) {
				log.warn("Class {} is abstract, skipping", cn.name);
				continue;
			}

			iff.init(cn);

			List<Fuckshit> fieldsToInit = new ArrayList<>();

			for (MethodNode originalMethod : cn.methods) {
				if (Obfuscator.skip(context, cn.name, originalMethod)) continue;
				MethodNode newNode = Util.emptyCopyOf(originalMethod);
				LocalVariablesSorter lvs = new LocalVariablesSorter(originalMethod.access, originalMethod.desc, newNode);
				originalMethod.accept(lvs);
				int maxArraySize = -1;
				List<MethodInsnNode> toReplace = new ArrayList<>();
				Analyzer<SourceValue> sv = new Analyzer<>(new SourceInterpreter());
				Frame<SourceValue>[] analyzed = sv.analyzeAndComputeMaxs(cn.name, newNode);
				int offset = 0;
				for (AbstractInsnNode instruction : newNode.instructions) {
					if (instruction instanceof MethodInsnNode min) {
						boolean isSpecial = min.getOpcode() == Opcodes.INVOKESPECIAL;
						if (isSpecial && min.name.equals("<init>")) {
							int nArgs = Type.getArgumentCount(min.desc);
							Frame<SourceValue> frameHere = analyzed[newNode.instructions.indexOf(instruction) + offset];
							// nArgs for constructor args, +1 for instance, +1 for the other instance produced by the dup
							if (frameHere.getStackSize() < nArgs + 2) continue;
							SourceValue instanceSE = frameHere.getStack(frameHere.getStackSize() - nArgs - 1);
							SourceValue instanceSE2nd = frameHere.getStack(frameHere.getStackSize() - nArgs - 2);
							if (instanceSE.insns.size() != 1 || !instanceSE.insns.stream().allMatch(e -> e.getOpcode() == Opcodes.DUP))
								continue;
							AbstractInsnNode theActualDup = instanceSE.insns.stream().findFirst().orElseThrow();
							Frame<SourceValue> theFunnyFrame = analyzed[newNode.instructions.indexOf(theActualDup) + offset];
							SourceValue topStackAtDup = theFunnyFrame.getStack(theFunnyFrame.getStackSize() - 1);
							if (topStackAtDup.insns.size() != 1 || topStackAtDup.insns.stream().findFirst().orElseThrow().getOpcode() != Opcodes.NEW)
								continue;
							TypeInsnNode theNew = (TypeInsnNode) topStackAtDup.insns.stream().findFirst().orElseThrow();
							// the 2nd stack element after dup HAS to come from the new behind the dup
							if (instanceSE2nd.insns.size() != 1 || instanceSE2nd.insns.stream().findFirst().orElseThrow() != theNew)
								continue;
							newNode.instructions.remove(theActualDup);
							newNode.instructions.remove(theNew);
							offset += 2;
							maxArraySize = Math.max(maxArraySize, nArgs);
							toReplace.add(min);
						} else {
							int amount = Type.getArgumentCount(min.desc);
							if (min.getOpcode() != Opcodes.INVOKESTATIC) {
								amount++; // `this`
							}
							maxArraySize = Math.max(maxArraySize, amount);
							toReplace.add(min);
						}
					}
				}
				if (maxArraySize > -1) {
					// work to do
					int oaLocal = lvs.newLocal(Type.getType("[Ljava/lang/Object;"));


					InsnList methodStart = new InsnList();

					methodStart.add(new IntInsnNode(Opcodes.SIPUSH, maxArraySize));
					methodStart.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
					methodStart.add(new VarInsnNode(Opcodes.ASTORE, oaLocal));
					newNode.instructions.insert(methodStart);
					for (MethodInsnNode methodInsnNode : toReplace) {
						int index;
						int finalMaxArraySize = maxArraySize;
						Optional<Fuckshit> first = fieldsToInit.stream().filter(f -> f.amountOfArgs == finalMaxArraySize && f.methodToPut.name.equals(methodInsnNode.name) && f.methodToPut.owner.equals(methodInsnNode.owner) && f.methodToPut.desc.equals(methodInsnNode.desc)).findFirst();
						if (first.isPresent()) {
							index = fieldsToInit.indexOf(first.get());
						} else {
							index = fieldsToInit.size();
							fieldsToInit.add(new Fuckshit(
									methodInsnNode, maxArraySize));
						}

						InsnList replacement = new InsnList();
						Type[] consumed = getTypesConsumed(methodInsnNode);
						for (int i = consumed.length - 1; i >= 0; i--) {
							Type currentType = consumed[i];
							int sort = currentType.getSort();
							if (sort != Type.OBJECT && sort != Type.ARRAY) {
								// we need to box
								Type real = Util.boxType(currentType);
								MethodInsnNode insn = new MethodInsnNode(Opcodes.INVOKESTATIC, real.getInternalName(), "valueOf",
										Type.getMethodDescriptor(real, currentType));
								replacement.add(insn);
							}
							// we have a usable Object on the stack
							replacement.add(new VarInsnNode(Opcodes.ALOAD, oaLocal)); // load array ref, swap
							replacement.add(new InsnNode(Opcodes.SWAP)); // [oa, ref]
							replacement.add(new IntInsnNode(Opcodes.SIPUSH, i)); // [oa, ref, i]
							replacement.add(new InsnNode(Opcodes.SWAP)); // [oa, i, ref]
							replacement.add(new InsnNode(Opcodes.AASTORE));
						}
						replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "j2$mh", Type.getDescriptor(MethodHandle[].class)));
						replacement.add(new IntInsnNode(Opcodes.SIPUSH, index));
						replacement.add(new InsnNode(Opcodes.AALOAD));
						replacement.add(new InsnNode(Opcodes.DUP));
						LabelNode mhIsPresent = new LabelNode();
						replacement.add(new JumpInsnNode(Opcodes.IFNONNULL, mhIsPresent));
						// there is a null
						replacement.add(new InsnNode(Opcodes.POP));
						replacement.add(new LdcInsnNode(index));
						replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "j2$mh", Type.getMethodDescriptor(Type.getType(MethodHandle.class), Type.INT_TYPE)));
						replacement.add(mhIsPresent);
						// there is a mh on the stack
						// object array is filled, stack is empty
						replacement.add(new VarInsnNode(Opcodes.ALOAD, oaLocal));

						if (methodInsnNode.getOpcode() == Opcodes.INVOKESPECIAL && methodInsnNode.name.equals("<init>")) {
							Type actualMhReturn = Type.getObjectType(methodInsnNode.owner);
							MethodInsnNode mhi = new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
									Type.getInternalName(MethodHandle.class), "invoke", Type.getMethodDescriptor(actualMhReturn, Type.getType(Object[].class)), false);
							replacement.add(mhi);
						} else {
							MethodInsnNode mhi = new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
									Type.getInternalName(MethodHandle.class), "invoke", Type.getMethodDescriptor(Type.getReturnType(methodInsnNode.desc), Type.getType(Object[].class)), false);
							replacement.add(mhi);
						}
						newNode.instructions.insertBefore(methodInsnNode, replacement);
						newNode.instructions.remove(methodInsnNode);
					}
				}

				int idx = cn.methods.indexOf(originalMethod);
				cn.methods.set(idx, newNode);
			}

			if (!fieldsToInit.isEmpty()) {
				FieldNode j2$mh = new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "j2$mh", Type.getDescriptor(MethodHandle[].class), null, null);
				cn.fields.add(j2$mh);
				MethodNode theClinitMethod = cn.methods.stream().filter(f -> f.name.equals("<clinit>")).findFirst().orElse(null);
				if (theClinitMethod == null) {
					cn.methods.add(theClinitMethod = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null));
					theClinitMethod.instructions.add(new InsnNode(Opcodes.RETURN));
				}
				InsnList insnL = theClinitMethod.instructions;
				InsnList head = new InsnList();

				head.add(new IntInsnNode(Opcodes.SIPUSH, fieldsToInit.size()));
				head.add(new TypeInsnNode(Opcodes.ANEWARRAY, Type.getInternalName(MethodHandle.class)));
				head.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "j2$mh", Type.getDescriptor(MethodHandle[].class)));
				insnL.insert(head);

				MethodNode j2$mhFillerMethod = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "j2$mh", Type.getMethodDescriptor(Type.getType(MethodHandle.class), Type.INT_TYPE), null, null);
				cn.methods.add(j2$mhFillerMethod);
				List<LabelNode> labels = new ArrayList<>();
				IntList keys = new IntArrayList();

				InsnList theWholeOrdeal = new InsnList();
				LabelNode end = new LabelNode();

				for (int i = 0; i < fieldsToInit.size(); i++) {
					Fuckshit fieldInfoToInitFor = fieldsToInit.get(i);

					MethodInsnNode methodToInitTo = fieldInfoToInitFor.methodToPut();
					String mname = methodToInitTo.name;
					String mdesc = methodToInitTo.desc;
					String mown = methodToInitTo.owner;

					LabelNode ourLabel = new LabelNode();
					theWholeOrdeal.add(ourLabel);
					labels.add(ourLabel);
					keys.add(i);

					boolean isStatic = methodToInitTo.getOpcode() == Opcodes.INVOKESTATIC;
					boolean isCtor = mname.equals("<init>");
					Handle hand = new Handle(isCtor ? Opcodes.H_NEWINVOKESPECIAL : switch (methodToInitTo.getOpcode()) {
						case Opcodes.INVOKESTATIC -> Opcodes.H_INVOKESTATIC;
						case Opcodes.INVOKEVIRTUAL -> Opcodes.H_INVOKEVIRTUAL;
						case Opcodes.INVOKESPECIAL -> Opcodes.H_INVOKESPECIAL;
						case Opcodes.INVOKEINTERFACE -> Opcodes.H_INVOKEINTERFACE;
						default -> throw new IllegalStateException("Unexpected value: " + methodToInitTo.getOpcode());
					}, mown, mname, mdesc,
							methodToInitTo.itf);
					theWholeOrdeal.add(new LdcInsnNode(hand));
					theWholeOrdeal.add(Util.invoke(Opcodes.INVOKEVIRTUAL, MethodHandle.class.getMethod("asFixedArity")));
					int amountConsumed = Type.getArgumentCount(mdesc) + (isStatic || isCtor ? 0 : 1);
					int amountToDrop = fieldInfoToInitFor.amountOfArgs - amountConsumed;
					theWholeOrdeal.add(new IntInsnNode(Opcodes.SIPUSH, amountConsumed));
					theWholeOrdeal.add(new IntInsnNode(Opcodes.SIPUSH, amountToDrop));
					theWholeOrdeal.add(new TypeInsnNode(Opcodes.ANEWARRAY, Type.getInternalName(Class.class)));
					if (amountToDrop > 0) {
						theWholeOrdeal.add(new InsnNode(Opcodes.DUP));
						theWholeOrdeal.add(new LdcInsnNode(Util.OBJECT_TYPE));
						theWholeOrdeal.add(Util.invoke(Opcodes.INVOKESTATIC, Arrays.class.getMethod("fill", Object[].class, Object.class)));
					}
					theWholeOrdeal.add(Util.invoke(Opcodes.INVOKESTATIC, MethodHandles.class.getMethod("dropArguments", MethodHandle.class, int.class, Class[].class)));
					theWholeOrdeal.add(new LdcInsnNode(Type.getType(Object[].class)));
					theWholeOrdeal.add(new IntInsnNode(Opcodes.SIPUSH, fieldInfoToInitFor.amountOfArgs));
					theWholeOrdeal.add(Util.invoke(Opcodes.INVOKEVIRTUAL, MethodHandle.class.getMethod("asSpreader", Class.class, int.class)));
					theWholeOrdeal.add(new LdcInsnNode(Type.getMethodType(Util.OBJECT_TYPE, Type.getType(Object[].class))));
					theWholeOrdeal.add(Util.invoke(Opcodes.INVOKEVIRTUAL, MethodHandle.class.getMethod("asType", MethodType.class)));
					theWholeOrdeal.add(new JumpInsnNode(Opcodes.GOTO, end));
				}

				LabelNode invalid = new LabelNode();
				theWholeOrdeal.add(invalid);
				theWholeOrdeal.add(new InsnNode(Opcodes.ACONST_NULL));


				InsnList j2mhFMCode = j2$mhFillerMethod.instructions;
				j2mhFMCode.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, j2$mh.name, j2$mh.desc));  // arr
				j2mhFMCode.add(new VarInsnNode(Opcodes.ILOAD, 0)); // arr, i
				j2mhFMCode.add(new InsnNode(Opcodes.DUP)); // arr, i, i
				j2mhFMCode.add(new LookupSwitchInsnNode(invalid, keys.toIntArray(), labels.toArray(LabelNode[]::new))); // arr, i
				j2mhFMCode.add(theWholeOrdeal);

				j2mhFMCode.add(end);

				j2mhFMCode.add(new InsnNode(Opcodes.DUP_X2));
				j2mhFMCode.add(new InsnNode(Opcodes.AASTORE));

				j2mhFMCode.add(new InsnNode(Opcodes.ARETURN));

//				head.add(Util.invoke(Opcodes.INVOKESTATIC, MethodHandles.class.getMethod("lookup")));

			}

			iff.checkAndRestoreIfNeeded(cn, true);
		}
	}

	record Fuckshit(MethodInsnNode methodToPut, int amountOfArgs) {
	}
}
