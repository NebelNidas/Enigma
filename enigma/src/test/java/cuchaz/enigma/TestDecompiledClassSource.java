package cuchaz.enigma;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Test;

import cuchaz.enigma.source.DecompiledClassSource;
import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

public class TestDecompiledClassSource {
	@Test
	public void qualifiedClassProposalIsFormattedForSource() {
		ClassEntry entry = new ClassEntry("example/Foo");

		assertThat(DecompiledClassSource.formatProposedNameForSource(entry, "net/minecraft/TextureManager"), equalTo("TextureManager"));
	}

	@Test
	public void ownerPackageClassProposalIsFormattedForSource() {
		ClassEntry entry = new ClassEntry("example/Foo");

		assertThat(DecompiledClassSource.formatProposedNameForSource(entry, "example/TextureManager"), equalTo("TextureManager"));
	}

	@Test
	public void innerClassProposalIsFormattedForSource() {
		ClassEntry entry = new ClassEntry("example/Foo$Bar");

		assertThat(DecompiledClassSource.formatProposedNameForSource(entry, "Part"), equalTo("Foo.Part"));
	}

	@Test
	public void memberProposalIsKeptForSource() {
		MethodEntry entry = new MethodEntry(new ClassEntry("example/Foo"), "a", new MethodDescriptor("()V"));

		assertThat(DecompiledClassSource.formatProposedNameForSource(entry, "renderTexture"), equalTo("renderTexture"));
	}
}
