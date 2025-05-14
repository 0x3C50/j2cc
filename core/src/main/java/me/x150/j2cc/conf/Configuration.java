package me.x150.j2cc.conf;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import me.x150.j2cc.Entry;
import me.x150.j2cc.conf.javaconf.Configurable;
import me.x150.j2cc.conf.javaconf.annots.ConfigValue;
import me.x150.j2cc.conf.javaconf.annots.DTOConfigurable;
import me.x150.j2cc.conf.javaconf.annots.MapConfigurable;
import me.x150.j2cc.obfuscator.Obfuscator;
import me.x150.j2cc.obfuscator.ObfuscatorPass;

import java.util.HashMap;
import java.util.Map;

@Getter
@FieldDefaults(level = AccessLevel.PUBLIC)
public class Configuration extends DTOConfigurable {

	private final Obfuscator obf;

	public Configuration(Obfuscator obf) {
		this.obf = obf;
		Map<String, Configurable> hm = new HashMap<>();
		for (ObfuscatorPass pass : obf.passes) {
			if (pass.hasConfiguration()) hm.put(pass.getClass().getSimpleName(), pass);
		}
		hm.put("Renamer", renamerSettings = new RenamerSettings());
		this.obfuscatorPasses = new MapConfigurable(hm);
	}

	RenamerSettings renamerSettings;

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	public static class Paths extends DTOConfigurable {
		@ConfigValue(value = "utilPath", description = "Path to the util directory (native code of j2cc)", exampleContent = "util")
		String utilPath = "util";
		@ConfigValue(value = "zigPath", description = "Path to the zig compiler's directory, parent of zig / zig.exe", exampleContent = "zig-compiler")
		String zigPath = "zig-compiler";
		@ConfigValue(value = "inputPath", description = "Input jar / directory", exampleContent = "input.jar", required = true)
		String inputPath;

		@ConfigValue(value = "outputPath", description = "Output jar / directory", exampleContent = "output.jar", required = true)
		String outputPath;

		@ConfigValue(value = "tempPath", description = "Temporary path override", exampleContent = "temp")
		String tempPath;

		@ConfigValue(value = "libraries", description = "List of library jars to use for hierarchy computing", exampleContent = "[a.jar, b.jar, c.jar]")
		String[] libraries = new String[0];

		@ConfigValue(value = "libraryMatchers", description = "Glob pattern matchers to include bulk libraries")
		LibraryMatcher[] libraryMatchers = new LibraryMatcher[0];
	}

	@ConfigValue(value = "paths", description = "Path settings", required = true)
	Paths paths;

	@ConfigValue(value = "compilerType", description = "Compiler type (zig / gcc)", exampleContent = "zig")
	Entry.CompilerType compilerType = Entry.CompilerType.ZIG;

	@ConfigValue(value = "keepTempDirectory", description = "Keep temporary directory when done", exampleContent = "true")
	boolean keepTempDir = false;

	@ConfigValue(value = "parallelJobs", description = "How many compile jobs to run in parallel at most, 0 is unlimited", exampleContent = "4")
	int parallelJobs = 1;

	@ConfigValue(value = "targets", description = "Targets to compile against. Triplet format", exampleContent = "x86_64-linux-gnu", required = true)
	String[] targets;

	@ConfigValue(value = "zigArgs", description = "Extra zig arguments to pass to the compiler", exampleContent = "-fdeclspec")
	String[] zigArgs = new String[0];

	@ConfigValue(value = "compileAllMethods", description = "Compiles all methods", exampleContent = "true")
	boolean compileAllMethods = false;

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	public static class ClassAnnotOverride extends DTOConfigurable {
		@ConfigValue(value = "classes", description = "Which classes to modify")
		String[] classes;
		@ConfigValue(value = "excludeFrom", description = "Which modifier to apply (RENAMING / OBFUSCATION / COMPILATION)")
		String[] excludeFrom;
	}

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	public static class MemberAnnotOverride extends DTOConfigurable {
		@ConfigValue(value = "members", description = "Which members to modify")
		Member[] members;
		@ConfigValue(value = "excludeFrom", description = "Which modifier to apply (RENAMING / OBFUSCATION / COMPILATION)")
		String[] excludeFrom;
	}

	@ConfigValue(value = "classAnnotationModifiers", description = "Adds @Exclude annotations to the specified classes")
	ClassAnnotOverride[] annotOverridesC = new ClassAnnotOverride[0];
	@ConfigValue(value = "memberAnnotationModifiers", description = "Adds @Exclude annotations to the specified members")
	MemberAnnotOverride[] annotOverridesM = new MemberAnnotOverride[0];


	@ConfigValue(value = "compileClasses", description = "Compile all methods in these classes, regardless of annotation", exampleContent = "me.user.pkg.**")
	String[] compileClasses = new String[0];

	@ConfigValue(value = "compileMethods", description = "Compile these methods, regardless of annotation")
	Member[] compileMethods = new Member[0];

	@ConfigValue(value = "skipOptimizations", description = "Skips the optimizer pass, useful if you already have an obfuscated jar")
	boolean skipOptimizations = false;

	@ConfigValue(value = "postCompileScripts", description = "Scripts / executables that will be executed when a native has finished compiling, to modify the given native. The first argument of the script is the path to the native that was compiled.", exampleContent = "modifyToUseVmProtect.bat, modifyToObfuscate.sh, modifyToPack.py")
	String[] postCompile = new String[0];

