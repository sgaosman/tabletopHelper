package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.ActiveSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.ConcentrationRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.EndSpellEffectRequest;
import com.tabletophelper.encounter.dto.MoveSpellEffectRequest;
import com.tabletophelper.encounter.dto.RepeatSpellEffectRequest;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.dto.SpellSlotRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.CastingTime;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A participant's spellcasting in combat: casting a spell paid for from a Spell Source, the same way for player
 * characters and monsters (D165); using a lasting spell's effect again; spell slots; and concentration set by
 * hand. A player acts only for their own character, casting only on its turn (D028) and only what the character
 * can cast now (D164). A class spell uses that class's save DC and attack bonus, and a race or feat spell the
 * caster's main ones. A spell attack bonus or save DC entered in place of the caster's is a Manual Modifier,
 * which a player may enter only while the campaign allows it.
 *
 * <p>A cast costs its casting time from the caster's turn, or a bonus action with Quickened Spell, and a reaction
 * spell may be cast on another creature's turn; a sorcerer's Metamagic is checked and its sorcery points spent with the
 * cast (M18, {@link MetamagicCasting}); a spell that takes longer than a turn is refused to a player in combat and logged for the DM. A lasting
 * spell leaves a Spell Effect on its caster (M15), which its caster uses on their turn at what its template says that
 * costs (D174), and whose area the DM applies to creatures on their turns.
 */
@Service
@RequiredArgsConstructor
public class SpellcastingService {

    private static final String CLASS_ORIGIN = "class:";

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final CasterSpells casterSpells;
    private final SpellCaster spellCaster;
    private final SpellEffects spellEffects;
    private final EffectApplier effectApplier;
    private final ActionEconomyKeeper economy;
    private final ManualModifiers manualModifiers;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;
    private final CharacterService characterService;
    private final CreatureStats stats;
    private final MetamagicCasting metamagicCasting;
    private final Summoner summoner;
    private final FormSpells formSpells;

    /**
     * Casts a spell for a player character or a monster: everything is checked, and the cast charged to the
     * caster's turn economy, before anything is spent; then the Spell Source pays, and the spell is cast at the
     * level that source gives. A player casts on their character's turn, except a reaction spell (D174).
     */
    @Transactional
    public CastSpellResponse castSpell(UUID encounterId, CastSpellRequest request, UUID actorParticipantId,
                                       UUID userId) {
        Encounter encounter = access.beginFor(encounterId, actorParticipantId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);
        requireCanCast(caster);
        manualModifiers.require(encounter, userId, ManualModifiers.forSpell(caster,
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC()));
        SpellSources.Payment payment = casterSpells.payment(caster, request.getSpellName(), request.getSpellSource(),
                request.getSlotLevel());
        MetamagicCasting.Plan metamagic = metamagicCasting.plan(caster, payment, request.getMetamagic(),
                request.getTargetIds());
        boolean quickened = metamagic.has(Metamagic.Option.QUICKENED);
        // A spell longer than a turn would take the caster's action each turn, so the DM's is charged an action.
        // Quickened Spell makes a casting time of 1 action 1 bonus action (PHB p.102)
        ActionCost cost = quickened ? ActionCost.BONUS_ACTION : payment.castingTime().cost().orElse(ActionCost.ACTION);
        access.requireTurnForCost(encounter, actorParticipantId, userId, cost);
        // Wish duplicating another spell (PHB p.288, D190): its option names the spell, which is then cast at its own
        // level with Wish's slot paying for it; the spell's own option follows a colon, as "Protection from Energy: fire"
        Duplicate duplicate = duplicate(payment.spellName(), request.getOption());
        SpellSources.Payment casting = duplicate == null ? payment
                : new SpellSources.Payment(duplicate.spellName(), duplicate.level(), payment.source(),
                        duplicate.level(), payment.slotKey(), payment.pool(), payment.origin(), payment.castingTime());
        requireTargetCount(casting, request.getTargetIds().size(), metamagic.has(Metamagic.Option.TWINNED));
        // A spell with options is cast with one of them (D184)
        String option = spellEffects.option(casting.spellName(),
                duplicate == null ? request.getOption() : duplicate.option());
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.getTargetIds(), userId);
        // Nothing crosses Otiluke's Resilient Sphere, and a spell for one creature type refuses others (D185), checked
        // before anything is spent
        CombatAccess.requireReachable(caster, targets);
        spellCaster.requireTargetTypes(casting.spellName(), targets);
        SpellNumbers own = numbers(caster, payment.origin());
        List<ManualModifiers.Use> modifiers = ManualModifiers.forSpell(own.attackBonus(), own.saveDc(),
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC());
        // A summoning spell's creatures are checked before anything is spent (D182)
        Summoner.Prepared summon = SummonRules.spell(casting.spellName())
                .map(spell -> summoner.prepare(encounter, caster, spell, casting.castLevel(), request.getSummon()))
                .orElse(null);
        if (summon == null && request.getSummon() != null) {
            throw new ValidationException(casting.spellName() + " summons no creatures");
        }
        // Summon Draconic Spirit's Shared Resistances is one of the spirit's own resistances (FTD p.21)
        if (summon != null && summon.plan() != null && summon.plan().variant() != null && option != null
                && !summon.plan().variant().damageResistances().isEmpty()
                && summon.plan().variant().damageResistances().stream().noneMatch(option::equalsIgnoreCase)) {
            throw new ValidationException("The " + summon.plan().variant().name() + " spirit doesn't resist "
                    + option + "; choose one of " + String.join(", ", summon.plan().variant().damageResistances()));
        }

