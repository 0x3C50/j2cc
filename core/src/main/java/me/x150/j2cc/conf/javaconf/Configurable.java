package me.x150.j2cc.conf.javaconf;

import java.util.Deque;
import java.util.Set;

public interface Configurable {
	String[] getConfigKeys();

	Class<?> getConfigValueType(String key);

	Object getConfigValue(String key);

	void setConfigValue(String key, Object value);

	void validatePathsFilled(Deque<String> currentPath, Set<String> missing);

	String getDescription(String key);

	String getExample(String key);
}
