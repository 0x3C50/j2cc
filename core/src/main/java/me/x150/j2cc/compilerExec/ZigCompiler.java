package me.x150.j2cc.compilerExec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

//@Nativeify
public class ZigCompiler implements Compiler {
	private static final String[] knownZigPaths = {
			"zig",
			"zig.exe"
	};
	private final Path target;

	public ZigCompiler(Path target) {
		this.target = target;
	}


	public static ZigCompiler locate(Path p) {
		List<Path> list = Arrays.stream(knownZigPaths).map(p::resolve).filter(Files::exists).toList();
		if (list.isEmpty()) throw new IllegalStateException("Could not locate zig compiler in " + p.toAbsolutePath());
		if (list.size() > 1) {
			StringBuilder s1 = new StringBuilder("Multiple zig binaries found:");
			for (Path path : list) {
				s1.append("\n  ").append(path);
			}
			s1.append("\n").append("Looked in: ").append(p);
			throw new IllegalStateException(s1.toString());
		}
		return new ZigCompiler(list.getFirst());
	}

	@Override
	public boolean supportsCrossComp() {
		return true;
	}

	@Override
	public Process invoke(Path cwd, String targetTriple, String... args) throws IOException {
		String[] full = new String[args.length + 2 + (targetTriple == null ? 0 : 2)];
		full[0] = target.toAbsolutePath().toString();
		full[1] = "c++";
		System.arraycopy(args, 0, full, 2, args.length);
		if (targetTriple != null) {
			full[full.length - 2] = "-target";
			full[full.length - 1] = targetTriple;
		}

		ProcessBuilder pb = new ProcessBuilder();
		pb.directory(cwd.toFile());
		pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
		pb.command(full);
		return pb.start();
	}
}
