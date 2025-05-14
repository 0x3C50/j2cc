package j2cc.internal;

/**
 * Stripped down and modified version of JNA's Platform class
 *
 * @see <a href="https://github.com/java-native-access/jna/blob/master/src/com/sun/jna/Platform.java">jna/.../Platform</a>
 */
public final class Platform {
	public static final int UNSPECIFIED = -1;
	public static final int MAC = 0;
	public static final int LINUX = 1;
	public static final int WINDOWS = 2;
	public static final int SOLARIS = 3;
	public static final int FREEBSD = 4;
	public static final int OPENBSD = 5;
	public static final int WINDOWSCE = 6;
	public static final int AIX = 7;
	public static final int ANDROID = 8;
	public static final int GNU = 9;
	public static final int KFREEBSD = 10;
	public static final int NETBSD = 11;

	public static final String RESOURCE_PREFIX;
	public static final int osType;
	public static final String ARCH;
	public static final String JNI_INCLUDE;

	static {
		String osName = System.getProperty("os.name");
		if (osName.startsWith("Linux")) {
			if ("dalvik".equalsIgnoreCase(System.getProperty("java.vm.name"))) {
				osType = ANDROID;
			} else {
				osType = LINUX;
			}
			JNI_INCLUDE = "linux";
		} else if (osName.startsWith("AIX")) {
			osType = AIX;
			JNI_INCLUDE = "linux";
		} else if (osName.startsWith("Mac") || osName.startsWith("Darwin")) {
			osType = MAC;
			JNI_INCLUDE = "darwin";
		} else if (osName.startsWith("Windows CE")) {
			osType = WINDOWSCE;
			JNI_INCLUDE = "win32";
		} else if (osName.startsWith("Windows")) {
			osType = WINDOWS;
			JNI_INCLUDE = "win32";
		} else if (osName.startsWith("Solaris") || osName.startsWith("SunOS")) {
			osType = SOLARIS;
			JNI_INCLUDE = "linux";
		} else if (osName.startsWith("FreeBSD")) {
			osType = FREEBSD;
			JNI_INCLUDE = "linux";
		} else if (osName.startsWith("OpenBSD")) {
			osType = OPENBSD;
			JNI_INCLUDE = "linux";
		} else if (osName.equalsIgnoreCase("gnu")) {
			osType = GNU;
			JNI_INCLUDE = "linux";
		} else if (osName.equalsIgnoreCase("gnu/kfreebsd")) {
			osType = KFREEBSD;
			JNI_INCLUDE = "linux";
		} else if (osName.equalsIgnoreCase("netbsd")) {
			osType = NETBSD;
			JNI_INCLUDE = "linux";
		} else {
			osType = UNSPECIFIED;
			JNI_INCLUDE = "linux";
		}
		ARCH = getCanonicalArchitecture(System.getProperty("os.arch"));
		RESOURCE_PREFIX = getNativeLibraryResourcePrefix();
	}

	private Platform() {
	}

	static String getCanonicalArchitecture(String arch) {
		arch = arch.toLowerCase().trim();
		switch (arch) {
			case "i386":
			case "i686":
				arch = "x86";
				break;
			case "x86_64":
			case "amd64":
				arch = "x86_64";
				break;
			case "zarch_64":
				arch = "s390x";
				break;
		}
		// Work around OpenJDK mis-reporting os.arch
		// https://bugs.openjdk.java.net/browse/JDK-8073139
		if ("powerpc64".equals(arch) && "little".equals(System.getProperty("sun.cpu.endian"))) {
			arch = "powerpc64le";
		}
		return arch;
	}

	static String getNativeLibraryResourcePrefix() {
		return getNativeLibraryResourcePrefix(osType, System.getProperty("os.arch"), System.getProperty("os.name"));
	}

	static String getNativeLibraryResourcePrefix(int osType, String arch, String name) {
		String osPrefix;
		arch = getCanonicalArchitecture(arch);
		switch (osType) {
			case Platform.ANDROID:
				if (arch.startsWith("arm")) {
					arch = "arm";
				}
				osPrefix = arch + "-android";
				break;
			case Platform.LINUX:
				osPrefix = arch + "-linux";
				break;
			case Platform.WINDOWS:
			case Platform.WINDOWSCE:
				osPrefix = arch + "-windows";
				break;
			case Platform.MAC:
				osPrefix = arch + "-macos";
				break;
			case Platform.SOLARIS:
				osPrefix = arch + "-sunos";
				break;
			case Platform.FREEBSD:
				osPrefix = arch + "-freebsd";
				break;
			case Platform.OPENBSD:
				osPrefix = arch + "-openbsd";
				break;
			case Platform.NETBSD:
				osPrefix = arch + "-netbsd";
				break;
			case Platform.KFREEBSD:
				osPrefix = arch + "-kfreebsd";
				break;
			default:
				osPrefix = name.toLowerCase();
				int space = osPrefix.indexOf(" ");
				if (space != -1) {
					osPrefix = osPrefix.substring(0, space);
				}
				osPrefix = arch + "-" + osPrefix;
				break;
		}
		return osPrefix;
	}
}
