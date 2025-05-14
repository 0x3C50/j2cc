package me.x150.j2cc.util.simulation;

import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Value;

import java.util.Objects;

public record SimulatorValue(Type type, boolean valueKnown, Object value) implements Value {
	public static final SimulatorValue UNKNOWN = new SimulatorValue(null, false, null);
	public static final SimulatorValue TWO_WORD_2ND = new SimulatorValue(null, false, null);
	public static final SimulatorValue UNINITIALIZED = new SimulatorValue(null, false, null);
	public static final SimulatorValue NULL = new SimulatorValue(Util.OBJECT_TYPE, true, null);

	@Override
	public int getSize() {
		return type == null ? 1 : type.getSize();
	}

	public <T> T valueAs(AbstractInsnNode where, Class<T> type) throws AnalyzerException {
		if (!valueKnown)
			throw new AnalyzerException(where, "(internal exception) Expected a stack element to be known, but wasnt. This is probably a programming oversight.");
		if (!type.isInstance(value)) {
			throw new AnalyzerException(where, "Expected a stack element to be of type " + type + ", but found " + this.type + " (represented as " + (value == null ? "<null>" : value.getClass()) + ": " + value + ")");
		}
		return type.cast(value);
	}

	public SimulatorValue merge(Workspace wsp, SimulatorValue other) {
		if (this.type == null || other.type == null) return UNKNOWN; // merge(U, ?) = U
		if (this.type.getSort() != other.type.getSort()) {
			if ((this.type.getSort() == Type.ARRAY && Util.OBJECT_TYPE.equals(other.type))
					|| (Util.OBJECT_TYPE.equals(this.type) && other.type.getSort() == Type.ARRAY)) {
				// merge(Object, ?[]) = Object
				return new SimulatorValue(Util.OBJECT_TYPE, false, null);
			}
			// we dont agree on a type
			return UNKNOWN;
		}

		// at this point, we have the same sort of type. if it's actually the same or a related type is up for us to find out now

		if (this == NULL && other == NULL) {
			// both are null, return null
			return NULL;
		} else if (this == NULL) {
			// we're null so we take the other as base
			return new SimulatorValue(other.type, false, null);
		} else if (other == NULL) {
			// the other's a null value so we have the talking stick
			return new SimulatorValue(this.type, false, null);
		}

		Type mergedType = this.type; // in case this isn't an object or array, the type already matches
		if (this.type.getSort() >= Type.ARRAY) {
			// we're both objects, find the common parent
			mergedType = wsp.findCommonSupertype(this.type, other.type);
		}

		if (this.valueKnown != other.valueKnown) {
			// we know the value but the other one doesnt, we cant say we can unify both of these statements
			return new SimulatorValue(mergedType, false, null);
		}
		if (this.valueKnown) {
			if (!Objects.equals(this.value, other.value))
				return new SimulatorValue(mergedType, false, null);
		}
		// we're the same value
		return new SimulatorValue(mergedType, this.valueKnown, this.value);
	}


	@Override
	public String toString() {
		return "SimulatorValue[" +
				"type=" + type + ", " +
				"valueKnown=" + valueKnown + ", " +
				"value=" + value + ']';
	}

}
