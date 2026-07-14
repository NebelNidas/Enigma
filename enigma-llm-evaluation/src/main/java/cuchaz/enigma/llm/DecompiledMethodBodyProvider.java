package cuchaz.enigma.llm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.objectweb.asm.Type;

import cuchaz.enigma.classprovider.CachingClassProvider;
import cuchaz.enigma.classprovider.ClassProvider;
import cuchaz.enigma.classprovider.JarClassProvider;
import cuchaz.enigma.source.Decompiler;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.source.Source;
import cuchaz.enigma.source.SourceSettings;

/**
 * Decompiles classes of an <em>obfuscated</em> corpus jar with Vineflower (Enigma's default decompiler,
 * already on the classpath) and extracts the source text of a single target method, for the
 * code-in-prompt benchmark arm. Names stay obfuscated ({@code remapper == null}), so the snippet shows
 * the exact opaque identifiers the model must reason about — the decompiler's own local names
 * ({@code var3}, {@code this$0}) are placeholders that {@link CodePromptNormalizer} canonicalises.
 *
 * <p>Decompilation is per class and cached; a class that fails to decompile yields
 * {@link Optional#empty()} rather than throwing, so one bad class never aborts a sweep. Method lookup
 * is a brace-matched textual extraction keyed on the obfuscated method name, disambiguated by argument
 * count when a class has several methods of that name.
 */
final class DecompiledMethodBodyProvider implements AutoCloseable {
	private final JarClassProvider jarClassProvider;
	private final Decompiler decompiler;
	private final Map<String, Optional<String>> classSourceCache = new HashMap<>();

	DecompiledMethodBodyProvider(Path obfJar) throws IOException {
		this.jarClassProvider = new JarClassProvider(obfJar);
		ClassProvider classProvider = new CachingClassProvider(this.jarClassProvider);
		// removeImports=true, removeVariableFinal=true -> compact bodies matching the Enigma GUI's own
		// decompiler settings; remapper stays null in getSource so identifiers stay obfuscated.
		this.decompiler = Decompilers.VINEFLOWER.create(classProvider, new SourceSettings(true, true));
	}

	/**
	 * The decompiled Java source of the target method (its declaration plus body as Vineflower renders
	 * it), or empty if the owning class cannot be decompiled or the method cannot be located.
	 */
	Optional<String> methodSource(String obfOwnerInternalName, String obfMethodName, String obfMethodDescriptor) {
		Optional<String> classSource = classSource(obfOwnerInternalName);

		if (classSource.isEmpty()) {
			return Optional.empty();
		}

		return extractMethod(classSource.get(), obfMethodName, argumentCount(obfMethodDescriptor));
	}

	/**
	 * The decompiled Java declaration of the target field when Vineflower renders an initializer on
	 * the declaration itself, or empty if the owning class cannot be decompiled or the declaration has
	 * no inline initializer. Constructor-assignment and use-site snippets are deliberately excluded:
	 * they need a separate target-name-blind selection policy before they are suitable for benchmark
	 * prompts.
	 */
	Optional<String> fieldInitializerSource(String obfOwnerInternalName, String obfFieldName) {
		Optional<String> classSource = classSource(obfOwnerInternalName);

		if (classSource.isEmpty()) {
			return Optional.empty();
		}

		return extractFieldInitializer(classSource.get(), obfFieldName);
	}

	private Optional<String> classSource(String internalName) {
		return this.classSourceCache.computeIfAbsent(internalName, name -> {
			try {
				Source source = this.decompiler.getSource(name, null);
				String text = source == null ? null : source.asString();

				if (text == null || text.isBlank() || isFailedDecompile(text)) {
					return Optional.empty();
				}

				return Optional.of(text);
			} catch (RuntimeException e) {
				return Optional.empty();
			}
		});
	}

	private static boolean isFailedDecompile(String text) {
		// Vineflower emits an error banner in the source instead of throwing when a class won't decompile.
		return text.contains("$VF: Couldn't be decompiled") || text.contains("// Error while decompiling");
	}

