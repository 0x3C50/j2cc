package me.x150.j2cc.obfuscator;

import me.x150.j2cc.J2CC;
import me.x150.j2cc.conf.Context;
import me.x150.j2cc.conf.javaconf.annots.ConfigValue;
import me.x150.j2cc.conf.javaconf.annots.DTOConfigurable;
import org.objectweb.asm.tree.ClassNode;

import java.util.Collection;
import java.util.Collections;
import java.util.jar.Manifest;

public abstract class ObfuscatorPass extends DTOConfigurable {
	@ConfigValue(value = "enabled", description = "Enable this transformer")
	public boolean enabled = false;


//	public abstract void copyConfiguration(ObfuscationSettings obfuscationSettings);

	public abstract void obfuscate(ObfuscationContext obfCtx, Context context, Collection<J2CC.ClassEntry> classes);

	public void modifyManifest(Context ctx, Manifest manifest) {
	}

	public boolean shouldRun() {
		return enabled;
	}

	public boolean hasConfiguration() {
		return true;
	}

	public Collection<ClassNode> getAdditionalClasses() {
		return Collections.emptyList();
	}

	public Collection<Class<? extends ObfuscatorPass>> requires() {
		return Collections.emptyList();
	}
}