	@ConfigValue(value = "obfuscator", description = "Obfuscator settings")
	Configurable obfuscatorPasses;

	@ConfigValue(value = "antiHook", description = "Anti hook settings")
	AntiHookSettings antiHook = new AntiHookSettings();

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	@AllArgsConstructor
	@NoArgsConstructor
	public static class RenamerSettings extends DTOConfigurable {
		@ConfigValue(value = "enabled", description = "Rename members")
		boolean enable = false;
		@ConfigValue(value = "mergePackages", description = "Merge all packages into one")
		boolean mergePackages = false;
		@ConfigValue(value = "exportPath", description = "Export mappings to said path, empty if no export", exampleContent = "mappings.bin")
		String exportPath;

		@ConfigValue(value = "filters", description = "Filters to control which elements are included. Filters are ran top to bottom, and can override previous results. exclude **, include me.x150.Abc will include only Abc")
		FilterSet[] filterSets = new FilterSet[0];

		@ConfigValue(value = "alsoRenameInternalClasses", description = "Automatically rename internal classes Loader and Platform")
		boolean alsoRenameInternalClasses = false;

		@ConfigValue(value = "internalClassPackageName", description = "Package name of the internal classes Loader and Platform, independent of enabled")
		String internalClassesPackageName = "j2cc/internal";

		@Getter
		@FieldDefaults(level = AccessLevel.PUBLIC)
		public static class FilterSet extends DTOConfigurable {
			@ConfigValue(value = "type", description = "What action should be applied? (include or exclude)", required = true)
			String filterType;
			@ConfigValue(value = "class", description = "Class patterns to match against. All classes matching will be marked in respect to the action", exampleContent = "me.user.pkg.**", required = true)
			String[] clazz;
		}
	}

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	@AllArgsConstructor
	@NoArgsConstructor
	public static class AntiHookSettings extends DTOConfigurable {
		@ConfigValue(value = "enableAntiHook", description = "Enables JNI hooking detection. This may not catch 100% of the cases,\n" +
				"but catches most amateur attempts to replace the JNI function table with shimmed functions.")
		boolean enableAntiHook = false;
		@ConfigValue(value = "action", description = """
				Action to take when a hooked method has been detected.
				EXIT_JVM: Exit JVM with status code 0. If JVM wasn't exited, panic. If panic wasn't raised, try to set memory address 0x0 (segfault).
				CALL_METHOD: Calls a method specified in callMethodOnHookDetected. Method should have the following descriptor:
					public static void (String dli_fname, long dli_fbase, String dli_sname, long dli_saddr)
					For more information about what exactly those arguments mean, refer to https://www.man7.org/linux/man-pages/man3/dladdr.3.html#DESCRIPTION
					Note that dli_sname and dli_saddr may be null and 0 respectively if they couldn't be resolved
				""")
		Action action = Action.EXIT_JVM;
		@ConfigValue(value = "callMethodOnHookDetected", description = "Method to call when a hook has been detected and action is CALL_METHOD. See action description for required descriptor",
		exampleContent = "some/Clazz.someMethod")
		String callMethodOnHookDetected = null;
		public enum Action {
			EXIT_JVM, CALL_METHOD
		}
	}

	@ConfigValue(value = "debugSettings", description = "Settings for debugging purposes")
	DebugSettings debugSettings = new DebugSettings();

	@ConfigValue(value = "vagueExceptions", description = "Don't mention class references in JVM exceptions, such as a NPE")
	boolean vagueExceptions;

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	public static class DebugSettings extends DTOConfigurable {
		@ConfigValue(value = "verboseRuntime", description = "Print extra logs at runtime")
		boolean verboseRuntime;
		@ConfigValue(value = "printMethodCalls", description = "Print outgoing method calls to java land")
		boolean printMethodCalls;
		@ConfigValue(value = "printMethodEntryExit", description = "Print when a native method is entered and exited")
		boolean printMethodEntryExit;
		@ConfigValue(value = "printMethodLink", description = "Print the linked native methods")
		boolean printMethodLink;
		@ConfigValue(value = "printBytecode", description = "Print the original bytecode instructions as they're executed")
		boolean printBytecode;
		@ConfigValue(value = "verboseLoader", description = "Enable debug logs from the loader")
		boolean verboseLoader;
		@ConfigValue(value = "dumpTranspilees", description = "Dump all transformed methods as they're compiled into the dump/ directory")
		boolean dumpTranspilees;
	}

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	public static class Member extends DTOConfigurable {
		@ConfigValue(value = "class", description = "Name of the class, using dots OR slashes", required = true, exampleContent = "java.lang.String, java/lang/String, me.user.pkg.**")
		String clazz;
		@ConfigValue(value = "memberName", description = "Name of the member (field / class)", required = true, exampleContent = "content, toString")
		String memberName;
		@ConfigValue(value = "descriptor", description = "Descriptor of the member, either a method descriptor or field descriptor", required = true, exampleContent = "[B, ()Ljava/lang/String;")
		String descriptor;
	}

	@FieldDefaults(level = AccessLevel.PUBLIC)
	@Getter
	public static class LibraryMatcher extends DTOConfigurable {
		@ConfigValue(value = "basePath", description = "Base directory to start matching from", exampleContent = "libs", required = true)
		String basePath;
		@ConfigValue(value = "globPattern", description = "Pattern to match against", exampleContent = "**.jar", required = true)
		String globPattern;
	}
}
