package cuchaz.enigma.source.vineflower;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Test;

public class TestVineflowerSource {
	@Test
	public void detectsVineflowerFailureSource() {
		String source = """
				/*
				 * $VF: Unable to decompile class f
				 * Please report this to the Vineflower issue tracker
				 */
				""";

		assertThat(VineflowerSource.isFailedDecompile(source), equalTo(true));
	}

	@Test
	public void doesNotTreatNormalSourceAsFailure() {
		assertThat(VineflowerSource.isFailedDecompile("public class f {\n}\n"), equalTo(false));
	}
}