	private static int argumentCount(String descriptor) {
		try {
			return Type.getArgumentTypes(descriptor).length;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/**
	 * Finds a method declaration named {@code methodName} in the decompiled class text and returns it
	 * from the start of its (possibly annotated/modifier-prefixed) signature line through the brace that
	 * closes its body. When several methods share the name, the one whose parameter count matches
	 * {@code argCount} is preferred; abstract/interface methods (no body, terminated by {@code ;}) are skipped.
	 */
	private static Optional<String> extractMethod(String classText, String methodName, int argCount) {
		// Constructors (<init>) and static initializers (<clinit>) decompile under the class name / no name,
		// so they are never located by their bytecode name; they are also excluded from the scored ground truth.
		if (methodName.startsWith("<")) {
			return Optional.empty();
		}

		// Locate declarations on a masked copy where comment and string/char/text-block CONTENT is blanked to
		// spaces (same indices). This makes the search and the brace/paren matching immune to a method name or a
		// stray '{' / '}' / '(' / ')' that appears inside a literal or comment; the snippet is then cut from the
		// ORIGINAL text at those indices so the returned code is verbatim.
		String masked = maskLiterals(classText);
		Optional<String> fallback = Optional.empty();
		int from = 0;

		while (true) {
			int nameAt = masked.indexOf(methodName + "(", from);

			if (nameAt < 0) {
				break;
			}

			from = nameAt + methodName.length();

			// Reject a use of the name that is a member call (obj.m34(...)) or a substring of a longer
			// identifier -- only a declaration is preceded by a type/modifier boundary, never '.'.
			char before = nameAt > 0 ? masked.charAt(nameAt - 1) : ' ';

			if (isIdentifierPart(before) || before == '.') {
				continue;
			}

			int openParen = nameAt + methodName.length();
			int closeParen = matchParen(masked, openParen);

			if (closeParen < 0) {
				continue;
			}

			// A declaration opens its body with '{' after the ')', possibly past a 'throws' clause; a call is
			// followed by ';', ')', '.', an operator, etc. Anything but a body brace means this is not the decl.
			int bodyBrace = methodBodyBrace(masked, closeParen + 1);

			if (bodyBrace < 0) {
				continue;
			}

			int bodyEnd = matchBrace(masked, bodyBrace);

			if (bodyEnd < 0) {
				continue;
			}

			int signatureStart = signatureStart(masked, nameAt);
			String snippet = classText.substring(signatureStart, bodyEnd + 1).strip();
			int params = countParameters(masked, openParen, closeParen);

			if (argCount < 0 || params == argCount) {
				return Optional.of(snippet);
			}

			if (fallback.isEmpty()) {
				fallback = Optional.of(snippet);
			}
		}

		return fallback;
	}

	private static Optional<String> extractFieldInitializer(String classText, String fieldName) {
		String masked = maskLiterals(classText);
		int from = 0;

		while (true) {
			int nameAt = masked.indexOf(fieldName, from);

			if (nameAt < 0) {
				return Optional.empty();
			}

			from = nameAt + fieldName.length();

			if (!isIdentifierBoundary(masked, nameAt, fieldName.length()) || classBraceDepth(masked, nameAt) != 1) {
				continue;
			}

			int statementStart = fieldStatementStart(masked, nameAt);
			int statementEnd = fieldStatementEnd(masked, nameAt);

			if (statementStart < 0 || statementEnd < 0) {
				continue;
			}

			String maskedStatement = masked.substring(statementStart, statementEnd + 1);

			if (maskedStatement.indexOf('=') < 0) {
				continue;
			}

			return Optional.of(classText.substring(statementStart, statementEnd + 1).strip());
		}
	}

	/** Walks back from the method name to the start of its signature line (after the previous statement). */
	private static int signatureStart(String classText, int nameAt) {
		int i = nameAt;

		while (i > 0) {
			char c = classText.charAt(i - 1);

			if (c == '\n') {
				// Skip back over annotation lines that belong to this declaration.
				int prevLineStart = lineStart(classText, i - 2);
				String prevLine = classText.substring(prevLineStart, i - 1).strip();

				if (prevLine.startsWith("@")) {
					i = prevLineStart;
					continue;
				}

				return lineStart(classText, nameAt - 1);
			}

			if (c == '{' || c == '}' || c == ';') {
				return i;
			}

			i--;
		}

		return 0;
	}

	private static int lineStart(String text, int index) {
		int i = Math.max(0, Math.min(index, text.length() - 1));

		while (i > 0 && text.charAt(i - 1) != '\n') {
			i--;
		}

		return i;
	}

	private static int fieldStatementStart(String text, int nameAt) {
		int i = nameAt;

		while (i > 0) {
			char c = text.charAt(i - 1);

			if (c == ';' || c == '{' || c == '}') {
				return i;
			}

			i--;
		}

		return 0;
	}

	private static int fieldStatementEnd(String text, int nameAt) {
		int parenDepth = 0;
		int angleDepth = 0;

		for (int i = nameAt; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == '(' || c == '[') {
				parenDepth++;
			} else if (c == ')' || c == ']') {
				parenDepth = Math.max(0, parenDepth - 1);
			} else if (c == '<') {
				angleDepth++;
			} else if (c == '>') {
				angleDepth = Math.max(0, angleDepth - 1);
			} else if (c == ';' && parenDepth == 0 && angleDepth == 0) {
				return i;
			} else if (c == '{' || c == '}') {
				return -1;
			}
		}

		return -1;
	}

	private static int classBraceDepth(String text, int index) {
		int depth = 0;

		for (int i = 0; i < index && i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth = Math.max(0, depth - 1);
			}
		}

		return depth;
	}

