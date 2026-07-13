package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class LlmEvaluationHarnessTest {
	@Test
	public void contextBackendComparisonFixtureProducesDifferentPrompts() {
		LlmContextBackendComparisonHarness.ComparisonFixture fixture = LlmContextBackendComparisonHarness.comparisonFixture();
		LlmContextBackendComparisonHarness.ComparisonCase testCase = fixture.cases().get(0);
		LlmContextBackendComparisonHarness.EvaluationProjectView project = new LlmContextBackendComparisonHarness.EvaluationProjectView(testCase.mappings());

		String ownerPrompt = new LlmPromptBuilder().build(testCase.key(), project, fixture.index(), LlmContextBackend.OWNER);
		String graphPrompt = new LlmPromptBuilder().build(testCase.key(), project, fixture.index(), LlmContextBackend.GRAPH);

		assertThat(ownerPrompt, not(containsString("Context backend: graph")));
		assertThat(ownerPrompt, not(containsString("Incoming callers")));
		assertThat(graphPrompt, containsString("Context backend: graph"));
		assertThat(graphPrompt, containsString("Incoming callers"));
		assertThat(graphPrompt, containsString("example/HudRenderer.c(Lexample/Counter;)V mapped: renderCount"));
	}

	@Test
	public void parsesEvaluationCaseJsonl() {
		LlmEvaluationHarness.EvaluationCase testCase = LlmEvaluationHarness.parseCase("{\"id\":\"field-pi\",\"kind\":\"FIELD\",\"targetName\":\"b.a : F\",\"prompt\":\"Target field\",\"expected\":\"pi\",\"acceptable\":[\"piValue\"]}");

		assertThat(testCase.id(), equalTo("field-pi"));
		assertThat(testCase.kind(), equalTo(EntryKind.FIELD));
		assertThat(testCase.targetName(), equalTo("b.a : F"));
		assertThat(testCase.prompt(), equalTo("Target field"));
		assertThat(testCase.expected(), equalTo("pi"));
		assertTrue(testCase.acceptable().contains("pi"));
		assertTrue(testCase.acceptable().contains("piValue"));
	}

	@Test
	public void evaluationResultSerializesJsonl() {
		LlmEvaluationHarness.EvaluationCase testCase = new LlmEvaluationHarness.EvaluationCase("field-pi", EntryKind.FIELD, "b.a : F", "Target field", "pi", Set.of("pi", "piValue"));
		LlmSuggestion suggestion = new LlmSuggestion("piValue", List.of("pi"), 0.8, "near miss");

		String json = LlmEvaluationHarness.EvaluationResult.success("local-model", testCase, suggestion, Duration.ofMillis(42)).toJson();

		assertThat(json, containsString("\"model\":\"local-model\""));
		assertThat(json, containsString("\"id\":\"field-pi\""));
		assertThat(json, containsString("\"accepted\":true"));
		assertThat(json, containsString("\"exact\":false"));
		assertThat(json, containsString("\"usable\":true"));
		assertThat(json, containsString("\"latencyMillis\":42"));
		assertThat(json, containsString("\"errorCategory\":\"\""));
	}

	@Test
	public void evaluationFailureSerializesInvalidJsonCategory() {
		LlmEvaluationHarness.EvaluationCase testCase = new LlmEvaluationHarness.EvaluationCase("field-pi", EntryKind.FIELD, "b.a : F", "Target field", "pi", Set.of("pi"));
		IOException error = new IOException("LLM response was not valid OpenAI-compatible suggestion JSON");

		String json = LlmEvaluationHarness.EvaluationResult.failure("local-model", testCase, error, Duration.ofMillis(42)).toJson();

		assertThat(json, containsString("\"accepted\":false"));
		assertThat(json, containsString("\"errorCategory\":\"invalid_json\""));
		assertThat(json, containsString("LLM response was not valid OpenAI-compatible suggestion JSON"));
	}

	@Test
	public void truncatedResponsesHaveDedicatedEvaluationCategory() {
		IOException error = new IOException("LLM response truncated at token limit");

		assertThat(LlmEvaluationHarness.errorCategory(error), equalTo("truncated"));
	}
}
