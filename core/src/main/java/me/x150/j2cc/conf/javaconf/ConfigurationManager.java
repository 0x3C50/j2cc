package me.x150.j2cc.conf.javaconf;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.NullObject;
import com.electronwill.nightconfig.toml.TomlFormat;
import lombok.SneakyThrows;

import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;

public record ConfigurationManager(Configurable root) {
	private static final String[] EMPTY_S = {};

	private static String[] _p(String path) {
		return path.split("\\.");
	}

	private static String[] _pathParent(String[] path) {
		if (path.length < 2) return EMPTY_S;
		return Arrays.copyOfRange(path, 0, path.length - 1);
	}

	private static String _pathTLN(String[] path) {
		return path[path.length - 1];
	}

	public Configurable getConfigurableAtPath(String... path) {
		Configurable current = root;
		for (int i = 0; i < path.length; i++) {
			String s = path[i];
			Object vo = current.getConfigValue(s);
			if (!(vo instanceof Configurable vv)) {
				throw new IllegalStateException("Path " + String.join("", Arrays.copyOfRange(path, 0, i + 1)) + " doesn't store a Configurable");
			}
			current = vv;
		}
		return current;
	}

	public void set(String path, Object value) {
		String[] pp = _p(path);
		setPath(pp, value);
	}

	public void setPath(String[] pp, Object value) {
		String[] bln = _pathParent(pp);
		String tln = _pathTLN(pp);
		Configurable bottom = getConfigurableAtPath(bln);
		bottom.setConfigValue(tln, value);
	}

	public Object get(String path) {
		String[] pp = _p(path);
		return getPath(pp);
	}

	public Object getPath(String... pp) {
		String[] bln = _pathParent(pp);
		String tln = _pathTLN(pp);
		Configurable bottom = getConfigurableAtPath(bln);
		return bottom.getConfigValue(tln);
	}

	public boolean hasPath(String... path) {
		Configurable current = root;
		for (String s : path) {
			if (current == null) return false; // we tried to access the child of a non-configurable element
			Object vo = current.getConfigValue(s);
			if ((vo instanceof Configurable vv)) {
				current = vv;
			} else {
				current = null;
			}
		}
		return true; // if current is null, it still exists, it's just not a configurable
	}

	private static final Class<?>[] simpleClasses = {
			String.class,
			Number.class,
			Character.class,
			Boolean.class,

	};

	private static boolean isSimpleType(Class<?> cl) {
		if (cl.isPrimitive()) return true;
		for (Class<?> simpleClass : simpleClasses) {
			if (simpleClass.isAssignableFrom(cl)) return true;
		}
		return false;
	}

	@SneakyThrows
	public <T> Object getValueOf(Class<T> type, Object value, Object prev) {
		if (isSimpleType(type)) {
			return value; // hope we can assign
		} else if (type.isArray()) {
			Class<?> compType = type.componentType();
			@SuppressWarnings("unchecked") List<Object> lo = (List<Object>) value;
			// value is an array
			int thatLen = lo.size();
			Object arrayObject = Array.newInstance(compType, thatLen);
			if (prev != null) {
				// copy old defaults over
				//noinspection SuspiciousSystemArraycopy
				System.arraycopy(prev, 0, arrayObject, 0, Math.min(lo.size(), Array.getLength(prev)));
			}
			for (int i = 0; i < thatLen; i++) {
				Array.set(arrayObject, i, getValueOf(compType, lo.get(i), Array.get(arrayObject, i)));
			}
			return arrayObject;
		} else if (type.isEnum()) {
			T[] enumConstants = type.getEnumConstants();
			String s = (String) value;
			for (T enumConstant : enumConstants) {
				if (((Enum<?>) enumConstant).name().equalsIgnoreCase(s)) {
					return enumConstant;
				}
			}
			throw new IllegalStateException("Unknown enum value '%s'. Available: %s"
					.formatted(s, Arrays.stream(enumConstants).map(it -> ((Enum<?>) it).name()).collect(Collectors.joining(", "))));
		} else {
			Configurable theObject = prev == null ? (Configurable) type.getConstructor().newInstance() : (Configurable) prev;
			lcInto(theObject, (Config) value);
			return theObject;
		}
	}

	private void lcInto(Configurable cfg, Config config) {
		for (String configKey : cfg.getConfigKeys()) {
			if (config.contains(configKey)) {
				Object value = config.getRaw(configKey);
				assert value != null; // should never be null here, we have validated this key exists
				if (value == NullObject.NULL_OBJECT) continue; // default value
				Object prev = cfg.getConfigValue(configKey);
				Class<?> type = cfg.getConfigValueType(configKey);
				cfg.setConfigValue(configKey, getValueOf(type, value, prev));
			}
		}
	}

	public void fromLbConfig1(Config config) {
		lcInto(root, config);
	}

	public CommentedConfig getExampleConfiguration() {
		CommentedConfig cc = TomlFormat.newConfig();
		return mapInto(cc, root);
	}

	private CommentedConfig mapInto(CommentedConfig cc, Configurable root) {
		for (String configKey : root.getConfigKeys()) {
			String desc = root.getDescription(configKey);
			String example = root.getExample(configKey);
			if (desc != null && desc.isBlank()) desc = null;
			if (example != null && example.isBlank()) example = null;

			String fullComment = null;
			if (desc != null && example != null) fullComment = " "+desc+"\n Example: "+example;
			else if (desc != null) fullComment = " "+desc;
			else if (example != null) fullComment = " Example: "+example;

			if (fullComment != null) cc.setComment(List.of(configKey), fullComment);
			Class<?> rt = root.getConfigValueType(configKey);
			cc.set(configKey, mapValue(cc, rt, root.getConfigValue(configKey)));
		}
		return cc;
	}

	@SneakyThrows
	private Object mapValue(CommentedConfig cc, Class<?> rt, Object r) {
		if (Configurable.class.isAssignableFrom(rt)) {
			// configurable object
			if (r == null) r = rt.getConstructor().newInstance();
			CommentedConfig subConfig = cc.createSubConfig();
			mapInto(subConfig, (Configurable) r);
			return subConfig;
		} else if (rt.isArray()) {
			if (r == null || Array.getLength(r) == 0) r = Array.newInstance(rt.componentType(), 1);
			int len = Array.getLength(r);
			List<Object> ret = new ArrayList<>(len);
			Class<?> componentType = r.getClass().componentType();
			for (int i = 0; i < len; i++) {
				ret.add(mapValue(cc, componentType, Array.get(r, i)));
			}
			return ret;
		} else if (rt.isEnum()) {
			return ((Enum<?>) r).name();
		} else {
			// simple type
			return r == null ? "" : r;
		}
	}

	public void validatePathsFilled(Set<String> missingPaths) {
		root.validatePathsFilled(new ArrayDeque<>(), missingPaths);
	}
}
