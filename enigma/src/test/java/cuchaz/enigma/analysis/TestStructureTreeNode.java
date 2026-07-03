package cuchaz.enigma.analysis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Test;

import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

public class TestStructureTreeNode {
	@Test
	public void qualifiedClassProposalIsFormattedForTree() {
		ClassEntry entry = new ClassEntry("example/Foo");

		assertThat(StructureTreeNode.formatProposedNameForTree(entry, "net/minecraft/TextureManager"), equalTo("TextureManager"));
	}

	@Test
	public void ownerPackageClassProposalIsFormattedForTree() {
		ClassEntry entry = new ClassEntry("example/Foo");

		assertThat(StructureTreeNode.formatProposedNameForTree(entry, "example/TextureManager"), equalTo("TextureManager"));
	}

	@Test
	public void innerClassProposalIsFormattedForTree() {
		ClassEntry entry = new ClassEntry("example/Foo$Bar");

		assertThat(StructureTreeNode.formatProposedNameForTree(entry, "Part"), equalTo("Part"));
	}

	@Test
	public void memberProposalIsKeptForTree() {
		MethodEntry entry = new MethodEntry(new ClassEntry("example/Foo"), "a", new MethodDescriptor("()V"));

		assertThat(StructureTreeNode.formatProposedNameForTree(entry, "renderTexture"), equalTo("renderTexture"));
	}
}
