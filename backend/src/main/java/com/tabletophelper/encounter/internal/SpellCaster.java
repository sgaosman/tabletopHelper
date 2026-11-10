package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Casting a spell once it is paid for, for player characters and monsters alike: the template becomes an
 * Effect, a concentration spell first ends its caster's current Concentration Instance and starts its own
 * (D162), the Effect is resolved and applied, and the log gets an entry for the cast that names no target and
 * one for each target (D157).
 */
@Component
@RequiredArgsConstructor
public class SpellCaster {

    private final SpellEffects spellEffects;
    private final Resolver resolver;
    private final EffectApplier effectApplier;
    private final CreatureStats stats;

    /**
     * What a cast did.
     *
     * @param concentration whether it started a Concentration Instance
     * @param manualReason  why the DM resolves it, or null when it was resolved
     * @param summary       the whole result in words, for the response to whoever cast it
     * @param targets       each target's result as the response reports it
     */
    public record CastResult(String spellName, int spellLevel, boolean concentration, String manualReason,
                             String summary, List<CastSpellResponse.TargetOutcome> targets) {

        public boolean autoResolved() {
            return manualReason == null;
        }
    }

    /**
     * Casts the spell with a slot of slotLevel, or 0 for none, at the targets, which have passed the access
     * checks. A spell on its caster reaches only the caster. castingAbility is the ability it is cast with, null
     * when unknown (D164). costNote names what the cast cost from the turn where it isn't an action, such as
     * " (bonus action)", and is blank otherwise (D174).
     */
    public CastResult cast(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                           List<EncounterParticipant> targets, String castingAbility, int attackBonus, int saveDc,
                           Boolean advantage, String costNote) {
        return cast(encounter, caster, spellName, slotLevel, targets, castingAbility, attackBonus, saveDc, advantage,
                costNote, MetamagicCasting.Plan.NONE, null);
    }

    /**
     * As {@link #cast(Encounter, EncounterParticipant, String, int, List, String, int, int, Boolean, String)}, with a
     * sorcerer's Metamagic, already checked and paid for (PHB p.102, M18): Careful Spell's creatures succeed on their
     * saves and Heightened Spell's has disadvantage on its first, Empowered Spell rerolls low damage dice, Extended
     * Spell doubles the durations, Subtle Spell needs no verbal component, so silence doesn't stop it, and Twinned
     * Spell's second target is among the targets. On a spell the DM resolves, the log names what the DM applies.
     * A summoning spell's cast, whose creatures {@link Summoner#afterCast} brings, says what it summons or that they
     * wait for the DM, not the spell data's note for resolving it by hand (D182).
     *
     * @param summoning the summoning as checked, or null for any other spell
     */
    public CastResult cast(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                           List<EncounterParticipant> targets, String castingAbility, int attackBonus, int saveDc,
                           Boolean advantage, String costNote, MetamagicCasting.Plan metamagic,
                           Summoner.Prepared summoning) {
        return cast(encounter, caster, spellName, slotLevel, targets, castingAbility, attackBonus, saveDc, advantage,
                costNote, metamagic, summoning, null);
    }

    /**
     * As {@link #cast(Encounter, EncounterParticipant, String, int, List, String, int, int, Boolean, String,
     * MetamagicCasting.Plan, Summoner.Prepared)}, with the option chosen for a spell that has them, already checked
     * (D184). A spell whose template gives Ongoing Modifiers leaves them on a Spell Effect, on the creatures it reached:
     * those it hit, those that failed its save, every target of a spell that needs no roll, or its caster for a spell on
     * the caster.
     */
    public CastResult cast(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                           List<EncounterParticipant> targets, String castingAbility, int attackBonus, int saveDc,
                           Boolean advantage, String costNote, MetamagicCasting.Plan metamagic,
                           Summoner.Prepared summoning, String option) {
        return cast(encounter, caster, spellName, slotLevel, targets, castingAbility, attackBonus, saveDc, advantage,
                costNote, metamagic, summoning, option, Map.of());
    }

