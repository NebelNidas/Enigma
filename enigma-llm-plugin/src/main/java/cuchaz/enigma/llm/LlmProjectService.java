package cuchaz.enigma.llm;

import cuchaz.enigma.api.service.ProjectService;
import cuchaz.enigma.api.view.ProjectView;

public class LlmProjectService implements ProjectService {
	private final LlmNameProposalPlugin plugin;

	public LlmProjectService(LlmNameProposalPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void onProjectOpen(ProjectView project) {
		this.plugin.openProject(project);
		project.addDataInvalidationListener(this.plugin::onDataInvalidated);
	}

	@Override
	public void onProjectClose(ProjectView project) {
		this.plugin.closeProject();
	}
}
