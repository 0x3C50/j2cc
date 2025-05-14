package me.x150.j2cc.conf.javaconf.annots;

import me.x150.j2cc.conf.javaconf.Configurable;
import me.x150.j2cc.conf.javaconf.Util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public abstract class DTOConfigurable implements Configurable {

	record E(ConfigValue cv, Field target) {

	}

	private final Map<String, VarHandle> confEntries;
	private final Map<String, ConfigValue> confMeta;
	private final String[] keys;
	private final BitSet setKeys;

	public DTOConfigurable() {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		List<E> list = discoverConfigValueFields(getClass());
		List<String> list1 = Util.walkHierarchy(getClass())
				.flatMap(it -> Arrays.stream(it.getDeclaredFields()))
				.filter(f -> f.isAnnotationPresent(ConfigValue.class))
				.filter(f -> !Modifier.isPublic(f.getModifiers()))
				.map(Field::toString).toList();
		if (!list1.isEmpty()) {
			throw new IllegalStateException("Fields have @ConfigValue but aren't public: " + list1);
		}
		confEntries = list.stream()
				.collect(Collectors.toMap(e -> e.cv.value(), e -> {
					try {
						return lookup.unreflectVarHandle(e.target);
					} catch (IllegalAccessException ex) {
						throw new RuntimeException(ex);
					}
				}));
		this.confMeta = list.stream().collect(Collectors.toMap(e -> e.cv.value(), e -> e.cv));
		this.keys = confEntries.keySet().toArray(String[]::new);
		Arrays.sort(this.keys);
		this.setKeys = new BitSet(keys.length);
	}

	private static List<E> discoverConfigValueFields(Class<?> cl) {
		List<E> fld = new ArrayList<>();
		for (Field field : cl.getFields()) {
			ConfigValue cfv = field.getAnnotation(ConfigValue.class);
			if (cfv == null) continue;
			if (Modifier.isStatic(field.getModifiers()))
				throw new IllegalStateException("Static field " + field + " has @" + ConfigValue.class.getSimpleName());
			fld.add(new E(cfv, field));
		}
		return fld;
	}

	@Override
	public String[] getConfigKeys() {
		return keys;
	}

	@Override
	public Class<?> getConfigValueType(String key) {
		return confEntries.get(key).varType();
	}

	@Override
	public Object getConfigValue(String key) {
		return confEntries.get(key).get(this);
	}

	@Override
	public void setConfigValue(String key, Object value) {
		confEntries.get(key).set(this, value);
		setKeys.set(Arrays.binarySearch(this.keys, key), value != null);
	}

	private void validateObject(Deque<String> currentPath, Set<String> missing, Object val) {
		if (val instanceof Configurable cf) cf.validatePathsFilled(currentPath, missing);
		else if (val != null && val.getClass().isArray()) {
			int len = Array.getLength(val);
			for (int i1 = 0; i1 < len; i1++) {
				Object o = Array.get(val, i1);
				currentPath.add(String.valueOf(i1));
				validateObject(currentPath, missing, o);
				currentPath.removeLast();
			}
		}
	}

	@Override
	public void validatePathsFilled(Deque<String> currentPath, Set<String> missing) {
		for (int i = 0; i < keys.length; i++) {
			String key = keys[i];
			currentPath.add(key);
			// validate the value itself (if not null)
			validateObject(currentPath, missing, getConfigValue(key));
			if (!setKeys.get(i) && confMeta.get(key).required()) {
				// we have a missing key
				missing.add(String.join(".", currentPath));
			}
			currentPath.removeLast();
		}
	}

	@Override
	public String getDescription(String key) {
		return confMeta.get(key).description();
	}

	@Override
	public String getExample(String key) {
		return confMeta.get(key).exampleContent();
	}
}
