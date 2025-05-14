package me.x150.j2cc.obfuscator.optim;

import j2cc.AlwaysInline;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.conf.javaconf.annots.ConfigValue;
import me.x150.j2cc.obfuscator.ObfuscationContext;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.util.Graph;
import me.x150.j2cc.util.MethodInliner;
import me.x150.j2cc.util.Pair;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.stream.StreamSupport;

@Log4j2
public class Inliner extends ObfuscatorPass {
	@ConfigValue(value = "maxSize", description = "Max code size to inline", exampleContent = "42")
	public int maxSize = 42;

	static InlineCandidate checkRecursive(Graph<InlineCandidate> gn, Deque<InlineCandidate> stack, InlineCandidate element, Deque<InlineCandidate> startingQueue) {
		if (stack.contains(element)) {
			return element;
		}
		stack.push(element);
		startingQueue.remove(element);
		List<InlineCandidate> nodesRelated = gn.getEdges().stream()
				.filter(f -> f.a().equals(element))
				.map(Graph.Edge::b)
				.toList();
		for (InlineCandidate inlineCandidate : nodesRelated) {
			InlineCandidate inlineCandidate1 = checkRecursive(gn, stack, inlineCandidate, startingQueue);
			if (inlineCandidate1 != null) {
				stack.pop();
				return inlineCandidate1;
			}
		}
		stack.pop();
		return null;
	}

