package cuchaz.enigma.source;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Test;

import cuchaz.enigma.translation.representation.entry.ClassEntry;

public class TestSourceTokenValidation {
	@Test
	public void sourceIndexRejectsOutOfBoundsTokens() {
		SourceIndex index = new SourceIndex("class a {}");
		index.addReference(new Token(20, 21, "a"), ClassEntry.parse("a"), null);

		assertThat(index.referenceTokens(), emptyIterable());
	}

	@Test
	public void sourceRemapperSkipsOutOfBoundsTokens() {
		SourceRemapper remapper = new SourceRemapper("class a {}", java.util.List.of(new Token(20, 21, "a")));
		SourceRemapper.Result result = remapper.remap((token, movedToken) -> "Example");

		assertThat(result.getSource(), equalTo("class a {}"));
		assertThat(result.isEmpty(), equalTo(true));
	}

	@Test
	public void sourceRemapperSkipsTokensWithMismatchedTextLength() {
		SourceRemapper remapper = new SourceRemapper("class a {}", java.util.List.of(new Token(6, 7, "longer")));
		SourceRemapper.Result result = remapper.remap((token, movedToken) -> "Example");

		assertThat(result.getSource(), equalTo("class a {}"));
		assertThat(result.isEmpty(), equalTo(true));
	}

	@Test
	public void sourceRemapperSkipsTokensMovedOutOfBoundsByEarlierRenames() {
		Token first = new Token(0, 9, "class a b");
		Token second = new Token(6, 7, "a");
		SourceRemapper remapper = new SourceRemapper("class a b", java.util.List.of(first, second));

		SourceRemapper.Result result = remapper.remap((token, movedToken) -> {
			if (token.equals(first)) {
				return "";
			}

			return "renamed";
		});

		assertThat(result.getSource(), equalTo(""));
		assertThat(result.getRemappedToken(first).text, equalTo(""));
		assertThat(result.getRemappedToken(second), equalTo(second));
	}
}
