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
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.character.CurrentHitPoints;
import com.tabletophelper.shared.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

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
 * does, and those of a later class level, such as a Fighter 7 Samurai's Wisdom saving throws (XGE p.31), recorded on
 * that level; so does the Expertise a subclass gives, such as a Scout's (XGE p.47), where a level records it, and a
 * cantrip a subclass grants unless known, such as a School of Illusion wizard's Minor Illusion (PHB p.118), where a level
 * records the subclass and the character doesn't know it. Since D231 the cantrips a subclass grants without a fallback,
 * such as a Light Domain cleric's light (PHB p.60), are among those; the spells a subclass teaches at the class levels the
 * character has, such as a Gloom Stalker's (XGE p.42), are learned and recorded on their levels; and the race spells
 * whose casting the race data now gives otherwise, such as a Hexblood's hex once a long rest rather than at will (VRGR
 * p.18), take the data's, with those the sheet lacks added, such as a VGM firbolg's detect magic (VGM p.107). A
 * Circle of the Land druid's terrain is a choice, which the backfill-pending-choices job adds. A class or subclass feature whose text on the sheet differs from the class data's takes the data's, as the
 * features nested inside another, such as the Life Domain's Disciple of Life (PHB p.60), reach the data only from the
 * class loader's version 4; names, sources and order stay, so levelling down still removes them. Dry run by default;
 * {@code --maintenance.apply=true} writes.
 */
@Component
public class RecomputeCharactersJob implements MaintenanceJob {

    // An AC adjustment's reason that suggests the DM entered armour, or the Defense fighting style (M17), by hand before
    // either was modelled
    static final Pattern ARMOUR_REASON = Pattern.compile("armou?r|shield|mail|plate|leather|defen[cs]e|fighting style",
            Pattern.CASE_INSENSITIVE);

    private final CharacterRepository characterRepository;
    private final CharacterService characterService;
    private final FeatEligibility featEligibility;
    private final TransactionOperations perCharacter;

    @Autowired
    public RecomputeCharactersJob(CharacterRepository characterRepository, CharacterService characterService,
                                  FeatEligibility featEligibility, PlatformTransactionManager transactionManager) {
        this(characterRepository, characterService, featEligibility, requiresNew(transactionManager));
    }

    RecomputeCharactersJob(CharacterRepository characterRepository, CharacterService characterService,
                           FeatEligibility featEligibility, TransactionOperations perCharacter) {
        this.characterRepository = characterRepository;
        this.characterService = characterService;
        this.featEligibility = featEligibility;
        this.perCharacter = perCharacter;
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

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

        for (PlayerCharacter listed : characters) {
            // Each character in a transaction of its own, so a failure that dooms a transaction, such as the
            // database's, rolls back only this character's changes; a dry run rolls back every one
            List<String> found = new ArrayList<>();
            List<String> noted = new ArrayList<>();
            try {
                perCharacter.executeWithoutResult(status -> {
                    characterRepository.findById(listed.getId())
                            .ifPresent(pc -> check(pc, apply, found, noted));
                    if (!apply) status.setRollbackOnly();
                });
            } catch (RuntimeException e) {
                warnings.add(describe(listed) + ": skipped, nothing written (" + e.getClass().getSimpleName() + ": "
                        + e.getMessage() + ")");
                continue;
            }
            changes.addAll(found);
            warnings.addAll(noted);
        }
        return new MaintenanceReport(name(), apply, "characters", characters.size(), changes, warnings);
    }