	@Override
	@SneakyThrows
	public void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes) {
		log.debug("Discovering candidates...");
		List<InlineCandidate> candidates = new ArrayList<>();
		for (J2CC.ClassEntry aClass : classes) {
			ClassNode cn = aClass.info().node();
			for (MethodNode method : cn.methods) {
				candidates.add(new InlineCandidate(aClass.info(), method, new AtomicReference<>(makeAccessFilter(context.workspace(), method))));
			}
		}

		log.debug(" ... no filter: {}", candidates.size());

		// 1. remove methods too big
		candidates.removeIf(this::filterOnCodeSize);

		log.debug(" ... after filter(tooBig): {}", candidates.size());

		// 2. remove constructors
		candidates.removeIf(it -> it.mn.name.startsWith("<"));

		log.debug(" ... after filter(isInit): {}", candidates.size());

		// 3. remove methods if they might be inherited
		candidates.removeIf(this::filterOnHeritage);

		log.debug(" ... after filter(mayBeInherited): {}", candidates.size());

		// 5. remove if method is abstract or has no code
		candidates.removeIf(this::filterIfAccess);

		log.debug(" ... after filter(hasNoCode): {}", candidates.size());
		filterOnGraph(candidates);

		log.debug(" ... after filter(isRecursive) (final candidates): {}", candidates.size());

		Set<InlineCandidate> allInlined = new HashSet<>(candidates);

		log.debug("Inlining {} methods", candidates.size());
		AtomicLong counterInlined = new AtomicLong();

		boolean didAnything;

		do {
			didAnything = false;
			try (ExecutorService esv = context.parallelExecutorForNThreads()) {
				for (J2CC.ClassEntry aClass : classes) {
					ClassNode callerClass = aClass.info().node();
					boolean skipThisClass = Obfuscator.skip(context.workspace(), context, callerClass);
					List<Pair<MethodNode, List<MethodInsnNode>>> allMethodCalls = callerClass.methods.stream()
							.map(it -> new Pair<>(it, StreamSupport.stream(it.instructions.spliterator(), false).filter(k -> k instanceof MethodInsnNode).map(k -> (MethodInsnNode) k).toList()))
							.toList();

					for (InlineCandidate candidate : candidates) {
						boolean canAccess = candidate.accessFilter.get().stream().allMatch(e -> e.canAccess.test(aClass.info(), candidate.owner));

						MethodNode calledMethod = candidate.mn;
						String calledMethodOwner = candidate.owner.node().name;
						List<MethodNode> methodsThatCallThisCandidate = allMethodCalls.stream()
								.filter(f -> f.getB().stream().anyMatch(v -> v.name.equals(calledMethod.name) && v.desc.equals(calledMethod.desc) && v.owner.equals(calledMethodOwner)))
								.map(Pair::getA)
								.toList();
						for (MethodNode callerMethod : methodsThatCallThisCandidate) {
							boolean skipThisMethod = Obfuscator.skip(context, callerClass.name, callerMethod);
							if (skipThisMethod || skipThisClass || !canAccess) {
								// cant inline
								allInlined.remove(candidate);
								log.debug("Not inlining {}.{}{} into {}", candidate.owner.node().name, calledMethod.name, calledMethod.desc, callerClass.name);
							} else {
								// OK we can do it
								esv.submit(() -> {
									log.debug("Inlining method {}.{}{} into {}.{}{}", candidate.owner.node().name, calledMethod.name, calledMethod.desc, callerClass.name, callerMethod.name, callerMethod.desc);
									try {
										synchronized (callerMethod) {
											MethodInliner.inline(callerClass, callerMethod, candidate.owner.node(), calledMethod);
										}
									} catch (AnalyzerException e) {
										throw new RuntimeException(e);
									}
									counterInlined.getAndIncrement();

									Optional<InlineCandidate> maybeCandidate = candidates.stream().filter(f -> f.owner.equals(aClass.info()) && f.mn.equals(callerMethod)).findFirst();
									maybeCandidate.ifPresent(inlineCandidate -> {
										// we've updated another inline candidate's method body,
										// we need to recompute the access filter to make sure our changes propagate
										inlineCandidate.accessFilter.set(makeAccessFilter(context.workspace(), callerMethod));
									});
								});
								didAnything = true;
							}
						}
					}
				}
			}
		} while (didAnything);
		log.info("Inlined {} methods into {} occurrences", candidates.size(), counterInlined);
		for (InlineCandidate candidate : candidates) {
			String removeOriginalProp = "removeOriginal";
			boolean shouldRemove =
					candidate.mn.invisibleAnnotations != null &&
							candidate.mn.invisibleAnnotations.stream()
									.filter(f -> f.desc.equals(Type.getDescriptor(AlwaysInline.class))).findFirst()
									.map(a -> a.values != null && a.values.contains(removeOriginalProp) && a.values.get(a.values.indexOf(removeOriginalProp) + 1) == Boolean.TRUE)
									.orElse(false);
			if (shouldRemove) {
				if (allInlined.contains(candidate)) {
					log.info("Removing original, now inlined method {}.{}{}",
							candidate.owner.node().name,
							candidate.mn.name,
							candidate.mn.desc);
					candidate.owner.node().methods.remove(candidate.mn);
				} else {
					log.warn("Cannot remove original inline candidate method {}.{}{} since one or more occurrences were skipped",
							candidate.owner.node().name,
							candidate.mn.name,
							candidate.mn.desc);
				}
			}
		}
	}

	private void filterOnGraph(List<InlineCandidate> candidates) {
		Graph<InlineCandidate> gn = new Graph<>();
		for (InlineCandidate candidate : candidates) {
			MethodNode mn = candidate.mn;
			for (AbstractInsnNode instruction : mn.instructions) {
				if (instruction instanceof MethodInsnNode im) {
					Optional<InlineCandidate> first = candidates.stream().filter(f -> f.owner.node().name.equals(im.owner) && f.mn.name.equals(im.name) && f.mn.desc.equals(im.desc)).findFirst();
					first.ifPresent(inlineCandidate -> {
						gn.add(candidate);
						gn.add(inlineCandidate);
						gn.addEdge(candidate, inlineCandidate);
					});
				}
			}
		}
		Deque<InlineCandidate> startingQueue = new ArrayDeque<>(gn.getNodes());
		while (!startingQueue.isEmpty()) {
			InlineCandidate start = startingQueue.pop();
			Deque<InlineCandidate> positionStack = new ArrayDeque<>();
			InlineCandidate pOF = checkRecursive(gn, positionStack, start, startingQueue);
			if (pOF != null) {
				log.debug("Discovered recursive element {}", pOF.getContent());
				candidates.remove(pOF);
			}
		}
	}

	private void writeGraph(List<InlineCandidate> candidates) throws IOException {
		Path p = Path.of("out.dot");
		Graph<InlineCandidate> gn = new Graph<>();
		for (InlineCandidate candidate : candidates) {
			MethodNode mn = candidate.mn;
			for (AbstractInsnNode instruction : mn.instructions) {
				if (instruction instanceof MethodInsnNode im) {
					Optional<InlineCandidate> first = candidates.stream().filter(f -> f.owner.node().name.equals(im.owner) && f.mn.name.equals(im.name) && f.mn.desc.equals(im.desc)).findFirst();
					first.ifPresent(inlineCandidate -> {
						gn.add(candidate);
						gn.add(inlineCandidate);
						log.debug("found ref {} -> {}", candidate, inlineCandidate);
						gn.addEdge(candidate, inlineCandidate);
					});
				}
			}
		}
		gn.writeDotFormat(new PrintStream(Files.newOutputStream(p)));
	}

	private Set<AccessScope> makeAccessFilter(Workspace wsp, MethodNode method) {
		HashSet<AccessScope> acs = new HashSet<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof InvokeDynamicInsnNode
					|| instruction instanceof LdcInsnNode li && li.cst instanceof ConstantDynamic) {
				// we're fucked
				// invokedynamics could do anything and check access still; we can't reliably track them
				// its joever
				acs.clear();
				acs.add(AccessScope.PRIVATE);
				break;
			}
			if (instruction instanceof FieldInsnNode fi) {
				String owner = fi.owner;
				if (owner.startsWith("[")) continue; // everything's public
				Workspace.ClassInfo classInfo = wsp.get(owner);
				if (classInfo == null) continue;
				FieldNode fieldNode = classInfo.node().fields.stream().filter(f -> f.name.equals(fi.name) && f.desc.equals(fi.desc)).findFirst().orElse(null);
				if (fieldNode != null) {
					AccessScope accessScope = AccessScope.fromAccessMod(fieldNode.access);
					acs.add(accessScope);
				}
			}
			if (instruction instanceof MethodInsnNode min) {
				String owner = min.owner;
				if (owner.startsWith("[")) continue; // everything's public
				Workspace.ClassInfo classInfo = wsp.get(owner);
				if (classInfo == null) continue;
				MethodNode methodNode = classInfo.node().methods.stream().filter(f -> f.name.equals(min.name) && f.desc.equals(min.desc)).findFirst().orElse(null);
				if (methodNode != null) {
					// too lazy to make proper resolving
					AccessScope accessScope = AccessScope.fromAccessMod(methodNode.access);
					acs.add(accessScope);
				}
			}
		}
		return acs;
	}

	private boolean filterIfAccess(InlineCandidate inlineCandidate) {
		int acc = inlineCandidate.mn.access;
		return Modifier.isAbstract(acc) || Modifier.isNative(acc) || Modifier.isInterface(acc);
	}

	private boolean alwaysInline(InlineCandidate cand) {
		List<AnnotationNode> invisibleAnnotations = cand.mn.invisibleAnnotations;
		return invisibleAnnotations != null && invisibleAnnotations.stream().anyMatch(it -> Type.getType(it.desc).equals(Type.getType(AlwaysInline.class)));
	}

	private boolean filterOnCodeSize(InlineCandidate cand) {
		if (alwaysInline(cand)) return false;
		CodeSizeEvaluator cse = new CodeSizeEvaluator(null);
		cand.mn().accept(cse);
		int minSize = cse.getMinSize();
		return minSize > maxSize;
	}


	private boolean filterOnHeritage(InlineCandidate ic) {
		if (alwaysInline(ic)) return false;
		// if a method is inherited, dont inline it
		MethodNode r = ic.mn;
		return !Modifier.isStatic(r.access) && !Modifier.isFinal(r.access); // we can do this one, cant be inherited
	}

	enum AccessScope {
		PUBLIC((a, b) -> b.node().name.contains("/") || !a.node().name.contains("/")),
		PROTECTED((a, b) -> Util.getPackage(a.node().name).equals(Util.getPackage(b.node().name)) || a.hierarchyParents().stream().anyMatch(it -> b.node().name.equals(it.name)) || b.hierarchyParents().stream().anyMatch(it -> it.name.equals(a.node().name))),
		PACKAGE_PRIVATE((a, b) -> Util.getPackage(a.node().name).equals(Util.getPackage(b.node().name))),
		PRIVATE((a, b) -> a.node().equals(b.node()));

		private final BiPredicate<Workspace.ClassInfo, Workspace.ClassInfo> canAccess;

		AccessScope(BiPredicate<Workspace.ClassInfo, Workspace.ClassInfo> canAccess /* can A access member in B? */) {
			this.canAccess = canAccess;
		}

		public static AccessScope fromAccessMod(int ac) {
			if (Modifier.isPublic(ac)) return PUBLIC;
			if (Modifier.isProtected(ac)) return PROTECTED;
			if (Modifier.isPrivate(ac)) return PRIVATE;
			return PACKAGE_PRIVATE;
		}
	}

	record InlineCandidate(Workspace.ClassInfo owner, MethodNode mn,
						   AtomicReference<Set<AccessScope>> accessFilter) implements Graph.Node {
		@Override
		public String getContent() {
			return String.format("%s.%s%s", owner.node().name, mn.name, mn.desc);
		}
	}
}
