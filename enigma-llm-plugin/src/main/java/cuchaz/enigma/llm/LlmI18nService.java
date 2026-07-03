package cuchaz.enigma.llm;

import java.io.InputStream;

import cuchaz.enigma.api.service.I18nService;

public class LlmI18nService implements I18nService {
	@Override
	public InputStream getTranslationResource(String language) {
		return LlmI18nService.class.getResourceAsStream("/llm_name_proposal/lang/" + language + ".json");
	}
}
