package com.tabletophelper.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Re-points {@code featId} in level history feat records at the current feat with the same name.
 * Feat IDs used to change on every restart, so stored IDs are stale (D169).
 */
@Component
@RequiredArgsConstructor
public class RelinkFeatIdsJob implements MaintenanceJob {

    private final CharacterRepository characterRepository;
    private final FeatRepository featRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "relink-feat-ids";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        Map<String, List<Feat>> featsByName = featRepository.findAllByOrderByNameAsc().stream()
                .collect(Collectors.groupingBy(Feat::getName));
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<PlayerCharacter> characters = characterRepository.findAll();

        for (PlayerCharacter pc : characters) {
            if (pc.getLevelHistory() == null) continue;
            try {
                JsonNode history = objectMapper.readTree(pc.getLevelHistory());
                if (!history.isArray()) continue;

                boolean changed = false;
                for (JsonNode entry : history) {
                    JsonNode asi = entry.path("choices").path("asi");
                    if (!asi.isObject() || !"feat".equals(asi.path("type").asText())) continue;

                    String where = pc.getName() + ", level " + entry.path("characterLevel").asInt();
                    String featName = asi.path("featName").asText(null);
                    List<Feat> matches = featName != null ? featsByName.getOrDefault(featName, List.of()) : List.of();
                    if (matches.size() != 1) {
                        warnings.add(where + ": " + (matches.isEmpty() ? "no feat named '" + featName + "'"
                                : matches.size() + " feats named '" + featName + "'") + "; left unchanged");
                        continue;
                    }

                    String currentId = matches.get(0).getId().toString();
                    if (!currentId.equals(asi.path("featId").asText(null))) {
                        ((ObjectNode) asi).put("featId", currentId);
                        changes.add(where + ": " + featName + " re-linked");
                        changed = true;
                    }
                }

                if (changed && apply) {
                    pc.setLevelHistory(objectMapper.writeValueAsString(history));
                    characterRepository.save(pc);
                }
            } catch (Exception e) {
                warnings.add(pc.getName() + ": skipped (" + e.getMessage() + ")");
            }
        }
        return new MaintenanceReport(name(), apply, characters.size(), changes, warnings);
    }
}
