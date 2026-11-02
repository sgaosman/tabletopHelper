package com.tabletophelper.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.rules.SpellSlotCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    private final ObjectMapper objectMapper;

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

                Map<String, Integer> totals = SpellSlotCalculator.calculateSlots(entries);
                JsonNode current = pc.getSpellSlots() != null
                        ? objectMapper.readTree(pc.getSpellSlots())
                        : objectMapper.createObjectNode();

                ObjectNode updated = objectMapper.createObjectNode();
                List<String> diffs = new ArrayList<>();
                for (var slot : totals.entrySet()) {
                    JsonNode old = current.get(slot.getKey());
                    int oldTotal = old != null ? old.path("total").asInt(0) : 0;
                    int used = old != null ? Math.min(old.path("used").asInt(0), slot.getValue()) : 0;
                    ObjectNode node = updated.putObject(slot.getKey());
                    node.put("total", slot.getValue());
                    node.put("used", used);
                    if (oldTotal != slot.getValue()) {
                        diffs.add(label(slot.getKey()) + " " + oldTotal + "→" + slot.getValue());
                    }
                }
                current.fieldNames().forEachRemaining(key -> {
                    if (!totals.containsKey(key)) {
                        diffs.add(label(key) + " " + current.get(key).path("total").asInt(0) + "→0");
                    }
                });

                if (diffs.isEmpty()) continue;
                changes.add(pc.getName() + " (" + describe(entries) + "): " + String.join(", ", diffs));
                if (apply) {
                    pc.setSpellSlots(objectMapper.writeValueAsString(updated));
                    characterRepository.save(pc);
                }
            } catch (Exception e) {
                warnings.add(pc.getName() + ": skipped (" + e.getMessage() + ")");
            }
        }
        return new MaintenanceReport(name(), apply, characters.size(), changes, warnings);
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
