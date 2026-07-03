package cuchaz.enigma.llm;

import java.util.Collection;
import java.util.Optional;

import cuchaz.enigma.api.DataInvalidationEvent;
import cuchaz.enigma.api.EnigmaPlugin;
import cuchaz.enigma.api.EnigmaPluginContext;
import cuchaz.enigma.api.service.GuiService;
import cuchaz.enigma.api.service.I18nService;
import cuchaz.enigma.api.service.JarIndexerService;
import cuchaz.enigma.api.service.NameProposalService;
import cuchaz.enigma.api.service.ProjectService;
import cuchaz.enigma.api.view.ProjectView;

public class LlmNameProposalPlugin implements EnigmaPlugin {
	static final String ID_PREFIX = "llm_name_proposal:";

	private final LlmSuggestionCache suggestions = new LlmSuggestionCache();
	private volatile LlmProjectIndex index = LlmProjectIndex.empty();
	private volatile ProjectView project;
	private volatile boolean projectLifecycleObserved;
	private int internalRefreshInvalidations;

	@Override
	public void init(EnigmaPluginContext ctx) {
		ctx.registerService(ID_PREFIX + "jar_indexer", JarIndexerService.TYPE, () -> new LlmJarIndexerService(this));
		ctx.registerService(ID_PREFIX + "name_proposal", NameProposalService.TYPE, () -> new LlmCachedNameProposalService(this));
		ctx.registerService(ID_PREFIX + "gui", GuiService.TYPE, () -> new LlmGuiService(this));
		ctx.registerService(ID_PREFIX + "project", ProjectService.TYPE, () -> new LlmProjectService(this));
		ctx.registerService(ID_PREFIX + "i18n", I18nService.TYPE, LlmI18nService::new);
	}

	void setIndex(LlmProjectIndex index) {
		this.index = index;
		this.suggestions.clear();
	}

	LlmProjectIndex getIndex() {
		return this.index;
	}

	synchronized void openProject(ProjectView project) {
		this.project = project;
		this.projectLifecycleObserved = true;
		this.internalRefreshInvalidations = 0;
		this.suggestions.clear();
	}

	synchronized void closeProject() {
		this.project = null;
		this.index = LlmProjectIndex.empty();
		this.internalRefreshInvalidations = 0;
		this.suggestions.clear();
		this.projectLifecycleObserved = true;
	}

	synchronized void markInternalRefreshInvalidation() {
		this.internalRefreshInvalidations++;
	}

	synchronized void onDataInvalidated(DataInvalidationEvent event) {
		if (event.getType() != DataInvalidationEvent.InvalidationType.MAPPINGS && event.getType() != DataInvalidationEvent.InvalidationType.JAVADOC && event.getType() != DataInvalidationEvent.InvalidationType.DECOMPILE) {
			return;
		}

		if (this.internalRefreshInvalidations > 0) {
			this.internalRefreshInvalidations--;
			return;
		}

		Collection<String> classes = event.getClasses();

		if (classes == null) {
			this.suggestions.clear();
		} else {
			this.suggestions.removeIf(key -> classes.stream().anyMatch(clazz -> key.owner().equals(clazz) || key.owner().startsWith(clazz + "$")));
		}
	}

	synchronized boolean isProjectCurrent(ProjectView project) {
		return !this.projectLifecycleObserved || this.project == project;
	}

	synchronized Optional<ProjectView> getProject() {
		return Optional.ofNullable(this.project);
	}

	LlmSuggestionCache getSuggestions() {
		return this.suggestions;
	}
}
