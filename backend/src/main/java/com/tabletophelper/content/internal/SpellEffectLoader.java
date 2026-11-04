package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes each spell's effect template from spell-effect-definitions.json, matching a definition to the spell of its
 * name whatever the case. A later definition of the same spell wins, and a spell the file doesn't name keeps the
 * template it has.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpellEffectLoader implements ContentLoader {

    private static final String FILE = "data/spell-effects/spell-effect-definitions.json";

    private final SpellRepository spellRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "spell-effects";
    }

    @Override
    public List<String> files() {
        return List.of(FILE);
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<String> dependsOn() {
        return List.of("spells");
    }

    @Override
    public void load(LoadContext context) throws Exception {
        JsonNode definitions;
        try (InputStream is = new ClassPathResource(FILE).getInputStream()) {
            definitions = objectMapper.readTree(is);
        }
        if (!definitions.isArray()) {
            throw new IllegalStateException(FILE + " is not an array");
        }

        Map<String, Spell> spellsByName = new HashMap<>();
        for (Spell spell : spellRepository.findAll()) {
            Spell other = spellsByName.putIfAbsent(spell.getName().toLowerCase(), spell);
            if (other != null) {
                throw new IllegalStateException("Two spells are named " + spell.getName()
                        + ", so an effect template can't tell them apart");
            }
        }

        Map<Spell, String> templates = new LinkedHashMap<>();
        int unmatched = 0;
        for (JsonNode definition : definitions) {
            String spellName = definition.path("spellName").asText(null);
            if (spellName == null) continue;
            Spell spell = spellsByName.get(spellName.toLowerCase());
            if (spell == null) {
                unmatched++;
                log.debug("No spell entity found for effect template: {}", spellName);
                continue;
            }
            templates.put(spell, objectMapper.writeValueAsString(definition));
        }

        int changed = 0;
        for (var entry : templates.entrySet()) {
            if (!context.sameJson(entry.getKey().getEffectTemplate(), entry.getValue())) {
                entry.getKey().setEffectTemplate(entry.getValue());
                changed++;
            }
        }
        if (unmatched > 0) {
            log.info("spell-effects: {} definitions name no spell", unmatched);
        }
        context.count("spells.effect_template", changed, templates.size() - changed, 0);
    }
}
