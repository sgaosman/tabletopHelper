package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
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
 * template it has. Each definition is stored whole, as the {@link SpellTemplate} the column maps to (D168), and one
 * whose parts don't fit those records fails the load naming the spell (D155). So does a definition with a repeat
 * effect that doesn't say what using it again costs, ACTION, BONUS_ACTION or NONE (D174); an unknown cost is a part
 * that doesn't fit.
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

        Map<Spell, JsonNode> templates = new LinkedHashMap<>();
        int unmatched = 0;
        for (JsonNode definition : definitions) {
            String spellName = definition.path("spellName").asString(null);
            if (spellName == null) continue;
            Spell spell = spellsByName.get(spellName.toLowerCase());
            if (spell == null) {
                unmatched++;
                log.debug("No spell entity found for effect template: {}", spellName);
                continue;
            }
            SpellTemplate template = SpellTemplate.of(definition);
            if (template.unreadable() != null) {
                throw new IllegalStateException("The effect template of " + spell.getName()
                        + " doesn't fit the template records: " + template.unreadable());
            }
            if (template.repeatEffect() != null && template.repeatActionType() == null) {
                throw new IllegalStateException("The effect template of " + spell.getName()
                        + " has a repeat effect but no repeatActionType (ACTION, BONUS_ACTION or NONE)");
            }
            templates.put(spell, definition);
        }

        int changed = 0;
        for (var entry : templates.entrySet()) {
            SpellTemplate stored = entry.getKey().getEffectTemplate();
            if (stored == null || !entry.getValue().equals(stored.json())) {
                entry.getKey().setEffectTemplate(SpellTemplate.of(entry.getValue()));
                changed++;
            }
        }
        if (unmatched > 0) {
            log.info("spell-effects: {} definitions name no spell", unmatched);
        }
        context.count("spells.effect_template", changed, templates.size() - changed, 0);
    }
}
