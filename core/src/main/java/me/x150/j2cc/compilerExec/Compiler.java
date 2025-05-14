package me.x150.j2cc.compilerExec;

import java.io.IOException;
import java.nio.file.Path;

public interface Compiler {
	Process invoke(Path cwd, String targetTriple, String... args) throws IOException;

	boolean supportsCrossComp();
}
