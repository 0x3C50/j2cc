package me.x150.j2cc.analysis;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.tree.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@FieldDefaults(level = AccessLevel.PUBLIC)
public class Block {
	@Getter
	List<AbstractInsnNode> nodes;
	UniqueList<Block> comesFrom;
	UniqueList<Block> jumpsTo;
	UniqueList<Block> exceptionHandlers;
	Block flowsTo;
	ExceptionHandlerState excHandlerState;

	@SuppressWarnings("CanBeFinal")
	public String debugInfo = "";

	public Block(List<AbstractInsnNode> nodes, UniqueList<Block> comesFrom, UniqueList<Block> jumpsTo, UniqueList<Block> exceptionHandlers, Block flowsTo, ExceptionHandlerState state) {
		this.nodes = nodes;
		this.comesFrom = comesFrom;
		this.jumpsTo = jumpsTo;
		this.flowsTo = flowsTo;
		this.exceptionHandlers = exceptionHandlers;
		this.excHandlerState = state;
	}

	public String stringify(Map<LabelNode, String> lb) {
		StringBuilder sb = new StringBuilder();
		List<AbstractInsnNode> abstractInsnNodes = this.nodes;
		for (AbstractInsnNode node : abstractInsnNodes) {
			String i = Util.stringifyInstruction(node, lb);
			if (i.isBlank()) continue;
			sb.append(i);
			sb.append("\n");
		}
		sb.append(debugInfo);
		return sb.toString().trim();
	}

	@Override
	public String toString() {
		return String.format(
				"%s{excHandlerState=%s}", getClass().getSimpleName(), this.excHandlerState);
	}

	public boolean canMergeSuccessor(Block nextBlock) {
		return this.flowsTo == nextBlock // we flow into the next block
				&& nextBlock.comesFrom.size() == 1 && nextBlock.comesFrom.getFirst() == this // the next block only follows us
				&& this.jumpsTo.isEmpty() // we don't jump anywhere else
				&& Objects.equals(nextBlock.excHandlerState, this.excHandlerState); // we're part of the same exception handler
	}

	public Block merge(Block that, List<Block> allBlocks) {
		if (!canMergeSuccessor(that)) throw new IllegalStateException("can't merge");
		this.nodes.addAll(that.nodes);
		this.jumpsTo = that.jumpsTo;
		this.flowsTo = that.flowsTo;
		for (Block allBlock : allBlocks) {
			if (allBlock.flowsTo == that) allBlock.flowsTo = this;
			if (allBlock.jumpsTo.contains(that)) allBlock.jumpsTo.replaceAll(block -> block == that ? this : block);
			if (allBlock.comesFrom.contains(that)) allBlock.comesFrom.replaceAll(block -> block == that ? this : block);
		}
		return this;
	}

	public record ExceptionHandlerState(TryCatchBlockNode node, boolean isHandler) {
	}
}
