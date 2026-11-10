package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Blink and Mirror Image (D196), the low-level spells that keep their caster out of harm's way, kept as conditions on
 * the caster so undo, Dispel Magic and the condition list cover them.
 * <p>
 * <b>Blink</b> (PHB p.219): its caster has {@code blinking} for the minute, and at the end of each of its turns rolls
 * a d20; on 11 or higher it is {@code ethereal} until the start of its next turn, when nothing on the material plane
 * can target it ({@link CombatAccess#requireNotDead}).
 * <p>
 * <b>Mirror Image</b> (PHB p.260): its caster has {@code mirror image}, whose count is its duplicates, three at the
 * cast. An attack roll against the caster rolls a d20 first, 6 or higher with three duplicates, 8 with two, 11 with one,
 * to target a duplicate instead; the duplicate's AC is 10 + the caster's DEX modifier, a hit destroys it, and the spell
 * ends with the last. The attacker that can't see, or relies on blindsight or truesight, is the DM's to leave out.
 * <p>
 * <b>Sanctuary</b> (PHB p.272): the warded creature has {@code sanctuary}, keeping the caster's save DC. A creature
 * making an attack roll against it, or casting a one-target spell that harms it, makes a WIS save first; on a failure
 * the attack or spell does nothing to it. The ward ends when the warded creature attacks or casts a spell.
 */
@Component
@RequiredArgsConstructor
public class DefensiveSpells {

    public static final String BLINKING = "blinking";
    public static final String ETHEREAL = "ethereal";
    public static final String MIRROR_IMAGE = "mirror image";
    public static final String SANCTUARY = "sanctuary";
    private static final int DUPLICATES = 3;
    private static final int DEFAULT_SANCTUARY_DC = 13;

    private final Resolver resolver;
    private final CreatureStats stats;
    private final CombatLogWriter combatLog;

    /** The d20 an attack against the caster needs to target a duplicate instead (PHB p.260). */
    public static int redirectThreshold(int duplicates) {
        return switch (duplicates) {
            case 3 -> 6;
            case 2 -> 8;
            default -> 11;
        };
    }

    /**
     * Whether the attack hits the duplicate it was turned to: a critical hit always does, a natural 1 never, and
     * otherwise the attack's total against the duplicate's AC.
     */
    public static boolean hitsDuplicate(Outcome outcome, Integer d20, Integer total, int duplicateAc) {
        if (outcome == Outcome.CRITICAL) {
            return true;
        }
        if (d20 != null && d20 == 1 || total == null) {
            return false;
        }
        return total >= duplicateAc;
    }

    /** Whether the d20 at the end of a blinking creature's turn sends it to the Ethereal Plane (PHB p.219). */
    public static boolean vanishes(int roll) {
        return roll >= 11;
    }

    /** Whether the creature is on the Ethereal Plane through Blink (PHB p.219). */
    public static boolean ethereal(EncounterParticipant participant) {
        return find(participant, ETHEREAL) != null;
    }

    /**
     * Mirror Image's duplicates start at three as it is cast (PHB p.260); Sanctuary's ward keeps its caster's save DC
     * (PHB p.272), counted on the ward's condition.
     */
    public void afterCast(EncounterParticipant caster, String spellName, List<EncounterParticipant> targets,
                          int saveDc) {
        if ("Mirror Image".equalsIgnoreCase(spellName)) {
            replace(caster, MIRROR_IMAGE, entry -> entry.withBonus(DUPLICATES));
        } else if ("Sanctuary".equalsIgnoreCase(spellName)) {
            for (EncounterParticipant target : targets) {
                List<ConditionEntry> conditions = conditions(target);
                conditions.replaceAll(c -> c != null && SANCTUARY.equalsIgnoreCase(c.name())
                        && caster.getId().equals(c.sourceParticipantId()) && c.bonus() == null
                        ? c.withBonus(saveDc) : c);
                target.setActiveConditions(conditions);
            }
        }
    }

    /**
     * Sanctuary's WIS save for a creature that attacks the warded creature or casts a harmful spell at it (PHB p.272):
     * null when it succeeds, or the creature is the ward's own, otherwise the logged failure, and the attack or spell
     * does nothing to the warded creature; choosing a new target is the DM's, who issues it again. The DC is the
     * caster's, kept on the ward, or 13 when it is unknown.
     */
    public String sanctuary(Encounter encounter, EncounterParticipant attacker, EncounterParticipant target) {
        ConditionEntry ward = find(target, SANCTUARY);
        if (ward == null || attacker == null || attacker == target) {
            return null;
        }
        int dc = ward.bonus() != null ? ward.bonus() : DEFAULT_SANCTUARY_DC;
        int roll = resolver.d20();
        int modifier = stats.saveModifier(attacker, Ability.WIS);
        int total = roll + modifier;
        boolean saved = total >= dc;
        String text = "Sanctuary: " + attacker.getDisplayName() + "'s WIS save d20(" + roll + ")"
                + (modifier >= 0 ? " + " + modifier : " - " + (-modifier)) + " = " + total + " vs DC " + dc
                + (saved ? " - it goes ahead against " + target.getDisplayName() + "."
                : " - it fails: it must choose a new target or lose the attack or spell; "
                        + target.getDisplayName() + " is untouched.");
        combatLog.write(encounter, CombatActionType.REPEAT_SAVE, text, attacker, target);
        return saved ? null : text;
    }

    /**
     * At the end of a blinking creature's turn, its d20 for the Ethereal Plane (PHB p.219): on 11 or higher it is
     * ethereal until the start of its next turn.
     */
    public void atTurnEnd(Encounter encounter, EncounterParticipant creature) {
        ConditionEntry blinking = find(creature, BLINKING);
        if (blinking == null || creature.getLifeState() != LifeState.ALIVE || ethereal(creature)) {
            return;
        }
        int roll = resolver.d20();
        boolean vanishes = vanishes(roll);
        combatLog.write(encounter, CombatActionType.CONDITION_ADD, creature.getDisplayName() + " rolls d20(" + roll
                + ") for Blink" + (vanishes ? ": it vanishes to the Ethereal Plane until the start of its next turn."
                : ": it stays."), creature, null);
        if (vanishes) {
            List<ConditionEntry> conditions = conditions(creature);
            conditions.add(new ConditionEntry(UUID.randomUUID(), ETHEREAL, null, encounter.getRoundNumber(), "Blink",
                    creature.getId(), false, null, EndRule.startOfTurn(creature.getId()), null));
            creature.setActiveConditions(conditions);
        }
    }

    /**
     * Mirror Image's d20 for an attack roll against its caster (PHB p.260): null when the attack goes ahead against
     * the caster, otherwise what happened to the duplicate it targeted instead, which is logged; the caster takes
     * nothing either way.
     */
    public String redirect(Encounter encounter, EncounterParticipant attacker, EncounterParticipant target,
                           Resolver.TargetOutcome outcome) {
        ConditionEntry images = find(target, MIRROR_IMAGE);
        if (images == null || attacker == target) {
            return null;
        }
        int duplicates = images.bonus() != null ? images.bonus() : DUPLICATES;
        if (duplicates <= 0) {
            return null;
        }
        int roll = resolver.d20();
        int needed = redirectThreshold(duplicates);
        String lead = "Mirror Image: d20(" + roll + ") against " + needed + "+ with " + duplicates
                + (duplicates == 1 ? " duplicate" : " duplicates");
        if (roll < needed) {
            combatLog.write(encounter, CombatActionType.ATTACK, lead + " - the attack targets "
                    + target.getDisplayName() + ".", attacker, target);
            return null;
        }
        int duplicateAc = 10 + Ability.modifier(stats.abilityScore(target, Ability.DEX));
        boolean hit = hitsDuplicate(outcome.outcome(), outcome.d20(), outcome.total(), duplicateAc);
        int left = hit ? duplicates - 1 : duplicates;
        String text = lead + " - the attack targets a duplicate (AC " + duplicateAc + ")"
                + (outcome.total() != null ? " with " + outcome.total() : "")
                + (hit ? " and destroys it; " + (left == 0 ? "the last is gone and Mirror Image ends."
                : left + " left.") : " and misses.");
        combatLog.write(encounter, CombatActionType.ATTACK, text, attacker, target);
        if (hit) {
            if (left == 0) {
                List<ConditionEntry> conditions = conditions(target);
                conditions.removeIf(c -> c != null && c.id().equals(images.id()));
                target.setActiveConditions(conditions);
            } else {
                replace(target, MIRROR_IMAGE, entry -> entry.withBonus(left));
            }
        }
        return text;
    }

    private static void replace(EncounterParticipant creature, String name,
                                java.util.function.UnaryOperator<ConditionEntry> change) {
        List<ConditionEntry> conditions = conditions(creature);
        conditions.replaceAll(c -> c != null && name.equalsIgnoreCase(c.name()) ? change.apply(c) : c);
        creature.setActiveConditions(conditions);
    }

    private static ConditionEntry find(EncounterParticipant creature, String name) {
        return creature.getActiveConditions() == null ? null : creature.getActiveConditions().stream()
                .filter(c -> c != null && name.equalsIgnoreCase(c.name()))
                .findFirst()
                .orElse(null);
    }

    private static List<ConditionEntry> conditions(EncounterParticipant creature) {
        return creature.getActiveConditions() != null ? new ArrayList<>(creature.getActiveConditions())
                : new ArrayList<>();
    }
}