        // A Polymorph-family spell's forms are checked before anything is spent (D192)
        FormSpells.Prepared forms = formSpells.prepare(caster, casting.spellName(), targets, request.getForms());

        String costNote = quickened ? costNote(CastingTime.BONUS_ACTION) : costNote(payment.castingTime());
        Charge.SpellKind kind = payment.spellLevel() == 0 ? Charge.SpellKind.CANTRIP : Charge.SpellKind.LEVELLED;
        String tooLong = payment.castingTime() == CastingTime.LONGER
                ? payment.spellName() + " takes longer than a turn to cast, so it can't be cast in combat" : null;
        economy.charge(encounter, caster,
                Charge.spell(cost, kind, EncounterTurns.isCurrentTurn(encounter, caster.getId())), tooLong, userId,
                new ActionEconomyKeeper.Command(CombatActionType.SPELL_CAST,
                        caster.getDisplayName() + "'s " + payment.spellName() + costNote));

        casterSpells.spend(encounter, caster, payment);
        metamagicCasting.spend(caster, metamagic);
        manualModifiers.log(encounter, CombatActionType.SPELL_CAST, caster,
                caster.getDisplayName() + "'s " + payment.spellName(), userId, modifiers);
        // Casting a spell ends the caster's invisibility from Invisibility (PHB p.254) or Cloak of Shadows (PHB p.63)
        // once the spell is cast, but not what the spell itself gives it
        Set<UUID> ending = EffectApplier.endingOnAttackOrCast(caster);
        SpellCaster.CastResult result = spellCaster.cast(encounter, caster, casting.spellName(), casting.castLevel(),
                targets, own.ability(), attackBonus(own, request.getOverrideSpellAttackBonus()),
                saveDc(own, request.getOverrideSpellSaveDC()), request.getAdvantage(),
                costNote + (duplicate != null ? " through " + payment.spellName() : ""), metamagic, summon, option);
        if (summon != null) {
            summoner.afterCast(encounter, caster, summon, casting.castLevel(), own.ability(),
                    attackBonus(own, request.getOverrideSpellAttackBonus()),
                    saveDc(own, request.getOverrideSpellSaveDC()), "");
        }
        formSpells.afterCast(encounter, caster, forms);
        effectApplier.endOnAttackOrCast(encounter, caster, ending, "it casts a spell");
        return response(encounter, result, payment.castLevel(), payment.source());
    }

    // A creature wearing armour or a shield it isn't proficient with can't cast spells (PHB p.144), nor can one that
    // rages (PHB p.48)
    private void requireCanCast(EncounterParticipant caster) {
        String reason = castingBan(caster);
        if (reason != null) {
            throw new ConflictException(reason);
        }
    }

    // Why the caster can't cast spells at all, or null: it rages (PHB p.48), or wears armour or a shield without
    // proficiency (PHB p.144), or it is in Wild Shape, where concentration on a spell it cast before continues (PHB p.67)
    private String castingBan(EncounterParticipant caster) {
        Optional<WildShapeForm> form = WildShapeForm.of(caster);
        if (form.filter(WildShapeForm::wildShape).isPresent()) {
            return caster.getDisplayName() + " can't cast spells in Wild Shape";
        }
        // A polymorphed creature can't cast spells; Shapechange's caster can, in a form able to (PHB p.266, p.274, D192)
        if (form.isPresent() && !Boolean.TRUE.equals(form.get().canCast())) {
            return caster.getDisplayName() + " can't cast spells in " + form.get().beastName() + " form ("
                    + form.get().spell() + ")";
        }
        if (ClassFeatures.raging(CreatureStats.conditionNames(caster))) {
            return caster.getDisplayName() + " can't cast spells while raging";
        }
        // Feeblemind leaves a creature unable to cast spells (PHB p.239, D190)
        if (CreatureStats.conditionNames(caster).stream().anyMatch(EffectApplier.FEEBLEMINDED::equalsIgnoreCase)) {
            return caster.getDisplayName() + " can't cast spells while feebleminded";
        }
        List<String> notProficient = stats.armour(caster).notProficient().stream().map(item -> item.getName()).toList();
        return notProficient.isEmpty() ? null : caster.getDisplayName() + " can't cast spells while wearing "
                + String.join(" and ", notProficient) + " without proficiency";
    }

    /**
     * The spells a participant can cast now, with what can pay for each (D164, D165), each with the reason when
     * armour worn without proficiency stops all casting (PHB p.144): the DM may read anyone's, a player only their own
     * character's. It changes nothing, so it needs no lock and any status will do.
     */
    @Transactional(readOnly = true)
    public List<SpellOption> spellOptions(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        accessPolicy.requireMember(campaignId, userId);
        EncounterParticipant participant = CombatAccess.target(encounter, participantId, userId);
        accessPolicy.requireControls(campaignId, participant.getControlledByUserId(), userId);
        String ban = castingBan(participant);
        List<SpellOption> options = metamagicCasting.withMetamagic(participant, casterSpells.options(participant))
                .stream()
                .map(option -> option.withCastOptions(castOptions(option.name()))
                        .withDuplicatesUpTo(duplicatesUpTo(option.name())))
                .toList();
        return ban == null ? options : options.stream().map(option -> option.unavailable(ban)).toList();
    }

    /**
     * Uses one of the caster's Spell Effects (M15). Without a trigger it is the caster's own use, on their turn and
     * charged as the template says (D174): an attack, a ram or a heal resolved against the targets, or with none a
     * move, and a move only for an effect whose use just moves it, such as Moonbeam's beam (PHB p.261). An effect with
     * uses, such as Melf's Minute Meteors, spends one for each time it is rolled (XGE p.161). With a trigger it is
     * {@link #triggerSpellEffect}. With no effectId it uses the caster's only effect that can be used that way, and is
     * a 409 when there are several, so a cleric keeping up Spirit Guardians still attacks with Spiritual Weapon.
     */
    @Transactional
    public CastSpellResponse repeatSpellEffect(UUID encounterId, RepeatSpellEffectRequest request,
                                               UUID actorParticipantId, UUID userId) {
        if (request.getTrigger() != null) {
            return triggerSpellEffect(encounterId, request, actorParticipantId, userId);
        }
        // Every directed cost, nothing included, is taken on the caster's turn
        Encounter encounter = access.beginOnTurnFor(encounterId, actorParticipantId, userId,
                CombatAccess.OFF_TURN_SPELL_EFFECT);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);
        manualModifiers.require(encounter, userId, ManualModifiers.forSpell(caster,
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC()));

        SpellEffectEntry effect = effectToUse(caster, request.getEffectId(),
                candidate -> spellEffects.effectUse(candidate.spell()).directedCost() != null,
                "no Spell Effect it can use");
        String spellName = effect.spell();
        SpellEffects.EffectUse how = spellEffects.effectUse(spellName);
        if (how.directedCost() == null) {
            throw new ConflictException(spellName + " needs nothing from its caster; the DM applies what it does to "
                    + "creatures on their turns");
        }
        if (!how.directedResolves() && !request.getTargetIds().isEmpty()) {
            throw new ValidationException("Using " + spellName + " only moves it; what it does to a creature is "
                    + "applied when the creature enters it or starts its turn there");
        }
        int times = timesToUse(effect, how, request.getUses(), request.getTargetIds().isEmpty());
        // Eyebite chooses its effect again for each new creature (PHB p.238, D188); no other use takes an option
        String option = spellEffects.optionEachUse(spellName) && !request.getTargetIds().isEmpty()
                ? spellEffects.option(spellName, request.getOption()) : null;
        if (option == null && request.getOption() != null && !request.getOption().isBlank()) {
            throw new ValidationException("Using " + spellName + " again takes no option");
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.getTargetIds(), userId);
        SpellNumbers own = numbers(caster, casterSpells.origin(caster, spellName));
        List<ManualModifiers.Use> modifiers = ManualModifiers.forSpell(own.attackBonus(), own.saveDc(),
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC());

        ActionCost cost = how.directedCost();
        String costNote = cost == ActionCost.BONUS_ACTION ? " (bonus action)" : "";
        economy.charge(encounter, caster, Charge.of(cost, EncounterTurns.isCurrentTurn(encounter, caster.getId())),
                userId, new ActionEconomyKeeper.Command(CombatActionType.SPELL_EFFECT_REPEAT,
                        caster.getDisplayName() + "'s " + spellName + costNote));

        manualModifiers.log(encounter, CombatActionType.SPELL_EFFECT_REPEAT, caster,
                caster.getDisplayName() + "'s " + spellName, userId, modifiers);
        SpellCaster.CastResult result = how.directedResolves()
                ? spellCaster.repeat(encounter, caster, effect, targets, own.ability(),
                        attackBonus(own, request.getOverrideSpellAttackBonus()),
                        saveDc(own, request.getOverrideSpellSaveDC()), request.getAdvantage(), costNote, times, option)
                : spellCaster.move(encounter, caster, effect, costNote);
        if (effect.usesLeft() != null && how.directedResolves() && !targets.isEmpty()) {
            effectApplier.spendEffectUses(caster, effect.id(), times);
        }
        return response(encounter, result, effect.castLevel(), SpellSource.NONE);
    }

    /**
     * What a Spell Effect's area does by itself to the targets at a moment of a turn (M15): Moonbeam's damage to a
     * creature that enters the beam or starts its turn there (PHB p.261), Flaming Sphere's to one that ends its turn
     * beside it (PHB p.242). There is no map, so the DM applies it, on any creature's turn, and it costs the caster
     * nothing; the caster's player uses the effect only on their own turn.
     */
    private CastSpellResponse triggerSpellEffect(UUID encounterId, RepeatSpellEffectRequest request,
                                                 UUID actorParticipantId, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);
        SpellTrigger when = request.getTrigger();
        SpellEffectEntry effect = effectToUse(caster, request.getEffectId(),
                candidate -> spellEffects.effectUse(candidate.spell()).triggers().contains(when),
                "no Spell Effect that acts at that moment");
        String spellName = effect.spell();
        if (!spellEffects.effectUse(spellName).triggers().contains(when)) {
            throw new ConflictException(spellName + " does nothing by itself at that moment");
        }
        if (request.getTargetIds().isEmpty()) {
            throw new ValidationException("Choose the creatures " + spellName + " acts on");
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.getTargetIds(), userId);
        SpellNumbers own = numbers(caster, casterSpells.origin(caster, spellName));
        List<ManualModifiers.Use> modifiers = ManualModifiers.forSpell(own.attackBonus(), own.saveDc(),
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC());
        manualModifiers.log(encounter, CombatActionType.SPELL_EFFECT_REPEAT, caster,
                caster.getDisplayName() + "'s " + spellName, userId, modifiers);
        SpellCaster.CastResult result = spellCaster.trigger(encounter, caster, effect, when, targets, own.ability(),
                attackBonus(own, request.getOverrideSpellAttackBonus()), saveDc(own, request.getOverrideSpellSaveDC()));
        return response(encounter, result, effect.castLevel(), SpellSource.NONE);
    }

    /**
     * Ends one of a participant's Spell Effects by hand (M15): the DM for anyone, a player for their own character, on
     * any turn. An effect that belongs to the participant's concentration ends it too, taking its conditions with it
     * (D162); one kept up without concentration, such as Spiritual Weapon, just ends.
     */
    @Transactional
    public EncounterResponse endSpellEffect(UUID encounterId, EndSpellEffectRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getParticipantId(), userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, request.getParticipantId());
        SpellEffectEntry effect = EffectApplier.effects(caster).stream()
                .filter(candidate -> candidate.id().equals(request.getEffectId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Spell Effect not found"));
        effectApplier.endEffect(encounter, caster, effect.id(), "ended by hand");
        ConcentrationInstance held = caster.getConcentration();
        if (held != null && held.id().equals(effect.concentrationInstanceId())) {
            effectApplier.endConcentration(encounter, caster, effect.spell() + " ended by hand");
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * The DM's record that a caster's lasting spell is already going (D184), such as Mage Armor or Aid cast before the
     * fight, or Bless cast just before initiative, while the encounter is prepared or under way: no slot is spent and
     * nothing is cast, rolled or logged as a cast. A concentration spell starts the caster's concentration, ending any
     * other with its Spell Effect, and the spell's Ongoing Modifiers start on the targets, or on the caster for a spell
     * on the caster. Under way it is a command undo covers.
     */
    @Transactional
    public EncounterResponse startActiveSpell(UUID encounterId, ActiveSpellRequest request, UUID userId) {
        Encounter encounter = access.beginAsDmPreparingOrActive(encounterId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, request.getCasterId());
        String spellName = spellEffects.spellName(request.getSpellName())
                .orElseThrow(() -> new ValidationException("No spell named " + request.getSpellName()));
        int spellLevel = spellEffects.spellLevel(spellName);
        int slotLevel = request.getSlotLevel() != null ? request.getSlotLevel() : spellLevel;
        if (slotLevel < spellLevel || (spellLevel == 0 && slotLevel != 0)) {
            throw new ValidationException(spellName + " can't be cast at level " + slotLevel);
        }
        String option = spellEffects.option(spellName, request.getOption());
        List<UUID> targetIds = request.getTargetIds() != null ? request.getTargetIds() : List.of();
        spellEffects.maxTargets(spellName, slotLevel).ifPresent(max -> {
            if (targetIds.size() > max) {
                throw new ValidationException(spellName + " can affect at most " + max + " creature"
                        + (max == 1 ? "" : "s") + " at level " + slotLevel);
            }
        });
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, targetIds, userId);
        if (targets.isEmpty() && !spellEffects.selfOnly(spellName)
                && !spellEffects.modifiers(spellName, slotLevel, option, 0, null).modifiers().isEmpty()) {
            throw new ValidationException("Choose the creatures " + spellName + " affects");
        }
        CombatAccess.requireNotDead(targets);
        spellCaster.startActive(encounter, caster, spellName, slotLevel, targets, option);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * Moves a caster's Hunter's Mark or Hex to a new creature (PHB p.251, D184): with a bonus action on the caster's
     * turn, once every creature it was on has dropped to 0 hit points. The DM's for anyone, a player's for their own
     * character.
     */
    @Transactional
    public EncounterResponse moveSpellEffect(UUID encounterId, MoveSpellEffectRequest request, UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, request.getParticipantId(), userId,
                CombatAccess.OFF_TURN_SPELL_EFFECT);
        EncounterParticipant caster = CombatAccess.participant(encounter, request.getParticipantId());
        SpellEffectEntry effect = EffectApplier.effects(caster).stream()
                .filter(candidate -> candidate.id().equals(request.getEffectId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Spell Effect not found"));
        if (!spellEffects.retargetable(effect.spell())) {
            throw new ConflictException(effect.spell() + " can't be moved to another creature");
        }
        boolean standing = encounter.getParticipants().stream()
                .filter(p -> effect.affected().contains(p.getId()))
                .anyMatch(p -> p.getHpCurrent() != null && p.getHpCurrent() > 0
                        && p.getLifeState() != com.tabletophelper.rules.combat.LifeState.DEAD);
        // Fizban's Platinum Shield moves whenever its caster likes (FTD p.20, D188)
        if (standing && !spellEffects.castRules(effect.spell()).movable()) {
            throw new ConflictException(effect.spell() + " moves only once its creature has dropped to 0 hit points");
        }
        EncounterParticipant target = CombatAccess.target(encounter, request.getTargetId(), userId);
        CombatAccess.requireNotDead(List.of(target));
        economy.charge(encounter, caster, Charge.of(ActionCost.BONUS_ACTION,
                        EncounterTurns.isCurrentTurn(encounter, caster.getId())), userId,
                new ActionEconomyKeeper.Command(CombatActionType.SPELL_EFFECT_REPEAT,
                        caster.getDisplayName() + "'s " + effect.spell() + " (bonus action)"));
        effectApplier.moveTo(encounter, caster, effect, target);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    // The options a cast of the spell chooses between (D184); none when its template can't be read, so the rest of the
    // list still loads
    private List<String> castOptions(String spellName) {
        try {
            return spellEffects.castOptions(spellName);
        } catch (ConflictException e) {
            return List.of();
        }
    }

    // The highest level of spell a cast of the spell duplicates, as Wish's 8th (D190); null for none, or when its
    // template can't be read, so the rest of the list still loads
    private Integer duplicatesUpTo(String spellName) {
        try {
            return spellEffects.highLevel(spellName).castsSpellUpTo();
        } catch (ConflictException e) {
            return null;
        }
    }

    // The effect the request names, or the caster's only one that fits; a 409 for none, or several to choose from
    private static SpellEffectEntry effectToUse(EncounterParticipant caster, UUID effectId,
                                                Predicate<SpellEffectEntry> fits, String none) {
        List<SpellEffectEntry> effects = EffectApplier.effects(caster);
        if (effectId != null) {
            return effects.stream()
                    .filter(effect -> effect.id().equals(effectId))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Spell Effect not found"));
        }
        List<SpellEffectEntry> fitting = effects.stream().filter(fits).toList();
        if (fitting.isEmpty()) {
            throw new ConflictException(caster.getDisplayName() + " has " + none);
        }
        if (fitting.size() > 1) {
            throw new ConflictException(caster.getDisplayName() + " has more than one Spell Effect ("
                    + String.join(", ", fitting.stream().map(SpellEffectEntry::spell).toList())
                    + "); choose which to use");
        }
        return fitting.getFirst();
    }

    // How many times the use rolls: one, or the uses asked for where the effect has a count, which it must still have
    private static int timesToUse(SpellEffectEntry effect, SpellEffects.EffectUse how, Integer asked,
                                  boolean noTargets) {
        int times = asked != null ? asked : 1;
        if (effect.usesLeft() == null) {
            if (times > 1) {
                throw new ValidationException(effect.spell() + " is used once at a time");
            }
            return 1;
        }
        int most = how.maxUsesEachTurn() != null ? how.maxUsesEachTurn() : 1;
        if (times > most) {
            throw new ValidationException(effect.spell() + " can spend at most " + most + " at a time");
        }
        if (!noTargets && effect.usesLeft() < times) {
            throw new ConflictException(effect.spell() + " has " + effect.usesLeft() + " left");
        }
        return times;
    }

    @Transactional
    public EncounterResponse useSpellSlot(UUID encounterId, SpellSlotRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getParticipantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.getParticipantId());
        Map<String, SpellSlot> slots = slotsOf(participant);
        String key = slotKey(slots, request.getSlotLevel());
        SpellSlot slot = slots.get(key);
        if (slot == null || slot.remaining() <= 0) {
            throw new ConflictException("No level " + request.getSlotLevel() + " spell slots remaining");
        }

        SpellSlot spent = slot.spend();
        slots.put(key, spent);
        participant.setSpellSlotsCurrent(slots);
        combatLog.write(encounter, CombatActionType.SPELL_SLOT_USE, participant.getDisplayName() + " uses a level "
                + key + " spell slot (" + spent.remaining() + "/" + spent.total() + " remaining)", null, participant);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse restoreSpellSlot(UUID encounterId, SpellSlotRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.getParticipantId());
        Map<String, SpellSlot> slots = slotsOf(participant);
        String key = slotKey(slots, request.getSlotLevel());
        SpellSlot slot = slots.get(key);
        if (slot == null) {
            throw new ValidationException("Participant has no level " + request.getSlotLevel() + " spell slots");
        }
        if (slot.used() <= 0) {
            throw new ConflictException("Level " + key + " spell slots already at maximum");
        }

        SpellSlot restored = slot.restore();
        slots.put(key, restored);
        participant.setSpellSlotsCurrent(slots);
        combatLog.write(encounter, CombatActionType.SPELL_SLOT_RESTORE, participant.getDisplayName()
                + " restores a level " + key + " spell slot (" + restored.remaining() + "/" + restored.total()
                + " remaining)", null, participant);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * Concentration set by hand (D028): naming a spell starts a Concentration Instance for it, ending the one
     * held first, with the spell's Spell Effect when it has a lasting one (M15); naming none ends it. Either way, the conditions the ended instance created end too (D162).
     */
    @Transactional
    public EncounterResponse setConcentration(UUID encounterId, ConcentrationRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getParticipantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.getParticipantId());
        String spellName = request.getSpellName() != null && !request.getSpellName().isBlank()
                ? request.getSpellName().trim() : null;
        if (spellName != null) {
            ConcentrationInstance instance = effectApplier.startConcentration(encounter, participant, spellName, null,
                    null);
            // A lasting spell's effect comes with it, at an unknown slot level, so its caster can use it (M15)
            spellEffects.lasting(spellName, 0).ifPresent(lasting -> effectApplier.startEffect(encounter, participant,
                    spellName, null, instance, lasting.durationRounds(), lasting.uses()));
        } else {
            effectApplier.endConcentration(encounter, participant, "ended by hand");
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    private CastSpellResponse response(Encounter encounter, SpellCaster.CastResult result, int castLevel,
                                       SpellSource source) {
        return CastSpellResponse.builder()
                .encounterState(encounterService.toResponse(encounterRepository.save(encounter)))
                .spellName(result.spellName())
                .slotLevelUsed(castLevel)
                .spellSource(source)
                .autoResolved(result.autoResolved())
                .resultSummary(result.summary())
                .targets(result.targets())
                .manualResolutionReason(result.manualReason())
                .build();
    }

    // How the log names a cast's cost when it isn't an action (D174)
    private static String costNote(CastingTime castingTime) {
        return switch (castingTime) {
            case ACTION -> "";
            case BONUS_ACTION -> " (bonus action)";
            case REACTION -> " (reaction)";
            case LONGER -> " (longer than a turn)";
        };
    }

    // The template's limit at the cast level, and one more with Twinned Spell (PHB p.102)
    /** The spell a cast of Wish duplicates (D190), at its own level, with its own option or null. */
    private record Duplicate(String spellName, int level, String option) {
    }

    /**
     * The spell a spell that duplicates others casts, as Wish duplicates any spell of 8th level or lower (PHB p.288,
     * D190): the request's option names it, with that spell's own option after a colon. Null for a spell that duplicates
     * none, or a Wish cast with no option, which is the DM's to resolve.
     *
     * @throws ValidationException an unknown spell, one too high, or the duplicating spell itself
     */
    private Duplicate duplicate(String spellName, String requested) {
        Integer maxLevel = spellEffects.highLevel(spellName).castsSpellUpTo();
        if (maxLevel == null || requested == null || requested.isBlank()) {
            return null;
        }
        String wanted = requested.trim();
        String ownOption = null;
        Optional<String> found = spellEffects.spellName(wanted);
        if (found.isEmpty() && wanted.contains(":")) {
            int colon = wanted.lastIndexOf(':');
            ownOption = wanted.substring(colon + 1).trim();
            found = spellEffects.spellName(wanted.substring(0, colon).trim());
        }
        String name = found.orElseThrow(() -> new ValidationException(spellName + " can't duplicate " + wanted
                + ": no such spell"));
        if (name.equalsIgnoreCase(spellName)) {
            throw new ValidationException(spellName + " can't duplicate itself");
        }
        int level = spellEffects.spellLevel(name);
        if (level > maxLevel) {
            throw new ValidationException(spellName + " can duplicate a spell of level " + maxLevel + " or lower, and "
                    + name + " is level " + level);
        }
        return new Duplicate(name, level, ownOption != null && !ownOption.isBlank() ? ownOption : null);
    }

    private void requireTargetCount(SpellSources.Payment payment, int targetCount, boolean twinned) {
        OptionalInt max = spellEffects.maxTargets(payment.spellName(), payment.castLevel());
        if (twinned) {
            max = OptionalInt.of(max.orElse(1) + 1);
        }
        if (max.isPresent() && targetCount > max.getAsInt()) {
            int limit = max.getAsInt();
            throw new ValidationException(payment.spellName() + " can target at most " + limit
                    + " creature" + (limit != 1 ? "s" : "") + " at level " + payment.castLevel()
                    + ", but " + targetCount + " targets were selected");
        }
    }

    /**
     * A spell's own attack bonus and save DC, before any Manual Modifier, and the ability it is cast with, whose
     * modifier its damage and healing add; null where the caster has none.
     */
    private record SpellNumbers(Integer attackBonus, Integer saveDc, String ability) {
    }

    /**
     * The numbers a spell is cast with (D164): a player character's class spell uses that class's, worked out from
     * the live sheet as saves and resistances are, and a cantrip its subclass taught, such as a Nature Domain cleric's
     * druid cantrip (PHB p.61), the subclass's class's when that class casts; a race or feat spell, a subclass cantrip
     * of a class that doesn't cast, and a monster's, the caster's main ones, with its race and feat spellcasting
     * ability or the monster's spellcasting ability.
     */
    private SpellNumbers numbers(EncounterParticipant caster, String origin) {
        PlayerCharacter character = caster.getCharacter();
        if (character != null && origin != null
                && (origin.startsWith(CLASS_ORIGIN) || origin.startsWith(KnownSpell.SUBCLASS_SOURCE)
                    || origin.startsWith(KnownSpell.MYSTIC_ARCANUM_SOURCE))) {
            // A Mystic Arcanum spell is a warlock spell, cast with the warlock's numbers (PHB p.108)
            Optional<ClassSpellcasting> casting = origin.startsWith(CLASS_ORIGIN)
                    ? characterService.classSpellcasting(character, origin.substring(CLASS_ORIGIN.length()))
                    : origin.startsWith(KnownSpell.MYSTIC_ARCANUM_SOURCE)
                    ? characterService.classSpellcasting(character,
                            origin.substring(KnownSpell.MYSTIC_ARCANUM_SOURCE.length()))
                    : characterService.subclassSpellcasting(character,
                            origin.substring(KnownSpell.SUBCLASS_SOURCE.length()));
            if (casting.isPresent()) {
                return new SpellNumbers(casting.get().spellAttackBonus(), casting.get().spellSaveDc(),
                        casting.get().ability());
            }
        }
        // A player character's race and feat spells use its main ability, the chosen one or its first class's
        String ability = character != null ? characterService.mainSpellcastingAbility(character)
                : caster.getSpellcastingAbility();
        return new SpellNumbers(caster.getSpellAttackBonus(), caster.getSpellSaveDc(), ability);
    }

    // The spell's own numbers unless a Manual Modifier replaces them; a caster without them has +0 and DC 10
    private static int attackBonus(SpellNumbers own, Integer entered) {
        if (entered != null) return entered;
        return own.attackBonus() != null ? own.attackBonus() : 0;
    }

    private static int saveDc(SpellNumbers own, Integer entered) {
        if (entered != null) return entered;
        return own.saveDc() != null ? own.saveDc() : 10;
    }

    // A level's slots, or a Warlock's pact slots of that level when there are none
    private static String slotKey(Map<String, SpellSlot> slots, int level) {
        String key = String.valueOf(level);
        return slots.containsKey(key) || !slots.containsKey("pact_" + level) ? key : "pact_" + level;
    }

    // A copy, so callers can change it and set it back
    private static Map<String, SpellSlot> slotsOf(EncounterParticipant participant) {
        return participant.getSpellSlotsCurrent() != null
                ? new LinkedHashMap<>(participant.getSpellSlotsCurrent()) : new LinkedHashMap<>();
    }
}
