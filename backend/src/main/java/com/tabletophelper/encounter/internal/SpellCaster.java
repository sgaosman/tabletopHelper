package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.AttackRules;
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
import com.tabletophelper.shared.ValidationException;
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

        // A lasting spell whose area acts only later, as Spike Growth's thorns (PHB p.277, D199)
        if (summoning == null && !spellEffects.castResolves(spellName)) {
            return castForLater(encounter, caster, spellName, slotLevel, plan, lead, castingAbility, attackBonus,
                    saveDc, costNote, metamagic, option);
        }

        // A summoning resolves through its summon step whatever its template says (D182, D199)
        if (!plan.automated() || summoning != null) {
            if (summoning != null && plan.verbal() && silenced(caster) && !metamagic.has(Metamagic.Option.SUBTLE)) {
                throw new ConflictException("Cannot cast spells with verbal components while silenced");
            }
            ConcentrationInstance instance = plan.concentration()
                    ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                    : null;
            String manualReason = summoning != null ? summoning.manualReason() : plan.manualReason();
            String summary = lead + " — " + (summoning != null ? summoning.castNote() : plan.manualReason())
                    + forTheDm(metamagic, targets);
            effectApplier.summary(encounter, use(caster, spellName, costNote, instance), summary);
            // What the DM still applies, as a familiar's touch spells (PHB p.240) or a Glyph of Warding's trigger (PHB
            // p.245) (D199)
            String reminder = spellEffects.castReminder(spellName);
            if (reminder != null) {
                effectApplier.summary(encounter, use(caster, spellName, costNote, instance), spellName + ": " + reminder);
                summary += " " + reminder;
            }
            // A summoning with an option may give its caster Ongoing Modifiers for it, as Summon Draconic Spirit's
            // Shared Resistances, a resistance the spirit has, until the spell ends (FTD p.21)
            ModifierCast onCaster = summoning != null && option != null
                    ? new ModifierCast(spellEffects.modifiers(spellName, slotLevel, option,
                            stats.spellcastingModifier(caster, castingAbility, attackBonus), caster),
                            List.of(caster), saveDc)
                    : ModifierCast.NONE;
            leaveEffect(encounter, caster, spellName, slotLevel, instance, false, metamagic, onCaster,
                    spellEffects.later(spellName, slotLevel, option), List.of(), 0,
                    stats.spellcastingModifier(caster, castingAbility, attackBonus), option);
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
        // A spell that returns the dead to life reaches only dead creatures, as Revivify (PHB p.272, D199)
        Integer revives = spellEffects.revivesDead(spellName);
        if (revives != null) {
            return revive(encounter, caster, spellName, plan.spellLevel(), reached, lead, costNote, revives);
        }
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
        List<EncounterParticipant> eligible = reached.stream()
                .filter(target -> !globed.contains(target) && !immune.contains(target) && !unaffected.contains(target)
                        && !spared.contains(target) && !maximised.contains(target))
                .toList();
        // A pool of hit points that reaches the creatures lowest first, as Sleep's (PHB p.276, D199)
        List<EncounterParticipant> normal = spellEffects.hpPool(spellName) == null ? eligible
                : withinPool(encounter, use, spellName, slotLevel, eligible);
        if (!spared.isEmpty()) {
            effectApplier.summary(encounter, use, spellName + " has no effect on " + spared.stream()
                    .map(target -> target.getDisplayName() + " (" + (sparedWhy(target, seventh) != null
                            ? sparedWhy(target, seventh) : high.unaffectedAtOrBelow().ability() + " "
                            + high.unaffectedAtOrBelow().max() + " or lower") + ")")
                    .collect(Collectors.joining(", ")) + ".");
        }
        List<EffectApplier.Applied> applied = new ArrayList<>();
        // Conditions that stay when the spell ends, as Sleet Storm's prone (PHB p.276, D199), belong to no
        // Concentration Instance and keep no duration
        EffectApplier.Use applying = rules.conditionsOutlastSpell() ? use.withoutInstance() : use;
        if (rules.conditionsOutlastSpell()) {
            effect = new Effect(effect.name(), effect.delivery(), effect.damage(), effect.healing(),
                    effect.conditions(), effect.concentration(), null, effect.conditionEnds());
        }
        if (!seventh.rays().isEmpty()) {
            // A table each creature rolls on, as Prismatic Spray's d8 (PHB p.267, D189)
            applied.addAll(rays(encounter, caster, applying, spellName, effect, seventh.rays(), normal, saveDc));
        } else if (!normal.isEmpty() || maximised.isEmpty()) {
            // With no targets at all the spell still resolves, rolling its dice as before (D177)
            Effect resolving = effect;
            String advantageVs = spellEffects.attackAdvantageVs(spellName);
            boolean foesAdvantaged = spellEffects.foesSaveWithAdvantage(spellName);
            List<Resolver.Target> resolvedAgainst = stats.spellTargets(normal, resolving, caster).stream()
                    .map(target -> withMetamagic(withCastRules(target, normal, rules), metamagic))
                    .map(target -> withAttackAdvantage(target, normal, advantageVs))
                    .map(target -> foesAdvantaged ? asFoe(target, normal, caster) : target)
                    .toList();
            applied.addAll(effectApplier.apply(encounter, applying, resolving,
                    resolver.resolve(resolving, CreatureStats.side(caster), resolvedAgainst,
                            metamagic.empoweredDice()), rules));
        }
        if (!maximised.isEmpty()) {
            Effect most = maximumDamage(effect);
            List<Resolver.Target> resolvedAgainst = stats.spellTargets(maximised, most, caster).stream()
                    .map(target -> withMetamagic(target.withSaveSource(RollMode.DISADVANTAGE), metamagic))
                    .toList();
            applied.addAll(effectApplier.apply(encounter, applying, most,
                    resolver.resolve(most, CreatureStats.side(caster), resolvedAgainst, 0), rules));
        }
        String failNote = failNotes(encounter, use, caster, rules, applied);
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
        // Modifiers that reach a creature whatever its save, as Spirit Guardians' halved speed (PHB p.278, D199)
        List<EncounterParticipant> underModifiers = !rules.modifiersOnSuccess() ? affected : applied.stream()
                .filter(result -> result.outcome().outcome() != Outcome.MISS)
                .map(EffectApplier.Applied::target)
                .filter(target -> target.getLifeState() != LifeState.DEAD)
                .distinct()
                .toList();
        int spellcastingModifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);
        SpellEffects.ModifierPlan modifiers = spellEffects.modifiers(spellName, slotLevel, option,
                spellcastingModifier, underModifiers.isEmpty() ? null : underModifiers.getFirst());
        // Temporary hit points the cast gives, as Guardian of Nature's Great Tree's (XGE p.157, D185)
        Integer tempHp = spellEffects.tempHitPoints(spellName, option, slotLevel);
        if (tempHp != null) {
            affected.forEach(creature -> effectApplier.gainTempHp(encounter, caster, creature, tempHp, spellName));
        }
        // Or rolled for each creature, as Enhance Ability's Bear's Endurance's 2d6 (PHB p.237, D199)
        String tempHpDice = spellEffects.tempHitPointsDice(spellName, option);
        if (tempHpDice != null) {
            Dice dice = Dice.parse(tempHpDice);
            affected.forEach(creature -> effectApplier.gainTempHp(encounter, caster, creature, rolled(dice),
                    spellName));
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
                metamagic, new ModifierCast(modifiers, underModifiers, saveDc), later, delayedOn, dealt,
                spellcastingModifier, option);
        // The save a creature holding the heated object makes, as against Heat Metal (PHB p.250, D199)
        modifierNote += heldObject(encounter, caster, use, spellName, slotLevel, option, applied, saveDc, null);
        // A spell that lets its caster Dash as a bonus action lets it Dash as it casts, as Expeditious Retreat does
        // (PHB p.238, D199)
        modifierNote += dashOnCast(encounter, caster, use, spellName, modifiers, affected);
        String fifthNote = afterCast(encounter, caster, spellName, option, use, instance, reached, applied,
                affected, saveDc, castingAbility, attackBonus);
        String seventhNote = afterSeventh(encounter, caster, spellName, slotLevel, option, use, seventh, normal,
                applied, saveDc);
        String highNote = afterHighLevelCast(encounter, caster, spellName, slotLevel, high, use, affected)
                + givePool(encounter, caster, spellName, use);
        // What the DM still rolls for a spell with no Ongoing Modifiers to carry its reminder, as Heat Metal's drop
        // save (PHB p.250, D197)
        String reminder = spellEffects.castReminder(spellName);
        String reminderNote = reminder != null ? " " + reminder : "";
        if (reminder != null) {
            effectApplier.summary(encounter, use, spellName + ": " + reminder);
        }
        return new CastResult(spellName, plan.spellLevel(), plan.concentration(), null,
                summary(lead, applied) + typeNote + failNote + modifierNote + fifthNote + seventhNote + highNote
                        + reminderNote,
                outcomes(effect, applied));
    }

    /*
     * The cast of a lasting spell whose area or later uses do everything (D199), as Spike Growth's thorns (PHB p.277),
     * Stinking Cloud's save at the start of a creature's turn (PHB p.278) or Aura of Vitality's heal as a bonus action
     * (PHB p.216): its concentration and Spell Effect start, and nothing is rolled; the DM applies the area to creatures
     * through the effect's triggers, and its caster uses it on later turns.
     */
    private CastResult castForLater(Encounter encounter, EncounterParticipant caster, String spellName, int slotLevel,
                                    SpellEffects.SpellPlan plan, String lead, String castingAbility, int attackBonus,
                                    int saveDc, String costNote, MetamagicCasting.Plan metamagic, String option) {
        if (plan.verbal() && silenced(caster) && !metamagic.has(Metamagic.Option.SUBTLE)) {
            throw new ConflictException("Cannot cast spells with verbal components while silenced");
        }
        ConcentrationInstance instance = plan.concentration()
                ? effectApplier.startConcentration(encounter, caster, spellName, slotLevel, plan.durationRounds())
                : null;
        EffectApplier.Use use = use(caster, spellName, costNote, instance);
        String summary = lead + ". Nothing is rolled yet: " + spellName + " acts on creatures through its Spell "
                + "Effect.";
        effectApplier.summary(encounter, use, summary);
        int spellcastingModifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);
        String note = leaveEffect(encounter, caster, spellName, slotLevel, instance, false, metamagic,
                new ModifierCast(spellEffects.modifiers(spellName, slotLevel, option, spellcastingModifier, null),
                        List.of(), saveDc),
                spellEffects.later(spellName, slotLevel, option), List.of(), 0, spellcastingModifier, option);
        String uses = EffectApplier.effects(caster).stream()
                .filter(effect -> effect.spell().equalsIgnoreCase(spellName) && effect.usesLeft() != null)
                .reduce((first, second) -> second)
                .map(effect -> " It has " + effect.usesLeft() + " uses.")
                .orElse("");
        String reminder = spellEffects.castReminder(spellName);
        if (reminder != null) {
            effectApplier.summary(encounter, use, spellName + ": " + reminder);
        }
        return new CastResult(spellName, plan.spellLevel(), plan.concentration(), null,
                summary + note + uses + (reminder != null ? " " + reminder : ""), List.of());
    }

    /*
     * What the log says of each creature that failed the spell's save (D199), as Gust of Wind's push of 15 feet (PHB
     * p.248), for the DM to play out; what the summary adds, or nothing.
     */
    private String failNotes(Encounter encounter, EffectApplier.Use use, EncounterParticipant caster,
                             SpellEffects.CastRules rules, List<EffectApplier.Applied> applied) {
        if (rules.failNote() == null) {
            return "";
        }
        List<String> notes = applied.stream()
                .filter(result -> result.outcome().outcome() == Outcome.SAVE_FAIL
                        && result.target().getLifeState() != LifeState.DEAD)
                .map(result -> result.target().getDisplayName() + " "
                        + rules.failNote().replace("{caster}", caster.getDisplayName()) + ".")
                .distinct()
                .toList();
        notes.forEach(note -> effectApplier.summary(encounter, use, note));
        return notes.isEmpty() ? "" : " " + String.join(" ", notes);
    }

    /**
     * The creatures a pool of hit points reaches (D199), as Sleep's 5d8 (PHB p.276) and Color Spray's 6d10 (PHB p.222):
     * the pool rolled, with its upcast dice, then each of the creatures in turn from the lowest current hit points whose
     * hit points are no more than what is left, which they take from it. Those beyond it are named in the log.
     */
    private List<EncounterParticipant> withinPool(Encounter encounter, EffectApplier.Use use, String spellName,
                                                  int slotLevel, List<EncounterParticipant> creatures) {
        Dice dice = spellEffects.hpPoolDice(spellName, slotLevel);
        List<Integer> rolls = new ArrayList<>();
        for (int i = 0; i < dice.count(); i++) {
            rolls.add(resolver.roll(dice.sides()));
        }
        int pool = dice.total(rolls.stream().mapToInt(Integer::intValue).sum());
        List<EncounterParticipant> reached = poolReach(pool, creatures);
        int left = pool - reached.stream().mapToInt(EncounterParticipant::getHpCurrent).sum();
        List<EncounterParticipant> beyond = creatures.stream().filter(creature -> !reached.contains(creature)).toList();
        String text = spellName + ": " + dice + " (" + rolls.stream().map(String::valueOf)
                .collect(Collectors.joining(", ")) + ") = " + pool + " hit points, lowest first"
                + (reached.isEmpty() ? "; it reaches no one"
                        : "; it reaches " + reached.stream().map(creature -> creature.getDisplayName() + " ("
                                + creature.getHpCurrent() + " HP)").collect(Collectors.joining(", "))
                        + ", " + left + " left")
                + (beyond.isEmpty() ? "" : "; " + beyond.stream().map(creature -> creature.getDisplayName() + " ("
                        + creature.getHpCurrent() + " HP)").collect(Collectors.joining(", ")) + " "
                        + (beyond.size() == 1 ? "is" : "are") + " beyond it") + ".";
        effectApplier.summary(encounter, use, text);
        return reached;
    }

    /**
     * The creatures a pool of hit points reaches (PHB p.276): in ascending order of current hit points, each whose hit
     * points are equal to or less than what is left of the pool, which subtracts them; a creature at 0 hit points is
     * passed over (D199). Ties keep the order given.
     */
    static List<EncounterParticipant> poolReach(int pool, List<EncounterParticipant> creatures) {
        List<EncounterParticipant> order = creatures.stream()
                .filter(creature -> creature.getHpCurrent() != null && creature.getHpCurrent() > 0)
                .sorted(java.util.Comparator.comparingInt(EncounterParticipant::getHpCurrent))
                .toList();
        List<EncounterParticipant> reached = new ArrayList<>();
        int left = pool;
        for (EncounterParticipant creature : order) {
            if (creature.getHpCurrent() > left) {
                break;
            }
            reached.add(creature);
            left -= creature.getHpCurrent();
        }
        return reached;
    }

    /*
     * A spell that returns the dead to life, as Revivify (PHB p.272, D199): each target must be dead, and returns with
     * the hit points the template names. How long ago it died, and the diamond the spell consumes, are the DM's.
     */
    private CastResult revive(Encounter encounter, EncounterParticipant caster, String spellName, int spellLevel,
                              List<EncounterParticipant> targets, String lead, String costNote, int hp) {
        if (targets.isEmpty()) {
            throw new ValidationException("Choose the dead creature " + spellName + " returns to life");
        }
        for (EncounterParticipant target : targets) {
            if (target.getLifeState() != LifeState.DEAD) {
                throw new ConflictException(target.getDisplayName() + " isn't dead; " + spellName
                        + " returns only a creature that has died");
            }
        }
        EffectApplier.Use use = use(caster, spellName, costNote, null);
        effectApplier.summary(encounter, use, lead + ".");
        for (EncounterParticipant target : targets) {
            effectApplier.revive(encounter, caster, target, hp, spellName);
        }
        String reminder = spellEffects.castReminder(spellName);
        if (reminder != null) {
            effectApplier.summary(encounter, use, spellName + ": " + reminder);
        }
        return new CastResult(spellName, spellLevel, false, null, lead + ": " + names(targets) + " returns to life with "
                + hp + " HP." + (reminder != null ? " " + reminder : ""), List.of());
    }

    // What a cast gives its caster to use later from a pool, as Goodberry's ten berries (PHB p.246, D199); the log's note
    private String givePool(Encounter encounter, EncounterParticipant caster, String spellName, EffectApplier.Use use) {
        SpellTemplate.SpellPool pool = spellEffects.spellPool(spellName);
        if (pool == null || caster.getLifeState() == LifeState.DEAD) {
            return "";
        }
        int held = effectApplier.addSpellPool(caster, spellName, pool);
        String text = caster.getDisplayName() + " holds " + held + " " + pool.name().toLowerCase(java.util.Locale.ROOT)
                + (pool.heals() > 0 ? ", each restoring " + pool.heals() + " hit point" + (pool.heals() == 1 ? "" : "s")
                        + " to the creature that eats it with its action" : "") + ".";
        effectApplier.summary(encounter, use, text);
        return " " + text;
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
        // One roll for the whole area, as Nathair's Mischief's d4 for its cube (FTD p.20, D199), each row with its own
        // save; a row that asks for no save, as its molasses' difficult terrain, is a reminder for the DM
        boolean shared = spellEffects.sharedRoll(spellName);
        List<SpellTemplate.Ray> sharedStruck = new ArrayList<>();
        List<Integer> sharedRolls = new ArrayList<>();
        if (shared) {
            SpellTemplate.Ray first = ray(table, sharedRolls, sides);
            sharedStruck.addAll(first.rollsTwice()
                    ? List.of(reroll(table, sharedRolls, sides), reroll(table, sharedRolls, sides)) : List.of(first));
            SpellTemplate.Ray row = sharedStruck.getFirst();
            boolean saved = row.damageDice() != null || !row.conditions().isEmpty();
            String ability = row.saveAbility() != null ? row.saveAbility().toUpperCase(java.util.Locale.ROOT)
                    : save.ability() != null ? save.ability().name() : "";
            effectApplier.summary(encounter, use, spellName + " rolls d" + sides + ": "
                    + sharedRolls.stream().map(String::valueOf).collect(Collectors.joining(", ")) + " ("
                    + sharedStruck.stream().map(r -> r.color() != null ? r.color() : "#" + r.roll())
                    .collect(Collectors.joining(" and ")) + ")"
                    + (saved && !creatures.isEmpty() ? ": a DC " + save.dc() + " " + ability + " save for each creature"
                    + " in the area." : "."));
            if (!saved) {
                sharedStruck.stream().filter(r -> r.reminder() != null)
                        .forEach(r -> reminders.add(spellName + ": " + r.reminder()
                                .replace("{dc}", String.valueOf(saveDc)).replace("{caster}", caster.getDisplayName())));
                countAndRemind(encounter, caster, use, spellName, counting, reminders, saveDc);
                return applied;
            }
        }
        for (EncounterParticipant creature : creatures) {
            List<SpellTemplate.Ray> struck = new ArrayList<>();
            List<Integer> rolls = new ArrayList<>();
            if (shared) {
                struck.addAll(sharedStruck);
                rolls.addAll(sharedRolls);
            } else {
                SpellTemplate.Ray first = ray(table, rolls, sides);
                if (first.rollsTwice()) {
                    struck.add(reroll(table, rolls, sides));
                    struck.add(reroll(table, rolls, sides));
                } else {
                    struck.add(first);
                }
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
            if (!shared) {
                effectApplier.summary(encounter, use, creature.getDisplayName() + " is struck by the "
                        + struck.stream().map(ray -> ray.color() != null ? ray.color() : "#" + ray.roll())
                                .collect(Collectors.joining(" and ")) + " ray" + (struck.size() > 1 ? "s" : "")
                        + " (d" + sides + ": " + rolls.stream().map(String::valueOf).collect(Collectors.joining(", "))
                        + ").");
            }
            // A row's own save, as Nathair's Mischief's Dexterity save against its flowers (FTD p.20, D199)
            Effect.Save rowSave = struck.getFirst().saveAbility() != null
                    ? new Effect.Save(Ability.parse(struck.getFirst().saveAbility()).orElse(save.ability()), save.dc(),
                    save.halfDamageOnSuccess()) : save;
            Effect each = new Effect(spellName, rowSave, damage, null, conditions, false, null, ends);
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
        concentrationSaves(encounter, caster, use, spellName, saveDc, creatures,
                spellEffects.concentrationSave(spellName));
    }

    // As above, with the save's ability given, as a trigger's own, Sleet Storm's at the start of a turn (PHB p.276,
    // D199); nothing for none
    private void concentrationSaves(Encounter encounter, EncounterParticipant caster, EffectApplier.Use use,
                                    String spellName, int saveDc, List<EncounterParticipant> creatures,
                                    String abilityName) {
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
                                List<EncounterParticipant> reached, List<EffectApplier.Applied> applied, int saveDc) {
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
        if (followUp != null && (followUp.option() == null || followUp.option().equalsIgnoreCase(option))) {
            boolean afterSave = "FAILED_SAVE".equalsIgnoreCase(followUp.on());
            List<EncounterParticipant> hit = applied.stream()
                    .filter(result -> afterSave ? result.outcome().outcome() == Outcome.SAVE_FAIL
                            : result.outcome().outcome() == Outcome.HIT
                            || result.outcome().outcome() == Outcome.CRITICAL)
                    .map(EffectApplier.Applied::target)
                    .filter(target -> target.getLifeState() != LifeState.DEAD)
                    .toList();
            if (!hit.isEmpty()) {
                Ability ability = Ability.parse(followUp.ability()).orElse(Ability.CHA);
                List<String> conditions = followUp.conditions().stream().map(SpellTemplate.ConditionInflicted::name)
                        .filter(java.util.Objects::nonNull).toList();
                Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
                followUp.conditions().stream().filter(c -> c.name() != null && c.durationRounds() != null)
                        .forEach(c -> ends.put(c.name(), new Effect.ConditionEnd(c.durationRounds(), null, null,
                                false)));
                Effect then = new Effect(spellName, new Effect.Save(ability, saveDc, false), List.of(), null,
                        conditions, false, null, ends);
                effectApplier.summary(encounter, use, names(hit) + " makes a DC " + saveDc + " " + ability.name()
                        + " save against " + spellName + ".");
                effectApplier.apply(encounter, use, then, resolver.resolve(then,
                        CreatureStats.side(caster), stats.spellTargets(hit, then, caster)));
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
            // Each side's advantage or disadvantage on its check, as Enhance Ability's Bull's Strength on the creature's
            // Strength (PHB p.237, D199)
            AbilityChecks.Roll casterCheck = AbilityChecks.roll(resolver::d20, AbilityChecks.sources(caster,
                    Ability.parse(castingAbility != null ? castingAbility : caster.getSpellcastingAbility())
                            .orElse(null), null));
            AbilityChecks.Roll creatureCheck = AbilityChecks.roll(resolver::d20,
                    AbilityChecks.sources(creature, ability, null));
            int casterRoll = casterCheck.d20();
            int creatureRoll = creatureCheck.d20();
            int creatureModifier = com.tabletophelper.rules.Ability.modifier(stats.abilityScore(creature, ability));
            int casterTotal = casterRoll + casterModifier;
            int creatureTotal = creatureRoll + creatureModifier;
            boolean won = casterTotal > creatureTotal;
            String outcome = won ? " - " + caster.getDisplayName() + " wins"
                    + (contest.winNote() != null ? ": " + contest.winNote() : "")
                    : casterTotal == creatureTotal ? " - a tie, nothing changes"
                    : " - " + creature.getDisplayName() + " wins";
            String text = "Contest: " + caster.getDisplayName() + " " + casterCheck.dice() + signed(casterModifier)
                    + " = " + casterTotal + " vs " + creature.getDisplayName() + "'s " + ability.name() + " "
                    + creatureCheck.dice() + signed(creatureModifier) + " = " + creatureTotal + outcome + ".";
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
        // A creature in Gaseous Form can't cast spells (PHB p.242, D199)
        String barred = ActiveModifiers.barredFromAttacking(caster);
        if (barred != null) {
            throw new ConflictException(caster.getDisplayName() + " can't cast spells while " + barred + " lasts");
        }
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
        // Stinking Cloud's poison: a creature immune to poison, or that doesn't need to breathe, succeeds (PHB p.278,
        // D199)
        if (rules.succeedsIfImmuneToDamage() != null && stats.defences(creature, rules.succeedsIfImmuneToDamage())
                .contains(com.tabletophelper.rules.combat.DamageRules.Defence.IMMUNITY)) {
            changed = changed.succeedingAutomatically();
        } else if (rules.succeedsWithoutBreath() && breathless(stats.traits(creature))) {
            changed = changed.succeedingAutomatically();
        }
        return changed;
    }

    /**
     * Whether traits say the creature doesn't need to breathe (D199), as an undead's or construct's stat block or a
     * warforged's Constructed Resilience words it: "doesn't need to breathe", "doesn't require air" (MM's Unusual
     * Nature and Undead Nature) or "can't breathe". Rules call: a creature's type alone says nothing, since the stat
     * blocks name this where it holds.
     */
    static boolean breathless(List<CreatureStats.Trait> traits) {
        for (CreatureStats.Trait trait : traits) {
            String text = ((trait.name() != null ? trait.name() : "") + " " + (trait.description() != null
                    ? trait.description() : "")).toLowerCase(java.util.Locale.ROOT).replace('’', '\'');
            if (BREATHLESS.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern BREATHLESS = java.util.regex.Pattern.compile(
            "(doesn't|does not|don't|do not|needn't) (need to |)(eat, drink, or |eat or |)breathe"
                    + "|(doesn't|does not|don't|do not) (need|require) air"
                    + "|doesn't need air");

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
        boolean concentration = spellEffects.concentration(spellName, slotLevel);
        Integer rounds = spellEffects.durationScales(spellName) ? spellEffects.durationRounds(spellName, slotLevel)
                : spellEffects.durationOf(spellName);
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
                spellEffects.later(spellName, slotLevel, option), List.of(), 0, spellcastingModifier, option);
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
                               int dealt, int spellcastingModifier, String option) {
        // Uses that grow with the caster's modifier, as Healing Spirit's heals (XGE p.157, D199)
        Optional<SpellEffects.Lasting> lasting = spellEffects.lasting(spellName, slotLevel, spellcastingModifier);
        SpellEffects.ModifierPlan plan = modifiers.plan();
        // A lasting spell keeps its modifiers for the creatures its triggers bring under it later, as Sickening
        // Radiance's light (XGE p.164, D185)
        boolean withModifiers = plan.any() && (!modifiers.affected().isEmpty() || lasting.isPresent());
        String notes = plan.notes().isEmpty() ? "" : " " + String.join(". ", plan.notes()) + ".";
        boolean forLater = later.damageBudget() != null || later.retaliationDice() != null
                || later.retaliationSave() != null
                || later.turnReminder() != null || spellEffects.fifthLevel(spellName).keepsEffect()
                || spellEffects.seventhLevel(spellName).keepsEffect()
                || spellEffects.highLevel(spellName).keepsEffect()
                // A spell whose lasting effect is the DM's to apply still shows and expires (D199)
                || spellEffects.keepsEffect(spellName);
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
                // A duration by slot may last until dispelled, as Bestow Curse's from a 9th-level slot (D199)
                : spellEffects.durationScales(spellName) ? spellEffects.durationRounds(spellName, slotLevel)
                : spellEffects.durationOf(spellName);
        if (rounds != null && metamagic.has(Metamagic.Option.EXTENDED)) {
            rounds = Metamagic.extendedRounds(rounds);
        }
        SpellEffectEntry.EffectSave repeatSave = plan.repeatSave() != null
                ? new SpellEffectEntry.EffectSave(plan.repeatSave(), modifiers.saveDc()) : null;
        // Modifiers that end with a turn of the creature they affect, as Arms of Hadar's loss of reactions until each
        // creature's next turn (PHB p.215), go on a Spell Effect for each creature, so each ends on its own turn (D199)
        if (withModifiers && byCreature(instance, lasting.isPresent(), forLater, delayedOn, modifiers)) {
            SpellEffectEntry first = null;
            for (EncounterParticipant creature : modifiers.affected()) {
                SpellEffectEntry own = effectApplier.startEffect(encounter, caster, spellName,
                        slotLevel > 0 ? slotLevel : null, null, rounds, uses, false);
                effectApplier.startModifiers(encounter, caster, own.id(), List.of(creature),
                        plan.modifiers().stream().map(OngoingModifier::copied).toList(), plan.option(), repeatSave,
                        plan.endReminder(), !spellEffects.fifthLevel(spellName).keepsEffect());
                first = first != null ? first : own;
            }
            return notes + " " + modifierText(encounter, caster, instance, first, plan);
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
            // The option chosen, and the creatures reached by a spell its target uses or one kept for the DM, as
            // Dragon's Breath's touched creature (XGE p.154), Alter Self's natural weapons (PHB p.211) or Bestow
            // Curse's wasted actions (PHB p.218) (D199)
            boolean holdsCreatures = spellEffects.usedByTarget(spellName) || spellEffects.keepsEffect(spellName);
            if (holdsCreatures) {
                effectApplier.holdFor(caster, effect.id(), modifiers.affected(), option);
            }
            effectApplier.spendBudget(encounter, caster, effect.id(), dealt);
            return notes;
        }
        // A spell whose effect outlives its spent rider, as Banishing Smite's banishment (PHB p.216, D187), doesn't end
        // with its last modifier
        effectApplier.startModifiers(encounter, caster, effect.id(), modifiers.affected(), plan.modifiers(),
                plan.option(), repeatSave, plan.endReminder(),
                lasting.isEmpty() && !spellEffects.fifthLevel(spellName).keepsEffect());
        effectApplier.spendBudget(encounter, caster, effect.id(), dealt);
        if (modifiers.affected().isEmpty()) {
            return notes;
        }
        return notes + " " + modifierText(encounter, caster, instance, effect, plan);
    }

    // What the log says of the modifiers a cast left, with the spell's reminder, as one summary entry
    private String modifierText(Encounter encounter, EncounterParticipant caster, ConcentrationInstance instance,
                                SpellEffectEntry effect, SpellEffects.ModifierPlan plan) {
        String label = ActiveModifiers.label(effect.withModifiers(List.of(), List.of(), plan.option(), null, null,
                false));
        String text = label + ": " + plan.modifiers().stream().map(OngoingModifier::describe)
                .collect(Collectors.joining("; ")) + "." + (plan.reminder() != null ? " " + plan.reminder() : "");
        effectApplier.summary(encounter, use(caster, effect.spell(), "", instance), text);
        return text;
    }

    /*
     * Whether a cast's modifiers go on a Spell Effect for each creature (D199): some end with a turn of the creature
     * they affect, which one effect can follow for one creature only, there are several creatures, and the effect holds
     * nothing else, no concentration, area or damage to come.
     */
    private static boolean byCreature(ConcentrationInstance instance, boolean lasting, boolean forLater,
                                      List<EncounterParticipant> delayedOn, ModifierCast modifiers) {
        return instance == null && !lasting && !forLater && delayedOn.isEmpty() && modifiers.affected().size() > 1
                && modifiers.plan().modifiers().stream().anyMatch(m -> m.until() == OngoingModifier.Until.TARGET_TURN_END
                || m.until() == OngoingModifier.Until.TARGET_TURN_START);
    }

    /*
     * The save each creature the spell's damage reached makes while it holds the spell's object, as Heat Metal's
     * Constitution save to drop it (PHB p.250, D199): one that keeps the object, by succeeding or because it can't drop
     * it, as armour it wears (the cast's worn option), has the modifiers, disadvantage on attack rolls and ability checks
     * until the start of the caster's next turn; one that fails drops it, so it has none. They go on the spell's Spell
     * Effect, replacing the last use's. What the log adds, or nothing.
     */
    private String heldObject(Encounter encounter, EncounterParticipant caster, EffectApplier.Use use,
                              String spellName, int slotLevel, String option, List<EffectApplier.Applied> applied,
                              int saveDc, SpellEffectEntry spellEffect) {
        Optional<SpellEffects.HeldObject> held = spellEffects.heldObject(spellName, slotLevel, option);
        Optional<SpellEffectEntry> effect = spellEffect != null ? Optional.of(spellEffect)
                : EffectApplier.effects(caster).stream()
                        .filter(each -> each.spell().equalsIgnoreCase(spellName))
                        .reduce((first, second) -> second);
        if (held.isEmpty() || effect.isEmpty()) {
            return "";
        }
        List<EncounterParticipant> burned = applied.stream()
                .filter(result -> result.damageTaken() > 0 && result.target().getLifeState() != LifeState.DEAD)
                .map(EffectApplier.Applied::target)
                .distinct()
                .toList();
        boolean worn = held.get().wornOption() != null && held.get().wornOption().equalsIgnoreCase(option);
        Ability ability = Ability.parse(held.get().ability()).orElse(Ability.CON);
        List<EncounterParticipant> keeping = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (EncounterParticipant creature : burned) {
            if (worn) {
                keeping.add(creature);
                notes.add(creature.getDisplayName() + " can't drop the armour it wears");
                continue;
            }
            Effect save = new Effect(spellName, new Effect.Save(ability, saveDc, false), List.of(), null, List.of(),
                    false, null);
            List<EffectApplier.Applied> saved = effectApplier.apply(encounter, use, save, resolver.resolve(save,
                    CreatureStats.side(caster), stats.spellTargets(List.of(creature), save, caster)));
            if (saved.stream().anyMatch(result -> result.outcome().outcome() == Outcome.SAVE_FAIL)) {
                notes.add(creature.getDisplayName() + " drops the object, which the spell no longer burns it with "
                        + "unless it takes hold of it again");
            } else {
                keeping.add(creature);
                notes.add(creature.getDisplayName() + " keeps hold of the object");
            }
        }
        List<OngoingModifier> modifiers = keeping.isEmpty() ? List.of() : held.get().modifiers();
        effectApplier.startModifiers(encounter, caster, effect.get().id(), keeping, modifiers, option, null,
                null, false);
        if (burned.isEmpty()) {
            return "";
        }
        String text = String.join("; ", notes) + (keeping.isEmpty() ? "." : ", so " + names(keeping)
                + (keeping.size() == 1 ? " has " : " have ") + modifiers.stream().map(OngoingModifier::describe)
                .collect(Collectors.joining(" and ")) + ".");
        effectApplier.summary(encounter, use, text);
        return " " + text;
    }

    /*
     * The Dash a spell that lets its caster Dash as a bonus action gives as it is cast, on the caster's own turn: "when
     * you cast this spell, and then as a bonus action on each of your turns", Expeditious Retreat's (PHB p.238, D199).
     */
    private String dashOnCast(Encounter encounter, EncounterParticipant caster, EffectApplier.Use use,
                              String spellName, SpellEffects.ModifierPlan plan, List<EncounterParticipant> affected) {
        if (plan.modifiers().stream().noneMatch(m -> m.kind() == OngoingModifier.Kind.BONUS_DASH)
                || !affected.contains(caster) || !EncounterTurns.isCurrentTurn(encounter, caster.getId())) {
            return "";
        }
        TurnEconomy economy = caster.getTurnEconomy() != null ? caster.getTurnEconomy() : TurnEconomy.fresh();
        caster.setTurnEconomy(economy.withBudget(ActionEconomy.dash(economy.budget())));
        String text = caster.getDisplayName() + " takes the Dash action as it casts " + spellName
                + ", gaining extra movement equal to its speed this turn.";
        effectApplier.summary(encounter, use, text);
        return " " + text;
    }

    // Shocking Grasp's advantage against a target in metal armour (PHB p.275, D199), as a source the log names
    private Resolver.Target withAttackAdvantage(Resolver.Target target, List<EncounterParticipant> creatures,
                                                String against) {
        if (!SpellTemplate.METAL_ARMOUR.equals(against)) {
            return target;
        }
        EncounterParticipant creature = creatures.stream().filter(c -> c.getId().equals(target.id())).findFirst()
                .orElse(null);
        if (creature == null || !stats.wearsMetalArmour(creature)) {
            return target;
        }
        return target.withModifiers(target.modifiers().withAttackSource(
                new AttackRules.Source(RollMode.ADVANTAGE, "target wears metal armour")));
    }

    // A creature the caster's side is fighting saves with advantage, as against Enthrall (PHB p.238, D199): one of the
    // other side in the encounter
    private static Resolver.Target asFoe(Resolver.Target target, List<EncounterParticipant> creatures,
                                         EncounterParticipant caster) {
        return creatures.stream().filter(c -> c.getId().equals(target.id())).findFirst()
                .filter(creature -> !CreatureStats.allegiance(creature).equals(CreatureStats.allegiance(caster)))
                .map(creature -> target.withNamedSaveSource(new AttackRules.Source(RollMode.ADVANTAGE,
                        "fought by the caster's side")))
                .orElse(target);
    }

    // The dice rolled, never below 0
    private int rolled(Dice dice) {
        int total = dice.modifier();
        for (int i = 0; i < dice.count(); i++) {
            total += resolver.roll(dice.sides());
        }
        return Math.max(0, total);
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
        List<SpellTemplate.Ray> table = spellEffects.seventhLevel(spellName).rays();
        for (int i = 0; i < times; i++) {
            if (layers != null) {
                // Passing through the layers in order, as Prismatic Wall's (PHB p.267, D195)
                applied.addAll(layers(encounter, caster, applying, spellName, layers, outside, saveDc));
                continue;
            }
            List<EncounterParticipant> standing = outside.stream()
                    .filter(target -> target.getLifeState() != LifeState.DEAD)
                    .toList();
            if (!table.isEmpty()) {
                // The table rolled again, as Nathair's Mischief's at the start of each of its caster's turns (FTD
                // p.21, D199)
                applied.addAll(rays(encounter, caster, applying, spellName, effect, table, standing, saveDc));
                continue;
            }
            List<Resolver.Target> resolvedAgainst = stats.spellTargets(standing, effect, caster).stream()
                    .map(target -> withCastRules(target, standing, rules))
                    .toList();
            applied.addAll(effectApplier.apply(encounter, applying, effect,
                    resolver.resolve(effect, CreatureStats.side(caster), resolvedAgainst), rules));
        }
        String failNote = failNotes(encounter, use, caster, rules, applied);
        afterSixthLevelRules(encounter, caster, spellName, spellEffect.castLevel(), rules, applied);
        concentrationSaves(encounter, caster, use, spellName, saveDc, outside);
        drain(encounter, caster, spellName, fifth, applied);
        String extra = fifth.contest() != null
                ? " " + contest(encounter, caster, spellName, fifth.contest(), use, targets, castingAbility, attackBonus)
                : "";
        // Each use's damage brings the held-object save again, as Heat Metal's (PHB p.250, D199)
        extra += heldObject(encounter, caster, use, spellName, spellEffect.castLevel(), spellEffect.option(), applied,
                saveDc, spellEffect);
        if (fifth.repeatEndsSpell()) {
            ConcentrationInstance held = instanceOf(caster, spellEffect);
            if (held != null) {
                effectApplier.endConcentration(encounter, caster, spellName + " ends: its last use");
            } else {
                effectApplier.endEffect(encounter, caster, spellEffect.id(), "its last use");
            }
            extra += " " + spellName + " ends.";
        }
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied) + failNote + extra,
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
        return trigger(encounter, caster, spellEffect, when, targets, castingAbility, attackBonus, saveDc, 1);
    }

    /**
     * As {@link #trigger(Encounter, EncounterParticipant, SpellEffectEntry, SpellTrigger, List, String, int, int)},
     * applied `times` times at once, as Spike Growth's 2d4 for each 5 feet a creature moves (PHB p.277, D199): the
     * damage dice are multiplied and rolled once. The spell's rules hold for its area too (D199): its creature types it
     * has no effect on, as Healing Spirit's undead and constructs (XGE p.157), and the saves it makes easier or harder,
     * as Moonbeam's shapechangers' disadvantage (PHB p.261) or Stinking Cloud's automatic success for a creature that
     * doesn't breathe (PHB p.278). A trigger may also spend the effect's uses, one for each creature it reaches, the
     * spell ending on the last, as Cordon of Arrows' ammunition (PHB p.228); make a concentrating creature save, as Sleet
     * Storm does (PHB p.276); or use up the action of a creature that fails, as Stinking Cloud does.
     */
    public CastResult trigger(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                              SpellTrigger when, List<EncounterParticipant> targets, String castingAbility,
                              int attackBonus, int saveDc, int times) {
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
        String lead = caster.getDisplayName() + "'s " + spellName + " acts on a creature " + moment
                + (times > 1 ? ", " + times + " times" : "");

        if (!plan.automated()) {
            String summary = lead + " — " + plan.manualReason();
            effectApplier.summary(encounter, use, summary);
            return new CastResult(spellName, plan.spellLevel(), false, plan.manualReason(), summary, List.of());
        }
        CombatAccess.requireNotDead(targets);
        CombatAccess.requireReachable(caster, targets);
        SpellEffects.TriggerRules extras = spellEffects.triggerRules(spellName, when);
        if (extras.spendsUse() && spellEffect.usesLeft() != null && targets.size() > spellEffect.usesLeft()) {
            throw new ConflictException(spellName + " has " + spellEffect.usesLeft() + " left");
        }
        effectApplier.summary(encounter, use, lead + ".");
        // The cast rules hold for what the area does too, as Dark Star's disintegrating a creature its damage leaves at 0
        // hit points (EGW p.186, D190)
        SpellEffects.CastRules triggerRules = spellEffects.castRules(spellName);
        // Conditions that outlast the spell, as Sleet Storm's prone (PHB p.276, D199)
        EffectApplier.Use applying = triggerRules.conditionsOutlastSpell() ? use.withoutInstance() : use;
        // A Globe of Invulnerability keeps the spell's area out too (PHB p.245, D188)
        List<EncounterParticipant> outside = outsideGlobes(encounter, use, caster, spellName, plan.spellLevel(),
                targets);
        // The creature types it has no effect on, as Healing Spirit's undead and constructs (XGE p.157, D199)
        List<EncounterParticipant> unaffected = outside.stream()
                .filter(target -> ofType(target, spellEffects.typeRules(spellName).noEffectOn()))
                .toList();
        if (!unaffected.isEmpty()) {
            effectApplier.summary(encounter, use, typeNote(spellName, unaffected, List.of()).trim());
        }
        List<EncounterParticipant> reached = outside.stream().filter(target -> !unaffected.contains(target)).toList();
        // A creature that already has the trigger's condition from this spell takes its damage with no save, as one
        // Evard's Black Tentacles already restrain (PHB p.238, D185)
        Optional<SpellTemplate.EffectTrigger> trigger = spellEffects.trigger(spellName, when);
        String held = trigger.map(SpellTemplate.EffectTrigger::whenConditioned).orElse(null);
        List<EncounterParticipant> conditioned = held == null ? List.of() : reached.stream()
                .filter(target -> hasFrom(target, held, spellName, caster))
                .toList();
        List<EncounterParticipant> saving = reached.stream().filter(target -> !conditioned.contains(target)).toList();
        List<EffectApplier.Applied> applied = new ArrayList<>();
        // The damage type the cast chose, as Spirit Guardians' radiant or necrotic (PHB p.278, D199)
        Effect effect = times(withOptionType(plan.effect(), spellEffect.option()), times);
        if (!saving.isEmpty() || conditioned.isEmpty() && reached.size() == outside.size()) {
            List<Resolver.Target> resolvedAgainst = stats.spellTargets(saving, effect, caster).stream()
                    .map(target -> withCastRules(target, saving, triggerRules))
                    .toList();
            applied.addAll(effectApplier.apply(encounter, applying, effect,
                    resolver.resolve(effect, CreatureStats.side(caster), resolvedAgainst), triggerRules));
        }
        Effect resolvedWith = effect;
        if (!conditioned.isEmpty()) {
            Optional<SpellEffects.SpellPlan> automatic = spellEffects.forConditionedTrigger(spellName, when,
                    spellEffect.castLevel(), caster, castingAbility, attackBonus, saveDc);
            if (automatic.isPresent() && automatic.get().automated()) {
                Effect noSave = times(withOptionType(automatic.get().effect(), spellEffect.option()), times);
                applied.addAll(effectApplier.apply(encounter, applying, noSave,
                        resolver.resolve(noSave, CreatureStats.side(caster),
                                stats.spellTargets(conditioned, noSave, caster)), triggerRules));
                if (saving.isEmpty()) {
                    resolvedWith = noSave;
                }
            }
        }
        String extra = failNotes(encounter, use, caster, triggerRules, applied)
                + lostActions(encounter, use, extras, applied);
        if (extras.concentrationSave() != null) {
            concentrationSaves(encounter, caster, use, spellName, saveDc, reached, extras.concentrationSave());
        }
        afterTrigger(encounter, caster, spellEffect, trigger.orElse(null), applied, triggerRules);
        endOnSave(encounter, caster, spellEffect, when, applied);
        if (extras.spendsUse()) {
            extra += spendUses(encounter, caster, spellEffect, applied.stream()
                    .map(EffectApplier.Applied::target).distinct().toList().size());
        }
        return new CastResult(spellName, plan.spellLevel(), false, null, summary(lead, applied) + extra,
                outcomes(resolvedWith, applied));
    }

    // The Effect with a damage type of OPTION dealing the type its cast chose, as Spirit Guardians' (PHB p.278, D199)
    static Effect withOptionType(Effect effect, String option) {
        if (option == null || effect.damage().stream().noneMatch(part -> "option".equals(part.type()))) {
            return effect;
        }
        List<Effect.DamagePart> parts = effect.damage().stream()
                .map(part -> "option".equals(part.type())
                        ? new Effect.DamagePart(part.dice(), option.toLowerCase(java.util.Locale.ROOT)) : part)
                .toList();
        return new Effect(effect.name(), effect.delivery(), parts, effect.healing(), effect.conditions(),
                effect.concentration(), effect.durationRounds(), effect.conditionEnds());
    }

    // The Effect's damage dice taken `times` times, as Spike Growth's 2d4 for each of several 5-foot moves (D199)
    static Effect times(Effect effect, int times) {
        if (times <= 1 || effect.damage().isEmpty()) {
            return effect;
        }
        List<Effect.DamagePart> parts = effect.damage().stream()
                .map(part -> new Effect.DamagePart(new Dice(part.dice().count() * times, part.dice().sides(),
                        part.dice().modifier() * times), part.type()))
                .toList();
        return new Effect(effect.name(), effect.delivery(), parts, effect.healing(), effect.conditions(),
                effect.concentration(), effect.durationRounds(), effect.conditionEnds());
    }

    /*
     * The creatures that failed a trigger's save and so use up their action this turn, as one retching in Stinking
     * Cloud (PHB p.278, D199): spent from the economy of the creature whose turn it is, and logged for the DM otherwise.
     */
    private String lostActions(Encounter encounter, EffectApplier.Use use, SpellEffects.TriggerRules rules,
                               List<EffectApplier.Applied> applied) {
        if (!rules.costsAction()) {
            return "";
        }
        List<String> notes = new ArrayList<>();
        for (EffectApplier.Applied result : applied) {
            EncounterParticipant creature = result.target();
            if (result.outcome().outcome() != Outcome.SAVE_FAIL || creature.getLifeState() != LifeState.ALIVE) {
                continue;
            }
            String note = effectApplier.loseAction(encounter, creature)
                    ? creature.getDisplayName() + "'s action this turn is spent."
                    : creature.getDisplayName() + " can't use its action on its turn (the DM keeps to it).";
            effectApplier.summary(encounter, use, note);
            notes.add(note);
        }
        return notes.isEmpty() ? "" : " " + String.join(" ", notes);
    }

    /*
     * One of the Spell Effect's uses for each creature a trigger reached, as a piece of Cordon of Arrows' ammunition (PHB
     * p.228) or one of Healing Spirit's heals (XGE p.157) (D199); with none left the spell ends, and its concentration
     * with it. What the summary adds.
     */
    private String spendUses(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                             int creatures) {
        if (spellEffect.usesLeft() == null || creatures == 0) {
            return "";
        }
        SpellEffectEntry spent = effectApplier.spendEffectUses(caster, spellEffect.id(), creatures);
        int left = spent != null && spent.usesLeft() != null ? spent.usesLeft() : 0;
        if (left > 0) {
            return " " + spellEffect.spell() + " has " + left + " left.";
        }
        ConcentrationInstance held = instanceOf(caster, spellEffect);
        if (held != null) {
            effectApplier.endConcentration(encounter, caster, spellEffect.spell() + " ends: none of its uses are left");
        } else {
            effectApplier.endEffect(encounter, caster, spellEffect.id(), "none of its uses are left");
        }
        return " " + spellEffect.spell() + " ends, none of its uses left.";
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
     * dealt counts against the effect's budget, as Guardian of Faith's (PHB p.246). A spell whose modifiers reach a
     * creature whatever its save, as Spirit Guardians' halved speed (PHB p.278, D199), brings one that saved under them
     * too.
     */
    private void afterTrigger(Encounter encounter, EncounterParticipant caster, SpellEffectEntry spellEffect,
                              SpellTemplate.EffectTrigger trigger, List<EffectApplier.Applied> applied,
                              SpellEffects.CastRules rules) {
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
                List<EncounterParticipant> under = !rules.modifiersOnSuccess() ? failed : applied.stream()
                        .filter(result -> result.outcome().outcome() != Outcome.MISS)
                        .map(EffectApplier.Applied::target)
                        .filter(target -> target.getLifeState() != LifeState.DEAD)
                        .distinct()
                        .toList();
                effectApplier.addAffected(caster, spellEffect.id(), under);
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
