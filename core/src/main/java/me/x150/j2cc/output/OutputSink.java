package me.x150.j2cc.output;

import java.io.IOException;
import java.io.OutputStream;

public interface OutputSink extends AutoCloseable {
	OutputStream openFile(String name) throws IOException;
}
