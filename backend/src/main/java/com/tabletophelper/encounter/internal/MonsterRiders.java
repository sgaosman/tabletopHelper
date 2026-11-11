package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a monster action's riders come to against one target (D202), worked out before its attack is rolled.
 * <ul>
 *   <li>A condition with a size limit, as an ankheg's bite grapples only "a Large or smaller creature" (MM p.21),
 *       doesn't reach a bigger target, and the hit's save no longer guards it; a target whose size isn't known gets
 *       it, and the log asks the DM to check.</li>
 *   <li>Damage dealt only when its condition holds is added, or replaces the hit's first damage, when it holds: the
 *       target has a condition (one the attacker gave, for "grappled by Geryon"), is of a creature type, the attacker
 *       has advantage on the roll as the attack is set up (the same sources {@link AttackRules#setup} combines, as for
 *       Sneak Attack), is in a form (its conditions, the spells on it and a Wild Shape or Polymorph form), or has half
 *       its hit points or fewer. Otherwise it is skipped, and the log says so.</li>
 *   <li>A condition or a lasting rider that passes over some creature types doesn't reach a target of one, as a
 *       ghoul's paralysis passes over elves and undead (MM p.148); the hit's save no longer guards it (D203).</li>
 *   <li>An attack whose grapple holds only so many creatures, as a chain devil's "if the devil isn't already grappling
 *       a creature" (MM p.72), grapples no one more once it holds them; one that can't be used on another creature then,
 *       as a giant constrictor snake's Constrict (MM p.324), is refused before it is rolled (D203).</li>
 *   <li>A condition that lasts only while another of the hit does goes with it, as a restraint with a grapple the
 *       target is too big for (D203).</li>
 * </ul>
 */
final class MonsterRiders {

    private static final List<String> SIZES = List.of("tiny", "small", "medium", "large", "huge", "gargantuan");

    private MonsterRiders() {
    }

    /** The Effect as it reaches this target, and what the log should say about it. */
    record Against(Effect effect, List<String> notes) {
    }

    /**
     * The attack's Effect against one target with its riders worked out.
     *
     * @param target the target as the Resolver sees it, whose side and modifiers set up the attack roll
     */
    static Against against(Effect effect, MonsterEffects.Riders riders, EncounterParticipant attacker,
                           EncounterParticipant participant, Resolver.Target target) {
        if (riders.none() || !(effect.delivery() instanceof Effect.Attack attack)) {
            return new Against(effect, List.of());
        }
        List<String> notes = new ArrayList<>();
        List<String> tooBig = dropped(effect, riders, attacker, participant, notes);
        List<String> conditions = effect.conditions().stream().filter(c -> !tooBig.contains(c)).toList();
        Effect.HitSave hitSave = attack.hitSave() != null ? attack.hitSave().without(tooBig) : null;
        // Lasting riders the target is exempt from leave the save nothing more to guard (D203)
        if (hitSave != null && riders.lasting().parts().stream().filter(MonsterTemplates.Part::ownSave)
                .allMatch(part -> exempt(riders.lasting(), part.exemptTypes(), participant))) {
            hitSave = hitSave.withoutRiders();
        }
        Effect.Attack delivery = attack.withHitSave(hitSave != null && !hitSave.guardsNothing() ? hitSave : null);

        List<Effect.DamagePart> damage = new ArrayList<>(effect.damage());
        boolean replaced = false;
        boolean advantage = AttackRules.setup(attack.attackerSources(), CreatureStats.side(attacker), target.side(),
                attack.withinFiveFeet(), target.modifiers().attackSources()).rollMode() == RollMode.ADVANTAGE;
        for (MonsterEffects.ConditionalDamage conditional : riders.conditionalDamage()) {
            String dice = describe(conditional.damage());
            Boolean holds = holds(conditional.onlyIf(), attacker, participant, advantage);
            if (Boolean.TRUE.equals(holds)) {
                if (conditional.replacesDamage() && !replaced) {
                    int first = Math.min(riders.firstDamageParts(), damage.size());
                    damage.subList(0, first).clear();
                    damage.addAll(0, conditional.damage());
                    replaced = true;
                    notes.add(effect.name() + " deals " + dice + " in place of its usual damage "
                            + MonsterEffects.conditionText(conditional) + ".");
                } else if (!conditional.replacesDamage()) {
                    damage.addAll(conditional.damage());
                }
            } else if (holds == null) {
                notes.add(effect.name() + "'s " + dice + " " + MonsterEffects.conditionText(conditional)
                        + " is left to the DM: " + attacker.getDisplayName() + " isn't known to be "
                        + conditional.onlyIf().attackerForm() + ".");
            } else {
                notes.add(effect.name() + "'s " + dice + " " + MonsterEffects.conditionText(conditional)
                        + " doesn't apply to " + participant.getDisplayName() + ".");
            }
        }
        return new Against(new Effect(effect.name(), delivery, damage, effect.healing(), conditions,
                effect.concentration(), effect.durationRounds(), effect.conditionEnds()), List.copyOf(notes));
    }

    /**
     * The conditions an Effect's riders keep from a target (D202, D203), with a log note for each: those it is too big
     * for, those that pass over its creature type, a grapple its attack can't add to those it holds, and those that last
     * only while one of these would.
     */
    static List<String> dropped(Effect effect, MonsterEffects.Riders riders, EncounterParticipant attacker,
                                EncounterParticipant participant, List<String> notes) {
        List<String> dropped = new ArrayList<>(tooBig(effect.name(), riders, participant, notes));
        MonsterEffects.Lasting lasting = riders.lasting();
        for (String condition : effect.conditions()) {
            List<String> types = lasting.exemptTypes().getOrDefault(condition, List.of());
            if (!dropped.contains(condition) && exempt(lasting, types, participant)) {
                dropped.add(condition);
                notes.add(effect.name() + " doesn't leave " + participant.getDisplayName() + " " + condition + ": it "
                        + "passes over " + String.join(" and ", !types.isEmpty() ? types : lasting.actionExempt())
                        + " creatures.");
            }
        }
        if (effect.conditions().contains(GRAPPLED) && !dropped.contains(GRAPPLED) && lasting.grappleLimit() != null
                && !lasting.grappleLimitBlocks() && attacker != null && !holds(attacker, lasting.action(), participant)
                && held(attacker, lasting.action()).size() >= lasting.grappleLimit()) {
            dropped.add(GRAPPLED);
            notes.add(attacker.getDisplayName() + " already holds " + lasting.grappleLimit() + " creature"
                    + (lasting.grappleLimit() == 1 ? "" : "s") + " with " + lasting.action() + ", so "
                    + participant.getDisplayName() + " isn't grappled.");
        }
        // A condition that lasts while one kept away would is kept away too, as a restraint until a grapple ends
        boolean more = true;
        while (more) {
            more = false;
            for (Map.Entry<String, String> link : lasting.endsWith().entrySet()) {
                if (dropped.contains(link.getValue()) && !dropped.contains(link.getKey())) {
                    dropped.add(link.getKey());
                    more = true;
                }
            }
        }
        return dropped;
    }

    /**
     * Why the attack can't be used on these targets (D203): its grapple can't hold another creature and it can't be
     * used on one while it holds its limit, as "the snake can't constrict another target" (MM p.324); null when it can.
     */
    static String refusal(MonsterEffects.Riders riders, EncounterParticipant attacker,
                          List<EncounterParticipant> targets) {
        MonsterEffects.Lasting lasting = riders.lasting();
        if (lasting.grappleLimit() == null || !lasting.grappleLimitBlocks() || attacker == null) {
            return null;
        }
        List<EncounterParticipant> holding = held(attacker, lasting.action());
        long others = targets.stream().filter(target -> !holds(attacker, lasting.action(), target)).count();
        if (others == 0 || holding.size() + others <= lasting.grappleLimit()) {
            return null;
        }
        return attacker.getDisplayName() + "'s " + lasting.action() + " already holds "
                + holding.stream().map(EncounterParticipant::getDisplayName).collect(Collectors.joining(" and "))
                + ", and it can't be used on another creature until " + (holding.size() == 1 ? "that grapple ends"
                : "one of those grapples ends");
    }

    // The creatures the attacker's action holds grappled, the living ones
    static List<EncounterParticipant> held(EncounterParticipant attacker, String action) {
        return everyone(attacker).stream()
                .filter(creature -> creature.getLifeState() != com.tabletophelper.rules.combat.LifeState.DEAD
                        && holds(attacker, action, creature))
                .toList();
    }

    // Whether the attacker's action holds the creature grappled
    static boolean holds(EncounterParticipant attacker, String action, EncounterParticipant creature) {
        List<ConditionEntry> entries = creature.getActiveConditions() != null ? creature.getActiveConditions()
                : List.of();
        return entries.stream().anyMatch(c -> GRAPPLED.equalsIgnoreCase(c.name())
                && Objects.equals(c.sourceParticipantId(), attacker.getId()) && action != null
                && action.equalsIgnoreCase(c.sourceAction()));
    }

    /**
     * Whether the creature is of one of the types, or of one the whole action passes over (D203): its creature type as
     * spells read it, a stat block's tags included, such as "humanoid (elf)", or its race, so an elf passes but a
     * half-elf, which isn't one, doesn't.
     */
    static boolean exempt(MonsterEffects.Lasting lasting, List<String> types, EncounterParticipant creature) {
        List<String> all = new ArrayList<>(types);
        all.addAll(lasting.actionExempt());
        if (all.isEmpty()) {
            return false;
        }
        String type = CreatureStats.typeForSpells(creature);
        String race = creature.getCharacter() != null && creature.getCharacter().getRaceRef() != null
                && creature.getCharacter().getRaceRef().getName() != null
                ? creature.getCharacter().getRaceRef().getName() : "";
        String described = ((type != null ? type : "") + " " + race).toLowerCase(Locale.ROOT);
        return all.stream().anyMatch(wanted -> java.util.regex.Pattern.compile("(?<![a-z-])"
                + java.util.regex.Pattern.quote(wanted.trim().toLowerCase(Locale.ROOT)) + "s?\\b")
                .matcher(described).find());
    }

    // The participants of the creature's encounter, or itself alone without one, as in a unit test
    private static List<EncounterParticipant> everyone(EncounterParticipant anyone) {
        if (anyone.getEncounter() == null || anyone.getEncounter().getParticipants() == null) {
            return List.of(anyone);
        }
        return anyone.getEncounter().getParticipants().stream().filter(Objects::nonNull).toList();
    }

    private static final String GRAPPLED = "grappled";

    /**
     * The conditions an Effect's riders keep from a target too big for them, with a log note for each, and a note to
     * check one whose size isn't known.
     */
    static List<String> tooBig(String name, MonsterEffects.Riders riders, EncounterParticipant participant,
                               List<String> notes) {
        List<String> dropped = new ArrayList<>();
        String size = CreatureStats.size(participant);
        for (Map.Entry<String, String> limit : riders.maxTargetSize().entrySet()) {
            int largest = rank(limit.getValue());
            if (largest < 0) {
                continue;
            }
            if (size == null || rank(size) < 0) {
                notes.add(participant.getDisplayName() + "'s size isn't known here: " + name + " makes only a "
                        + limit.getValue() + " or smaller creature " + limit.getKey() + ", which the DM checks.");
            } else if (rank(size) > largest) {
                dropped.add(limit.getKey());
                notes.add(participant.getDisplayName() + " is " + size + ", too big to be " + limit.getKey() + " by "
                        + name + " (" + limit.getValue() + " or smaller).");
            }
        }
        return dropped;
    }

    // Whether the condition holds; null when it names a form the app can't see the attacker in
    private static Boolean holds(MonsterTemplates.DamageCondition when, EncounterParticipant attacker,
                                 EncounterParticipant target, boolean advantage) {
        if (when == null) {
            return true;
        }
        if (when.targetCondition() != null) {
            String wanted = when.targetCondition().trim().toLowerCase(Locale.ROOT);
            List<ConditionEntry> entries = target.getActiveConditions() != null ? target.getActiveConditions()
                    : List.of();
            return entries.stream().anyMatch(c -> c.name() != null && c.name().equalsIgnoreCase(wanted)
                    && (!when.byAttacker() || Objects.equals(c.sourceParticipantId(), attacker.getId())));
        }
        if (!when.targetType().isEmpty()) {
            String type = CreatureStats.typeForSpells(target);
            return type != null && when.targetType().stream()
                    .anyMatch(wanted -> type.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT)));
        }
        if (when.attackerAdvantage()) {
            return advantage;
        }
        if (when.attackerBloodied()) {
            int max = attacker.getHpMax() != null ? attacker.getHpMax() : 0;
            int current = attacker.getHpCurrent() != null ? attacker.getHpCurrent() : max;
            return max > 0 && current * 2 <= max;
        }
        if (when.attackerForm() != null) {
            return inForm(attacker, when.attackerForm()) ? true : null;
        }
        return true;
    }

    // Whether the attacker is known to be in the form: a condition, a spell on it or a form it took names it, as
    // "enlarged" is the Enlarge/Reduce spell (PHB p.237) and "crocodile form" a Polymorph into a crocodile
    private static boolean inForm(EncounterParticipant attacker, String form) {
        String word = form.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+form$", "")
                .replaceAll("^enlarged$", "enlarge").replaceAll("^raging$", "rage");
        Stream<String> names = Stream.of(
                CreatureStats.conditionNames(attacker).stream(),
                ActiveModifiers.on(attacker).stream().map(found -> ActiveModifiers.label(found.effect())),
                ActiveModifiers.on(attacker).stream().map(found -> found.effect().spell()),
                WildShapeForm.of(attacker).map(WildShapeForm::beastName).stream()).flatMap(s -> s);
        return names.filter(Objects::nonNull).anyMatch(name -> name.toLowerCase(Locale.ROOT).contains(word));
    }

    // Tiny 0 to Gargantuan 5 by name; -1 for an unknown size
    private static int rank(String size) {
        return size == null ? -1 : SIZES.indexOf(size.trim().toLowerCase(Locale.ROOT));
    }

    // "2d10 slashing", the log's words for the damage
    private static String describe(Collection<Effect.DamagePart> damage) {
        return damage.stream().map(part -> part.dice() + (part.type() != null ? " " + part.type() : ""))
                .collect(Collectors.joining(" + "));
    }
}
