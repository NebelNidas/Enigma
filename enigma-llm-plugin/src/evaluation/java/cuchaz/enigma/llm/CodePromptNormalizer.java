package cuchaz.enigma.llm;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodePromptNormalizer {
	public enum Track {
		REALISTIC, STRUCTURE_ONLY
	}

	private CodePromptNormalizer() {
	}

	public static String normalize(String rawMethodSource, Track track) {
		if (rawMethodSource == null) {
			return "";
		}

		// 1. Lexer pass: remove comments, optionally blank string literals
		StringBuilder sb = new StringBuilder();
		int len = rawMethodSource.length();
		int i = 0;
		int state = 0; // 0=NORMAL, 1=STRING, 2=CHAR, 3=LINE_COMMENT, 4=BLOCK_COMMENT

		try {
			while (i < len) {
				char c = rawMethodSource.charAt(i);
				if (state == 0) { // NORMAL
					if (c == '/' && i + 1 < len && rawMethodSource.charAt(i + 1) == '/') {
						state = 3; // LINE_COMMENT
						i += 2;
					} else if (c == '/' && i + 1 < len && rawMethodSource.charAt(i + 1) == '*') {
						state = 4; // BLOCK_COMMENT
						i += 2;
					} else if (c == '"') {
						state = 1; // STRING
						sb.append('"');
						i++;
					} else if (c == '\'') {
						state = 2; // CHAR
						sb.append('\'');
						i++;
					} else {
						sb.append(c);
						i++;
					}
				} else if (state == 1) { // STRING
					if (c == '\\') {
						if (track != Track.STRUCTURE_ONLY) {
							sb.append('\\');

							if (i + 1 < len) {
								sb.append(rawMethodSource.charAt(i + 1));
							}
						}

						i += 2; // skip the escaped char
					} else if (c == '"') {
						state = 0;
						sb.append('"');
						i++;
					} else {
						if (track != Track.STRUCTURE_ONLY) {
							sb.append(c);
						}

						i++;
					}
				} else if (state == 2) { // CHAR
					if (c == '\\') {
						sb.append('\\');

						if (i + 1 < len) {
							sb.append(rawMethodSource.charAt(i + 1));
						}

						i += 2;
					} else if (c == '\'') {
						state = 0;
						sb.append('\'');
						i++;
					} else {
						sb.append(c);
						i++;
					}
				} else if (state == 3) { // LINE_COMMENT
					if (c == '\n') {
						state = 0;
						sb.append('\n');
						i++;
					} else {
						i++;
					}
				} else if (state == 4) { // BLOCK_COMMENT
					if (c == '*' && i + 1 < len && rawMethodSource.charAt(i + 1) == '/') {
						state = 0;
						i += 2;
					} else {
						if (c == '\n') {
							sb.append('\n');
						}

						i++;
					}
				}
			}
		} catch (Exception e) {
			// fallback on best effort if the scan somehow fails
		}

		String text = sb.toString();

		try {
			// 2. Strip package and imports
			StringBuilder lines = new StringBuilder();

			for (String line : text.split("\n", -1)) {
				String t = line.trim();

				if (t.startsWith("package ") || t.startsWith("import ")) {
					continue;
				}

				lines.append(line).append('\n');
			}

			text = lines.toString();

			// 3. Strip class wrapper if present
			String trimmed = text.trim();
			Matcher classMatcher = Pattern.compile(
					"^(?:@[A-Za-z0-9_.$]+\\s*(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|protected|static|final|abstract|strictfp)\\s+)*(?:class|enum|interface|record)\\b"
			).matcher(trimmed);

			if (classMatcher.find()) {
				int firstBrace = text.indexOf('{');
				int lastBrace = text.lastIndexOf('}');

				if (firstBrace != -1 && lastBrace > firstBrace) {
					text = text.substring(firstBrace + 1, lastBrace);
				}
			}

			// 4. Canonicalize decompiler-synthetic local names. Scan manually and skip string/char literals so
			// the realistic track (where string payloads are kept) never mutates an identifier-looking substring
			// inside a string literal -- only real code identifiers are renamed.
			StringBuilder replaced = new StringBuilder(text.length());
			Map<String, String> localRenames = new HashMap<>();
			int[] localCounter = {1};
			Map<String, String> captureRenames = new HashMap<>();
			int[] captureCounter = {0};
			int textLen = text.length();
			int pos = 0;

			while (pos < textLen) {
				char c = text.charAt(pos);

				if (c == '"' || c == '\'') {
					int end = pos + 1;

					while (end < textLen) {
						char e = text.charAt(end);

						if (e == '\\') {
							end += 2;
						} else if (e == c) {
							end++;
							break;
						} else {
							end++;
						}
					}

					replaced.append(text, pos, Math.min(end, textLen));
					pos = Math.min(end, textLen);
				} else if (Character.isJavaIdentifierStart(c) || c == '$') {
					int start = pos;
					pos++;

					while (pos < textLen && (Character.isJavaIdentifierPart(text.charAt(pos)) || text.charAt(pos) == '$')) {
						pos++;
					}

					String id = text.substring(start, pos);

					if (id.matches("var\\d+")) {
						replaced.append(localRenames.computeIfAbsent(id, k -> "local" + (localCounter[0]++)));
					} else if (id.matches("this\\$\\d+") || id.startsWith("val$")) {
						replaced.append(captureRenames.computeIfAbsent(id, k -> "capture" + (captureCounter[0]++)));
					} else if (id.matches("lambda\\$[A-Za-z0-9_$]+\\$\\d+")) {
						replaced.append(id, 0, id.lastIndexOf('$'));
					} else {
						replaced.append(id);
					}
				} else {
					replaced.append(c);
					pos++;
				}
			}

			text = replaced.toString();

			// 5. Trim trailing whitespace & collapse blank lines
			String[] textLines = text.split("\n", -1);
			StringBuilder finalOut = new StringBuilder();
			int blankCount = 0;

			for (String line : textLines) {
				int end = line.length();

				while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
					end--;
				}

				line = line.substring(0, end);

				if (line.isEmpty()) {
					blankCount++;
				} else {
					if (blankCount > 0 && finalOut.length() > 0) {
						finalOut.append("\n");
					}

					finalOut.append(line).append("\n");
					blankCount = 0;
				}
			}

			// Remove the very last newline if it exists
			if (finalOut.length() > 0 && finalOut.charAt(finalOut.length() - 1) == '\n') {
				finalOut.setLength(finalOut.length() - 1);
			}

			return finalOut.toString();
		} catch (Exception e) {
			return text;
		}
	}
}
