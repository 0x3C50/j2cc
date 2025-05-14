package me.x150.j2cc.obfuscator;

import me.x150.j2cc.tree.Remapper;

public record ObfuscationContext(String mainClassName, Remapper mapper
) {
}
