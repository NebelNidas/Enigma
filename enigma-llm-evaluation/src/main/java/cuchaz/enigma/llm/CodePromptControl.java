package cuchaz.enigma.llm;

/**
 * Length-control ("M++") padding generator for the token-volume control arm of the code-in-prompt
 * benchmark. Produces a SEMANTICALLY INERT block of Java-like code, character-length-matched to the real
 * decompiled body it stands in for, so the M++ prompt equals the M+C prompt in length -- and therefore in
 * token count, since decompiled-code and sterile-code char/token ratios align (unlike natural-language
 * filler) -- while carrying NO naming evidence. This isolates the effect of THIS method's actual code
 * CONTENT (the M+C lift) from the effect of merely adding code-like token VOLUME (M++).
 *
 * <p>Design settled by a three-model red-team (Codex / Gemini / Grok), converging on option (b)-sterile:
 * <ul>
 *   <li><b>Sterile, unrelated code</b> — the pool methods use only single-letter identifiers and integer
 *       arithmetic / array indexing / control flow: zero string literals, zero external or API calls, zero
 *       domain vocabulary. So no call name, constant, or string can leak into the target's name prediction.</li>
 *   <li><b>Not shuffled target code</b> — shuffling preserves exactly the strongest naming signal (called
 *       library methods, constants, string literals) which an attention mechanism reads bag-of-words, so a
 *       shuffle control would itself lift and defeat the isolation.</li>
 *   <li><b>Explicitly labelled as padding by the caller</b> (see {@code LlmPromptBuilder.appendCodeSection})
 *       so the model does not treat sterile code as the target's body — presenting it as the real body would
 *       create a metadata-vs-body contradiction confound that depresses M++ for the wrong reason.</li>
 * </ul>
 *
 * <p>Deterministic given {@code (targetChars, seed)}; {@code seed} rotates the pool start so a run can be
 * repeated with a different padding assignment as a pad-choice sensitivity check.
 */
final class CodePromptControl {
	// Sterile method bodies: single-letter identifiers only; integer arithmetic, array indexing, and plain
	// control flow. NO string literals, NO method/API calls, NO domain vocabulary -> an absolute semantic null
	// that still matches the token distribution of decompiled Java (keywords, braces, operators, casts).
	private static final String[] STERILE_POOL = {
			"""
			int q0(int[] a) {
				int n = a.length;

				for (int i = 1; i < n; i++) {
					int t = a[i];
					int j = i - 1;

					while (j >= 0 && a[j] > t) {
						a[j + 1] = a[j];
						j--;
					}

					a[j + 1] = t;
				}

				return n;
			}""",
			"""
			int q1(int[] a, int x) {
				int lo = 0;
				int hi = a.length - 1;

				while (lo <= hi) {
					int mid = (lo + hi) >>> 1;

					if (a[mid] < x) {
						lo = mid + 1;
					} else if (a[mid] > x) {
						hi = mid - 1;
					} else {
						return mid;
					}
				}

				return -1;
			}""",
			"""
			int q2(int a, int b) {
				while (b != 0) {
					int t = b;
					b = a % b;
					a = t;
				}

				return a < 0 ? -a : a;
			}""",
			"""
			int q3(int[] a, int[] b, int n) {
				int r = 0;

				for (int i = 0; i < n; i++) {
					for (int j = 0; j < n; j++) {
						r += a[i * n + j] * b[j * n + i];
					}
				}

				return r;
			}""",
			"""
			long q4(int n) {
				long p = 0;
				long c = 1;

				for (int i = 0; i < n; i++) {
					long t = p + c;
					p = c;
					c = t;
				}

				return p;
			}""",
			"""
			void q5(int[] a) {
				int i = 0;
				int j = a.length - 1;

				while (i < j) {
					int t = a[i];
					a[i] = a[j];
					a[j] = t;
					i++;
					j--;
				}
			}""",
			"""
			int q6(int[] a) {
				int m = a.length == 0 ? 0 : a[0];

				for (int i = 1; i < a.length; i++) {
					if (a[i] > m) {
						m = a[i];
					}
				}

				return m;
			}""",
			"""
			int[] q7(int[] a) {
				int n = a.length;
				int[] s = new int[n];
				int acc = 0;

				for (int i = 0; i < n; i++) {
					acc += a[i];
					s[i] = acc;
				}

				return s;
			}""",
			"""
			int q8(int v) {
				int c = 0;

				while (v != 0) {
					c += v & 1;
					v >>>= 1;
				}

				return c;
			}""",
			"""
			void q9(int[] a) {
				int n = a.length;

				for (int i = 0; i < n - 1; i++) {
					for (int j = 0; j < n - 1 - i; j++) {
						if (a[j] > a[j + 1]) {
							int t = a[j];
							a[j] = a[j + 1];
							a[j + 1] = t;
						}
					}
				}
			}"""
	};

	private CodePromptControl() {
	}

	/**
	 * A sterile filler block whose length is {@code targetChars} characters. If the repeated pool would end
	 * mid-token, the content is cut back to the previous non-identifier boundary and the remaining budget is
	 * filled with spaces, preserving exact length without adding semantics. Returns "" for a non-positive
	 * target. {@code seed} rotates which pool method the block starts with, so the same target can be re-padded
	 * differently for a sensitivity check.
	 */
	static String sterileFiller(int targetChars, int seed) {
		if (targetChars <= 0) {
			return "";
		}

		int start = Math.floorMod(seed, STERILE_POOL.length);
		StringBuilder sb = new StringBuilder(targetChars + 64);
		int i = 0;

		while (sb.length() < targetChars) {
			if (sb.length() > 0) {
				sb.append("\n\n");
			}

			sb.append(STERILE_POOL[(start + i) % STERILE_POOL.length]);
			i++;
		}

		if (sb.length() <= targetChars) {
			return sb.toString();
		}

		int cut = targetChars;

		// Do not end in the middle of an identifier/number token: back up to the last non-identifier char.
		if (cut < sb.length() && Character.isJavaIdentifierPart(sb.charAt(cut))) {
			while (cut > 0 && Character.isJavaIdentifierPart(sb.charAt(cut - 1))) {
				cut--;
			}
		}

		StringBuilder out = new StringBuilder(sb.substring(0, cut));

		while (out.length() < targetChars) {
			out.append(' ');
		}

		return out.toString();
	}
}
