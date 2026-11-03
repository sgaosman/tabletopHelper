package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
                List<LevelHistoryEntry> history = new ArrayList<>(pc.getLevelHistory());

                boolean changed = false;
                for (int i = 0; i < history.size(); i++) {
                    LevelHistoryEntry entry = history.get(i);
                    AbilityScoreImprovement asi = entry.choices() != null ? entry.choices().asi() : null;
                    if (asi == null || !"feat".equals(asi.type())) continue;

                    String where = pc.getName() + ", level " + entry.characterLevel();
                    String featName = asi.featName();
                    List<Feat> matches = featName != null ? featsByName.getOrDefault(featName, List.of()) : List.of();
                    if (matches.size() != 1) {
                        warnings.add(where + ": " + (matches.isEmpty() ? "no feat named '" + featName + "'"
                                : matches.size() + " feats named '" + featName + "'") + "; left unchanged");
                        continue;
                    }

                    UUID currentId = matches.get(0).getId();
                    if (!currentId.equals(asi.featId())) {
                        history.set(i, entry.withChoices(entry.choices().withAsi(asi.withFeatId(currentId))));
                        changes.add(where + ": " + featName + " re-linked");
                        changed = true;
                    }
                }

                if (changed && apply) {
                    pc.setLevelHistory(history);
                    characterRepository.save(pc);
                }
            } catch (Exception e) {
                warnings.add(pc.getName() + ": skipped (" + e.getMessage() + ")");
            }
        }
        return new MaintenanceReport(name(), apply, "characters", characters.size(), changes, warnings);
    }
}
