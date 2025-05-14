package me.x150.j2cc.compilerExec;

import java.io.IOException;
import java.nio.file.Path;

public class GccCompiler implements Compiler {

	private final Path execPath;

	public GccCompiler(Path execPath) {
		this.execPath = execPath;
	}

	@Override
	public Process invoke(Path cwd, String targetTriple, String... args) throws IOException {
		String[] full = new String[args.length + 1];
		full[0] = execPath.toAbsolutePath().toString();
		System.arraycopy(args, 0, full, 1, args.length);

		ProcessBuilder pb = new ProcessBuilder();
		pb.directory(cwd.toFile());
		pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
		pb.command(full);
		return pb.start();
	}

	@Override
	public boolean supportsCrossComp() {
		return false;
	}
}