	/**
	 * Returns a same-length copy of {@code text} in which the CONTENT of line comments, block comments and
	 * string / char / text-block literals (including their delimiters) is replaced by spaces, with newlines
	 * kept. Indices are preserved, so a declaration located in the mask maps back to the same span in the
	 * original. This is what makes declaration search and brace matching literal- and comment-safe.
	 */
	private static String maskLiterals(String text) {
		char[] out = text.toCharArray();
		int n = text.length();
		int i = 0;

		while (i < n) {
			char c = text.charAt(i);

			if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
				int nl = text.indexOf('\n', i + 2);
				int end = nl < 0 ? n : nl;
				blank(out, i, end);
				i = end;
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
				int e = text.indexOf("*/", i + 2);
				int end = e < 0 ? n : e + 2;
				blank(out, i, end);
				i = end;
			} else if (c == '"' && i + 2 < n && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
				int e = text.indexOf("\"\"\"", i + 3);
				int end = e < 0 ? n : e + 3;
				blank(out, i, end);
				i = end;
			} else if (c == '"' || c == '\'') {
				int j = i + 1;

				while (j < n) {
					char d = text.charAt(j);

					if (d == '\\') {
						j += 2;
					} else if (d == c || d == '\n') {
						j++;
						break;
					} else {
						j++;
					}
				}

				blank(out, i, Math.min(j, n));
				i = j;
			} else {
				i++;
			}
		}

		return new String(out);
	}

	private static void blank(char[] out, int start, int end) {
		for (int i = start; i < end && i < out.length; i++) {
			if (out[i] != '\n') {
				out[i] = ' ';
			}
		}
	}

	/** Skips whitespace and an optional {@code throws} clause after {@code )}, returning the body '{' or -1. */
	private static int methodBodyBrace(String text, int from) {
		int i = nextNonSpace(text, from);

		if (i < 0) {
			return -1;
		}

		if (text.charAt(i) == '{') {
			return i;
		}

		if (text.startsWith("throws", i) && (i + 6 >= text.length() || !isIdentifierPart(text.charAt(i + 6)))) {
			for (int j = i + 6; j < text.length(); j++) {
				char c = text.charAt(j);

				if (c == '{') {
					return j;
				}

				if (c == ';') {
					return -1;
				}
			}
		}

		return -1;
	}

	private static int matchParen(String text, int openParen) {
		return matchBracket(text, openParen, '(', ')');
	}

	private static int matchBrace(String text, int openBrace) {
		return matchBracket(text, openBrace, '{', '}');
	}

	/** Index of the bracket closing the one at {@code openIndex}, skipping strings, chars and comments. */
	private static int matchBracket(String text, int openIndex, char open, char close) {
		int depth = 0;
		int i = openIndex;
		int len = text.length();

		while (i < len) {
			char c = text.charAt(i);

			if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') {
				int nl = text.indexOf('\n', i + 2);
				i = nl < 0 ? len : nl + 1;
				continue;
			}

			if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
				int end = text.indexOf("*/", i + 2);

				if (end < 0) {
					return -1;
				}

				i = end + 2;
				continue;
			}

			if (c == '"' || c == '\'') {
				i = skipQuoted(text, i, c);

				if (i < 0) {
					return -1;
				}

				continue;
			}

			if (c == open) {
				depth++;
			} else if (c == close) {
				depth--;

				if (depth == 0) {
					return i;
				}
			}

			i++;
		}

		return -1;
	}

	/** Given the index of an opening quote, returns the index just past the matching closing quote (or -1). */
	private static int skipQuoted(String text, int quoteIndex, char quote) {
		for (int i = quoteIndex + 1; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == '\\') {
				i++;
			} else if (c == quote) {
				return i + 1;
			}
		}

		return -1;
	}

	private static int countParameters(String text, int openParen, int closeParen) {
		String params = text.substring(openParen + 1, closeParen).strip();

		if (params.isEmpty()) {
			return 0;
		}

		int count = 1;
		int depth = 0;

		for (int i = 0; i < params.length(); i++) {
			char c = params.charAt(i);

			if (c == '<' || c == '(' || c == '[') {
				depth++;
			} else if (c == '>' || c == ')' || c == ']') {
				depth--;
			} else if (c == ',' && depth == 0) {
				count++;
			}
		}

		return count;
	}

	private static int nextNonSpace(String text, int from) {
		for (int i = from; i < text.length(); i++) {
			if (!Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}

		return -1;
	}

	private static boolean isIdentifierPart(char c) {
		return Character.isJavaIdentifierPart(c);
	}

	private static boolean isIdentifierBoundary(String text, int start, int length) {
		char before = start > 0 ? text.charAt(start - 1) : ' ';
		int afterIndex = start + length;
		char after = afterIndex < text.length() ? text.charAt(afterIndex) : ' ';
		return !isIdentifierPart(before) && before != '.' && !isIdentifierPart(after);
	}

	@Override
	public void close() throws Exception {
		this.jarClassProvider.close();
	}
}
