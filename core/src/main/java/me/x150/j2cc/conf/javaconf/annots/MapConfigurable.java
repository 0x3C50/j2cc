package me.x150.j2cc.conf.javaconf.annots;

import me.x150.j2cc.conf.javaconf.Configurable;

import java.util.Deque;
import java.util.Map;
import java.util.Set;

public class MapConfigurable implements Configurable {

	private final Map<String, Configurable> children;
	private final String[] array;

	public MapConfigurable(Map<String, Configurable> children) {
		this.children = children;
		array = children.keySet().toArray(new String[0]);
	}

	@Override
	public String[] getConfigKeys() {
		return array;
	}

	@Override
	public Class<?> getConfigValueType(String key) {
		return children.get(key).getClass();
	}

	@Override
	public Object getConfigValue(String key) {
		return children.get(key);
	}

	@Override
	public void setConfigValue(String key, Object value) {
		Class<?> type = getConfigValueType(key);
		if (!type.isInstance(value)) throw new IllegalStateException(type + " not assignable from " + value.getClass());
		children.put(key, (Configurable) value);
	}

	@Override
	public void validatePathsFilled(Deque<String> s, Set<String> missing) {
		for (Map.Entry<String, Configurable> stringConfigurableEntry : children.entrySet()) {
			s.add(stringConfigurableEntry.getKey());
			stringConfigurableEntry.getValue().validatePathsFilled(s, missing);
			s.removeLast();
		}
	}

	@Override
	public String getDescription(String key) {
		return null;
	}

	@Override
	public String getExample(String key) {
		return null;
	}
}
