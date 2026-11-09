package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Metamagic;
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
        SpellEffects.SpellPlan plan = spellEffects.forCast(spellName, slotLevel, caster, castingAbility, attackBonus,
                saveDc, advantage);
        if (metamagic.has(Metamagic.Option.EXTENDED)) {
            plan = extended(plan);
        }
        String lead = caster.getDisplayName() + " casts " + spellName
                + (slotLevel > 0 && slotLevel > plan.spellLevel() ? " at level " + slotLevel : "") + costNote
                + metamagic.note();

        if (!plan.automated()) {
            ConcentrationInstance instance = plan.concentration()
                    ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                    : null;
            String manualReason = summoning != null ? summoning.manualReason() : plan.manualReason();
            String summary = lead + " — " + (summoning != null ? summoning.castNote() : plan.manualReason())
                    + forTheDm(metamagic, targets);
            effectApplier.summary(encounter, use(caster, spellName, costNote, instance), summary);
            leaveEffect(encounter, caster, spellName, slotLevel, instance, false, metamagic);
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

        ConcentrationInstance instance = plan.concentration()
                ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                : null;
        EffectApplier.Use use = use(caster, spellName, costNote, instance);
        effectApplier.summary(encounter, use, lead + ".");
        List<Resolver.Target> resolvedAgainst = stats.targets(reached, effect).stream()
                .map(target -> withMetamagic(target, metamagic))
                .toList();
        List<EffectApplier.Applied> applied = effectApplier.apply(encounter, use, effect,
                resolver.resolve(effect, CreatureStats.side(caster), resolvedAgainst, metamagic.empoweredDice()));
        leaveEffect(encounter, caster, spellName, slotLevel, instance, !reached.isEmpty(), metamagic);
        return new CastResult(spellName, plan.spellLevel(), plan.concentration(), null, summary(lead, applied),
                outcomes(effect, applied));
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
                    ? new Effect.ConditionEnd(doubled, end.repeatSave(), end.escapeDc(), end.endOfUsersTurn(),
                            end.endsOnDamage(), end.endsOnAttackOrCast())
                    : end));
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
     * Minute Meteors (XGE p.161).
     */
    private void leaveEffect(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                             ConcentrationInstance instance, boolean spentOne, MetamagicCasting.Plan metamagic) {
        spellEffects.lasting(spellName, slotLevel).ifPresent(lasting -> {
            Integer uses = lasting.uses();
            if (uses != null && spentOne) {
                uses = Math.max(0, uses - 1);
            }
            Integer rounds = lasting.durationRounds();
            if (rounds != null && metamagic.has(Metamagic.Option.EXTENDED)) {
                rounds = Metamagic.extendedRounds(rounds);
            }
            effectApplier.startEffect(encounter, caster, spellName, slotLevel > 0 ? slotLevel : null, instance,
                    rounds, uses);
        });
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
        if (targets.isEmpty()) {
            return move(encounter, caster, spellEffect, costNote);
        }
        String spellName = spellEffect.spell();
        SpellEffects.SpellPlan plan = spellEffects.forRepeat(spellName, spellEffect.castLevel(), caster,
                castingAbility, attackBonus, saveDc, advantage);
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
        Effect effect = lifeDomainHealing(caster, plan.effect(), plan.spellLevel(), spellEffect.castLevel());
        lead += lifeDomainNote(caster, plan.effect(), plan.spellLevel(), spellEffect.castLevel());
        effectApplier.summary(encounter, use, lead + ".");
        List<EffectApplier.Applied> applied = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            List<EncounterParticipant> standing = targets.stream()
                    .filter(target -> target.getLifeState() != LifeState.DEAD)
                    .toList();
            applied.addAll(effectApplier.apply(encounter, use, effect,
                    resolver.resolve(effect, CreatureStats.side(caster), stats.targets(standing, effect))));
        }
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied),
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
        String moment = switch (when) {
            case ENTER -> "entering it";
            case START_OF_TURN -> "starting a turn in it";
            case END_OF_TURN -> "ending a turn in it";
        };
        EffectApplier.Use use = new EffectApplier.Use(caster, caster.getDisplayName(),
                CombatActionType.SPELL_EFFECT_REPEAT, spellName + " (" + moment + ")", "on", spellName,
                instanceOf(caster, spellEffect));
        String lead = caster.getDisplayName() + "'s " + spellName + " acts on a creature " + moment;

        if (!plan.automated()) {
            String summary = lead + " — " + plan.manualReason();
            effectApplier.summary(encounter, use, summary);
            return new CastResult(spellName, plan.spellLevel(), false, plan.manualReason(), summary, List.of());
        }
        CombatAccess.requireNotDead(targets);
        effectApplier.summary(encounter, use, lead + ".");
        List<EffectApplier.Applied> applied = effectApplier.apply(encounter, use, plan.effect(),
                resolver.resolve(plan.effect(), CreatureStats.side(caster), stats.targets(targets, plan.effect())));
        endOnSave(encounter, caster, spellEffect, when, applied);
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied),
                outcomes(plan.effect(), applied));
    }

    /*
     * A trigger whose successful save ends the spell, as Searing Smite's does (PHB p.274): once a creature saves, the
     * spell ends. One kept up by concentration ends with the concentration, which takes the effect and any conditions it
     * created with it (D162); one without just ends. A save Legendary Resistance turns into a success afterwards isn't
     * counted, as the spell's end isn't undone either; the DM ends the spell by hand then.
     */
    private void endOnSave(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                           SpellTrigger when, List<EffectApplier.Applied> applied) {
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
