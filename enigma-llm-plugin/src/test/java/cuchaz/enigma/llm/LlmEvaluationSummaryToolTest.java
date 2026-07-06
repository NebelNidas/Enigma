package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LlmEvaluationSummaryToolTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void summarizesEvaluationResultFile() throws Exception {
		java.io.File results = this.temporaryFolder.newFile("results.jsonl");
		Files.writeString(results.toPath(), """
				{"accepted":true,"exact":true,"usable":true,"latencyMillis":100}
				{"accepted":true,"exact":false,"usable":true,"latencyMillis":200}
				{"accepted":false,"exact":false,"usable":false,"latencyMillis":300,"errorCategory":"invalid_json"}
				""", StandardCharsets.UTF_8);

		Map<String, LlmEvaluationSummaryTool.Summary> summaries = LlmEvaluationSummaryTool.summarizeByGroup(results.toPath());
		LlmEvaluationSummaryTool.Summary summary = summaries.get("all");

		assertThat(summary.total, equalTo(3));
		assertThat(summary.accepted, equalTo(2));
		assertThat(summary.exact, equalTo(1));
		assertThat(summary.usable, equalTo(2));
		assertThat(summary.failed, equalTo(1));
		assertThat(summary.invalidJson, equalTo(1));
		assertThat(summary.averageLatencyMillis(), closeTo(200.0, 0.01));
	}

	@Test
	public void groupsContextComparisonResultsByBackend() throws Exception {
		java.io.File results = this.temporaryFolder.newFile("context.jsonl");
		Files.writeString(results.toPath(), """
				{"backend":"owner","accepted":true,"exact":true,"usable":true,"latencyMillis":100}
				{"backend":"owner","accepted":false,"exact":false,"usable":false,"latencyMillis":300}
				{"backend":"graph","accepted":true,"exact":false,"usable":true,"latencyMillis":500}
				""", StandardCharsets.UTF_8);

		Map<String, LlmEvaluationSummaryTool.Summary> summaries = LlmEvaluationSummaryTool.summarizeByGroup(results.toPath());

		assertThat(summaries.get("owner").total, equalTo(2));
		assertThat(summaries.get("owner").accepted, equalTo(1));
		assertThat(summaries.get("owner").failed, equalTo(1));
		assertThat(summaries.get("owner").averageLatencyMillis(), closeTo(200.0, 0.01));
		assertThat(summaries.get("graph").total, equalTo(1));
		assertThat(summaries.get("graph").usable, equalTo(1));
	}
}
