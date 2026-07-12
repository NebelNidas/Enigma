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

			// 4. Canonicalize local names
			Matcher m = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(text);
			StringBuilder replaced = new StringBuilder();

			Map<String, String> localRenames = new HashMap<>();
			int[] localCounter = {1};

			Map<String, String> captureRenames = new HashMap<>();
			int[] captureCounter = {0};

			while (m.find()) {
				String id = m.group();
				if (id.matches("var\\d+")) {
					String newName = localRenames.computeIfAbsent(id, k -> "local" + (localCounter[0]++));
					m.appendReplacement(replaced, Matcher.quoteReplacement(newName));
				} else if (id.matches("this\\$\\d+") || id.startsWith("val$")) {
					String newName = captureRenames.computeIfAbsent(id, k -> "capture" + (captureCounter[0]++));
					m.appendReplacement(replaced, Matcher.quoteReplacement(newName));
				} else if (id.matches("lambda\\$[A-Za-z0-9_$]+\\$\\d+")) {
					int lastDollar = id.lastIndexOf('$');
					String newName = id.substring(0, lastDollar);
					m.appendReplacement(replaced, Matcher.quoteReplacement(newName));
				} else {
					m.appendReplacement(replaced, Matcher.quoteReplacement(id));
				}
			}
			m.appendTail(replaced);
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