    /**
     * As {@link #cast(Encounter, EncounterParticipant, String, int, List, String, int, int, Boolean, String,
     * MetamagicCasting.Plan, Summoner.Prepared, String)}, with the choices made for this cast by whoever controls a
     * target whose trait keeps spells off it "unless it wishes to be" affected, as a rakshasa's (MM p.257, D198): true
     * to be affected, false not to, by the target's participant ID, already checked. A target with no choice made here
     * takes its standing {@link com.tabletophelper.encounter.state.SpellConsent}.
     */
    public CastResult cast(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                           List<EncounterParticipant> targets, String castingAbility, int attackBonus, int saveDc,
                           Boolean advantage, String costNote, MetamagicCasting.Plan metamagic,
                           Summoner.Prepared summoning, String option, Map<UUID, Boolean> consents) {
        requireCanCast(caster);
        SpellEffects.SpellPlan plan = spellEffects.forCast(spellName, slotLevel, caster, castingAbility, attackBonus,
                saveDc, advantage, option);
        if (metamagic.has(Metamagic.Option.EXTENDED)) {
            plan = extended(plan);
        }
        String lead = caster.getDisplayName() + " casts " + spellName
                + (slotLevel > 0 && slotLevel > plan.spellLevel() ? " at level " + slotLevel : "") + costNote
                + metamagic.note();
        // The targets after the spell's own are creatures the caster picks for what follows, as those Ice Knife's
        // shard bursts over (XGE p.157) or Hail of Thorns' thorns fall on (PHB p.249), there being no map (D199)
        List<EncounterParticipant> picked = picked(spellName, targets, metamagic);
        targets = targets.subList(0, targets.size() - picked.size());

        if (!plan.automated()) {
            ConcentrationInstance instance = plan.concentration()
                    ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                    : null;
            String manualReason = summoning != null ? summoning.manualReason() : plan.manualReason();
            String summary = lead + " — " + (summoning != null ? summoning.castNote() : plan.manualReason())
                    + forTheDm(metamagic, targets);
            effectApplier.summary(encounter, use(caster, spellName, costNote, instance), summary);
            // A summoning with an option may give its caster Ongoing Modifiers for it, as Summon Draconic Spirit's
            // Shared Resistances, a resistance the spirit has, until the spell ends (FTD p.21)
            ModifierCast onCaster = summoning != null && option != null
                    ? new ModifierCast(spellEffects.modifiers(spellName, slotLevel, option,
                            stats.spellcastingModifier(caster, castingAbility, attackBonus), caster),
                            List.of(caster), saveDc)
                    : ModifierCast.NONE;
            leaveEffect(encounter, caster, spellName, slotLevel, instance, false, metamagic, onCaster,
                    spellEffects.later(spellName, slotLevel, option), List.of(), 0);
            return new CastResult(spellName, plan.spellLevel(), plan.concentration(), manualReason, summary,
                    List.of());
        }

        // Subtle Spell casts it without verbal or somatic components (PHB p.102)
        if (plan.verbal() && silenced(caster) && !metamagic.has(Metamagic.Option.SUBTLE)) {
            throw new ConflictException("Cannot cast spells with verbal components while silenced");
        }
        Effect effect = lifeDomainHealing(caster, plan.effect(), plan.spellLevel(), slotLevel);
        lead += lifeDomainNote(caster, plan.effect(), plan.spellLevel(), slotLevel);
        List<EncounterParticipant> reached = effect.delivery() instanceof Effect.Self ? List.of(caster) : targets;
        CombatAccess.requireNotDead(reached);
        CombatAccess.requireReachable(caster, reached);
        requireTargetTypes(spellName, reached);

        ConcentrationInstance instance = plan.concentration()
                ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                : null;
        EffectApplier.Use use = use(caster, spellName, costNote, instance);
        effectApplier.summary(encounter, use, lead + ".");
        // The creature types a spell ignores or treats harder, as Blight's undead and plants (PHB p.219, D185)
        SpellEffects.TypeRules types = spellEffects.typeRules(spellName);
        SpellEffects.SeventhLevel seventh = spellEffects.seventhLevel(spellName);
        SpellEffects.CastRules rules = spellEffects.castRules(spellName);
        // A creature whose ability score is too low is unaffected, as one of Intelligence 2 or lower by Psychic Scream
        // (XGE p.163, D190)
        SpellEffects.HighLevel high = spellEffects.highLevel(spellName);
        // A Globe of Invulnerability keeps out a spell of its level or lower cast from outside (PHB p.245, D188), as a
        // rakshasa's Limited Magic Immunity does (MM p.257, D191), and a creature immune to the condition the spell needs
        // is immune to it, as one that can't be charmed is to Mass Suggestion (PHB p.258, D188)
        int spellLevel = plan.spellLevel();
        List<EncounterParticipant> globed = reached.stream()
                .filter(target -> shielded(caster, target, spellName, spellLevel, consents.get(target.getId())))
                .toList();
        // A creature that may choose to be affected and does, as a rakshasa letting through its ally's Heal (D198)
        List<EncounterParticipant> letThrough = reached.stream()
                .filter(target -> !globed.contains(target) && globe(caster, target, spellLevel) == null
                        && stats.spellImmunityTrait(caster, target, spellLevel) != null)
                .toList();
        if (!letThrough.isEmpty()) {
            effectApplier.summary(encounter, use, letThrough.stream()
                    .map(target -> target.getDisplayName() + " lets " + spellName + " affect it ("
                            + stats.spellImmunityTrait(caster, target, spellLevel) + ")")
                    .collect(Collectors.joining("; ")) + ".");
        }
        List<EncounterParticipant> immune = reached.stream()
                .filter(target -> !globed.contains(target) && rules.immuneIfImmuneTo() != null
                        && stats.immuneToCondition(target, rules.immuneIfImmuneTo(), true))
                .toList();
        List<EncounterParticipant> unaffected = reached.stream()
                .filter(target -> !globed.contains(target) && !immune.contains(target)
                        && ofType(target, types.noEffectOn()))
                .toList();
        // Too many hit points, as for Power Word Pain (XGE p.163), or a condition that keeps the spell from reaching it,
        // as deafness does Divine Word (PHB p.234) (D189)
        List<EncounterParticipant> spared = reached.stream()
                .filter(target -> !globed.contains(target) && !immune.contains(target) && !unaffected.contains(target)
                        && (spared(target, seventh) || atOrBelow(target, high.unaffectedAtOrBelow())))
                .toList();
        List<EncounterParticipant> maximised = reached.stream()
                .filter(target -> !globed.contains(target) && !immune.contains(target) && !unaffected.contains(target)
                        && !spared.contains(target) && ofType(target, types.maximumDamageTo()))
                .toList();
        List<EncounterParticipant> normal = reached.stream()
                .filter(target -> !globed.contains(target) && !immune.contains(target) && !unaffected.contains(target)
                        && !spared.contains(target) && !maximised.contains(target))
                .toList();
        if (!spared.isEmpty()) {
            effectApplier.summary(encounter, use, spellName + " has no effect on " + spared.stream()
                    .map(target -> target.getDisplayName() + " (" + (sparedWhy(target, seventh) != null
                            ? sparedWhy(target, seventh) : high.unaffectedAtOrBelow().ability() + " "
                            + high.unaffectedAtOrBelow().max() + " or lower") + ")")
                    .collect(Collectors.joining(", ")) + ".");
        }
        List<EffectApplier.Applied> applied = new ArrayList<>();
        SpellTemplate.Darts darts = spellEffects.darts(spellName);
        SpellTemplate.TypeDice typeDice = spellEffects.typeDice(spellName);
        int transfer = spellEffects.selfDamageHeals(spellName);
        String rollNote = "";
        if (darts != null) {
            // Darts that each strike on their own, as Magic Missile's (PHB p.257, D199)
            applied.addAll(darts(encounter, caster, use, spellName, effect, rules,
                    darts.at(plan.spellLevel(), slotLevel), normal, metamagic.empoweredDice()));
        } else if (typeDice != null) {
            // A damage type from the dice, and a leap on a double, as Chaos Bolt's (XGE p.151, D199)
            applied.addAll(typedBolts(encounter, caster, use, spellName, effect, rules, typeDice, normal, picked));
        } else if (transfer > 0) {
            // The caster's own damage heals the targets, as Life Transference's (XGE p.160, D199)
            rollNote = transfer(encounter, caster, spellName, effect, transfer, normal, metamagic.empoweredDice());
        } else if (!seventh.rays().isEmpty()) {
            // A table each creature rolls on, as Prismatic Spray's d8 (PHB p.267, D189)
            applied.addAll(rays(encounter, caster, use, spellName, effect, seventh.rays(), normal, saveDc));
        } else if (!normal.isEmpty() || maximised.isEmpty()) {
            // With no targets at all the spell still resolves, rolling its dice as before (D177)
            List<Resolver.Target> resolvedAgainst = stats.spellTargets(normal, effect, caster).stream()
                    .map(target -> withMetamagic(withCastRules(target, normal, rules), metamagic))
                    .toList();
            applied.addAll(effectApplier.apply(encounter, use, effect,
                    resolver.resolve(effect, CreatureStats.side(caster), resolvedAgainst, metamagic.empoweredDice()),
                    rules));
        }
        if (!maximised.isEmpty()) {
            Effect most = maximumDamage(effect);
            List<Resolver.Target> resolvedAgainst = stats.spellTargets(maximised, most, caster).stream()
                    .map(target -> withMetamagic(target.withSaveSource(RollMode.DISADVANTAGE), metamagic))
                    .toList();
            applied.addAll(effectApplier.apply(encounter, use, most,
                    resolver.resolve(most, CreatureStats.side(caster), resolvedAgainst, 0), rules));
        }
        String typeNote = globeNote(spellName, caster, globed, plan.spellLevel())
                + immuneNote(spellName, immune, rules.immuneIfImmuneTo())
                + typeNote(spellName, unaffected, maximised)
                + carapaceNote(spellName, normal);
        afterSixthLevelRules(encounter, caster, spellName, slotLevel, rules, applied);
        concentrationSaves(encounter, caster, use, spellName, saveDc, normal);
        if (!typeNote.isEmpty()) {
            effectApplier.summary(encounter, use, typeNote.trim());
        }
        List<EncounterParticipant> affected = applied.stream()
                .filter(result -> switch (result.outcome().outcome()) {
                    case HIT, CRITICAL, SAVE_FAIL, AUTOMATIC -> true;
                    default -> false;
                })
                .map(EffectApplier.Applied::target)
                .filter(target -> target.getLifeState() != LifeState.DEAD)
                .toList();
        int spellcastingModifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);
        SpellEffects.ModifierPlan modifiers = spellEffects.modifiers(spellName, slotLevel, option,
                spellcastingModifier, affected.isEmpty() ? null : affected.getFirst());
        // Temporary hit points the cast gives, as Guardian of Nature's Great Tree's (XGE p.157, D185)
        Integer tempHp = spellEffects.tempHitPoints(spellName, option);
        if (tempHp != null) {
            affected.forEach(creature -> effectApplier.gainTempHp(encounter, caster, creature, tempHp, spellName));
        }
        // Damage still to come for the creatures the outcome names, as Vitriolic Sphere's after a failed save (D185)
        SpellEffects.Later later = spellEffects.later(spellName, slotLevel, option);
        List<EncounterParticipant> delayedOn = later.delayedDice() == null ? List.of() : applied.stream()
                .filter(result -> "HIT".equalsIgnoreCase(later.delayedOn())
                        ? result.outcome().outcome() == Outcome.HIT || result.outcome().outcome() == Outcome.CRITICAL
                        : result.outcome().outcome() == Outcome.SAVE_FAIL)
                .map(EffectApplier.Applied::target)
                .filter(target -> target.getLifeState() != LifeState.DEAD)
                .toList();
        int dealt = applied.stream().mapToInt(EffectApplier.Applied::damageTaken).sum();
        // A spell on its caster that leaves uses, as Crown of Stars' motes (XGE p.152, D189), spends none by being cast
        boolean spentOne = !reached.isEmpty() && !(effect.delivery() instanceof Effect.Self);
        String modifierNote = leaveEffect(encounter, caster, spellName, slotLevel, instance, spentOne,
                metamagic, new ModifierCast(modifiers, affected, saveDc), later, delayedOn, dealt);
        String fifthNote = afterCast(encounter, caster, spellName, option, use, instance, reached, applied,
                affected, saveDc, castingAbility, attackBonus);
        String seventhNote = afterSeventh(encounter, caster, spellName, slotLevel, option, use, seventh, normal,
                applied, saveDc, castingAbility, attackBonus, picked) + rollNote;
        keepRider(caster, spellName, saveDc, picked);
        String highNote = afterHighLevelCast(encounter, caster, spellName, slotLevel, high, use, affected);
        // What the DM still rolls for a spell with no Ongoing Modifiers to carry its reminder, as Heat Metal's drop
        // save (PHB p.250, D197)
        String reminder = spellEffects.castReminder(spellName);
        String reminderNote = reminder != null ? " " + reminder : "";
        if (reminder != null) {
            effectApplier.summary(encounter, use, spellName + ": " + reminder);
        }
        return new CastResult(spellName, plan.spellLevel(), plan.concentration(), null,
                summary(lead, applied) + typeNote + modifierNote + fifthNote + seventhNote + highNote + reminderNote,
                outcomes(effect, applied));
    }

    // Whether the creature's score in the floor's ability is at or below it (D190)
    private boolean atOrBelow(EncounterParticipant creature, SpellTemplate.AbilityFloor floor) {
        if (floor == null) {
            return false;
        }
        return Ability.parse(floor.ability())
                .map(ability -> stats.abilityScore(creature, ability) <= floor.max())
                .orElse(false);
    }

    /*
     * What the 8th- and 9th-level spells' keys add once a cast has resolved (D190): the dice it rolls for the table, as
     * Time Stop's 1d4 + 1 turns (PHB p.283); healing to full, as Power Word Heal's (PHB p.266); and a pool of hit points
     * divided among the creatures it reached, as Mass Heal's 700 (PHB p.258). What the log adds, or nothing.
     */
    private String afterHighLevelCast(Encounter encounter, EncounterParticipant caster, String spellName,
                                      int slotLevel, SpellEffects.HighLevel high, EffectApplier.Use use,
                                      List<EncounterParticipant> affected) {
        List<String> notes = new ArrayList<>();
        if (high.castRoll() != null) {
            Dice dice = Dice.parse(high.castRoll().dice().replace(" ", ""));
            List<Integer> rolls = new ArrayList<>();
            for (int i = 0; i < dice.count(); i++) {
                rolls.add(resolver.roll(dice.sides()));
            }
            int total = dice.total(rolls.stream().mapToInt(Integer::intValue).sum());
            String text = spellName + ": " + dice.count() + "d" + dice.sides()
                    + (dice.modifier() > 0 ? " + " + dice.modifier() : dice.modifier() < 0 ? " - " + -dice.modifier() : "")
                    + " (" + rolls.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ") = " + total
                    + (high.castRoll().note() != null ? " " + high.castRoll().note() : "") + ".";
            effectApplier.summary(encounter, use, text);
            notes.add(text);
            // Turns in a row after this one, as Time Stop's (PHB p.283, D195): rules call, the casting turn isn't one
            // of them, and the turn order gives them as the caster ends each turn
            if (high.castRoll().extraTurns() && caster.getLifeState() == LifeState.ALIVE) {
                effectApplier.startExtraTurns(encounter, caster, spellName, slotLevel > 0 ? slotLevel : null, total);
                notes.add(caster.getDisplayName() + " takes " + total + " turns in a row after this one.");
            }
        }
        List<EncounterParticipant> living = affected.stream()
                .filter(creature -> creature.getLifeState() != LifeState.DEAD)
                .toList();
        if (high.healsToFull()) {
            for (EncounterParticipant creature : living) {
                effectApplier.heal(encounter, caster, creature, EffectApplier.missingHitPoints(creature),
                        spellName + ": all its hit points");
            }
        }
        if (high.healingPool() != null && !living.isEmpty()) {
            Map<EncounterParticipant, Integer> shares = poolShares(high.healingPool(), living);
            shares.forEach((creature, share) -> effectApplier.heal(encounter, caster, creature, share,
                    spellName + ": " + share + " of " + high.healingPool()));
            int used = shares.values().stream().mapToInt(Integer::intValue).sum();
            notes.add(spellName + " restores " + used + " of its " + high.healingPool() + " hit points.");
        }
        return notes.isEmpty() ? "" : " " + String.join(" ", notes);
    }

    /**
     * A pool of hit points divided among creatures (D190), as Mass Heal's 700 (PHB p.258), which the caster divides as
     * they choose: here so none is wasted, each creature in turn, from the least hurt, taking what it is missing or an
     * even share of what is left, whichever is less. Rules call: the app chooses the split.
     */
    static Map<EncounterParticipant, Integer> poolShares(int pool, List<EncounterParticipant> creatures) {
        List<EncounterParticipant> order = creatures.stream()
                .sorted(java.util.Comparator.comparingInt(EffectApplier::missingHitPoints))
                .toList();
        Map<EncounterParticipant, Integer> shares = new LinkedHashMap<>();
        int left = pool;
        for (int i = 0; i < order.size(); i++) {
            EncounterParticipant creature = order.get(i);
            int share = Math.min(EffectApplier.missingHitPoints(creature), left / (order.size() - i));
            shares.put(creature, share);
            left -= share;
        }
        return shares;
    }

    // Whether the 7th-level keys spare the creature (D189): more hit points than the spell allows, or a condition that
    // keeps the spell from it
    private static boolean spared(EncounterParticipant target, SpellEffects.SeventhLevel seventh) {
        return sparedWhy(target, seventh) != null;
    }

    private static String sparedWhy(EncounterParticipant target, SpellEffects.SeventhLevel seventh) {
        if (seventh.maxTargetHp() != null && target.getHpCurrent() != null
                && target.getHpCurrent() > seventh.maxTargetHp()) {
            return target.getHpCurrent() + " HP, above " + seventh.maxTargetHp();
        }
        List<String> has = CreatureStats.conditionNames(target);
        return seventh.noEffectOnConditions().stream()
                .filter(wanted -> has.stream().anyMatch(wanted::equalsIgnoreCase))
                .findFirst()
                .orElse(null);
    }

    /*
     * Prismatic Spray's rays (PHB p.267, D189): each creature rolls the table's die, a row that strikes twice rolling two
     * more with that row rerolled, and makes the spell's one save against all its rows together: the damage of each,
     * of its own type, halved on a success, and the conditions of each on a failure. A creature that fails against a row
     * with counted saves starts counting them, as the indigo ray's Constitution saves; a row's reminder is logged for one
     * that fails, as the violet ray's Wisdom save at the start of the caster's next turn.
     */
    private List<EffectApplier.Applied> rays(Encounter encounter, EncounterParticipant caster, EffectApplier.Use use,
                                             String spellName, Effect cast, List<SpellTemplate.Ray> table,
                                             List<EncounterParticipant> creatures, int saveDc) {
        if (!(cast.delivery() instanceof Effect.Save save)) {
            return List.of();
        }
        int sides = table.stream().mapToInt(SpellTemplate.Ray::roll).max().orElse(8);
        List<EffectApplier.Applied> applied = new ArrayList<>();
        Map<SpellTemplate.CountedSaves, List<EncounterParticipant>> counting = new LinkedHashMap<>();
        List<String> reminders = new ArrayList<>();
        for (EncounterParticipant creature : creatures) {
            List<SpellTemplate.Ray> struck = new ArrayList<>();
            List<Integer> rolls = new ArrayList<>();
            SpellTemplate.Ray first = ray(table, rolls, sides);
            if (first.rollsTwice()) {
                struck.add(reroll(table, rolls, sides));
                struck.add(reroll(table, rolls, sides));
            } else {
                struck.add(first);
            }
            List<Effect.DamagePart> damage = new ArrayList<>();
            List<String> conditions = new ArrayList<>();
            Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
            for (SpellTemplate.Ray ray : struck) {
                if (ray.damageDice() != null) {
                    damage.add(new Effect.DamagePart(Dice.parse(ray.damageDice().trim()), ray.damageType()));
                }
                for (SpellTemplate.ConditionInflicted condition : ray.conditions()) {
                    if (condition.name() != null && !conditions.contains(condition.name())) {
                        conditions.add(condition.name());
                        if (condition.durationRounds() != null) {
                            ends.put(condition.name(), new Effect.ConditionEnd(condition.durationRounds(), null, null,
                                    false));
                        }
                    }
                }
            }
            effectApplier.summary(encounter, use, creature.getDisplayName() + " is struck by the "
                    + struck.stream().map(ray -> ray.color() != null ? ray.color() : "#" + ray.roll())
                            .collect(Collectors.joining(" and ")) + " ray" + (struck.size() > 1 ? "s" : "")
                    + " (d" + sides + ": " + rolls.stream().map(String::valueOf).collect(Collectors.joining(", "))
                    + ").");
            Effect each = new Effect(spellName, save, damage, null, conditions, false, null, ends);
            List<EffectApplier.Applied> result = effectApplier.apply(encounter, use, each,
                    resolver.resolve(each, CreatureStats.side(caster), stats.spellTargets(List.of(creature), each, caster)));
            applied.addAll(result);
            boolean failed = result.stream().anyMatch(r -> r.outcome().outcome() == Outcome.SAVE_FAIL)
                    && creature.getLifeState() != LifeState.DEAD;
            if (failed) {
                for (SpellTemplate.Ray ray : struck) {
                    if (ray.countedSaves() != null) {
                        counting.computeIfAbsent(ray.countedSaves(), key -> new ArrayList<>()).add(creature);
                    }
                    if (ray.reminder() != null) {
                        reminders.add(creature.getDisplayName() + ": " + ray.reminder().replace("{dc}",
                                String.valueOf(saveDc)).replace("{caster}", caster.getDisplayName()));
                    }
                }
            }
        }
        countAndRemind(encounter, caster, use, spellName, counting, reminders, saveDc);
        return applied;
    }

    /*
     * Prismatic Wall's layers (PHB p.267, D195): a creature passing through meets each layer in order, red to violet, and
     * makes the layer's save against each, taking its damage, halved on a success, or its conditions on a failure, as
     * Prismatic Spray's rays of the same colour; a failure against the indigo layer starts its Constitution saves and one
     * against the violet layer logs its Wisdom save. A creature that drops stops there.
     */
    private List<EffectApplier.Applied> layers(Encounter encounter, EncounterParticipant caster, EffectApplier.Use use,
                                               String spellName, SpellTemplate.Layers layers,
                                               List<EncounterParticipant> creatures, int saveDc) {
        Ability ability = Ability.parse(layers.saveAbility()).orElse(Ability.DEX);
        List<EffectApplier.Applied> applied = new ArrayList<>();
        Map<SpellTemplate.CountedSaves, List<EncounterParticipant>> counting = new LinkedHashMap<>();
        List<String> reminders = new ArrayList<>();
        for (EncounterParticipant creature : creatures) {
            if (creature.getLifeState() != LifeState.ALIVE) {
                continue;
            }
            effectApplier.summary(encounter, use, creature.getDisplayName() + " passes through " + spellName + ": a DC "
                    + saveDc + " " + ability + " save against each layer in turn.");
            for (SpellTemplate.Ray layer : layers.rows()) {
                if (creature.getLifeState() != LifeState.ALIVE) {
                    break;
                }
                if (layer.rollsTwice()) {
                    continue;
                }
                List<Effect.DamagePart> damage = layer.damageDice() != null
                        ? List.of(new Effect.DamagePart(Dice.parse(layer.damageDice().trim()), layer.damageType()))
                        : List.of();
                List<String> conditions = new ArrayList<>();
                Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
                for (SpellTemplate.ConditionInflicted condition : layer.conditions()) {
                    if (condition.name() != null && !conditions.contains(condition.name())) {
                        conditions.add(condition.name());
                        if (condition.durationRounds() != null) {
                            ends.put(condition.name(), new Effect.ConditionEnd(condition.durationRounds(), null, null,
                                    false));
                        }
                    }
                }
                String name = spellName + (layer.color() != null ? " (" + layer.color() + " layer)" : "");
                Effect each = new Effect(name, new Effect.Save(ability, saveDc, !damage.isEmpty()), damage, null,
                        conditions, false, null, ends);
                List<EffectApplier.Applied> result = effectApplier.apply(encounter, use, each, resolver.resolve(each,
                        CreatureStats.side(caster), stats.spellTargets(List.of(creature), each, caster)));
                applied.addAll(result);
                boolean failed = result.stream().anyMatch(r -> r.outcome().outcome() == Outcome.SAVE_FAIL)
                        && creature.getLifeState() != LifeState.DEAD;
                if (failed && layer.countedSaves() != null) {
                    counting.computeIfAbsent(layer.countedSaves(), key -> new ArrayList<>()).add(creature);
                }
                if (failed && layer.reminder() != null) {
                    reminders.add(creature.getDisplayName() + ": " + layer.reminder().replace("{dc}",
                            String.valueOf(saveDc)).replace("{caster}", caster.getDisplayName()));
                }
            }
        }
        countAndRemind(encounter, caster, use, spellName, counting, reminders, saveDc);
        return applied;
    }

    /*
     * The saves a ray or layer starts, kept on a Spell Effect of the spell's that ends once every count is done (D187,
     * D189), and the reminders for creatures that failed.
     */
    private void countAndRemind(Encounter encounter, EncounterParticipant caster, EffectApplier.Use use,
                                String spellName, Map<SpellTemplate.CountedSaves, List<EncounterParticipant>> counting,
                                List<String> reminders, int saveDc) {
        if (!counting.isEmpty()) {
            // The counts live on a Spell Effect of the spell's, which ends once every count is done (D187)
            for (Map.Entry<SpellTemplate.CountedSaves, List<EncounterParticipant>> entry : counting.entrySet()) {
                SpellTemplate.CountedSaves counted = entry.getKey();
                SpellEffectEntry held = effectApplier.startEffect(encounter, caster, spellName, null, null, null, null,
                        false);
                effectApplier.startCountedSave(caster, held.id(), new SpellEffectEntry.CountedSave(counted.ability(),
                        saveDc, counted.successes(), counted.failures(), counted.onFailures(),
                        counted.endsConditions(), List.of(), counted.failureCondition()), entry.getValue());
                effectApplier.summary(encounter, use, names(entry.getValue()) + " makes a " + counted.ability()
                        + " save at the end of each of " + (entry.getValue().size() == 1 ? "its" : "their")
                        + " turns: " + counted.successes() + " successes end it, " + counted.failures()
                        + " failures leave it " + (counted.failureCondition() != null ? counted.failureCondition()
                        : "as the spell says") + ".");
            }
        }
        reminders.forEach(reminder -> effectApplier.summary(encounter, use, reminder));
    }

    /*
     * The damage a use deals in this round of the spell (D195), as Storm of Vengeance's 1d6 acid in round 2 or its 10d6
     * lightning bolts in round 3, at up to six creatures, a DEX save halving them (PHB p.279); a 409 for a round that
     * brings nothing, as the cast's own, and a 400 for more creatures than the round reaches.
     */
    private static Effect roundEffect(String spellName, int round, SpellTemplate.RoundEffect entry, int saveDc,
                                      int targets) {
        if (entry == null) {
            throw new ConflictException(spellName + " deals nothing to use in round " + round + " of the spell");
        }
        if (entry.maxTargets() != null && targets > entry.maxTargets()) {
            throw new com.tabletophelper.shared.ValidationException(spellName + " reaches at most "
                    + entry.maxTargets() + " creatures in round " + round);
        }
        Optional<Ability> ability = entry.saveAbility() != null ? Ability.parse(entry.saveAbility()) : Optional.empty();
        Effect.Delivery delivery = ability.isPresent()
                ? new Effect.Save(ability.get(), saveDc, entry.halfOnSave()) : new Effect.Automatic();
        return new Effect(spellName, delivery, List.of(new Effect.DamagePart(Dice.parse(entry.damageDice().trim()),
                entry.damageType())), null, List.of(), false, null);
    }

    /*
     * Earthquake's shaking (PHB p.236, D195): each creature the spell reaches that is concentrating makes the template's
     * save against the spell's DC, on the cast and each later use, its concentration breaking on a failure. It is a
     * saving throw against the spell, so Magic Resistance counts; it isn't a concentration check for damage, so War
     * Caster's advantage doesn't. Rules call: the caster's own concentration is checked only when the caster is a target.
     */
    private void concentrationSaves(Encounter encounter, EncounterParticipant caster, EffectApplier.Use use,
                                    String spellName, int saveDc, List<EncounterParticipant> creatures) {
        String abilityName = spellEffects.concentrationSave(spellName);
        Optional<Ability> ability = abilityName != null ? Ability.parse(abilityName) : Optional.empty();
        if (ability.isEmpty()) {
            return;
        }
        Effect save = new Effect(spellName, new Effect.Save(ability.get(), saveDc, false), List.of(), null, List.of(),
                false, null);
        for (EncounterParticipant creature : creatures) {
            ConcentrationInstance held = creature.getConcentration();
            if (held == null || creature.getLifeState() == LifeState.DEAD) {
                continue;
            }
            Resolver.TargetOutcome outcome = resolver.resolve(save, CreatureStats.side(caster),
                    stats.spellTargets(List.of(creature), save, caster)).getFirst();
            boolean failed = outcome.outcome() == Outcome.SAVE_FAIL;
            effectApplier.summary(encounter, use, creature.getDisplayName() + " makes a DC " + saveDc + " "
                    + ability.get() + " save to keep concentrating on " + held.spell() + ": "
                    + (outcome.total() != null ? outcome.total() + ", " : "")
                    + (failed ? "a failure, and its concentration breaks." : "a success."));
            if (failed) {
                effectApplier.endConcentration(encounter, creature, "fails " + spellName + "'s " + ability.get()
                        + " save");
            }
        }
    }

    // The row the die lands on
    private SpellTemplate.Ray ray(List<SpellTemplate.Ray> table, List<Integer> rolls, int sides) {
        int roll = resolver.roll(sides);
        rolls.add(roll);
        return table.stream().filter(row -> row.roll() == roll).findFirst().orElse(table.getFirst());
    }

    // A row rolled again, a row that strikes twice being rerolled (PHB p.267)
    private SpellTemplate.Ray reroll(List<SpellTemplate.Ray> table, List<Integer> rolls, int sides) {
        SpellTemplate.Ray row = ray(table, rolls, sides);
        int guard = 0;
        while (row.rollsTwice() && guard++ < 20) {
            row = ray(table, rolls, sides);
        }
        return row;
    }

    /*
     * What the 7th-level spells' keys add once a cast has resolved (D189): the hit-point bands, as Divine Word's (PHB
     * p.234), killing outright or giving conditions with their own durations; the creature types it sends away; the
     * save a creature it hit makes next, as Plane Shift's (PHB p.266); the creatures it killed that rise, as Finger of
     * Death's zombies (PHB p.241); and the detonation its Spell Effect holds, as Delayed Blast Fireball's (PHB p.230).
     */
    private String afterSeventh(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                                String option, EffectApplier.Use use, SpellEffects.SeventhLevel seventh,
                                List<EncounterParticipant> reached, List<EffectApplier.Applied> applied, int saveDc,
                                String castingAbility, int attackBonus, List<EncounterParticipant> picked) {
        if (seventh.equals(SpellEffects.SeventhLevel.NONE)) {
            return "";
        }
        List<String> notes = new ArrayList<>();
        List<EncounterParticipant> reachedNow = applied.stream()
                .filter(result -> switch (result.outcome().outcome()) {
                    case HIT, CRITICAL, SAVE_FAIL, AUTOMATIC -> true;
                    default -> false;
                })
                .map(EffectApplier.Applied::target)
                .distinct()
                .toList();
        for (EncounterParticipant creature : reachedNow) {
            if (creature.getLifeState() == LifeState.DEAD || seventh.bands().isEmpty()
                    || creature.getHpCurrent() == null) {
                continue;
            }
            int hp = creature.getHpCurrent();
            Optional<SpellTemplate.HpBand> band = seventh.bands().stream().filter(b -> hp <= b.maxHp()).findFirst();
            if (band.isEmpty()) {
                notes.add(creature.getDisplayName() + " has " + hp + " HP, too many for " + spellName + " to harm.");
                continue;
            }
            if (band.get().killsOutright()) {
                notes.add(effectApplier.killOutright(encounter, caster, creature, spellName));
                continue;
            }
            for (SpellTemplate.ConditionInflicted condition : band.get().conditions()) {
                if (condition.name() != null) {
                    effectApplier.addLastingCondition(encounter, caster, creature, condition.name(),
                            condition.durationRounds(), spellName);
                }
            }
            notes.add(creature.getDisplayName() + " (" + hp + " HP) is " + band.get().conditions().stream()
                    .map(SpellTemplate.ConditionInflicted::name).filter(java.util.Objects::nonNull)
                    .collect(Collectors.joining(", ")) + ".");
        }
        if (seventh.banishes() != null) {
            for (EncounterParticipant creature : reachedNow) {
                if (creature.getLifeState() != LifeState.DEAD && ofType(creature, seventh.banishes().types())
                        && !CombatAccess.banished(creature)) {
                    effectApplier.addLastingCondition(encounter, caster, creature, EffectApplier.BANISHED,
                            seventh.banishes().durationRounds(), spellName);
                    notes.add(creature.getDisplayName() + " (" + CreatureStats.typeForSpells(creature)
                            + ") is sent back to its home plane.");
                }
            }
        }
        SpellTemplate.FollowUpSave followUp = seventh.followUp();
        // A one-shot weapon rider's follow-up waits for its weapon hit (D199)
        if (followUp != null && (followUp.option() == null || followUp.option().equalsIgnoreCase(option))
                && !spellEffects.hitRider(spellName)) {
            if ("FAILED_SAVE".equalsIgnoreCase(followUp.on())) {
                List<EncounterParticipant> failed = applied.stream()
                        .filter(result -> result.outcome().outcome() == Outcome.SAVE_FAIL)
                        .map(EffectApplier.Applied::target)
                        .filter(target -> target.getLifeState() != LifeState.DEAD)
                        .toList();
                if (!failed.isEmpty()) {
                    Ability ability = Ability.parse(followUp.ability()).orElse(Ability.CHA);
                    List<String> conditions = followUp.conditions().stream()
                            .map(SpellTemplate.ConditionInflicted::name).filter(java.util.Objects::nonNull).toList();
                    Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
                    followUp.conditions().stream().filter(c -> c.name() != null && c.durationRounds() != null)
                            .forEach(c -> ends.put(c.name(), new Effect.ConditionEnd(c.durationRounds(), null, null,
                                    false)));
                    Effect then = new Effect(spellName, new Effect.Save(ability, saveDc, false), List.of(), null,
                            conditions, false, null, ends);
                    effectApplier.summary(encounter, use, names(failed) + " makes a DC " + saveDc + " "
                            + ability.name() + " save against " + spellName + ".");
                    effectApplier.apply(encounter, use, then, resolver.resolve(then,
                            CreatureStats.side(caster), stats.spellTargets(failed, then, caster)));
                }
            } else {
                afterAttack(encounter, caster, spellName, slotLevel, use, applied, picked, castingAbility,
                        attackBonus, saveDc);
            }
        }
        SpellTemplate.RaisesOnKill raises = seventh.raises();
        if (raises != null) {
            List<EncounterParticipant> killed = applied.stream()
                    .map(EffectApplier.Applied::target)
                    .distinct()
                    .filter(target -> target.getLifeState() == LifeState.DEAD
                            && (raises.creatureTypes().isEmpty() || ofType(target, raises.creatureTypes())))
                    .toList();
            if (!killed.isEmpty()) {
                effectApplier.startRising(encounter, caster, spellName, slotLevel > 0 ? slotLevel : null, killed);
                String text = names(killed) + " will rise as " + (killed.size() == 1 ? "a " : "") + raises.form()
                        .toLowerCase(java.util.Locale.ROOT) + (killed.size() == 1 ? "" : "s") + " under "
                        + caster.getDisplayName() + "'s command at the start of " + caster.getDisplayName()
                        + "'s next turn.";
                effectApplier.summary(encounter, use, text);
                notes.add(text);
            }
        }
        if (seventh.detonation() != null) {
            Optional<SpellEffectEntry> left = EffectApplier.effects(caster).stream()
                    .filter(effect -> effect.spell().equalsIgnoreCase(spellName))
                    .reduce((first, second) -> second);
            String dice = spellEffects.detonationDice(spellName, slotLevel);
            if (left.isPresent() && dice != null) {
                SpellTemplate.Detonation detonation = seventh.detonation();
                List<EncounterParticipant> caught = reached.stream()
                        .filter(target -> target.getLifeState() != LifeState.DEAD).toList();
                effectApplier.startDetonation(caster, left.get().id(), new SpellEffectEntry.Detonation(dice,
                        detonation.damageType(), detonation.saveAbility(), saveDc, detonation.halfOnSave(),
                        detonation.growsEachTurn()), caught);
                String text = "The bead holds " + dice + " " + detonation.damageType() + (detonation.growsEachTurn()
                        != null ? ", " + detonation.growsEachTurn() + " more at the end of each of "
                        + caster.getDisplayName() + "'s turns," : "") + " and explodes over "
                        + (caught.isEmpty() ? "no one yet" : names(caught)) + " when the spell ends.";
                effectApplier.summary(encounter, use, text);
                notes.add(text);
            }
        }
        String joined = notes.stream().filter(note -> note != null && !note.isBlank())
                .collect(Collectors.joining(" "));
        return joined.isEmpty() ? "" : " " + joined;
    }

    // ── The low-level spells' keys (D199) ────────────────────────

    /*
     * The creatures the caster picked for what follows the spell, there being no map (D199): the targets after the
     * template's primaryTargets, one more being the spell's own with Twinned Spell, as those Ice Knife's shard bursts
     * over (XGE p.157); every target for a spell on its caster, as Hail of Thorns (PHB p.249). Empty for a spell
     * without primaryTargets.
     */
    private List<EncounterParticipant> picked(String spellName, List<EncounterParticipant> targets,
                                              MetamagicCasting.Plan metamagic) {
        Integer primary = spellEffects.primaryTargets(spellName);
        if (primary == null) {
            return List.of();
        }
        int own = primary + (primary > 0 && metamagic.has(Metamagic.Option.TWINNED) ? 1 : 0);
        return targets.size() > own ? List.copyOf(targets.subList(own, targets.size())) : List.of();
    }

    /*
     * Keeps on the cast's Spell Effect what its one-shot weapon rider or its own triggers need later (D199): the
     * caster's spell save DC, for Wrathful Smite's save on its hit (PHB p.289) or Searing Smite's at the start of the
     * burning creature's turns (PHB p.274), and the creatures picked, as Hail of Thorns' (PHB p.249).
     */
    private void keepRider(EncounterParticipant caster, String spellName, int saveDc,
                           List<EncounterParticipant> picked) {
        if (!spellEffects.hitRider(spellName)
                && spellEffects.conditionedTriggers(spellName, SpellTrigger.START_OF_TURN).isEmpty()) {
            return;
        }
        EffectApplier.effects(caster).stream()
                .filter(effect -> effect.spell().equalsIgnoreCase(spellName))
                .reduce((first, second) -> second)
                .ifPresent(effect -> effectApplier.startRider(caster, effect.id(), new SpellEffectEntry.Rider(saveDc,
                        picked.stream().map(EncounterParticipant::getId).toList())));
    }

    /**
     * A spell's follow-up once its attack has resolved (D189, D199): each creature it hit, or every creature it attacked
     * for a follow-up on the attack, and the creatures the caster picked when the follow-up says so, make the follow-up's
     * save or take it with none: Plane Shift's Charisma save after its hit (PHB p.266), Ice Knife's burst over its target
     * and the creatures beside it, hit or miss, 2d6 cold on a failed DEX save (XGE p.157), and Green-Flame Blade's fire
     * leaping from the creature it hit to another (TCE p.107).
     */
    void afterAttack(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                     EffectApplier.Use use, List<EffectApplier.Applied> applied, List<EncounterParticipant> picked,
                     String castingAbility, int attackBonus, int saveDc) {
        Optional<SpellEffects.FollowUp> found = spellEffects.followUp(spellName, slotLevel, caster, castingAbility,
                attackBonus, saveDc);
        if (found.isEmpty()) {
            return;
        }
        SpellTemplate.FollowUpSave save = found.get().save();
        List<EncounterParticipant> struck = applied.stream()
                .filter(result -> save.onAttack() || result.outcome().outcome() == Outcome.HIT
                        || result.outcome().outcome() == Outcome.CRITICAL)
                .map(EffectApplier.Applied::target)
                .distinct()
                .toList();
        List<EncounterParticipant> creatures = new ArrayList<>();
        if (save.includesTarget()) {
            creatures.addAll(struck);
        }
        // The picked creatures, when the attack reached anyone, or for a follow-up hit or miss
        if (save.alsoPicked() && (save.onAttack() || !struck.isEmpty())) {
            picked.stream().filter(creature -> !creatures.contains(creature)).forEach(creatures::add);
        }
        List<EncounterParticipant> living = creatures.stream()
                .filter(creature -> creature.getLifeState() != LifeState.DEAD && !CombatAccess.banished(creature))
                .toList();
        if (living.isEmpty()) {
            return;
        }
        Effect then = found.get().effect();
        if (then.delivery() instanceof Effect.Save each) {
            effectApplier.summary(encounter, use, names(living) + " makes a DC " + each.dc() + " "
                    + each.ability().name() + " save against " + spellName + ".");
        }
        effectApplier.apply(encounter, use, then, resolver.resolve(then, CreatureStats.side(caster),
                stats.spellTargets(living, then, caster)));
    }

    /*
     * Darts that each strike on their own (D199): Magic Missile's three, one more for each slot level above 1st, each
     * 1d4 + 1 force with no roll to hit (PHB p.257), or Jim's Magic Missile's, each a ranged spell attack of 2d4 force,
     * 5d4 on a critical hit (AcqInc p.75). The caster shares them among the targets as listed, a target named more than once
     * taking more, and the darts left over going round the targets in order. Each dart rolls its own damage and is its own
     * damage, so a concentrating creature makes a check for each (PHB p.203) (rules call: each dart rolls its own 1d4). A
     * creature under Shield takes none from Magic Missile (PHB p.275). Empowered Spell rerolls the first dart's dice. For
     * Jim's darts, any attack roll of a natural 1 makes every dart miss and deal the backfire's damage to the caster
     * instead.
     */
    private List<EffectApplier.Applied> darts(Encounter encounter, EncounterParticipant caster,
                                              EffectApplier.Use use, String spellName, Effect effect,
                                              SpellEffects.CastRules rules, int count,
                                              List<EncounterParticipant> targets, int empoweredDice) {
        if (targets.isEmpty()) {
            return List.of();
        }
        List<EncounterParticipant> struck = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            struck.add(targets.get(i % targets.size()));
        }
        effectApplier.summary(encounter, use, spellName + ": " + count + " darts, at " + struck.stream()
                .map(EncounterParticipant::getDisplayName).collect(Collectors.joining(", ")) + ".");
        List<Resolver.TargetOutcome> rolled = new ArrayList<>();
        List<EncounterParticipant> rolledAt = new ArrayList<>();
        for (int i = 0; i < struck.size(); i++) {
            EncounterParticipant target = struck.get(i);
            if (blockedByShield(spellName, target)) {
                effectApplier.summary(encounter, use, target.getDisplayName() + "'s Shield blocks a dart of "
                        + spellName + ".");
                continue;
            }
            rolled.add(resolver.resolve(effect, CreatureStats.side(caster),
                    stats.spellTargets(List.of(target), effect, caster), i == 0 ? empoweredDice : 0).getFirst());
            rolledAt.add(target);
        }
        SpellTemplate.Backfire backfire = spellEffects.backfireOnOne(spellName);
        if (backfire != null && effect.delivery() instanceof Effect.Attack
                && rolled.stream().anyMatch(outcome -> Integer.valueOf(1).equals(outcome.d20()))) {
            effectApplier.summary(encounter, use, "A dart of " + spellName + " rolls a natural 1, so every dart misses "
                    + "and each deals " + backfire.damage() + " " + backfire.damageType() + " damage to "
                    + caster.getDisplayName() + ".");
            Effect back = new Effect(spellName, new Effect.Automatic(), List.of(new Effect.DamagePart(
                    new Dice(0, 0, backfire.damage()), backfire.damageType())), null, List.of(), false, null);
            EffectApplier.Use backUse = new EffectApplier.Use(caster, caster.getDisplayName() + "'s " + spellName,
                    CombatActionType.SPELL_CAST, "backfires on", "", spellName, null);
            List<EffectApplier.Applied> applied = new ArrayList<>();
            for (int i = 0; i < count && caster.getLifeState() != LifeState.DEAD; i++) {
                applied.addAll(effectApplier.apply(encounter, backUse, back, resolver.resolve(back,
                        List.of(stats.target(caster, back)))));
            }
            return applied;
        }
        List<EffectApplier.Applied> applied = new ArrayList<>();
        for (int i = 0; i < rolled.size(); i++) {
            if (rolledAt.get(i).getLifeState() == LifeState.DEAD) {
                continue;
            }
            applied.addAll(effectApplier.apply(encounter, use, effect, List.of(rolled.get(i)), rules));
        }
        return applied;
    }

    // Whether the spell is the one the creature's Shield blocks, as Magic Missile is (PHB p.275, D199)
    private boolean blockedByShield(String spellName, EncounterParticipant target) {
        return spellEffects.blockedByShield(spellName) && ActiveModifiers.on(target).stream()
                .anyMatch(found -> SHIELD.equalsIgnoreCase(found.effect().spell()));
    }

    private static final String SHIELD = "Shield";

    /*
     * Chaos Bolt (XGE p.151, D199): a ranged spell attack at each of its targets, and on a hit 2d8 + 1d6 damage, 1d6 more
     * for each slot level above 1st, the dice doubled on a critical hit; the first d8's face gives the type from the
     * template's table (rules call: the caster's choice of die is the first rolled). When the two d8s match, the bolt
     * leaps to the next creature the caster picked, which it hasn't struck yet, with a new attack and damage roll, and
     * can leap again; with none picked, the log leaves the leap to the DM. A creature is struck only once.
     */
    private List<EffectApplier.Applied> typedBolts(Encounter encounter, EncounterParticipant caster,
                                                   EffectApplier.Use use, String spellName, Effect effect,
                                                   SpellEffects.CastRules rules, SpellTemplate.TypeDice typeDice,
                                                   List<EncounterParticipant> targets,
                                                   List<EncounterParticipant> picked) {
        if (!(effect.delivery() instanceof Effect.Attack)) {
            return List.of();
        }
        List<EffectApplier.Applied> applied = new ArrayList<>();
        List<EncounterParticipant> struck = new ArrayList<>(targets);
        List<EncounterParticipant> leaps = new ArrayList<>(picked);
        java.util.Deque<EncounterParticipant> queue = new java.util.ArrayDeque<>(targets);
        Effect attackOnly = new Effect(spellName, effect.delivery(), List.of(), null, List.of(), false, null);
        while (!queue.isEmpty()) {
            EncounterParticipant target = queue.poll();
            if (target.getLifeState() == LifeState.DEAD) {
                continue;
            }
            Resolver.TargetOutcome outcome = resolver.resolve(attackOnly, CreatureStats.side(caster),
                    stats.spellTargets(List.of(target), attackOnly, caster)).getFirst();
            applied.addAll(effectApplier.apply(encounter, use, attackOnly, List.of(outcome), rules));
            if (outcome.outcome() != Outcome.HIT && outcome.outcome() != Outcome.CRITICAL) {
                continue;
            }
            boolean critical = outcome.outcome() == Outcome.CRITICAL;
            List<Integer> faces = new ArrayList<>();
            int total = 0;
            boolean typed = false;
            for (Effect.DamagePart part : effect.damage()) {
                Dice dice = part.dice();
                boolean typing = !typed && dice.sides() == typeDice.sides() && dice.count() >= typeDice.count();
                typed |= typing;
                int count = critical ? dice.count() * 2 : dice.count();
                for (int i = 0; i < count; i++) {
                    int face = resolver.roll(dice.sides());
                    if (typing && i < typeDice.count()) {
                        faces.add(face);
                    }
                    total += face;
                }
                total += dice.modifier();
            }
            String type = faces.isEmpty() ? null : typeDice.types().get(Math.min(faces.getFirst(),
                    typeDice.types().size()) - 1);
            Effect bolt = new Effect(spellName, new Effect.Automatic(),
                    List.of(new Effect.DamagePart(new Dice(0, 0, Math.max(0, total)), type)), null, List.of(), false,
                    null);
            effectApplier.summary(encounter, use, spellName + "'s d" + typeDice.sides() + "s show "
                    + faces.stream().map(String::valueOf).collect(Collectors.joining(" and ")) + ": " + type
                    + " damage" + (critical ? ", its dice doubled" : "") + ".");
            applied.addAll(effectApplier.apply(encounter, use, bolt, resolver.resolve(bolt,
                    CreatureStats.side(caster), stats.spellTargets(List.of(target), bolt, caster)), rules));
            boolean doubled = typeDice.leapsOnDouble() && faces.size() >= 2 && faces.stream().distinct().count() == 1;
            if (!doubled) {
                continue;
            }
            Optional<EncounterParticipant> next = leaps.stream()
                    .filter(creature -> !struck.contains(creature) && creature.getLifeState() != LifeState.DEAD)
                    .findFirst();
            if (next.isPresent()) {
                struck.add(next.get());
                queue.add(next.get());
                effectApplier.summary(encounter, use, spellName + " leaps from " + target.getDisplayName() + " to "
                        + next.get().getDisplayName() + ".");
            } else {
                effectApplier.summary(encounter, use, spellName + " leaps from " + target.getDisplayName()
                        + " to another creature of " + caster.getDisplayName() + "'s choice within 30 feet of it, "
                        + "which it hasn't struck: the DM resolves it, as none was picked.");
            }
        }
        return applied;
    }

    /*
     * Life Transference (XGE p.160, D199): the caster takes the spell's necrotic damage, 4d8 and 1d8 more for each slot
     * level above 3rd, which can't be reduced in any way, so it goes through no resistance or immunity, and each target
     * regains the multiple of what was rolled, twice for Life Transference. Temporary hit points still take it first,
     * as they take any damage (PHB p.198; rules call). What the log adds.
     */
    private String transfer(Encounter encounter, EncounterParticipant caster, String spellName, Effect effect,
                            int multiple, List<EncounterParticipant> targets, int empoweredDice) {
        Effect own = new Effect(spellName, new Effect.Automatic(), effect.damage(), null, List.of(), false, null);
        Resolver.TargetOutcome rolled = resolver.resolve(own, List.of(stats.target(caster, own)), empoweredDice)
                .getFirst();
        int amount = rolled.damage().stream().mapToInt(Resolver.Damage::amount).sum();
        String type = effect.damage().isEmpty() ? null : effect.damage().getFirst().type();
        effectApplier.damage(encounter, caster, caster.getDisplayName() + "'s " + spellName, caster, amount, null);
        String note = caster.getDisplayName() + " takes " + amount + (type != null ? " " + type : "")
                + " damage, which can't be reduced";
        for (EncounterParticipant target : targets) {
            if (target.getLifeState() != LifeState.DEAD) {
                effectApplier.heal(encounter, caster, target, amount * multiple, spellName + ": " + multiple
                        + " times the " + amount + " damage " + caster.getDisplayName() + " took");
                note += ", and " + target.getDisplayName() + " regains up to " + amount * multiple + " hit points";
            }
        }
        return " " + note + ".";
    }

    /**
     * The start of a creature's turn for the triggers the engine applies itself (D199): each Spell Effect whose spell
     * has a trigger at the start of a turn for a condition the creature has from that effect's caster acts on it now,
     * as Searing Smite's flames, a Constitution save against 1d6 fire, a success ending the spell (PHB p.274), and
     * Ensnaring Strike's thorns, 1d6 piercing, more with a higher slot (PHB p.237). The DC is the caster's when cast.
     */
    public void conditionedTriggersAtTurnStart(Encounter encounter, EncounterParticipant creature) {
        if (creature.getLifeState() == LifeState.DEAD || EncounterTurns.dismissed(creature)
                || CombatAccess.banished(creature)) {
            return;
        }
        for (EncounterParticipant caster : List.copyOf(encounter.getParticipants())) {
            for (SpellEffectEntry effect : EffectApplier.effects(caster)) {
                List<SpellTemplate.EffectTrigger> triggers = spellEffects.conditionedTriggers(effect.spell(),
                        SpellTrigger.START_OF_TURN);
                boolean held = triggers.stream().anyMatch(trigger -> hasFrom(creature, trigger.onConditioned(),
                        effect.spell(), caster));
                if (!held || EffectApplier.effects(caster).stream().noneMatch(e -> e.id().equals(effect.id()))) {
                    continue;
                }
                int dc = effect.rider() != null ? effect.rider().saveDc()
                        : caster.getSpellSaveDc() != null ? caster.getSpellSaveDc() : 10;
                try {
                    trigger(encounter, caster, effect, SpellTrigger.START_OF_TURN, List.of(creature),
                            caster.getSpellcastingAbility(),
                            caster.getSpellAttackBonus() != null ? caster.getSpellAttackBonus() : 0, dc);
                } catch (ConflictException unreachable) {
                    // Nothing reaches the creature now, as inside Otiluke's Resilient Sphere (D185); the turn goes on
                    effectApplier.summary(encounter, use(caster, effect.spell(), "", null), effect.spell()
                            + " can't reach " + creature.getDisplayName() + " now: " + unreachable.getMessage());
                }
                if (creature.getLifeState() == LifeState.DEAD) {
                    return;
                }
            }
        }
    }

    /**
     * What a spell that makes a weapon attack does once its attack has resolved (D199): Green-Flame Blade's fire leaps
     * from the creature it hit to the one the caster picked (TCE p.107); Booming Blade sheathes the creature it hit in
     * booming energy until the start of the caster's next turn, a Spell Effect the DM uses on it, as no map shows its
     * moving, dealing its thunder and ending the spell (TCE p.106). What the log adds.
     */
    public String afterWeaponAttack(Encounter encounter, EncounterParticipant caster, String spellName,
                                    EffectApplier.Use use, EffectApplier.Applied attack,
                                    List<EncounterParticipant> picked, String castingAbility, int attackBonus,
                                    int saveDc) {
        boolean hit = attack.outcome().outcome() == Outcome.HIT || attack.outcome().outcome() == Outcome.CRITICAL;
        if (!hit) {
            return "";
        }
        afterAttack(encounter, caster, spellName, 0, use, List.of(attack), picked, castingAbility, attackBonus,
                saveDc);
        if (spellEffects.lasting(spellName, 0).isEmpty() || attack.target().getLifeState() == LifeState.DEAD) {
            return "";
        }
        SpellEffectEntry effect = effectApplier.startEffect(encounter, caster, spellName, null, null,
                spellEffects.templateRounds(spellName), null, true);
        effectApplier.addAffected(caster, effect.id(), List.of(attack.target()));
        String reminder = spellEffects.castReminder(spellName);
        String text = attack.target().getDisplayName() + " is sheathed in " + spellName + " until the start of "
                + caster.getDisplayName() + "'s next turn" + (reminder != null ? ": " + reminder : ".");
        effectApplier.summary(encounter, use, text);
        return " " + text;
    }

    /*
     * What the 5th-level spells' keys add once a cast has resolved (D187): the conditions or exhaustion it ends, as the
     * Restoration spells' (PHB p.255, p.246); the hit points its caster drains, as Enervation's (XGE p.155); control of
     * a creature that failed, as Dominate Person's (PHB p.235); the saves its creatures count, as Contagion's (PHB
     * p.227); the contest it rolls, as Telekinesis's (PHB p.280); and its end when every creature saved against it, as
     * Immolation's (XGE p.158). What the log adds, or nothing.
     */
    private String afterCast(Encounter encounter, EncounterParticipant caster, String spellName, String option,
                             EffectApplier.Use use, ConcentrationInstance instance,
                             List<EncounterParticipant> reached, List<EffectApplier.Applied> applied,
                             List<EncounterParticipant> affected, int saveDc, String castingAbility, int attackBonus) {
        SpellEffects.FifthLevel fifth = spellEffects.fifthLevel(spellName);
        if (fifth.equals(SpellEffects.FifthLevel.NONE)) {
            return "";
        }
        List<String> notes = new ArrayList<>();
        if (!fifth.removesConditions().isEmpty()) {
            notes.add(removeConditions(encounter, caster, spellName, option, fifth.removesConditions(), affected));
        }
        drain(encounter, caster, spellName, fifth, applied);
        Optional<SpellEffectEntry> left = EffectApplier.effects(caster).stream()
                .filter(effect -> effect.spell().equalsIgnoreCase(spellName))
                .reduce((first, second) -> second);
        if (fifth.controlTransfer() && left.isPresent()) {
            effectApplier.takeControl(encounter, caster, left.get().id(), affected, fifth.controlCondition());
        }
        if (fifth.countedSaves() != null && left.isPresent() && !affected.isEmpty()) {
            SpellTemplate.CountedSaves counted = fifth.countedSaves();
            effectApplier.startCountedSave(caster, left.get().id(), new SpellEffectEntry.CountedSave(counted.ability(),
                    saveDc, counted.successes(), counted.failures(), counted.onFailures(), counted.endsConditions(),
                    List.of(), counted.failureCondition()), affected);
            // The failures may leave a condition of their own, as Flesh to Stone's petrified (PHB p.243, D188)
            notes.add(names(affected) + " makes a " + counted.ability() + " save at the end of each of "
                    + (affected.size() == 1 ? "its" : "their") + " turns: " + counted.successes() + " successes end "
                    + spellName + ", " + counted.failures() + " failures "
                    + (counted.failureCondition() != null ? "leave it " + counted.failureCondition()
                            : "make it take hold") + ".");
        }
        if (fifth.contest() != null) {
            notes.add(contest(encounter, caster, spellName, fifth.contest(), use, reached, castingAbility,
                    attackBonus));
        }
        boolean allSaved = !applied.isEmpty() && applied.stream()
                .allMatch(result -> result.outcome().outcome() == Outcome.SAVE_SUCCESS);
        if (fifth.endsOnInitialSave() && allSaved) {
            String why = names(applied.stream().map(EffectApplier.Applied::target).toList()) + " succeeds on the save";
            if (instance != null && caster.getConcentration() != null
                    && instance.id().equals(caster.getConcentration().id())) {
                effectApplier.endConcentration(encounter, caster, spellName + " ends: " + why);
            } else {
                left.ifPresent(effect -> effectApplier.endEffect(encounter, caster, effect.id(), why));
            }
            notes.add(spellName + " ends, as " + why + ".");
        }
        String joined = notes.stream().filter(note -> note != null && !note.isBlank())
                .collect(Collectors.joining(" "));
        return joined.isEmpty() ? "" : " " + joined;
    }

    /*
     * The Restoration spells (D187): the chosen condition ends on each creature reached, whatever gave it, as Lesser
     * Restoration's blinded, deafened, paralyzed or poisoned (PHB p.255) or Greater Restoration's charmed or petrified
     * (PHB p.246); the exhaustion option lowers exhaustion by one level (D183); an option the app doesn't track, as a
     * curse or a disease, is left to the DM with a note.
     */
    private String removeConditions(Encounter encounter, EncounterParticipant caster, String spellName, String option,
                                    List<String> removable, List<EncounterParticipant> creatures) {
        if (option == null) {
            // A spell without options ends every condition it lists, as Heal's blinded and deafened (PHB p.250, D188)
            if (!spellEffects.castOptions(spellName).isEmpty()) {
                return "";
            }
            List<String> ended = new ArrayList<>();
            for (EncounterParticipant creature : creatures) {
                for (String condition : removable) {
                    String lower = condition.toLowerCase(java.util.Locale.ROOT);
                    if (effectApplier.endConditionBySpell(encounter, caster, creature, lower, spellName)) {
                        ended.add(lower + " ends on " + creature.getDisplayName() + ".");
                    }
                }
            }
            return String.join(" ", ended);
        }
        boolean tracked = removable.stream().anyMatch(option::equalsIgnoreCase);
        String lower = option.toLowerCase(java.util.Locale.ROOT);
        List<String> notes = new ArrayList<>();
        for (EncounterParticipant creature : creatures) {
            if (!tracked) {
                notes.add("The DM ends the " + lower + " on " + creature.getDisplayName() + ".");
            } else if (SpellTemplate.EXHAUSTION_OPTION.equalsIgnoreCase(option)) {
                int level = com.tabletophelper.rules.Exhaustion.clamp(creature.getExhaustionLevel());
                if (level > 0) {
                    effectApplier.setExhaustion(encounter, creature, level - 1);
                    notes.add(creature.getDisplayName() + "'s exhaustion drops to level " + (level - 1) + ".");
                } else {
                    notes.add(creature.getDisplayName() + " has no exhaustion to remove.");
                }
            } else if (effectApplier.endConditionBySpell(encounter, caster, creature, lower, spellName)) {
                notes.add(lower + " ends on " + creature.getDisplayName() + ".");
            } else {
                notes.add(creature.getDisplayName() + " isn't " + lower + ".");
            }
        }
        return String.join(" ", notes);
    }

    /*
     * Life drain (D187): the caster regains the template's share of the damage the creatures took, rounded down, as
     * Enervation's and Vampiric Touch's half of the necrotic damage dealt (XGE p.155, PHB p.285).
     */
    private void drain(Encounter encounter, EncounterParticipant caster, String spellName,
                       SpellEffects.FifthLevel fifth, List<EffectApplier.Applied> applied) {
        int dealt = applied.stream().mapToInt(EffectApplier.Applied::damageTaken).sum();
        int regained = (int) Math.floor(dealt * fifth.lifeDrain());
        if (regained > 0 && caster.getLifeState() != LifeState.DEAD) {
            effectApplier.heal(encounter, caster, caster, regained, spellName + ": half the damage dealt");
        }
    }

    /*
     * An ability check contest (D187), as Telekinesis's (PHB p.280): the caster's spellcasting ability check against
     * each creature's check of the template's ability; a tie leaves things as they were (PHB p.174). A creature the
     * caster beats gets the template's condition until the end of the caster's next turn.
     */
    private String contest(Encounter encounter, EncounterParticipant caster, String spellName,
                           SpellTemplate.Contest contest, EffectApplier.Use use, List<EncounterParticipant> creatures,
                           String castingAbility, int attackBonus) {
        com.tabletophelper.rules.Ability ability = com.tabletophelper.rules.Ability.parse(contest.targetAbility())
                .orElse(com.tabletophelper.rules.Ability.STR);
        int casterModifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);
        List<String> notes = new ArrayList<>();
        List<EncounterParticipant> beaten = new ArrayList<>();
        for (EncounterParticipant creature : creatures) {
            if (creature.getLifeState() == LifeState.DEAD) {
                continue;
            }
            int casterRoll = resolver.d20();
            int creatureRoll = resolver.d20();
            int creatureModifier = com.tabletophelper.rules.Ability.modifier(stats.abilityScore(creature, ability));
            int casterTotal = casterRoll + casterModifier;
            int creatureTotal = creatureRoll + creatureModifier;
            boolean won = casterTotal > creatureTotal;
            String outcome = won ? " - " + caster.getDisplayName() + " wins"
                    + (contest.winNote() != null ? ": " + contest.winNote() : "")
                    : casterTotal == creatureTotal ? " - a tie, nothing changes"
                    : " - " + creature.getDisplayName() + " wins";
            String text = "Contest: " + caster.getDisplayName() + " d20(" + casterRoll + ")" + signed(casterModifier)
                    + " = " + casterTotal + " vs " + creature.getDisplayName() + "'s " + ability.name() + " d20("
                    + creatureRoll + ")" + signed(creatureModifier) + " = " + creatureTotal + outcome + ".";
            effectApplier.summary(encounter, use, text);
            notes.add(text);
            if (won) {
                beaten.add(creature);
            }
        }
        if (contest.condition() != null && !beaten.isEmpty()) {
            String condition = contest.condition().toLowerCase(java.util.Locale.ROOT);
            Effect held = new Effect(spellName, new Effect.Automatic(), List.of(), null, List.of(condition), false,
                    null, Map.of(condition, new Effect.ConditionEnd(null, null, null, true)));
            effectApplier.apply(encounter, use, held, resolver.resolve(held, CreatureStats.side(caster),
                    stats.spellTargets(beaten, held, caster)));
        }
        return String.join(" ", notes);
    }

    private static String signed(int value) {
        return value >= 0 ? " + " + value : " - " + (-value);
    }

    /**
     * Refuses a target the spell can't affect by its creature type, as Dominate Beast's non-beast (PHB p.234, D185):
     * checked before anything is spent, and again as the spell is cast.
     *
     * @throws com.tabletophelper.shared.ValidationException for a target of another type
     */
    public void requireTargetTypes(String spellName, List<EncounterParticipant> targets) {
        List<String> allowed = spellEffects.typeRules(spellName).targetTypes();
        if (allowed.isEmpty()) {
            return;
        }
        for (EncounterParticipant target : targets) {
            if (!ofType(target, allowed)) {
                String type = CreatureStats.typeForSpells(target);
                throw new com.tabletophelper.shared.ValidationException(spellName + " can only target "
                        + String.join(" or ", allowed) + " creatures, and " + target.getDisplayName() + " is "
                        + (type != null ? "a " + type : "of no known type"));
            }
        }
    }

    // Whether the creature is of one of the types, its type read as the spell rules read it (D185)
    private static boolean ofType(EncounterParticipant creature, List<String> types) {
        if (types.isEmpty()) {
            return false;
        }
        String type = CreatureStats.typeForSpells(creature);
        return type != null && types.stream().anyMatch(wanted -> type.contains(wanted.toLowerCase(java.util.Locale.ROOT)));
    }

    // The Effect with every damage die at its highest, as Blight deals a plant (PHB p.219, D185)
    static Effect maximumDamage(Effect effect) {
        List<Effect.DamagePart> parts = effect.damage().stream()
                .map(part -> new Effect.DamagePart(new Dice(0, 0,
                        Math.max(0, part.dice().count() * part.dice().sides() + part.dice().modifier())), part.type()))
                .toList();
        return new Effect(effect.name(), effect.delivery(), parts, effect.healing(), effect.conditions(),
                effect.concentration(), effect.durationRounds(), effect.conditionEnds());
    }

    // ── The 6th-level spells' rules (D188) ───────────────────────

    /**
     * Refuses a cast by a caster whose own Spell Effect forbids casting, as Tenser's Transformation does (XGE p.168,
     * D188).
     *
     * @throws ConflictException while such an effect lasts
     */
    public void requireCanCast(EncounterParticipant caster) {
        for (SpellEffectEntry effect : EffectApplier.effects(caster)) {
            if (spellEffects.castRules(effect.spell()).blocksCasting()) {
                throw new ConflictException(caster.getDisplayName() + " can't cast spells while " + effect.spell()
                        + " lasts");
            }
        }
    }

    /**
     * The Globe of Invulnerability that keeps a spell of this level from the target (PHB p.245, D188), or null: a
     * modifier on it that blocks spells of that level or lower, from a Spell Effect its caster isn't under too, since a
     * spell cast from inside the barrier reaches the creatures inside. With no map, the creatures the globe's caster chose
     * are those inside. A spell's level is its own, whatever slot it was cast with ("even if cast using a higher level
     * spell slot").
     */
    static SpellEffectEntry globe(EncounterParticipant caster, EncounterParticipant target, int spellLevel) {
        if (caster == target) {
            return null;
        }
        return ActiveModifiers.on(target).stream()
                .filter(found -> found.modifier().kind() == OngoingModifier.Kind.SPELL_IMMUNITY
                        && spellLevel <= found.modifier().flat()
                        && !found.effect().affected().contains(caster.getId()))
                .map(ActiveModifiers.Found::effect)
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether something keeps a spell of this level from the target (D191): a Globe of Invulnerability it is inside
     * (PHB p.245, D188), or its own trait, as a rakshasa's Limited Magic Immunity (MM p.257), unless the trait lets it
     * choose to be affected and it does (D198), by the choice made for this cast or its standing one.
     *
     * @param chosen the choice made for this cast by whoever controls the target, or null for none
     */
    boolean shielded(EncounterParticipant caster, EncounterParticipant target, String spellName, int spellLevel,
                     Boolean chosen) {
        if (globe(caster, target, spellLevel) != null) {
            return true;
        }
        return stats.spellImmunityTrait(caster, target, spellLevel) != null
                && !stats.choosesToBeAffected(target, chosen, spellEffects.harmful(spellName));
    }

    // What the log adds for the creatures a Globe of Invulnerability or a trait kept the spell from
    private String globeNote(String spellName, EncounterParticipant caster, List<EncounterParticipant> globed,
                             int spellLevel) {
        if (globed.isEmpty()) {
            return "";
        }
        List<EncounterParticipant> inside = globed.stream()
                .filter(target -> globe(caster, target, spellLevel) != null)
                .toList();
        StringBuilder note = new StringBuilder();
        if (!inside.isEmpty()) {
            SpellEffectEntry first = globe(caster, inside.getFirst(), spellLevel);
            note.append(" ").append(spellName).append(" has no effect on ").append(names(inside)).append(", inside ")
                    .append(first != null ? first.spell() : "a globe").append(" (it was cast from outside).");
        }
        for (EncounterParticipant target : globed) {
            if (!inside.contains(target)) {
                note.append(" ").append(spellName).append(" has no effect on ").append(target.getDisplayName())
                        .append(" (").append(stats.spellImmunityTrait(caster, target, spellLevel))
                        .append(").");
            }
        }
        return note.toString();
    }

    // A reminder for a creature whose Reflective Carapace may turn the spell back (MM p.286, D191): the tarrasque
    private String carapaceNote(String spellName, List<EncounterParticipant> targets) {
        List<EncounterParticipant> carapaced = targets.stream()
                .filter(target -> stats.traits(target).stream()
                        .anyMatch(trait -> trait.name().equalsIgnoreCase(REFLECTIVE_CARAPACE)))
                .toList();
        return carapaced.isEmpty() ? "" : " Reminder: " + names(carapaced) + "'s " + REFLECTIVE_CARAPACE
                + " — if " + spellName + " is Magic Missile, a line spell or a ranged spell attack, roll a d6: on 1-5 it"
                + " is unaffected, and on a 6 the spell is reflected back at its caster (the DM resolves it).";
    }

    // The tarrasque's trait against Magic Missile, line spells and ranged spell attacks (MM p.286)
    private static final String REFLECTIVE_CARAPACE = "Reflective Carapace";

    // What the log adds for the creatures immune to the spell through a condition immunity
    private static String immuneNote(String spellName, List<EncounterParticipant> immune, String condition) {
        return immune.isEmpty() ? "" : " " + names(immune) + (immune.size() == 1 ? " is" : " are")
                + " immune to " + spellName + " (can't be " + condition + ").";
    }

    // The target's save as the spell's rules change it: an automatic success for a creature immune to the condition the
    // spell names, as against Mental Prison (XGE p.161), and disadvantage for its types, as Sunbeam's undead (PHB p.279)
    private Resolver.Target withCastRules(Resolver.Target target, List<EncounterParticipant> creatures,
                                          SpellEffects.CastRules rules) {
        EncounterParticipant creature = creatures.stream().filter(c -> c.getId().equals(target.id())).findFirst()
                .orElse(null);
        if (creature == null) {
            return target;
        }
        Resolver.Target changed = target;
        if (rules.succeedsIfImmuneTo() != null && stats.immuneToCondition(creature, rules.succeedsIfImmuneTo(), true)) {
            changed = changed.succeedingAutomatically();
        }
        if (ofType(creature, rules.saveDisadvantageTypes())) {
            changed = changed.withSaveSource(RollMode.DISADVANTAGE);
        }
        return changed;
    }

    /*
     * What the spell's rules do once it is applied (D188): Harm lowers the maximum of a creature that failed its save by
     * the damage it took, for an hour (PHB p.249).
     */
    private void afterSixthLevelRules(Encounter encounter, EncounterParticipant caster, String spellName,
                                      int slotLevel, SpellEffects.CastRules rules,
                                      List<EffectApplier.Applied> applied) {
        for (EffectApplier.Applied result : applied) {
            EncounterParticipant target = result.target();
            Outcome outcome = result.outcome().outcome();
            if (rules.reducesHpMax() && outcome == Outcome.SAVE_FAIL && result.damageTaken() > 0) {
                effectApplier.reduceHpMax(encounter, caster, spellName, slotLevel > 0 ? slotLevel : null, target,
                        result.damageTaken(), rules.hpMaxRounds());
            }
        }
    }

    // The creatures a Globe of Invulnerability keeps a use of the spell's lasting effect from, logged (PHB p.245, D188)
    private List<EncounterParticipant> outsideGlobes(Encounter encounter, EffectApplier.Use use,
                                                     EncounterParticipant caster, String spellName, int spellLevel,
                                                     List<EncounterParticipant> targets) {
        List<EncounterParticipant> globed = targets.stream()
                .filter(target -> shielded(caster, target, spellName, spellLevel, null))
                .toList();
        if (!globed.isEmpty()) {
            effectApplier.summary(encounter, use, globeNote(spellName, caster, globed, spellLevel).trim());
        }
        return targets.stream().filter(target -> !globed.contains(target)).toList();
    }

    // What the log adds for the creatures the spell's type rules touched
    private static String typeNote(String spellName, List<EncounterParticipant> unaffected,
                                   List<EncounterParticipant> maximised) {
        StringBuilder note = new StringBuilder();
        if (!unaffected.isEmpty()) {
            note.append(' ').append(spellName).append(" has no effect on ").append(unaffected.stream()
                    .map(target -> target.getDisplayName() + " (" + CreatureStats.typeForSpells(target) + ")")
                    .collect(Collectors.joining(", "))).append('.');
        }
        if (!maximised.isEmpty()) {
            note.append(' ').append(maximised.stream().map(EncounterParticipant::getDisplayName)
                            .collect(Collectors.joining(", ")))
                    .append(" saves with disadvantage and takes maximum damage (")
                    .append(CreatureStats.typeForSpells(maximised.getFirst())).append(").");
        }
        return note.toString();
    }

    /**
     * The Ongoing Modifiers a cast leaves (D184), on the creatures it affected, with the save DC a repeated save is
     * made against.
     */
    record ModifierCast(SpellEffects.ModifierPlan plan, List<EncounterParticipant> affected, int saveDc) {
        static final ModifierCast NONE = new ModifierCast(SpellEffects.ModifierPlan.NONE, List.of(), 0);
    }

    /**
     * Starts a spell that is already going on its caster without casting it (D184): the DM's record of a spell cast
     * before the encounter or before initiative, such as Mage Armor or Bless. No slot is spent and nothing is rolled;
     * a concentration spell starts the caster's concentration, ending any other, and a lasting spell or one with
     * Ongoing Modifiers leaves its Spell Effect, on the targets, or on the caster for a spell on the caster.
     */
    public String startActive(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                              List<EncounterParticipant> targets, String option) {
        boolean concentration = spellEffects.concentration(spellName);
        Integer rounds = spellEffects.durationOf(spellName);
        ConcentrationInstance instance = concentration
                ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel > 0 ? slotLevel : null,
                        rounds)
                : null;
        List<EncounterParticipant> affected = spellEffects.selfOnly(spellName) ? List.of(caster) : targets;
        int spellcastingModifier = stats.spellcastingModifier(caster, caster.getSpellcastingAbility(),
                caster.getSpellAttackBonus() != null ? caster.getSpellAttackBonus() : 0);
        SpellEffects.ModifierPlan modifiers = spellEffects.modifiers(spellName, slotLevel, option,
                spellcastingModifier, affected.isEmpty() ? null : affected.getFirst());
        String summary = "DM: " + caster.getDisplayName() + "'s " + spellName
                + (option != null ? " (" + option + ")" : "") + " is already active"
                + (slotLevel > 0 ? " at level " + slotLevel : "")
                + (concentration ? ", and " + caster.getDisplayName() + " is concentrating on it" : "") + ".";
        effectApplier.summary(encounter, use(caster, spellName, "", instance), summary);
        String note = leaveEffect(encounter, caster, spellName, slotLevel, instance, false, MetamagicCasting.Plan.NONE,
                new ModifierCast(modifiers, affected,
                        caster.getSpellSaveDc() != null ? caster.getSpellSaveDc() : 10),
                spellEffects.later(spellName, slotLevel, option), List.of(), 0);
        return summary + note;
    }

    // Careful Spell's creatures succeed on the save, and Heightened Spell's rolls its first with disadvantage
    // (PHB p.102); a repeated save at the end of a turn is a later one
    private static Resolver.Target withMetamagic(Resolver.Target target, MetamagicCasting.Plan metamagic) {
        Resolver.Target changed = target;
        if (metamagic.careful().contains(target.id())) {
            changed = changed.succeedingAutomatically();
        }
        if (target.id().equals(metamagic.heightened())) {
            changed = changed.withSaveSource(RollMode.DISADVANTAGE);
        }
        return changed;
    }

    /**
     * Extended Spell (PHB p.102): the spell's duration doubled to at most 24 hours, and with it its concentration and
     * each condition that lasts as long as the spell.
     */
    static SpellEffects.SpellPlan extended(SpellEffects.SpellPlan plan) {
        Integer rounds = plan.durationRounds();
        if (rounds == null) {
            return plan;
        }
        int doubled = Metamagic.extendedRounds(rounds);
        Effect effect = plan.effect();
        if (effect != null) {
            Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
            effect.conditionEnds().forEach((name, end) -> ends.put(name, rounds.equals(end.durationRounds())
                    ? end.lasting(doubled) : end));
            effect = new Effect(effect.name(), effect.delivery(), effect.damage(), effect.healing(),
                    effect.conditions(), effect.concentration(),
                    effect.durationRounds() != null ? Metamagic.extendedRounds(effect.durationRounds()) : null, ends);
        }
        return new SpellEffects.SpellPlan(plan.spellName(), plan.spellLevel(), effect, plan.manualReason(),
                plan.concentration(), doubled, plan.verbal());
    }

    // What the DM applies of the Metamagic on a spell they resolve by hand
    private static String forTheDm(MetamagicCasting.Plan metamagic, List<EncounterParticipant> targets) {
        List<String> notes = new ArrayList<>();
        if (!metamagic.careful().isEmpty()) {
            notes.add("Careful Spell: " + names(targets, metamagic.careful()) + " succeed on the save");
        }
        if (metamagic.heightened() != null) {
            notes.add("Heightened Spell: " + names(targets, Set.of(metamagic.heightened()))
                    + " has disadvantage on the first save");
        }
        if (metamagic.empoweredDice() > 0) {
            notes.add("Empowered Spell: reroll up to " + metamagic.empoweredDice() + " damage dice");
        }
        return notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")";
    }

    private static String names(List<EncounterParticipant> targets, Set<UUID> ids) {
        return targets.stream().filter(target -> ids.contains(target.getId()))
                .map(EncounterParticipant::getDisplayName).collect(Collectors.joining(", "));
    }

    /**
     * The Spell Effect a lasting spell leaves on its caster (M15), linked to the Concentration Instance the cast
     * started. A cast that sent something at creatures has spent one of the effect's uses, such as the first of Melf's
     * Minute Meteors (XGE p.161). A spell with Ongoing Modifiers that affected anyone leaves one too, holding them
     * (D184); one kept up without concentration leaves an earlier casting's alone, as a second Mage Armor on another
     * creature does. What the log adds for the modifiers, or nothing.
     */
    private String leaveEffect(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                               ConcentrationInstance instance, boolean spentOne, MetamagicCasting.Plan metamagic,
                               ModifierCast modifiers, SpellEffects.Later later, List<EncounterParticipant> delayedOn,
                               int dealt) {
        Optional<SpellEffects.Lasting> lasting = spellEffects.lasting(spellName, slotLevel);
        SpellEffects.ModifierPlan plan = modifiers.plan();
        // A lasting spell keeps its modifiers for the creatures its triggers bring under it later, as Sickening
        // Radiance's light (XGE p.164, D185)
        boolean withModifiers = plan.any() && (!modifiers.affected().isEmpty() || lasting.isPresent());
        String notes = plan.notes().isEmpty() ? "" : " " + String.join(". ", plan.notes()) + ".";
        boolean forLater = later.damageBudget() != null || later.retaliationDice() != null
                || later.retaliationSave() != null
                || later.turnReminder() != null || spellEffects.fifthLevel(spellName).keepsEffect()
                || spellEffects.seventhLevel(spellName).keepsEffect()
                || spellEffects.highLevel(spellName).keepsEffect();
        if (lasting.isEmpty() && !withModifiers && !forLater && delayedOn.isEmpty()) {
            return notes;
        }
        if (lasting.isEmpty() && !withModifiers && !forLater) {
            // An instantaneous spell's damage still to come, which ends its Spell Effect once dealt; two rounds always
            // cover the end of a creature's next turn (D185)
            SpellEffectEntry effect = effectApplier.startEffect(encounter, caster, spellName,
                    slotLevel > 0 ? slotLevel : null, null, 2, null, false);
            delayedOn.forEach(target -> effectApplier.delay(caster, effect.id(), target, later.delayedDice(),
                    later.delayedType()));
            return notes + " " + names(delayedOn) + " will take " + later.delayedDice() + " "
                    + later.delayedType() + " damage at the end of " + (delayedOn.size() == 1 ? "its" : "their")
                    + " next turn.";
        }
        // A spell whose modifiers count down, as Flame Arrows' ammunition (XGE p.156, D197), keeps its count too
        Integer uses = lasting.isPresent() ? lasting.get().uses() : spellEffects.modifierUses(spellName, slotLevel);
        if (uses != null && spentOne && lasting.isPresent()) {
            uses = Math.max(0, uses - 1);
        }
        // Modifiers that each end by a turn of their own, as Mind Sliver's at the end of its caster's next turn (TCE
        // p.108), leave the effect to end with them rather than by the spell's rounds
        boolean endsByTurns = !plan.modifiers().isEmpty() && plan.modifiers().stream().allMatch(m -> m.until() != null);
        Integer rounds = lasting.isPresent() ? lasting.get().durationRounds()
                : endsByTurns ? null : plan.durationRounds() != null ? plan.durationRounds()
                : spellEffects.durationOf(spellName);
        if (rounds != null && metamagic.has(Metamagic.Option.EXTENDED)) {
            rounds = Metamagic.extendedRounds(rounds);
        }
        SpellEffectEntry effect = effectApplier.startEffect(encounter, caster, spellName,
                slotLevel > 0 ? slotLevel : null, instance, rounds, uses, lasting.isPresent());
        // What the effect keeps for later: Guardian of Faith's damage budget, Fire Shield's retaliation, the Faithful
        // Hound's reminder, and damage still to come (D185)
        if (forLater) {
            effectApplier.startLater(caster, effect.id(), later.damageBudget(), later.retaliation(modifiers.saveDc()),
                    later.turnReminder());
        }
        delayedOn.forEach(target -> effectApplier.delay(caster, effect.id(), target, later.delayedDice(),
                later.delayedType()));
        if (!withModifiers) {
            effectApplier.spendBudget(encounter, caster, effect.id(), dealt);
            return notes;
        }
        SpellEffectEntry.EffectSave save = plan.repeatSave() != null
                ? new SpellEffectEntry.EffectSave(plan.repeatSave(), modifiers.saveDc()) : null;
        // A spell whose effect outlives its spent rider, as Banishing Smite's banishment (PHB p.216, D187), doesn't end
        // with its last modifier, nor does one whose rider's follow-up decides whether it goes on, as Wrathful Smite's
        // fear (PHB p.289, D199)
        effectApplier.startModifiers(encounter, caster, effect.id(), modifiers.affected(), plan.modifiers(),
                plan.option(), save, plan.endReminder(),
                lasting.isEmpty() && !spellEffects.fifthLevel(spellName).keepsEffect()
                        && !spellEffects.hitRider(spellName));
        effectApplier.spendBudget(encounter, caster, effect.id(), dealt);
        if (modifiers.affected().isEmpty()) {
            return notes;
        }
        String label = ActiveModifiers.label(effect.withModifiers(List.of(), List.of(), plan.option(), null, null,
                false));
        String text = label + ": " + plan.modifiers().stream().map(OngoingModifier::describe)
                .collect(Collectors.joining("; ")) + "." + (plan.reminder() != null ? " " + plan.reminder() : "");
        effectApplier.summary(encounter, use(caster, spellName, "", instance), text);
        return notes + " " + text;
    }

    // The creatures' names, joined
    private static String names(List<EncounterParticipant> creatures) {
        return creatures.stream().map(EncounterParticipant::getDisplayName).collect(Collectors.joining(", "));
    }

    /**
     * The caster's use of one of their Spell Effects on a later turn (M15), such as Spiritual Weapon's attack or
     * Flaming Sphere's ram, rolled `times` times against the same targets, once for each of the uses it spends, such
     * as each of two meteors; any conditions it gives belong to the effect's Concentration Instance (D162). With no
     * targets it resolves nothing, as when the weapon or the sphere only moves. costNote is as for {@link #cast}.
     */
    public CastResult repeat(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                             List<EncounterParticipant> targets, String castingAbility, int attackBonus, int saveDc,
                             Boolean advantage, String costNote, int times) {
        return repeat(encounter, caster, spellEffect, targets, castingAbility, attackBonus, saveDc, advantage,
                costNote, times, null);
    }

    /**
     * As {@link #repeat(Encounter, EncounterParticipant, SpellEffectEntry, List, String, int, int, Boolean, String,
     * int)}, with the option chosen for this use, as Eyebite's effect for each new creature (PHB p.238, D188). A creature
     * inside a Globe of Invulnerability that keeps out the spell's level is left alone (PHB p.245), and the spell's
     * creature types save with disadvantage, as Sunbeam's undead (PHB p.279).
     */
    public CastResult repeat(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                             List<EncounterParticipant> targets, String castingAbility, int attackBonus, int saveDc,
                             Boolean advantage, String costNote, int times, String option) {
        if (targets.isEmpty()) {
            return move(encounter, caster, spellEffect, costNote);
        }
        String spellName = spellEffect.spell();
        SpellEffects.SpellPlan plan = spellEffects.forRepeat(spellName, spellEffect.castLevel(), caster,
                castingAbility, attackBonus, saveDc, advantage, option);
        SpellEffects.CastRules rules = spellEffects.castRules(spellName);
        EffectApplier.Use use = new EffectApplier.Use(caster, caster.getDisplayName(),
                CombatActionType.SPELL_EFFECT_REPEAT, "uses " + spellName + costNote, "on", spellName,
                instanceOf(caster, spellEffect));
        String lead = caster.getDisplayName() + " uses " + spellName + "'s effect" + costNote
                + (times > 1 ? " " + times + " times" : "");

        if (!plan.automated()) {
            String summary = lead + " — " + plan.manualReason();
            effectApplier.summary(encounter, use, summary);
            return new CastResult(spellName, plan.spellLevel(), false, plan.manualReason(), summary, List.of());
        }
        CombatAccess.requireNotDead(targets);
        CombatAccess.requireReachable(caster, targets);
        Effect effect = lifeDomainHealing(caster, plan.effect(), plan.spellLevel(), spellEffect.castLevel());
        lead += lifeDomainNote(caster, plan.effect(), plan.spellLevel(), spellEffect.castLevel());
        // A spell whose rounds each bring their own damage, as Storm of Vengeance's (PHB p.279, D195)
        Map<Integer, SpellTemplate.RoundEffect> byRound = spellEffects.roundEffects(spellName);
        if (!byRound.isEmpty()) {
            int round = (encounter.getRoundNumber() != null ? encounter.getRoundNumber() : 1)
                    - spellEffect.startedRound() + 1;
            effect = roundEffect(spellName, round, byRound.get(round), saveDc, targets.size());
            lead += " (round " + round + ")";
        }
        effectApplier.summary(encounter, use, lead + ".");
        SpellEffects.FifthLevel fifth = spellEffects.fifthLevel(spellName);
        // A use that ends the spell leaves its conditions on their own durations, as Holy Weapon's burst's blindness
        // outlasts the spell it ends (XGE p.157, D187)
        EffectApplier.Use applying = fifth.repeatEndsSpell() ? use.withoutInstance() : use;
        List<EncounterParticipant> outside = outsideGlobes(encounter, use, caster, spellName, plan.spellLevel(),
                targets);
        List<EffectApplier.Applied> applied = new ArrayList<>();
        SpellTemplate.Layers layers = spellEffects.layers(spellName);
        for (int i = 0; i < times; i++) {
            if (layers != null) {
                // Passing through the layers in order, as Prismatic Wall's (PHB p.267, D195)
                applied.addAll(layers(encounter, caster, applying, spellName, layers, outside, saveDc));
                continue;
            }
            List<EncounterParticipant> standing = outside.stream()
                    .filter(target -> target.getLifeState() != LifeState.DEAD)
                    .toList();
            List<Resolver.Target> resolvedAgainst = stats.spellTargets(standing, effect, caster).stream()
                    .map(target -> withCastRules(target, standing, rules))
                    .toList();
            applied.addAll(effectApplier.apply(encounter, applying, effect,
                    resolver.resolve(effect, CreatureStats.side(caster), resolvedAgainst), rules));
        }
        afterSixthLevelRules(encounter, caster, spellName, spellEffect.castLevel(), rules, applied);
        concentrationSaves(encounter, caster, use, spellName, saveDc, outside);
        drain(encounter, caster, spellName, fifth, applied);
        String extra = fifth.contest() != null
                ? " " + contest(encounter, caster, spellName, fifth.contest(), use, targets, castingAbility, attackBonus)
                : "";
        if (fifth.repeatEndsSpell()) {
            ConcentrationInstance held = instanceOf(caster, spellEffect);
            if (held != null) {
                effectApplier.endConcentration(encounter, caster, spellName + " ends: its last use");
            } else {
                effectApplier.endEffect(encounter, caster, spellEffect.id(), "its last use");
            }
            extra += " " + spellName + " ends.";
        }
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied) + extra,
                outcomes(effect, applied));
    }

    /**
     * Disciple of Life (Life Domain 1, PHB p.60): a healing spell of 1st level or higher cast by a cleric with the
     * feature restores 2 + the spell's level more hit points to each creature it heals, the level being the slot's.
     * The Effect rolls its healing once for every target (D161), so the bonus goes on the dice. The Effect unchanged
     * for anything else. A cleric has the feature from Life Domain's 1st level: the subclass data gives that level
     * only a feature named "Life Domain", so no sheet has one named Disciple of Life unless it was entered by hand,
     * which counts too.
     */
    static Effect discipleOfLife(EncounterParticipant caster, Effect effect, int spellLevel, int slotLevel) {
        if (effect.healing() == null || spellLevel < 1 || !hasDiscipleOfLife(caster)) {
            return effect;
        }
        int bonus = ClassFeatures.discipleOfLife(Math.max(spellLevel, slotLevel));
        Dice healing = effect.healing();
        return new Effect(effect.name(), effect.delivery(), effect.damage(),
                new Dice(healing.count(), healing.sides(), healing.modifier() + bonus), effect.conditions(),
                effect.concentration(), effect.durationRounds(), effect.conditionEnds());
    }

    // A Life Domain cleric of 1st level or higher (PHB p.60), or a sheet with the feature by name
    private static boolean hasDiscipleOfLife(EncounterParticipant caster) {
        return FeatureEffects.subclassLevel(caster.getCharacter(), ClassFeatures.CLERIC, ClassFeatures.LIFE_DOMAIN)
                >= ClassFeatures.DISCIPLE_OF_LIFE_LEVEL
                || FeatureEffects.hasFeature(caster.getCharacter(), ClassFeatures.DISCIPLE_OF_LIFE);
    }

    /**
     * Supreme Healing (Life Domain 17, PHB p.60): the dice a spell cast by a cleric with the feature rolls to restore hit
     * points use their highest number instead, so the healing is a fixed amount, Disciple of Life's bonus included. Any
     * spell's healing dice count, a cantrip's too, unlike Disciple of Life's. The Effect unchanged for anything else.
     */
    static Effect supremeHealing(EncounterParticipant caster, Effect effect) {
        if (effect.healing() == null || effect.healing().count() == 0 || !hasSupremeHealing(caster)) {
            return effect;
        }
        return new Effect(effect.name(), effect.delivery(), effect.damage(),
                ClassFeatures.supremeHealing(effect.healing()), effect.conditions(), effect.concentration(),
                effect.durationRounds(), effect.conditionEnds());
    }

    // A Life Domain cleric of 17th level or higher (PHB p.60), or a sheet with the feature by name
    private static boolean hasSupremeHealing(EncounterParticipant caster) {
        return FeatureEffects.subclassLevel(caster.getCharacter(), ClassFeatures.CLERIC, ClassFeatures.LIFE_DOMAIN)
                >= ClassFeatures.SUPREME_HEALING_LEVEL
                || FeatureEffects.hasFeature(caster.getCharacter(), ClassFeatures.SUPREME_HEALING);
    }

    // The Life Domain's healing: Disciple of Life's bonus, then Supreme Healing's maximum dice
    private static Effect lifeDomainHealing(EncounterParticipant caster, Effect effect, int spellLevel, int slotLevel) {
        return supremeHealing(caster, discipleOfLife(caster, effect, spellLevel, slotLevel));
    }

    // What the log adds for the Life Domain's healing, such as " (Disciple of Life +4; Supreme Healing: 2d8 as 16)"
    private static String lifeDomainNote(EncounterParticipant caster, Effect effect, int spellLevel, int slotLevel) {
        Effect disciple = discipleOfLife(caster, effect, spellLevel, slotLevel);
        Effect supreme = supremeHealing(caster, disciple);
        List<String> notes = new ArrayList<>();
        if (disciple != effect) {
            notes.add(ClassFeatures.DISCIPLE_OF_LIFE + " +"
                    + (disciple.healing().modifier() - effect.healing().modifier()));
        }
        if (supreme != disciple) {
            Dice dice = disciple.healing();
            notes.add(ClassFeatures.SUPREME_HEALING + ": " + dice.count() + "d" + dice.sides() + " as "
                    + dice.count() * dice.sides());
        }
        return notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")";
    }

    /**
     * The caster's use of a Spell Effect that only moves it (M15), as Moonbeam's action moves the beam and deals no
     * damage of its own (PHB p.261): logged, and nothing rolled.
     */
    public CastResult move(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                           String costNote) {
        String summary = caster.getDisplayName() + " moves " + spellEffect.spell() + costNote + ".";
        effectApplier.summary(encounter, new EffectApplier.Use(caster, caster.getDisplayName(),
                CombatActionType.SPELL_EFFECT_REPEAT, "moves " + spellEffect.spell() + costNote, "on",
                spellEffect.spell(), instanceOf(caster, spellEffect)), summary);
        return new CastResult(spellEffect.spell(), 0, false, null, summary, List.of());
    }

    /**
     * What a Spell Effect's area does by itself to creatures at a moment of a turn (M15), such as Moonbeam's damage
     * to a creature that starts its turn in the beam (PHB p.261), which the DM applies: it costs its caster nothing
     * and gives no conditions.
     */
    public CastResult trigger(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                              SpellTrigger when, List<EncounterParticipant> targets, String castingAbility,
                              int attackBonus, int saveDc) {
        String spellName = spellEffect.spell();
        SpellEffects.SpellPlan plan = spellEffects.forTrigger(spellName, when, spellEffect.castLevel(), caster,
                castingAbility, attackBonus, saveDc);
        // A trigger on the creatures with its condition acts at their own turn, there being no area (D199)
        boolean onCondition = !spellEffects.conditionedTriggers(spellName, when).isEmpty();
        String moment = onCondition ? when == SpellTrigger.END_OF_TURN ? "at the end of its turn"
                : "at the start of its turn" : switch (when) {
            case ENTER -> "entering it";
            case START_OF_TURN -> "starting a turn in it";
            case END_OF_TURN -> "ending a turn in it";
        };
        EffectApplier.Use use = new EffectApplier.Use(caster, caster.getDisplayName(),
                CombatActionType.SPELL_EFFECT_REPEAT, spellName + " (" + moment + ")", "on", spellName,
                instanceOf(caster, spellEffect));
        String lead = caster.getDisplayName() + "'s " + spellName + " acts on "
                + (onCondition ? names(targets) : "a creature") + " " + moment;

        if (!plan.automated()) {
            String summary = lead + " — " + plan.manualReason();
            effectApplier.summary(encounter, use, summary);
            return new CastResult(spellName, plan.spellLevel(), false, plan.manualReason(), summary, List.of());
        }
        CombatAccess.requireNotDead(targets);
        CombatAccess.requireReachable(caster, targets);
        effectApplier.summary(encounter, use, lead + ".");
        // A Globe of Invulnerability keeps the spell's area out too (PHB p.245, D188)
        List<EncounterParticipant> outside = outsideGlobes(encounter, use, caster, spellName, plan.spellLevel(),
                targets);
        // A creature that already has the trigger's condition from this spell takes its damage with no save, as one
        // Evard's Black Tentacles already restrain (PHB p.238, D185)
        Optional<SpellTemplate.EffectTrigger> trigger = spellEffects.trigger(spellName, when);
        String held = trigger.map(SpellTemplate.EffectTrigger::whenConditioned).orElse(null);
        List<EncounterParticipant> conditioned = held == null ? List.of() : outside.stream()
                .filter(target -> hasFrom(target, held, spellName, caster))
                .toList();
        List<EncounterParticipant> saving = outside.stream().filter(target -> !conditioned.contains(target)).toList();
        List<EffectApplier.Applied> applied = new ArrayList<>();
        // The cast rules hold for what the area does too, as Dark Star's disintegrating a creature its damage leaves at 0
        // hit points (EGW p.186, D190)
        SpellEffects.CastRules triggerRules = spellEffects.castRules(spellName);
        if (!saving.isEmpty() || conditioned.isEmpty()) {
            applied.addAll(effectApplier.apply(encounter, use, plan.effect(),
                    resolver.resolve(plan.effect(), CreatureStats.side(caster),
                            stats.spellTargets(saving, plan.effect(), caster)), triggerRules));
        }
        Effect resolvedWith = plan.effect();
        if (!conditioned.isEmpty()) {
            Optional<SpellEffects.SpellPlan> automatic = spellEffects.forConditionedTrigger(spellName, when,
                    spellEffect.castLevel(), caster, castingAbility, attackBonus, saveDc);
            if (automatic.isPresent() && automatic.get().automated()) {
                Effect noSave = automatic.get().effect();
                applied.addAll(effectApplier.apply(encounter, use, noSave,
                        resolver.resolve(noSave, CreatureStats.side(caster),
                                stats.spellTargets(conditioned, noSave, caster)), triggerRules));
                if (saving.isEmpty()) {
                    resolvedWith = noSave;
                }
            }
        }
        afterTrigger(encounter, caster, spellEffect, trigger.orElse(null), applied);
        endOnSave(encounter, caster, spellEffect, when, applied);
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied),
                outcomes(resolvedWith, applied));
    }

    // Whether the creature has the condition from this caster's spell
    private static boolean hasFrom(EncounterParticipant creature, String condition, String spellName,
                                   EncounterParticipant caster) {
        return creature.getActiveConditions() != null && creature.getActiveConditions().stream()
                .anyMatch(c -> c != null && condition.equalsIgnoreCase(c.name())
                        && spellName.equalsIgnoreCase(c.sourceSpellName())
                        && caster.getId().equals(c.sourceParticipantId()));
    }

    /*
     * What follows a trigger (D185): each creature that failed its save, or that it reached with no save, gains the
     * trigger's exhaustion, recorded on the Spell Effect so it goes when the spell ends, and comes under the spell's
     * Ongoing Modifiers when the trigger says so, as Sickening Radiance's light and exhaustion (XGE p.164); and the damage
     * dealt counts against the effect's budget, as Guardian of Faith's (PHB p.246).
     */
    private void afterTrigger(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                              SpellTemplate.EffectTrigger trigger, List<EffectApplier.Applied> applied) {
        if (trigger != null && (trigger.exhaustion() > 0 || trigger.addsAffected())) {
            List<EncounterParticipant> failed = applied.stream()
                    .filter(result -> switch (result.outcome().outcome()) {
                        case HIT, CRITICAL, SAVE_FAIL, AUTOMATIC -> true;
                        default -> false;
                    })
                    .map(EffectApplier.Applied::target)
                    .filter(target -> target.getLifeState() != LifeState.DEAD)
                    .toList();
            if (trigger.addsAffected()) {
                effectApplier.addAffected(caster, spellEffect.id(), failed);
            }
            if (trigger.exhaustion() > 0) {
                effectApplier.exhaust(encounter, caster, spellEffect.id(), failed, trigger.exhaustion());
            }
        }
        effectApplier.spendBudget(encounter, caster, spellEffect.id(),
                applied.stream().mapToInt(EffectApplier.Applied::damageTaken).sum());
    }

    /*
     * A trigger whose successful save ends the spell, as Searing Smite's does (PHB p.274): once a creature saves, the
     * spell ends. One kept up by concentration ends with the concentration, which takes the effect and any conditions it
     * created with it (D162); one without just ends. A save Legendary Resistance turns into a success afterwards isn't
     * counted, as the spell's end isn't undone either; the DM ends the spell by hand then.
     */
    private void endOnSave(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                           SpellTrigger when, List<EffectApplier.Applied> applied) {
        // A success that ends the spell for that creature only, as Weird's (PHB p.288, D190)
        if (spellEffects.triggerEndsForCreature(spellEffect.spell(), when)) {
            applied.stream()
                    .filter(result -> result.outcome().outcome() == Outcome.SAVE_SUCCESS)
                    .map(EffectApplier.Applied::target)
                    .forEach(creature -> effectApplier.endSpellConditions(encounter, caster, creature,
                            spellEffect.spell(), creature.getDisplayName() + " succeeds on the save"));
            return;
        }
        Optional<EffectApplier.Applied> saved = applied.stream()
                .filter(result -> result.outcome().outcome() == Outcome.SAVE_SUCCESS)
                .findFirst();
        if (saved.isEmpty() || !spellEffects.triggerSaveEndsSpell(spellEffect.spell(), when)) {
            return;
        }
        String why = saved.get().target().getDisplayName() + " succeeds on the save";
        ConcentrationInstance held = instanceOf(caster, spellEffect);
        if (held != null) {
            effectApplier.endConcentration(encounter, caster, spellEffect.spell() + " ends: " + why);
        } else {
            effectApplier.endEffect(encounter, caster, spellEffect.id(), why);
        }
    }

    // The Concentration Instance the effect belongs to, while its caster still holds it
    private static ConcentrationInstance instanceOf(EncounterParticipant caster, SpellEffectEntry spellEffect) {
        ConcentrationInstance held = caster.getConcentration();
        return held != null && held.id().equals(spellEffect.concentrationInstanceId()) ? held : null;
    }

    /**
     * Each target's result as a spell response reports it. The outcome keeps the words the session pages
     * know: hit, miss and critical for an attack, saved and failed_save for a save, self for a spell on its
     * caster, healed for healing alone, and hit for anything else that needs no roll.
     */
    static List<CastSpellResponse.TargetOutcome> outcomes(Effect effect, List<EffectApplier.Applied> applied) {
        return applied.stream()
                .map(result -> {
                    Resolver.TargetOutcome outcome = result.outcome();
                    boolean attack = effect.delivery() instanceof Effect.Attack;
                    boolean save = effect.delivery() instanceof Effect.Save;
                    return CastSpellResponse.TargetOutcome.builder()
                            .targetId(result.target().getId())
                            .targetName(result.target().getDisplayName())
                            .outcome(outcomeWord(effect, result))
                            .damage(result.damageTaken() > 0 ? result.damageTaken() : null)
                            .healing(result.healed() > 0 ? result.healed() : null)
                            .conditionsApplied(result.conditionNames())
                            .attackRoll(attack ? outcome.total() : null)
                            .saveRoll(save ? outcome.total() : null)
                            .build();
                })
                .toList();
    }

    private static String outcomeWord(Effect effect, EffectApplier.Applied result) {
        return switch (result.outcome().outcome()) {
            case HIT -> "hit";
            case MISS -> "miss";
            case CRITICAL -> "critical";
            case SAVE_SUCCESS -> "saved";
            case SAVE_FAIL -> "failed_save";
            case AUTOMATIC -> {
                if (effect.delivery() instanceof Effect.Self) yield "self";
                yield effect.damage().isEmpty() && effect.healing() != null ? "healed" : "hit";
            }
        };
    }

    private static EffectApplier.Use use(EncounterParticipant caster, String spellName, String costNote,
                                         ConcentrationInstance instance) {
        return new EffectApplier.Use(caster, caster.getDisplayName(), CombatActionType.SPELL_CAST,
                "casts " + spellName + costNote, "at", spellName, instance);
    }

    private static String summary(String lead, List<EffectApplier.Applied> applied) {
        return applied.isEmpty() ? lead + "."
                : lead + ". " + applied.stream().map(EffectApplier.Applied::description).collect(Collectors.joining(" "));
    }

    private static boolean silenced(EncounterParticipant caster) {
        return CreatureStats.conditionNames(caster).stream()
                .anyMatch(name -> "silenced".equalsIgnoreCase(name) || "silence".equalsIgnoreCase(name));
    }
}
