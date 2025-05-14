package me.x150.j2cc.compiler;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class CacheSlotManager {
	private final CopyOnWriteArrayList<InvokeDynamicSpec> specs = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<String> classSlots = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<GenericSpec> fieldSpecs = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<GenericSpec> methodSpecs = new CopyOnWriteArrayList<>();

	public synchronized int getOrCreateIndyCacheSlot(InvokeDynamicSpec spec) {
		return getOrCreateSlot(specs, spec);
	}

	public synchronized int getOrCreateClassSlot(String slot) {
		return getOrCreateSlot(classSlots, slot);
	}

	public synchronized int getOrCreateFieldSlot(String owner, String name, String desc) {
		return getOrCreateSlot(fieldSpecs, new GenericSpec(owner, name, Type.getType(desc)));
	}

	public synchronized int getOrCreateMethodSlot(String owner, String name, String desc) {
		return getOrCreateSlot(methodSpecs, new GenericSpec(owner, name, Type.getMethodType(desc)));
	}

	public int getIndyAmount() {
		return specs.size();
	}

	public int getClassAmount() {
		return classSlots.size();
	}

	public int getFieldAmount() {
		return fieldSpecs.size();
	}

	public int getMethodAmount() {
		return methodSpecs.size();
	}

	private <T> int getOrCreateSlot(List<T> tc, T val) {
		int idx = tc.indexOf(val);
		if (idx >= 0) {
			return idx;
		}
		tc.add(val);
		return tc.size() - 1;
	}

	private record GenericSpec(String owner, String name, Type type) {

	}

	public record InvokeDynamicSpec(Handle bsmHandle, Object[] bsmArgs, String methodName, String methodDesc) {
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			InvokeDynamicSpec that = (InvokeDynamicSpec) o;

			if (!Objects.equals(bsmHandle, that.bsmHandle)) return false;
			// Probably incorrect - comparing Object[] arrays with Arrays.equals
			if (!Arrays.equals(bsmArgs, that.bsmArgs)) return false;
			if (!Objects.equals(methodName, that.methodName)) return false;
			return Objects.equals(methodDesc, that.methodDesc);
		}

		@Override
		public int hashCode() {
			int result = bsmHandle != null ? bsmHandle.hashCode() : 0;
			result = 31 * result + Arrays.hashCode(bsmArgs);
			result = 31 * result + (methodName != null ? methodName.hashCode() : 0);
			result = 31 * result + (methodDesc != null ? methodDesc.hashCode() : 0);
			return result;
		}
	}
}
