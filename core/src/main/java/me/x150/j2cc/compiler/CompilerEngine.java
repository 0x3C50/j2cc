package me.x150.j2cc.compiler;

import me.x150.j2cc.conf.Context;
import me.x150.j2cc.cppwriter.Method;
import me.x150.j2cc.cppwriter.SourceBuilder;
import me.x150.j2cc.exc.CompilationFailure;
import me.x150.j2cc.tree.Remapper;
import me.x150.j2cc.util.StringCollector;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

public interface CompilerEngine {
	Method compile(Context context, ClassNode owner, MethodNode methodNode, SourceBuilder source, String targetSymbol, Remapper remapper, CacheSlotManager indyCache, StringCollector stringCollector) throws AnalyzerException, CompilationFailure;
}
