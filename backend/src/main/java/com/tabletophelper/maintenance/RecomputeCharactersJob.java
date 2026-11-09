package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.DerivedStats;
import com.tabletophelper.character.DmAdjustments;
import com.tabletophelper.character.FeatEligibility;
import com.tabletophelper.character.PendingChoiceService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.DmAdjustment;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Works every character's Derived Stats out again with the R6 rules (D167): the hit point maximum from the recorded
 * Hit Die Results, the proficiency bonus, initiative, speed, armour class (M13.5), spell slot and Hit Dice maximums, the
 * main spell DC and attack bonus, and the resource pools, innate uses included (D165). Each character that would change is reported
 * with each stat before and after, and what the sheet already has beyond the rules, such as more cantrips than its
 * class knows, is a warning that changes nothing, as is each feat the character has whose prerequisite it wouldn't
 * meet now, which it keeps (D159). A character with no stored spellcasting ability whose classes cast gets its first
 * casting class's, recorded on the level that gave the class its spellcasting when the level history has it, as
 * levelling into a first casting class now does (D159). A character whose sheet can't be worked out is skipped with
 * a warning. An active AC adjustment whose reason names armour, a shield or the Defense fighting style is a warning
 * too: the armour class now counts the armour worn and Defense (M17), so the adjustment may count it twice. A character
 * whose subclass grants bonus proficiencies its sheet lacks, such as a Life Domain cleric's heavy armour (PHB p.60),
 * gets them, recorded on the level that records the subclass when the level history has one, as choosing a subclass now
 * does. A class or subclass feature whose text on the sheet differs from the class data's takes the data's, as the
 * features nested inside another, such as the Life Domain's Disciple of Life (PHB p.60), reach the data only from the
 * class loader's version 4; names, sources and order stay, so levelling down still removes them. Dry run by default;
 * {@code --maintenance.apply=true} writes.
 */
@Component
@RequiredArgsConstructor
public class RecomputeCharactersJob implements MaintenanceJob {

    // An AC adjustment's reason that suggests the DM entered armour, or the Defense fighting style (M17), by hand before
    // either was modelled
    static final Pattern ARMOUR_REASON = Pattern.compile("armou?r|shield|mail|plate|leather|defen[cs]e|fighting style",
            Pattern.CASE_INSENSITIVE);

    private final CharacterRepository characterRepository;
    private final CharacterService characterService;
    private final FeatEligibility featEligibility;

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
            featEligibility.heldFeatsUnmet(pc).forEach(feat -> warnings.add(who + ": has the feat " + feat
                    + ", which it wouldn't qualify for now; kept"));
            // The reason is the DM's own text, so the report names the adjustment by ID, as its log does (D158)
            armourAdjustments(pc).forEach(adjustment -> warnings.add(who + ": has an AC adjustment of "
                    + signed(adjustment.amount()) + " (" + adjustment.id() + ") whose reason names armour or the Defense "
                    + "style, which the armour class now counts, so it may count twice; kept"));

            List<String> diffs = new ArrayList<>();
            Optional<CharacterService.MissingSpellcastingAbility> ability;
            try {
                ability = characterService.missingSpellcastingAbility(pc);
            } catch (RuntimeException e) {
                warnings.add(who + ": spellcasting ability skipped (" + (e instanceof ConflictException ? ""
                        : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
                ability = Optional.empty();
            }
            ability.ifPresent(found -> diffs.add(spellcastingAbility(found)));
            List<PendingChoiceService.MissingSubclassProficiencies> proficiencies;
            try {
                proficiencies = characterService.missingSubclassProficiencies(pc);
            } catch (RuntimeException e) {
                warnings.add(who + ": subclass proficiencies skipped (" + (e instanceof ConflictException ? ""
                        : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
                proficiencies = List.of();
            }
            proficiencies.forEach(missing -> diffs.add(subclassProficiencies(missing)));
            List<CharacterService.OutdatedFeatureText> texts;
            try {
                texts = characterService.outdatedFeatureTexts(pc);
            } catch (RuntimeException e) {
                warnings.add(who + ": feature texts skipped (" + (e instanceof ConflictException ? ""
                        : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
                texts = List.of();
            }
            texts.forEach(text -> diffs.add(featureText(text)));
            diffs.addAll(differences(pc, derived));
            if (diffs.isEmpty()) continue;
            changes.add(who + String.join("", diffs.stream().map(diff -> "\n      " + diff).toList()));
            if (apply) {
                if (ability.isPresent()) {
                    characterService.storeMissingSpellcastingAbility(pc);
                }
                if (!proficiencies.isEmpty()) {
                    characterService.storeMissingSubclassProficiencies(pc);
                }
                if (!texts.isEmpty()) {
                    characterService.storeFeatureTexts(pc);
                }
                characterService.recalculate(pc);
                characterRepository.save(pc);
            }
        }
        return new MaintenanceReport(name(), apply, "characters", characters.size(), changes, warnings);
    }

    private static String spellcastingAbility(CharacterService.MissingSpellcastingAbility found) {
        String level = found.className() + " " + found.classLevel();
        return "spellcasting ability: none → " + found.ability() + " (" + found.className() + "'s, "
                + (found.recorded() ? "recorded on " + level
                        : "not recorded: the level history has no " + level + ", so levelling down won't clear it")
                + ")";
    }

    // Such as "subclass proficiencies: + armour heavy (Life Domain, recorded on Cleric 1)"
    private static String subclassProficiencies(PendingChoiceService.MissingSubclassProficiencies missing) {
        return "subclass proficiencies: + " + missing.description() + " (" + missing.subclassName() + ", "
                + (missing.recorded() ? "recorded on " + missing.className() + " " + missing.classLevel()
                        : "not recorded: no level records the subclass, so levelling down won't remove them")
                + ")";
    }

    // Such as "feature text: Life Domain (Life Domain), 1203 → 1645 characters"
    private static String featureText(CharacterService.OutdatedFeatureText text) {
        return "feature text: " + text.name() + " (" + text.source() + "), " + text.lengthNow() + " → "
                + text.lengthData() + " characters";
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
        compare(diffs, "armour class", String.valueOf(pc.getArmourClass()), String.valueOf(derived.armourClass()));
        compare(diffs, "spell save DC", String.valueOf(pc.getSpellSaveDc()), String.valueOf(derived.spellSaveDc()));
        compare(diffs, "spell attack bonus", signed(pc.getSpellAttackBonus()), signed(derived.spellAttackBonus()));
        compareEach(diffs, "slots", pc.getSpellSlots(), derived.spellSlots(), RecomputeCharactersJob::slot);
        compareEach(diffs, "Hit Dice", pc.getHitDice(), derived.hitDice(), RecomputeCharactersJob::hitDice);
        comparePools(diffs, pc.getResourcePools(), derived.resourcePools());
        return diffs;
    }

    // The active AC adjustments whose reason names armour or a shield
    private static List<DmAdjustment> armourAdjustments(PlayerCharacter pc) {
        return DmAdjustments.active(pc).stream()
                .filter(adjustment -> adjustment.stat().filter(DmAdjustment.Stat.ARMOUR_CLASS::equals).isPresent())
                .filter(adjustment -> adjustment.reason() != null && ARMOUR_REASON.matcher(adjustment.reason()).find())
                .toList();
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
