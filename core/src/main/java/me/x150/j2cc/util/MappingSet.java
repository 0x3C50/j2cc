package me.x150.j2cc.util;

import me.x150.j2cc.tree.Remapper;
import org.objectweb.asm.Type;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MappingSet extends org.objectweb.asm.commons.Remapper {
	private static final byte VERSION_ID = 0;
	private static final short MAGIC = 0x1500;
	Map<String, String> classMappings;
	Set<Mapping> fieldMaps;
	Set<Mapping> methodMaps;

	private MappingSet(Void ignored) {
		// we trust the caller to init the fields properly
	}

	public MappingSet() {
		this.classMappings = new ConcurrentHashMap<>();
		this.fieldMaps = new HashSet<>();
		this.methodMaps = new HashSet<>();
	}

	public static MappingSet fromRemapper(Remapper r) {
		MappingSet ms = new MappingSet(null);
		ms.classMappings = new ConcurrentHashMap<>(r.getClassMaps().size());
		Map<Remapper.MemberID, String> fmp = r.getFieldMaps();
		ms.fieldMaps = new HashSet<>(fmp.size());
		Map<Remapper.MemberID, String> mmp = r.getMethodMaps();
		ms.methodMaps = new HashSet<>(mmp.size());
		for (Map.Entry<String, String> stringStringEntry : r.getClassMaps().entrySet()) {
			ms.addClassMapping(stringStringEntry.getKey(), stringStringEntry.getValue());
		}

		for (Map.Entry<Remapper.MemberID, String> memberIDStringEntry : fmp.entrySet()) {
			Remapper.MemberID k = memberIDStringEntry.getKey();
			ms.addFieldMapping(k.owner(), k.name(), memberIDStringEntry.getValue(), k.type());
		}

		for (Map.Entry<Remapper.MemberID, String> memberIDStringEntry : mmp.entrySet()) {
			Remapper.MemberID k = memberIDStringEntry.getKey();
			ms.addMethodMapping(k.owner(), k.name(), memberIDStringEntry.getValue(), k.type());
		}

		return ms;
	}

	public static MappingSet readFrom(DataInput inp) throws IOException {
		short i = inp.readShort();
		if (i != MAGIC)
			throw new IllegalArgumentException("Not a mapping file. Expected magic %08X, got %08X".formatted(MAGIC, i));
		byte ver = inp.readByte();
		if (ver != VERSION_ID)
			throw new IllegalArgumentException("Cannot parse mapping file of unknown version " + ver);

		MappingSet ms = new MappingSet(null);

		int oc = inp.readInt();
		ms.classMappings = new ConcurrentHashMap<>(oc);
		for (int i1 = 0; i1 < oc; i1++) {
			String clF = inp.readUTF();
			String clT = inp.readUTF();
			ms.addClassMapping(clF, clT);
		}

		int amountFi = inp.readInt();
		ms.fieldMaps = new HashSet<>(amountFi);
		for (int i1 = 0; i1 < amountFi; i1++) {
			String o = inp.readUTF();
			Type t = Type.getType(inp.readUTF());
			String fn = inp.readUTF();
			String tn = inp.readUTF();
			ms.addFieldMapping(o, fn, tn, t);
		}

		int amountMe = inp.readInt();
		ms.methodMaps = new HashSet<>(amountMe);
		for (int i1 = 0; i1 < amountMe; i1++) {
			String o = inp.readUTF();
			Type t = Type.getMethodType(inp.readUTF());
			String fn = inp.readUTF();
			String tn = inp.readUTF();
			ms.addMethodMapping(o, fn, tn, t);
		}

		return ms;
	}

	public void addClassMapping(String from, String to) {
		classMappings.put(from, to);
	}

	public void addMethodMapping(String owner, String from, String to, Type type) {
		methodMaps.add(new Mapping(owner, from, to, type));
	}

	public void addFieldMapping(String owner, String from, String to, Type type) {
		fieldMaps.add(new Mapping(owner, from, to, type));
	}

	public void exportTo(DataOutput out) throws IOException {
		out.writeShort(MAGIC);
		out.writeByte(VERSION_ID);
		out.writeInt(classMappings.size());
		for (Map.Entry<String, String> stringStringEntry : classMappings.entrySet()) {
			out.writeUTF(stringStringEntry.getKey());
			out.writeUTF(stringStringEntry.getValue());
		}

		out.writeInt(fieldMaps.size());
		for (Mapping fieldMap : fieldMaps) {
			fieldMap.write(out);
		}

		out.writeInt(methodMaps.size());
		for (Mapping methodMap : methodMaps) {
			methodMap.write(out);
		}
	}

	@Override
	public String map(String internalName) {
		return classMappings.getOrDefault(internalName, internalName);
	}

	@Override
	public String mapMethodName(String owner, String name, String descriptor) {
		if (owner.startsWith("[")) {
			return name;
		}
		Optional<Mapping> first = methodMaps.stream()
				.filter(f -> f.owner.equals(owner) && f.fromIdentifier.equals(name) && f.type.equals(Type.getMethodType(descriptor))).findFirst();
		return first.map(it -> it.toIdentifier).orElse(name);
	}

	@Override
	public String mapAnnotationAttributeName(String descriptor, String name) {
		Type t = Type.getType(descriptor);
		if (t.getSort() != Type.OBJECT) return name;
		String decringed = t.getInternalName();
		Optional<Mapping> first1 = methodMaps.stream()
				.filter(f -> f.owner.equals(decringed) && f.fromIdentifier.equals(name)).findFirst();
		return first1.map(it -> it.toIdentifier).orElse(name);
	}

	@Override
	public String mapFieldName(String owner, String name, String descriptor) {
		Optional<Mapping> first = fieldMaps.stream()
				.filter(f -> f.owner.equals(owner) && f.fromIdentifier.equals(name) && f.type.equals(Type.getType(descriptor))).findFirst();
		return first.map(it -> it.toIdentifier).orElse(name);
	}

	record Mapping(String owner, String fromIdentifier, String toIdentifier, Type type) {

		void write(DataOutput d) throws IOException {
			d.writeUTF(owner);
			d.writeUTF(type.getDescriptor());
			d.writeUTF(fromIdentifier);
			d.writeUTF(toIdentifier);
		}
	}
}
