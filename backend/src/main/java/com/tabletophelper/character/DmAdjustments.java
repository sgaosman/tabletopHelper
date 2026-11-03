package com.tabletophelper.character;

import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.DamageRules.Defence;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A character's sheet with its DM Adjustments on top (D158): the ability scores, armour class and damage defences it
 * has now, and every feat it has taken. Only the active adjustments count. CharacterStats reads the ability scores and
 * feats from here, so the adjustments apply before anything worked out from them, and combat reads a participant's
 * ability scores and defences from here live, so a DEFENCE adjustment applies at once (D161). Nothing here needs more
 * than the sheet.
 */
public final class DmAdjustments {

    // An ability score with adjustments may pass its Ability Score Maximum, but stays within 1 to 30 (PHB p.173)
    static final int LOWEST_SCORE = 1;
    static final int HIGHEST_SCORE = 30;

    private DmAdjustments() {
    }

    /** The active adjustments, oldest first. */
    public static List<DmAdjustment> active(PlayerCharacter character) {
        List<DmAdjustment> adjustments = character.getDmAdjustments();
        return adjustments != null ? adjustments.stream().filter(DmAdjustment::active).toList() : List.of();
    }

    /** What the active STAT adjustments to the stat add up to; 0 for none. */
    public static int total(PlayerCharacter character, DmAdjustment.Stat stat) {
        int total = 0;
        for (DmAdjustment adjustment : active(character)) {
            if (adjustment.amount() != null && adjustment.stat().filter(stat::equals).isPresent()) {
                total += adjustment.amount();
            }
        }
        return total;
    }

    /**
     * An ability score with the active adjustments to it, which may take it past its Ability Score Maximum but never
     * below 1 or above 30. The sheet's own score, which Ability Score Improvements and feats raise only as far as the
     * maximum (D167), has none of them.
     */
    public static int abilityScore(PlayerCharacter character, Ability ability) {
        int adjusted = CharacterUpdates.score(character, ability) + total(character, DmAdjustment.Stat.of(ability));
        return Math.max(LOWEST_SCORE, Math.min(HIGHEST_SCORE, adjusted));
    }

    /**
     * The armour class: the one the character was created with, 10 + its DEX modifier then, plus the active AC
     * adjustments. Armour isn't modelled, so only the DM changes it (D158).
     */
    public static int armourClass(PlayerCharacter character) {
        int created = character.getArmourClass() != null ? character.getArmourClass() : 10;
        return created + total(character, DmAdjustment.Stat.ARMOUR_CLASS);
    }

    /**
     * The damage types the character has the defence against, each once in any case: for resistance and immunity,
     * those its sheet lists from its race, features and feats, then those the active DEFENCE adjustments add; for
     * vulnerability only the adjustments', since no 2014 race or feat gives one (D161).
     */
    public static List<String> damageTypes(PlayerCharacter character, Defence defence) {
        List<String> types = new ArrayList<>();
        List<String> listed = switch (defence) {
            case RESISTANCE -> character.getDamageResistances();
            case IMMUNITY -> character.getDamageImmunities();
            case VULNERABILITY -> null;
        };
        if (listed != null) {
            listed.forEach(type -> addOnce(types, type));
        }
        for (DmAdjustment adjustment : active(character)) {
            if (adjustment.kind() == DmAdjustment.Kind.DEFENCE && adjustment.defence() == defence) {
                addOnce(types, adjustment.target());
            }
        }
        return types;
    }

    /**
     * The character's defences against a damage type, from its sheet and its active DEFENCE adjustments, so one it has
     * from both counts once; none for untyped damage.
     */
    public static Set<Defence> defences(PlayerCharacter character, String damageType) {
        Set<Defence> defences = EnumSet.noneOf(Defence.class);
        if (damageType == null || damageType.isBlank()) {
            return defences;
        }
        for (Defence defence : Defence.values()) {
            if (contains(damageTypes(character, defence), damageType)) {
                defences.add(defence);
            }
        }
        return defences;
    }

    /**
     * Every feat the character has taken whose effects were applied, with what applying it recorded: those its level
     * history records in place of an Ability Score Improvement, oldest first, then those the DM granted. A feat's
     * speed, initiative and limited uses are worked out from these (D158, D167).
     */
    public static List<AppliedFeatEffects> featsTaken(PlayerCharacter character) {
        List<AppliedFeatEffects> feats = new ArrayList<>();
        for (LevelHistoryEntry entry : CharacterUpdates.orEmpty(character.getLevelHistory())) {
            AbilityScoreImprovement asi = entry.choices() != null ? entry.choices().asi() : null;
            if (asi != null && asi.appliedEffects() != null) {
                feats.add(asi.appliedEffects());
            }
        }
        for (DmAdjustment adjustment : active(character)) {
            if (adjustment.kind() == DmAdjustment.Kind.FEAT && adjustment.appliedEffects() != null) {
                feats.add(adjustment.appliedEffects());
            }
        }
        return feats;
    }

    // Adds a damage type, trimmed, unless it is blank or the list has it in any case
    private static void addOnce(List<String> types, String type) {
        if (type != null && !type.isBlank() && !contains(types, type)) {
            types.add(type.trim());
        }
    }

    private static boolean contains(List<String> types, String type) {
        String wanted = type.trim();
        return types.stream().anyMatch(listed -> listed.trim().equalsIgnoreCase(wanted));
    }
}
