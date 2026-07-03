package cuchaz.enigma;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Test;

import cuchaz.enigma.analysis.EntryReference;
import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

public class TestEntryReference {
	@Test
	public void methodNameableEntryIsMethod() {
		ClassEntry owner = new ClassEntry("example/Foo");
		MethodEntry method = new MethodEntry(owner, "a", new MethodDescriptor("()V"));
		EntryReference<MethodEntry, MethodEntry> reference = new EntryReference<>(method, "a");

		assertThat(reference.getNameableEntry(), equalTo(method));
	}

	@Test
	public void constructorNameableEntryIsContainingClass() {
		ClassEntry owner = new ClassEntry("example/Foo");
		MethodEntry constructor = new MethodEntry(owner, "<init>", new MethodDescriptor("()V"));
		EntryReference<MethodEntry, MethodEntry> reference = new EntryReference<>(constructor, "<init>");

		assertThat(reference.getNameableEntry(), equalTo(owner));
	}
}
