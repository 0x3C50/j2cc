package me.x150.j2cc;

import java.util.ArrayList;
import java.util.InputMismatchException;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//@Nativeify
public class StackTranslator {
	public final Function<String, String> classNameRemapper;
	public final Function<Descriptor, String> methodRemapper;

	public StackTranslator(Function<String, String> classNameRemapper, Function<Descriptor, String> methodNameRemapper) {
		this.classNameRemapper = classNameRemapper;
		this.methodRemapper = methodNameRemapper;
	}

	public static void expect(boolean b, String msg) {
		if (!b) throw new IllegalStateException(msg);
	}

	public static String stripSlash(String s) {
		if (s == null) return null;
		while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
		return s.isEmpty() ? null : s;
	}

	public StackTraceElement translate(StackTraceElement original) {
		String className = original.getClassName();
		String methodName = original.getMethodName();
		String mappedClassName = classNameRemapper.apply(className);
		Descriptor methodDesc = new Descriptor(mappedClassName, methodName);
		return new StackTraceElement(original.getClassLoaderName(), original.getModuleName(), original.getModuleVersion(),
				mappedClassName,
				methodRemapper.apply(methodDesc),
				original.getFileName(),
				original.getLineNumber());
	}

	public StackTraceElement[] translate(StackTraceElement[] elements) {
		StackTraceElement[] ret = new StackTraceElement[elements.length];
		for (int i = 0; i < elements.length; i++) {
			ret[i] = translate(elements[i]);
		}
		return ret;
	}

	public ExceptionInfo parseAndRemapStacktrace(String s) {
		Tokenizer tok = new Tokenizer(s);
		return parseAndRemapStacktraceInternal(tok, 1);
	}

//	@Nativeify
	public ExceptionInfo parseAndRemapStacktraceInternal(Tokenizer tokenizer, int expectIndent) {
		String originalExceptionName = tokenizer.readNext(Pattern.compile("^.+?(?=[:\n])"), "className");
		String message = null;
		char read = tokenizer.read();
		if (read == ':') {
			tokenizer.skipWs();
			message = tokenizer.readNext(Pattern.compile("^.*?(?=\n)"), "message");
			expect(tokenizer.read() == '\n', null);
		} else expect(read == '\n', "exception class name postfix isn't : or \\n");
		List<ExceptionInfo> suppressed = new ArrayList<>();
		List<StackTraceElement> stel = new ArrayList<>();
		out:
		while (!tokenizer.atEnd()) {
			int before = tokenizer.idx;
			for (int i = 0; i < expectIndent; i++) {
				char read1 = tokenizer.peek();
				if (read1 != '\t') {
					tokenizer.idx = before;
					break out; // not our problem anymore
				}
				tokenizer.read();
			}

			String s1 = tokenizer.readNext(Pattern.compile("^(at |Suppressed: |\\.{3})"), "atSuppressedOrDot");
			if (s1.equals("at ")) {
				MatchResult stackTraceELement = tokenizer.readNextPattern(Pattern.compile("^(?<loaderName>.+?/)?(?<moduleName>.*?/)?(?<methodPath>.+?)\\((?<source>Unknown Source|Native Method|.*?)\\)$", Pattern.MULTILINE), "stackTraceElement");
				String loaderName = stackTraceELement.group("loaderName");
				String moduleString = stackTraceELement.group("moduleName");
				if (moduleString == null && loaderName != null) {
					moduleString = loaderName;
					loaderName = null;
				}
				loaderName = stripSlash(loaderName);
				moduleString = stripSlash(moduleString);
				String methodPath = stackTraceELement.group("methodPath");
				String source = stackTraceELement.group("source");
				String moduleName = null;
				String moduleVersion = null;
				if (moduleString != null) {
					String[] split = moduleString.split("@", 2);
					moduleName = split[0];
					if (split.length == 2) moduleVersion = split[1];
				}
				int endIndex = methodPath.lastIndexOf('.');
				String methodOwner = methodPath.substring(0, endIndex);
				String methodName = methodPath.substring(endIndex + 1);
				String sourceFile = null;
				int sourceLine = -1;
				if (source.equals("Native Method")) sourceLine = -2;
				else {
					String[] split = source.split(":", 2);
					sourceFile = split[0];
					if (split.length == 2) {
						try {
							sourceLine = Integer.parseInt(split[1]);
						} catch (Throwable ignored) {
						}
					}
				}
				StackTraceElement ste = new StackTraceElement(loaderName, moduleName, moduleVersion, methodOwner, methodName, sourceFile, sourceLine);
				stel.add(this.translate(ste));
				expect(tokenizer.read() == '\n', null);
			} else if (s1.equals("Suppressed: ")) {
				suppressed.add(parseAndRemapStacktraceInternal(tokenizer, expectIndent + 1));
			} else {
				tokenizer.readNext(Pattern.compile("^.*?$", Pattern.MULTILINE), "skipLine");
				expect(tokenizer.read() == '\n', null);
			}
		}
		int before = tokenizer.idx;
		boolean weContinue = !tokenizer.atEnd();
		if (weContinue) {
			for (int i = 0; i < expectIndent - 1; i++) {
				char read1 = tokenizer.peek();
				if (read1 != '\t') {
					tokenizer.idx = before;
					weContinue = false;
					break;
				}
				tokenizer.read();
			}
		}
		ExceptionInfo cause = null;
		if (weContinue) {
			Pattern causedBy = Pattern.compile("^Caused by: ");
			if (tokenizer.hasNext(causedBy)) {
				tokenizer.readNext(causedBy, "causedBy");
				cause = parseAndRemapStacktraceInternal(tokenizer, expectIndent);
			} else {
				tokenizer.idx = before;
			}
		}
		return new ExceptionInfo(classNameRemapper.apply(originalExceptionName), message, stel.toArray(StackTraceElement[]::new), suppressed.toArray(ExceptionInfo[]::new), cause);
	}

