package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.rules.SpellSlotCalculator;
import com.tabletophelper.rules.character.ClassTables;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recomputes stored spell slot maximums with the corrected calculator (D153). Used counts are
 * kept, capped at the new maximum. Characters without class entries are reported, not changed.
 */
@Component
@RequiredArgsConstructor
public class RecomputeSpellSlotsJob implements MaintenanceJob {

    private final CharacterRepository characterRepository;
    private final CharacterService characterService;
    private final ClassTables classTables;

    @Override
    public String name() {
        return "recompute-spell-slots";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<PlayerCharacter> characters = characterRepository.findAll();

        for (PlayerCharacter pc : characters) {
            try {
                Map<String, SpellSlotCalculator.ClassEntry> entries = characterService.spellSlotEntries(pc);
                if (entries.isEmpty()) {
                    if (pc.getSpellSlots() != null) {
                        warnings.add(pc.getName() + ": has spell slots but no casting class entries; left unchanged");
                    }
                    continue;
                }

                Map<String, Integer> maximums = SpellSlotCalculator.calculateSlots(classTables, entries);
                Map<String, SpellSlot> current = pc.getSpellSlots() != null ? pc.getSpellSlots() : Map.of();

                Map<String, SpellSlot> updated = new LinkedHashMap<>();
                List<String> diffs = new ArrayList<>();
                for (var slot : maximums.entrySet()) {
                    SpellSlot old = current.get(slot.getKey());
                    int oldMax = old != null ? old.max() : 0;
                    int used = old != null ? Math.min(old.used(), slot.getValue()) : 0;
                    updated.put(slot.getKey(), new SpellSlot(slot.getValue(), used));
                    if (oldMax != slot.getValue()) {
                        diffs.add(label(slot.getKey()) + " " + oldMax + "→" + slot.getValue());
                    }
                }
                current.forEach((key, old) -> {
                    if (!maximums.containsKey(key)) {
                        diffs.add(label(key) + " " + (old != null ? old.max() : 0) + "→0");
                    }
                });

                if (diffs.isEmpty()) continue;
                changes.add(pc.getName() + " (" + describe(entries) + "): " + String.join(", ", diffs));
                if (apply) {
                    pc.setSpellSlots(updated);
                    characterRepository.save(pc);
                }
            } catch (Exception e) {
                warnings.add(pc.getName() + ": skipped (" + e.getMessage() + ")");
            }
        }
        return new MaintenanceReport(name(), apply, "characters", characters.size(), changes, warnings);
    }

    private static String label(String slotKey) {
        return slotKey.startsWith("pact_") ? "pact level " + slotKey.substring(5) : "level " + slotKey;
    }

    private static String describe(Map<String, SpellSlotCalculator.ClassEntry> entries) {
        return entries.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue().level())
                .collect(Collectors.joining(" / "));
    }
}
