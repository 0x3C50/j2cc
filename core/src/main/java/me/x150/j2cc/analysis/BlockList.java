package me.x150.j2cc.analysis;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class BlockList {
	public final MethodNode source;
	@Getter
	private final Block initialBlock;
	@Getter
	private Block[] blocks;

	public BlockList(MethodNode source, Block[] block, Block initialBlock) {
		this.source = source;
		this.blocks = block;
		this.initialBlock = initialBlock;
	}

	public static BlockList createFrom(MethodNode node) {
		List<Block> blocks = new ArrayList<>();
		// 1. initialize one block for each node
		for (AbstractInsnNode instruction : node.instructions) {
			Block.ExceptionHandlerState state = null;
			int idx = node.instructions.indexOf(instruction);
			Optional<TryCatchBlockNode> first1 = node.tryCatchBlocks.stream().filter(f -> node.instructions.indexOf(f.start) <= idx && node.instructions.indexOf(f.end) >= idx).findFirst();
			if (first1.isPresent()) {
				state = new Block.ExceptionHandlerState(first1.get(), false);
			}
			if (instruction instanceof LabelNode ln) {
				Optional<TryCatchBlockNode> first = node.tryCatchBlocks.stream().filter(f -> f.handler == ln).findFirst();
				if (first.isPresent()) {
					state = new Block.ExceptionHandlerState(first.get(), true);
				}
			}
			Block e = new Block(new ArrayList<>(), new UniqueList<>(), new UniqueList<>(), new UniqueList<>(), null, state);
			e.nodes.add(instruction);
			blocks.add(e);
		}
		// 2. link blocks
		for (int i = 0; i < blocks.size(); i++) {
			Block block = blocks.get(i);
			AbstractInsnNode insn = block.nodes.getFirst();
			switch (insn) {
				case JumpInsnNode ji -> {
					Block jumpTarget = blocks.stream().filter(b -> b.nodes.getFirst() instanceof LabelNode ln && ln.equals(ji.label)).findFirst().orElseThrow();
					block.jumpsTo.add(jumpTarget);
					jumpTarget.comesFrom.add(block);
					if (ji.getOpcode() != Opcodes.GOTO && i + 1 < blocks.size()) {
						block.flowsTo = blocks.get(i + 1);
						blocks.get(i + 1).comesFrom.add(block);
					}
				}
				case TableSwitchInsnNode _, LookupSwitchInsnNode _ -> {
					Stream<LabelNode> labels;
					if (insn instanceof TableSwitchInsnNode tsw) labels = Stream.concat(tsw.labels.stream(), Stream.of(tsw.dflt));
					else labels = Stream.concat(((LookupSwitchInsnNode) insn).labels.stream(), Stream.of(((LookupSwitchInsnNode) insn).dflt));
					labels.forEach(label -> {
						Block jumpTarget = blocks.stream().filter(b -> b.nodes.getFirst() instanceof LabelNode ln && ln.equals(label)).findFirst().orElseThrow();
						block.jumpsTo.add(jumpTarget);
						jumpTarget.comesFrom.add(block);
					});
				}

				default -> {
					int opcode = insn.getOpcode();
					boolean doesNotContinue = opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW;
					if (!doesNotContinue && i + 1 < blocks.size()) {
						block.flowsTo = blocks.get(i + 1);
						blocks.get(i + 1).comesFrom.add(block);
					}
				}
			}
			AbstractInsnNode start = block.nodes.getFirst();
			int idx = node.instructions.indexOf(start);
			List<Block> list = node.tryCatchBlocks.stream().filter(f -> idx >= node.instructions.indexOf(f.start) && idx <= node.instructions.indexOf(f.end))
					.map(tcn -> blocks.stream().filter(p -> p.excHandlerState != null && p.excHandlerState.isHandler() && p.excHandlerState.node() == tcn).findFirst().orElse(null))
					.filter(Objects::nonNull).toList();
			block.exceptionHandlers.addAll(list);
		}
		return new BlockList(node, blocks.toArray(Block[]::new), !blocks.isEmpty() ? blocks.getFirst() : null);
	}

	public void optimize() {
		List<Block> blocks = new ArrayList<>(List.of(this.blocks));
		for (int i = 0; i < blocks.size() - 1; i++) {
			Block block = blocks.get(i);
			Block nextBlock = blocks.get(i + 1);
			if (block.canMergeSuccessor(nextBlock)) {
				Block merge = block.merge(nextBlock, blocks);
				blocks.set(i, merge);
				blocks.remove(nextBlock);
				i--;
			}
		}
		this.blocks = blocks.toArray(Block[]::new);
	}

	public Map<LabelNode, String> genLabels() {
		Map<LabelNode, String> lab = new HashMap<>();
		for (Block block : blocks) {
			for (AbstractInsnNode node : block.nodes) {
				if (node instanceof LabelNode ln) lab.computeIfAbsent(ln, s -> "lab" + lab.size());
			}
		}
		return lab;
	}

	public InsnList rebuild(Map<LabelNode, LabelNode> lab) {
		InsnList il = new InsnList();
		if (initialBlock == null || blocks.length == 0) {
			return il; // empty method
		}
		// 1. normalize blocks to have a label at the start
		for (Block block : blocks) {
			AbstractInsnNode first = block.nodes.getFirst();
			if (!(first instanceof LabelNode)) {
				block.nodes.addFirst(new LabelNode());
			}
		}
		for (Block block : blocks) {
			for (AbstractInsnNode node : block.nodes) {
				if (node instanceof LabelNode lb) lab.put(lb, new LabelNode());
			}
		}
		il.add(new JumpInsnNode(Opcodes.GOTO, lab.get((LabelNode) initialBlock.getNodes().getFirst())));
		for (Block block : blocks) {
			List<AbstractInsnNode> nodes = block.getNodes();
			for (AbstractInsnNode node : nodes) {
				il.add(node.clone(lab));
			}
			// jump node in case of jump is already present, we just need to ensure flow to the correct node
			// in case we dont have a direct successor, the block already terminates the method. no further action is required
			Block flowTarget = block.flowsTo;
			if (flowTarget != null) {
				AbstractInsnNode firstNode = flowTarget.nodes.getFirst();
				if (!(firstNode instanceof LabelNode lb))
					throw new IllegalStateException("Expected first node of flow target to be a label, was " + firstNode);
				il.add(new JumpInsnNode(Opcodes.GOTO, lab.get(lb)));
			}
		}
		for (int i = 0; i < il.size() - 1; i++) {
			AbstractInsnNode a = il.get(i);
			AbstractInsnNode b = il.get(i + 1);
			if (a instanceof JumpInsnNode ji && ji.getOpcode() == Opcodes.GOTO && ji.label == b) {
				il.remove(a);
				// dont need to decrement i because the next node would be a label (b), we can skip it
			}
		}
		return il;
	}

	public String graph() {
		StringBuilder maggot = new StringBuilder("digraph {\n" +
				"node [fontname=\"JetBrains Mono\";nojustify=true];");
		Map<Block, String> id = new HashMap<>();
		Map<LabelNode, String> lab = genLabels();
		Pattern cringe = Pattern.compile("\\P{Print}");
		TryCatchBlockNode emptyState = new TryCatchBlockNode(null, null, null, null);
		Map<TryCatchBlockNode, List<Block>> collect = Arrays.stream(blocks)
				.collect(Collectors.groupingBy(it -> it.excHandlerState == null || it.excHandlerState.isHandler() ? emptyState : it.excHandlerState.node()));
		Map<TryCatchBlockNode, String> tbnId = new HashMap<>();
		for (TryCatchBlockNode tryCatchBlockNode : collect.keySet()) {
			List<Block> f = collect.get(tryCatchBlockNode);
			boolean isActualTcb = tryCatchBlockNode != emptyState;
			if (isActualTcb) {
				String tbnI = tbnId.computeIfAbsent(tryCatchBlockNode, tryCatchBlockNode1 -> "cluster_tbn" + tbnId.size());
				maggot.append("subgraph ").append(tbnI)
						.append("{label=\"try { ").append(lab.get(tryCatchBlockNode.start)).append(" .. ").append(lab.get(tryCatchBlockNode.end))
						.append(" } catch (").append(tryCatchBlockNode.type).append(") { ").append(lab.get(tryCatchBlockNode.handler)).append("}\";");
			}
			for (Block block : f) {
				String bid = id.computeIfAbsent(block, block1 -> "b" + id.size());
				String s = block.stringify(lab);
				s = Arrays.stream(s.split("\n"))
						.map(it -> cringe.matcher(it).replaceAll(matchResult -> {
							String k = matchResult.group();
							char g = k.charAt(0);
							return String.format("\\u%04X", (int) g);
						})).collect(Collectors.joining("\\l")) + "\\l";
				maggot.append(bid).append("[shape=box;label=\"").append(s).append("\"").append("];\n");
			}
			if (isActualTcb) {
				maggot.append("}");
			}
		}
		for (Block block : blocks) {
			String bid = id.get(block);
			if (block.excHandlerState != null && !block.excHandlerState.isHandler()) {
				TryCatchBlockNode tryCatchBlockNode = block.excHandlerState.node();
				List<Block> handlers = Arrays.stream(blocks).filter(e -> e.excHandlerState != null && e.excHandlerState.node() == tryCatchBlockNode && e.excHandlerState.isHandler()).toList();
				for (Block handler : handlers) {
					String thatId = id.computeIfAbsent(handler, block1 -> "b" + id.size());
					maggot.append(bid).append("->").append(thatId).append("[style=dashed;color=red];");
				}
			}
			if (block.flowsTo != null)
				maggot.append(bid).append("->").append(id.get(block.flowsTo)).append("[color=black];\n");
			for (Block block1 : block.jumpsTo) {
				maggot.append(bid).append("->").append(id.get(block1)).append("[color=blue];\n");
			}
		}
		maggot.append("}");
		return maggot.toString();
	}

}
