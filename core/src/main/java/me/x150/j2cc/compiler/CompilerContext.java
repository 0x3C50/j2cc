package me.x150.j2cc.compiler;

import lombok.Getter;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.cppwriter.SourceBuilder;
import me.x150.j2cc.tree.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CompilerContext<T extends AbstractInsnNode>(ClassNode methodOwner, MethodNode methodNode,
														  Frame<BasicValue>[] frames, Frame<SourceValue>[] sourceFrames,
														  InsnList instructions,
														  T instruction, Method compileTo,
														  Map<LabelNode, String> labels, SourceBuilder builder,
														  @Getter Remapper remapper, MemberCache cache,
														  CacheSlotManager indyCache,
														  me.x150.j2cc.util.StringCollector stringCollector) {

	public void exceptionCheck() {
		exceptionCheck(false);
	}

	/**
	 * handles any exceptions currently queued, in the java style
	 *
	 * @param skipExceptionCheck skips check if an exception is actually present; assume one is always present at this point
	 */

	public void exceptionCheck(boolean skipExceptionCheck) {
		int idx = instructions.indexOf(instruction);
		List<TryCatchBlockNode> acceptableBlocks = methodNode.tryCatchBlocks.stream().filter(tryCatchBlock -> {
			int start = instructions.indexOf(tryCatchBlock.start);
			int end = instructions.indexOf(tryCatchBlock.end);
			return idx >= start && idx <= end;
		}).toList();
		if (!skipExceptionCheck) compileTo.beginScope("if (env->ExceptionCheck())");
		if (acceptableBlocks.isEmpty()) {
			compileTo.addStatement("DBG($s)", "going up");
			compileTo.addStatement("goto exceptionTerminate");
		} else {
			String excOccurred = "exceptionOccurred";
			compileTo.local("jthrowable", excOccurred).initStmt("env->ExceptionOccurred()");
			compileTo.addStatement("DBG($s, $l)", "exception occurred: %p", excOccurred);
			boolean endedWithAny = false;
			for (TryCatchBlockNode acceptableBlock : acceptableBlocks) {
				if (acceptableBlock.type == null) {
					String lab = labels.get(acceptableBlock.handler);
					compileTo.addStatement("DBG($s)", "found appropriate handler @ " + lab + " (finally)");
					compileTo.addStatement("stack[0].l = $l", excOccurred);
					compileTo.addStatement("env->ExceptionClear()");
					compileTo.addStatement("goto $l", lab);
					endedWithAny = true;
					break; // ends here always
				}
				if (acceptableBlock.type.equals("java/lang/Throwable")) {
					// the check for this one is always true; no reason to keep going
					String lab = labels.get(acceptableBlock.handler);
					compileTo.addStatement("DBG($s)", "found appropriate handler @ " + lab + " (catch Throwable)");
					compileTo.addStatement("stack[0].l = $l", excOccurred);
					compileTo.addStatement("env->ExceptionClear()");
					compileTo.addStatement("goto $l", lab);
					endedWithAny = true;
					break;
				}
				String targetExcType = this.cache.getOrCreateClassResolveNoExc(acceptableBlock.type, 0);
				compileTo.beginScope("if (env->IsInstanceOf($l, $l))", excOccurred, targetExcType);

				String lab = labels.get(acceptableBlock.handler);
				compileTo.addStatement("DBG($s)", "found appropriate handler @ " + lab + " (" + targetExcType + ")");
				compileTo.addStatement("stack[0].l = $l", excOccurred);
				compileTo.addStatement("env->ExceptionClear()");
				compileTo.addStatement("goto $l", lab);
				compileTo.endScope();
			}
			if (!endedWithAny) {
				compileTo.addStatement("DBG($s)", "going up");
				// no handler found, and the existing handlers don't guarantee a catch. escalate
				compileTo.addStatement("goto exceptionTerminate");
			}
		}
		if (!skipExceptionCheck) compileTo.endScope();
	}


	@Override
	public boolean equals(Object obj) {
		if (obj == this) return true;
		if (obj == null || obj.getClass() != this.getClass()) return false;
		CompilerContext<?> that = (CompilerContext<?>) obj;
		return Objects.equals(this.methodOwner, that.methodOwner) &&
				Objects.equals(this.methodNode, that.methodNode) &&
				Arrays.equals(this.frames, that.frames) &&
				Objects.equals(this.instructions, that.instructions) &&
				Objects.equals(this.instruction, that.instruction) &&
				Objects.equals(this.compileTo, that.compileTo) &&
				Objects.equals(this.labels, that.labels) &&
				Objects.equals(this.builder, that.builder);
	}

	@Override
	public int hashCode() {
		return Objects.hash(methodOwner, methodNode, Arrays.hashCode(frames), instructions, instruction, compileTo, labels, builder);
	}

	@Override
	public String toString() {
		return "CompilerContext[" +
				"methodOwner=" + methodOwner + ", " +
				"methodNode=" + methodNode + ", " +
				"frames=" + Arrays.toString(frames) + ", " +
				"instructions=" + instructions + ", " +
				"instruction=" + instruction + ", " +
				"compileTo=" + compileTo + ", " +
				"labels=" + labels + ", " +
				"builder=" + builder + ']';
	}

}