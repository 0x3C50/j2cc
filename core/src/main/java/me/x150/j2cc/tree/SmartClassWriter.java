package me.x150.j2cc.tree;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

/**
 * Class writer, that does not load the classes it needs to find frames for with the regular class loader, but uses the
 * jar that contains the classes to find common types instead. Prevents arbitrary code execution.
 */
public class SmartClassWriter extends ClassWriter {
	final Workspace jar;
	private final Remapper remapper;

	public SmartClassWriter(int flags, Workspace jar, Remapper remapper) {
		super(flags);
		this.jar = jar;
		this.remapper = remapper;
	}

	@Override
	protected String getCommonSuperClass(final String type1M, final String type2M) {
		// we get remapped types here
		String type1 = remapper.unmapClassName(type1M);
		String type2 = remapper.unmapClassName(type2M);

		Type commonTop = jar.findCommonSupertype(Type.getObjectType(type1), Type.getObjectType(type2));
		return remapper.map(commonTop.getInternalName());
	}

}