    // Reports the character's changes and warnings, and with apply makes the changes
    private void check(PlayerCharacter pc, boolean apply, List<String> changes, List<String> warnings) {
        String who = describe(pc);
        DerivedStats derived;
        try {
            derived = characterService.derivedStats(pc);
        } catch (RuntimeException e) {
            // Damaged data is a ConflictException, but any failure skips only this character
            warnings.add(who + ": skipped (" + (e instanceof ConflictException ? ""
                    : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
            return;
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
        List<PendingChoiceService.MissingSubclassCantrip> cantrips;
        try {
            cantrips = characterService.missingSubclassCantrips(pc);
        } catch (RuntimeException e) {
            warnings.add(who + ": subclass cantrips skipped (" + (e instanceof ConflictException ? ""
                    : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
            cantrips = List.of();
        }
        cantrips.forEach(missing -> diffs.add(subclassCantrip(missing)));
        List<PendingChoiceService.MissingSubclassSpell> subclassSpells;
        try {
            subclassSpells = characterService.missingSubclassSpells(pc);
        } catch (RuntimeException e) {
            warnings.add(who + ": subclass spells skipped (" + (e instanceof ConflictException ? ""
                    : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
            subclassSpells = List.of();
        }
        subclassSpells.forEach(missing -> diffs.add(subclassSpell(missing)));
        List<CharacterService.RaceSpellChange> raceSpells;
        try {
            raceSpells = characterService.outdatedRaceSpells(pc);
        } catch (RuntimeException e) {
            warnings.add(who + ": race spells skipped (" + (e instanceof ConflictException ? ""
                    : e.getClass().getSimpleName() + ": ") + e.getMessage() + ")");
            raceSpells = List.of();
        }
        raceSpells.forEach(change -> diffs.add("race spell: " + change.spell() + ", " + change.before() + " → "
                + change.after()));
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
        if (diffs.isEmpty()) return;
        changes.add(who + String.join("", diffs.stream().map(diff -> "\n      " + diff).toList()));
        if (apply) {
            if (ability.isPresent()) {
                characterService.storeMissingSpellcastingAbility(pc);
            }
            if (!proficiencies.isEmpty()) {
                characterService.storeMissingSubclassProficiencies(pc);
            }
            if (!cantrips.isEmpty()) {
                characterService.storeMissingSubclassCantrips(pc);
            }
            if (!subclassSpells.isEmpty()) {
                characterService.storeMissingSubclassSpells(pc);
            }
            if (!raceSpells.isEmpty()) {
                characterService.storeRaceSpells(pc);
            }
            if (!texts.isEmpty()) {
                characterService.storeFeatureTexts(pc);
            }
            Integer current = pc.getHpCurrent();
            Integer oldMaximum = pc.getHpMax();
            characterService.recalculate(pc);
            // A repaired maximum only caps current hit points when it falls: a stored maximum the rules disagree with
            // was never the character's to lose hit points from (D222)
            if (current != null) {
                pc.setHpCurrent(Math.min(Exhaustion.hitPointMaximum(pc.getHpMax(),
                                Exhaustion.clamp(pc.getExhaustionLevel())),
                        CurrentHitPoints.afterRepairedMaximum(current, oldMaximum, pc.getHpMax(), pc.isDead())));
            }
            characterRepository.save(pc);
        }
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

    // Such as "subclass cantrip: + Minor Illusion (School of Illusion, recorded on Wizard 2)"
    private static String subclassCantrip(PendingChoiceService.MissingSubclassCantrip missing) {
        return "subclass cantrip: + " + missing.cantrip() + " (" + missing.subclassName() + ", recorded on "
                + missing.className() + " " + missing.classLevel() + ")";
    }

    // Such as "subclass spell: + Disguise Self (Gloom Stalker, recorded on Ranger 3)"
    private static String subclassSpell(PendingChoiceService.MissingSubclassSpell missing) {
        return "subclass spell: + " + missing.spell() + " (" + missing.subclassName() + ", recorded on "
                + missing.className() + " " + missing.classLevel() + ")";
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
        // The rule the real run writes for a repaired maximum (D222), so the dry run shows what it does, a dead
        // character's 0 too
        int current = pc.getHpCurrent() != null ? pc.getHpCurrent() : derived.hpMax();
        int newCurrent = CurrentHitPoints.afterRepairedMaximum(current, pc.getHpMax(), derived.hpMax(), pc.isDead());
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
