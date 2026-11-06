package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.DerivedStats;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.rules.character.CurrentHitPoints;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Works every character's Derived Stats out again with the R6 rules (D167): the hit point maximum from the recorded
 * Hit Die Results, the proficiency bonus, initiative, speed, spell slot and Hit Dice maximums, the main spell DC and
 * attack bonus, and the resource pools, innate uses included (D165). Each character that would change is reported
 * with each stat before and after, and what the sheet already has beyond the rules, such as more cantrips than its
 * class knows, is a warning that changes nothing. A character whose sheet can't be worked out is skipped with a
 * warning. Dry run by default; {@code --maintenance.apply=true} writes.
 */
@Component
@RequiredArgsConstructor
public class RecomputeCharactersJob implements MaintenanceJob {

    private final CharacterRepository characterRepository;
    private final CharacterService characterService;

    @Override
    public String name() {
        return "recompute-characters";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<PlayerCharacter> characters = new ArrayList<>(characterRepository.findAll());
        characters.sort(Comparator.comparing((PlayerCharacter pc) -> String.valueOf(pc.getName()),
                String.CASE_INSENSITIVE_ORDER).thenComparing(pc -> String.valueOf(pc.getId())));

        for (PlayerCharacter pc : characters) {
            String who = describe(pc);
            DerivedStats derived;
            try {
                derived = characterService.derivedStats(pc);
            } catch (RuntimeException e) {
                // Damaged data is a ConflictException, but any failure skips only this character
                warnings.add(who + ": skipped (" + (e instanceof ConflictException ? ""
                        : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
                continue;
            }
            derived.warnings().forEach(warning -> warnings.add(who + ": " + warning + "; left unchanged"));

            List<String> diffs = differences(pc, derived);
            if (diffs.isEmpty()) continue;
            changes.add(who + String.join("", diffs.stream().map(diff -> "\n      " + diff).toList()));
            if (apply) {
                characterService.recalculate(pc);
                characterRepository.save(pc);
            }
        }
        return new MaintenanceReport(name(), apply, "characters", characters.size(), changes, warnings);
    }

    private static String describe(PlayerCharacter pc) {
        return pc.getName() + " (" + pc.getId() + ", level " + pc.getLevel() + " " + pc.getCharacterClass()
                + (Boolean.FALSE.equals(pc.getIsActive()) ? ", deleted" : "") + ")";
    }

    // Each stored stat the derivation would change, before and after
    private static List<String> differences(PlayerCharacter pc, DerivedStats derived) {
        List<String> diffs = new ArrayList<>();
        // The rule CharacterStats.apply writes, so the dry run shows what the real run does, a dead character's 0 too
        int current = pc.getHpCurrent() != null ? pc.getHpCurrent() : derived.hpMax();
        int newCurrent = CurrentHitPoints.afterMaximum(current, pc.getHpMax(), derived.hpMax(), pc.isDead());
        if (!Objects.equals(pc.getHpMax(), derived.hpMax()) || current != newCurrent) {
            diffs.add("hit points: " + current + "/" + pc.getHpMax() + " → " + newCurrent + "/" + derived.hpMax());
        }
        compare(diffs, "proficiency bonus", signed(pc.getProficiencyBonus()), signed(derived.proficiencyBonus()));
        compare(diffs, "initiative", signed(pc.getInitiativeBonus()), signed(derived.initiativeBonus()));
        compare(diffs, "speed", pc.getSpeed() + " ft", derived.speed() + " ft");
        compare(diffs, "spell save DC", String.valueOf(pc.getSpellSaveDc()), String.valueOf(derived.spellSaveDc()));
        compare(diffs, "spell attack bonus", signed(pc.getSpellAttackBonus()), signed(derived.spellAttackBonus()));
        compareEach(diffs, "slots", pc.getSpellSlots(), derived.spellSlots(), RecomputeCharactersJob::slot);
        compareEach(diffs, "Hit Dice", pc.getHitDice(), derived.hitDice(), RecomputeCharactersJob::hitDice);
        comparePools(diffs, pc.getResourcePools(), derived.resourcePools());
        return diffs;
    }

    private static void compare(List<String> diffs, String what, String before, String after) {
        if (!before.equals(after)) {
            diffs.add(what + ": " + before + " → " + after);
        }
    }

    // A map's entries before and after, by key, those added and removed included
    private static <T> void compareEach(List<String> diffs, String what, Map<String, T> before, Map<String, T> after,
                                        Function<T, String> label) {
        Map<String, T> old = before != null ? before : Map.of();
        Map<String, T> now = after != null ? after : Map.of();
        Set<String> keys = new LinkedHashSet<>(old.keySet());
        keys.addAll(now.keySet());
        for (String key : keys) {
            String was = old.containsKey(key) ? label.apply(old.get(key)) : "none";
            String is = now.containsKey(key) ? label.apply(now.get(key)) : "none";
            if (!was.equals(is)) {
                diffs.add(what + " " + key + ": " + was + " → " + is);
            }
        }
    }

    // Pools by ID: one added, one removed, or one whose maximum, uses, rest, die or limit change (D169)
    private static void comparePools(List<String> diffs, List<ResourcePoolEntry> before, List<ResourcePoolEntry> after) {
        Map<String, String> old = pools(before);
        Map<String, String> now = pools(after);
        Set<String> ids = new LinkedHashSet<>(old.keySet());
        ids.addAll(now.keySet());
        for (String id : ids) {
            String was = old.getOrDefault(id, "none");
            String is = now.getOrDefault(id, "none");
            if (!was.equals(is)) {
                diffs.add("pool " + id + ": " + was + " → " + is);
            }
        }
    }

    private static Map<String, String> pools(List<ResourcePoolEntry> pools) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (ResourcePoolEntry pool : pools != null ? pools : List.<ResourcePoolEntry>of()) {
            Object die = pool.metadata() instanceof Map<?, ?> metadata ? metadata.get("die") : null;
            byId.putIfAbsent(pool.poolId(), pool.currentUses() + "/" + pool.maxUses()
                    + (pool.unlimited() ? " unlimited" : "") + " " + pool.resetOn() + (die != null ? " " + die : ""));
        }
        return byId;
    }

    private static String slot(SpellSlot slot) {
        return slot != null ? (slot.max() - slot.used()) + "/" + slot.max() : "none";
    }

    private static String hitDice(HitDicePool pool) {
        return pool != null ? pool.remaining() + "/" + pool.total() + "d" + pool.dieSize() : "none";
    }

    private static String signed(Integer value) {
        if (value == null) return "none";
        return value < 0 ? String.valueOf(value) : "+" + value;
    }
}