	public record ExceptionInfo(String className, String message, StackTraceElement[] elements,
								ExceptionInfo[] suppressed, ExceptionInfo cause) {
		public void stringify(StringBuilder sb, int indent) {
			sb.append(className);
			if (message != null) sb.append(": ").append(message);
			for (StackTraceElement element : elements) {
				sb.append("\n").append("\t".repeat(indent + 1)).append("at ").append(element);
			}
			for (ExceptionInfo exceptionInfo : suppressed) {
				sb.append("\n").append("\t".repeat(indent + 1)).append("Suppressed: ");
				exceptionInfo.stringify(sb, indent + 1);
			}
			if (cause != null) {
				sb.append("\n").append("\t".repeat(indent)).append("Caused by: ");
				cause.stringify(sb, indent);
			}
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			stringify(sb, 0);
			return sb.toString();
		}
	}

	public record Descriptor(String mappedOwnerClass, String methodName) {
	}

	public static class ParserErrorBuilder {
		final List<CodeSegment> segments = new ArrayList<>();
		private final String message;

		public ParserErrorBuilder(String message) {
			this.message = message;
		}

//		@Nativeify
		public void addCodeSegment(String code, int from, int to) {
			StringBuilder sb = new StringBuilder();
			char[] charArray = code.toCharArray();
			for (int i = 0; i < charArray.length; i++) {
				char c = charArray[i];
				if (c == '\n') {
					if (i <= from) {
						from++;
						to++;
					} else if (i <= to) to++;
					sb.append("\\n");
				} else if (c == '\t') {
					if (i <= from) {
						from++;
						to++;
					} else if (i <= to) to++;
					sb.append("  ");
				} else sb.append(c);
			}
			segments.add(new CodeSegment(sb.toString(), from, to));
		}

		@Override
		public String toString() {
			StringBuilder b = new StringBuilder();
			b.append("╭  Error:\n");
			for (CodeSegment segment : segments) {
				int codeLength = segment.to - segment.from;
				b.append("│  ").append(segment.code).append("\n");
				b.append("│  ").append(" ".repeat(segment.from)).append("^".repeat(codeLength)).append("\n");
			}
			String[] array = message.split("\n");
			for (int i = 0; i < array.length; i++) {
				if (i == array.length - 1) {
					b.append("╰  ").append(array[i]);
				} else {
					b.append("│  ").append(array[i]).append("\n");
				}
			}
			return b.toString();
		}

		public record CodeSegment(String code, int from, int to) {

		}
	}

	public static class Tokenizer {
		public final String sc;
		int idx = 0;

		public Tokenizer(String input) {
			this.sc = input;
		}

		public char peek() {
			return sc.charAt(idx);
		}

		public char read() {
			return sc.charAt(idx++);
		}

		public boolean atEnd() {
			return idx >= sc.length();
		}

		public void skipWs() {
			while (!atEnd() && (peek() == ' ' || peek() == '\t')) read();
		}

		public String readNext(Pattern pat, String patternName) {
			MatchResult matchResult = readNextPattern(pat, patternName);
			return matchResult.group(0);
		}

		public MatchResult readNextPattern(Pattern pattern, String patternName) {
			Matcher matcher = pattern.matcher(sc);
			matcher.region(idx, sc.length());
			if (matcher.find()) {
				MatchResult matchResult = matcher.toMatchResult();
				idx = matchResult.end();
				return matchResult;
			} else {
				ParserErrorBuilder n = new ParserErrorBuilder("Unexpected content from " + idx + " to " + sc.length() + "\nExpected <" + patternName + "> with pattern " + pattern);
				n.addCodeSegment(sc, idx, sc.length());
				throw new InputMismatchException("Unexpected content\n" + n);
			}
		}

		public boolean hasNext(Pattern pat) {
			Matcher matcher = pat.matcher(sc);
			matcher.region(idx, sc.length());
			return matcher.find();
		}
	}
}
