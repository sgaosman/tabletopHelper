package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
        SpellEffects.SpellPlan plan = spellEffects.forCast(spellName, slotLevel, caster, castingAbility, attackBonus,
                saveDc, advantage);
        String lead = caster.getDisplayName() + " casts " + spellName
                + (slotLevel > 0 && slotLevel > plan.spellLevel() ? " at level " + slotLevel : "") + costNote;

        if (!plan.automated()) {
            ConcentrationInstance instance = plan.concentration()
                    ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                    : null;
            String summary = lead + " — " + plan.manualReason();
            effectApplier.summary(encounter, use(caster, spellName, costNote, instance), summary);
            leaveEffect(encounter, caster, spellName, slotLevel, instance, false);
            return new CastResult(spellName, plan.spellLevel(), plan.concentration(), plan.manualReason(), summary,
                    List.of());
        }

        if (plan.verbal() && silenced(caster)) {
            throw new ConflictException("Cannot cast spells with verbal components while silenced");
        }
        Effect effect = plan.effect();
        List<EncounterParticipant> reached = effect.delivery() instanceof Effect.Self ? List.of(caster) : targets;
        CombatAccess.requireNotDead(reached);

        ConcentrationInstance instance = plan.concentration()
                ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                : null;
        EffectApplier.Use use = use(caster, spellName, costNote, instance);
        effectApplier.summary(encounter, use, lead + ".");
        List<EffectApplier.Applied> applied = effectApplier.apply(encounter, use, effect,
                resolver.resolve(effect, stats.targets(reached, effect)));
        leaveEffect(encounter, caster, spellName, slotLevel, instance, !reached.isEmpty());
        return new CastResult(spellName, plan.spellLevel(), plan.concentration(), null, summary(lead, applied),
                outcomes(effect, applied));
    }

    /**
     * The Spell Effect a lasting spell leaves on its caster (M15), linked to the Concentration Instance the cast
     * started. A cast that sent something at creatures has spent one of the effect's uses, such as the first of Melf's
     * Minute Meteors (XGE p.161).
     */
    private void leaveEffect(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                             ConcentrationInstance instance, boolean spentOne) {
        spellEffects.lasting(spellName, slotLevel).ifPresent(lasting -> {
            Integer uses = lasting.uses();
            if (uses != null && spentOne) {
                uses = Math.max(0, uses - 1);
            }
            effectApplier.startEffect(encounter, caster, spellName, slotLevel > 0 ? slotLevel : null, instance,
                    lasting.durationRounds(), uses);
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
        effectApplier.summary(encounter, use, lead + ".");
        List<EffectApplier.Applied> applied = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            List<EncounterParticipant> standing = targets.stream()
                    .filter(target -> target.getLifeState() != LifeState.DEAD)
                    .toList();
            applied.addAll(effectApplier.apply(encounter, use, plan.effect(),
                    resolver.resolve(plan.effect(), stats.targets(standing, plan.effect()))));
        }
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied),
                outcomes(plan.effect(), applied));
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
                resolver.resolve(plan.effect(), stats.targets(targets, plan.effect())));
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied),
                outcomes(plan.effect(), applied));
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
