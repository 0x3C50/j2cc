package me.x150.j2cc.compiler;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.SneakyThrows;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.util.Util;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class MemberCache {
	private final Object2ObjectMap<String, String> foundClasses = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<Descriptor, String> foundMethods = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<Descriptor, String> foundFields = new Object2ObjectOpenHashMap<>();
	private final AtomicReference<String> createdLookup = new AtomicReference<>();
	private final CacheSlotManager cacheSM;
	private Method to;
	private CompilerContext<?> context;

	public MemberCache(CacheSlotManager indyCache) {
		cacheSM = indyCache;
	}

	public void setContext(CompilerContext<?> ctx) {
		this.context = ctx;
		this.to = ctx.compileTo();
	}

	public String getOrCreateClassResolve(String name, int ind) {
		return getOrCreateClassResolve(name, ind == -1 ? null : "sfclass"+ind);
	}

	public String getOrCreateClassResolve(String name, String storeUnder) {
		for (Method.Scope scope : to.scopes) {
			Optional<Map.Entry<String, String>> any = scope.currentClassDefs().entrySet().stream().filter(f -> f.getValue().equals(name)).findAny();
			if (any.isPresent()) return any.get().getKey(); // already have an existing definition for it
		}
		String identifier;
		if (storeUnder == null) {
			synchronized (foundClasses) {
				identifier = foundClasses.computeIfAbsent(name, i -> Util.uniquifyName("class"));
			}
		} else {
			identifier = storeUnder;
		}
		int slot = cacheSM.getOrCreateClassSlot(name);
		if (storeUnder == null) to.beginScope("if (!$l)", identifier);
		to.localInitialValue("jclass", identifier, "nullptr").initStmt("cache::findClass(env, STRING_CP($l), $l)", context.stringCollector().reserveString(name), slot);
		to.noteClassDef(identifier, name);
		context.exceptionCheck();
		if (storeUnder == null) to.endScope();
		return identifier;
	}

	public String getOrCreateClassResolveNoExc(String name, int index) {
		for (Method.Scope scope : to.scopes) {
			Optional<Map.Entry<String, String>> any = scope.currentClassDefs().entrySet().stream().filter(f -> f.getValue().equals(name)).findAny();
			if (any.isPresent()) return any.get().getKey(); // already have an existing definition for it
		}
		String identifier = "sfclass"+index;
		int slot = cacheSM.getOrCreateClassSlot(name);
		to.localInitialValue("jclass", identifier, "nullptr").initStmt("cache::findClass(env, STRING_CP($l), $l)", context.stringCollector().reserveString(name), slot);
		to.noteClassDef(identifier, name);
		return identifier;
	}

	public String getOrCreateNonstaticFieldFind(Descriptor desc, int saveInto) {
		for (Method.Scope scope : to.scopes) {
			Optional<Map.Entry<String, Descriptor>> any = scope.currentFieldDefs().entrySet().stream().filter(f -> f.getValue().equals(desc)).findAny();
			if (any.isPresent()) return any.get().getKey(); // already have an existing definition for it
		}
		String identifier;
		if (saveInto == -1) {
			synchronized (foundFields) {
				identifier = foundFields.computeIfAbsent(desc, i -> Util.uniquifyName("field"));
			}
		} else {
			identifier = "sffield"+saveInto;
		}
		int index = cacheSM.getOrCreateFieldSlot(desc.owner, desc.name, desc.desc);
		if (saveInto == -1)to.beginScope("if (!$l)", identifier);
		String ownerCl = getOrCreateClassResolve(desc.owner(), "mcLookup");
		to.localInitialValue("jfieldID", identifier, "nullptr").initStmt("cache::getNonstaticField(env, $l, STRING_CP($l), STRING_CP($l), $l)", ownerCl, context.stringCollector().reserveString(desc.name()),
				context.stringCollector().reserveString(desc.desc()), index);
		context.exceptionCheck();
		if (saveInto == -1)to.endScope();
		return identifier;
	}

	public String getOrCreateStaticFieldFind(Descriptor desc, int saveInto) {
		for (Method.Scope scope : to.scopes) {
			Optional<Map.Entry<String, Descriptor>> any = scope.currentFieldDefs().entrySet().stream().filter(f -> f.getValue().equals(desc)).findAny();
			if (any.isPresent()) return any.get().getKey(); // already have an existing definition for it
		}
		String identifier;
		if (saveInto == -1) {
			synchronized (foundFields) {
				identifier = foundFields.computeIfAbsent(desc, i -> Util.uniquifyName("field"));
			}
		} else {
			identifier = "sffield"+saveInto;
		}
		int index = cacheSM.getOrCreateFieldSlot(desc.owner, desc.name, desc.desc);
		if (saveInto == -1) to.beginScope("if (!$l)", identifier);
		String ownerCl = getOrCreateClassResolve(desc.owner(), "mcLookup");
		to.localInitialValue("jfieldID", identifier, "nullptr").initStmt("cache::getStaticField(env, $l, STRING_CP($l), STRING_CP($l), $l)", ownerCl, context.stringCollector().reserveString(desc.name()),
				context.stringCollector().reserveString(desc.desc()), index);
		context.exceptionCheck();
		context.exceptionCheck();
		if (saveInto == -1) to.endScope();
		return identifier;
	}

	public String getOrCreateNonstaticMethodFind(Descriptor desc, int saveIndex) {
		return getOrCreateNonstaticMethodFind(desc, saveIndex == -1 ? null : "sfmethod"+saveIndex);
	}

	public String getOrCreateNonstaticMethodFind(Descriptor desc, String saveIndex) {
		for (Method.Scope scope : to.scopes) {
			Optional<Map.Entry<String, Descriptor>> any = scope.currentMethodDefs().entrySet().stream().filter(f -> f.getValue().equals(desc)).findAny();
			if (any.isPresent()) return any.get().getKey(); // already have an existing definition for it
		}
		String identifier;
		if (saveIndex == null) {
			synchronized (foundMethods) {
				identifier = foundMethods.computeIfAbsent(desc, _ -> Util.uniquifyName("method"));
			}
		} else {
			identifier = saveIndex;
		}
		int index = cacheSM.getOrCreateMethodSlot(desc.owner, desc.name, desc.desc);
		if (saveIndex == null) to.beginScope("if (!$l)", identifier);
		String ownerCl = getOrCreateClassResolve(desc.owner(), "mcLookup");
		to.localInitialValue("jmethodID", identifier, "nullptr").initStmt("cache::getNonstaticMethod(env, $l, STRING_CP($l), STRING_CP($l), $l)", ownerCl, context.stringCollector().reserveString(desc.name()),
				context.stringCollector().reserveString(desc.desc()), index);
		to.noteMethodDef(identifier, desc);
		context.exceptionCheck();
		if (saveIndex == null) to.endScope();
		return identifier;
	}

	public String getOrCreateStaticMethodFind(Descriptor desc, int saveIndex) {
		return getOrCreateStaticMethodFind(desc, saveIndex == -1 ? null : "sfmethod" + saveIndex);
	}

	public String getOrCreateStaticMethodFind(Descriptor desc, String saveIndex) {
		for (Method.Scope scope : to.scopes) {
			Optional<Map.Entry<String, Descriptor>> any = scope.currentMethodDefs().entrySet().stream().filter(f -> f.getValue().equals(desc)).findAny();
			if (any.isPresent()) return any.get().getKey(); // already have an existing definition for it
		}

		String identifier;
		if (saveIndex == null) {
			synchronized (foundMethods) {
				identifier = foundMethods.computeIfAbsent(desc, _ -> Util.uniquifyName("method"));
			}
		} else {
			identifier = saveIndex;
		}
		int index = cacheSM.getOrCreateMethodSlot(desc.owner, desc.name, desc.desc);
		if (saveIndex == null) to.beginScope("if (!$l)", identifier);
		String ownerCl = getOrCreateClassResolve(desc.owner(), "mcLookup");
		to.localInitialValue("jmethodID", identifier, "nullptr").initStmt("cache::getStaticMethod(env, $l, STRING_CP($l), STRING_CP($l), $l)", ownerCl, context.stringCollector().reserveString(desc.name()),
				context.stringCollector().reserveString(desc.desc()), index);
		to.noteMethodDef(identifier, desc);
		context.exceptionCheck();
		if (saveIndex == null) to.endScope();
		return identifier;
	}

	@SneakyThrows
	public String lookupHere() {
		String g = createdLookup.get();
		String identifier = g != null ? g : "theLookup";
		to.beginScope("if (!$l)", identifier);
		to.addStatement("DBG($s)", "generating lookup");
		String methodHandles = getOrCreateClassResolve(Type.getInternalName(MethodHandles.class), "mcLookup");
		String lookupMethod = getOrCreateStaticMethodFind(Descriptor.ofMethod(MethodHandles.class.getMethod("lookup")), "lkm");
		to.localInitialValue("jobject", identifier, "nullptr").initStmt("env->CallStaticObjectMethod($l, $l)", methodHandles, lookupMethod);
		to.endScope();
		createdLookup.set(identifier);
		return identifier;
	}

	public record Descriptor(String owner, String name, String desc) {
		public static Descriptor ofMethod(java.lang.reflect.Method meth) {
			return new Descriptor(Type.getInternalName(meth.getDeclaringClass()), meth.getName(), Type.getMethodDescriptor(meth));
		}

		@Override
		public String toString() {
			return Util.fmt("$l.$l$l", owner, name, desc);
		}
	}
}
