package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterCommands;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.dto.CommandSummonsRequest;
import com.tabletophelper.encounter.dto.CompanionSummonRequest;
import com.tabletophelper.encounter.dto.DismissSummonsRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.PlaceSummonRequest;
import com.tabletophelper.encounter.dto.SummonOptions;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.BeastShapes;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.SpiritScaling;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.rules.combat.SummonSpirits;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The summoning commands beside casting (D182): what a spell can bring, the DM's creatures for a summoning that waits
 * or in place of a placed one's, a summoning the DM places with no slot spent (Find Familiar, Find Steed, Animate Dead,
 * Tiny Servant and the like, whose casting is longer than a turn), the DM's dismissal of summoned creatures, and a
 * caster's bonus-action command to Animate Dead's and Tiny Servant's creatures (PHB p.212, XGE p.168). Casting a
 * summoning spell is {@link SpellcastingService#castSpell}, which ends with {@link Summoner#afterCast}.
 */
@Service
@RequiredArgsConstructor
public class SummonService {

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final EncounterCommands encounterCommands;
    private final CommandRecorder commandRecorder;
    private final Summoner summoner;
    private final SpiritTemplates spiritTemplates;
    private final EffectApplier effectApplier;
    private final ActionEconomyKeeper economy;
    private final CombatLogWriter combatLog;
    private final MonsterRepository monsterRepository;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * What the spell can bring when the caster casts it at this slot level: the DM may ask for anyone, a player for a
     * participant they control. It changes nothing, so it needs no lock and any status will do.
     */
    @Transactional(readOnly = true)
    public SummonOptions options(UUID encounterId, UUID participantId, String spellName, Integer slotLevel,
                                 UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        accessPolicy.requireMember(campaignId, userId);
        EncounterParticipant caster = CombatAccess.target(encounter, participantId, userId);
        accessPolicy.requireControls(campaignId, caster.getControlledByUserId(), userId);
        SummonRules.Spell spell = Summoner.spell(spellName);
        int slot = slotLevel != null ? slotLevel : spell.level();
        if (slot < spell.level() || slot > 9) {
            throw new ValidationException(spell.name() + " is cast with a slot of level " + spell.level()
                    + " to 9, not " + slot);
        }
        boolean hasFamiliar = Summoner.hasFamiliar(encounter, caster);
        List<SummonRules.Option> options = SummonRules.options(spell, slot, hasFamiliar);
        boolean pact = CombatAccess.isDm(encounter, userId)
                || FeatureEffects.hasFeature(caster.getCharacter(), SummonRules.PACT_OF_THE_CHAIN);

        SummonOptions.SpiritPreview preview = null;
        List<String> variants = List.of();
        List<SummonOptions.Candidate> candidates;
        if (spell.kind() == SummonRules.Kind.SPIRIT) {
            SummonSpirits.Spirit spirit = spiritTemplates.spirit(spell.name())
                    .orElseThrow(() -> new ConflictException(spell.name() + "'s spirit isn't in the data"));
            Monster monster = monsterRepository.findByNameIgnoreCaseAndSource(spirit.monster(), spirit.source())
                    .orElseThrow(() -> new ConflictException(spirit.monster() + "'s stat block isn't loaded"));
            variants = spirit.variantNames();
            Map<String, Integer> hitPoints = new LinkedHashMap<>();
            spirit.variants().forEach(variant -> hitPoints.put(variant.name(),
                    SpiritTemplates.hitPoints(spirit, variant, slot)));
            preview = new SummonOptions.SpiritPreview(monster.getId(), monster.getName(),
                    SpiritTemplates.armourClass(spirit, slot), hitPoints, SpiritScaling.attacks(slot),
                    caster.getSpellAttackBonus() != null ? caster.getSpellAttackBonus() : 0,
                    caster.getSpellSaveDc() != null ? caster.getSpellSaveDc() : 10);
            candidates = List.of(candidate(monster));
        } else {
            candidates = candidates(spell, options, pact);
        }
        return new SummonOptions(spell.name(), spell.kind(), slot, spell.level(), spell.concentration(),
                spell.castInCombat(), Summoner.dmChooses(encounter, spell),
                options.stream().map(option -> new SummonOptions.Option(option.id(), option.count(), option.maxCr(),
                        option.label())).toList(),
                SummonRules.multiplier(spell, slot), variants, spell.initiativeModes(),
                spell.defaultInitiativeMode(), spell.houseRuleModes(), spell.controller(), spell.typeChoices(),
                spell.creatureType(), spell.cannotAttack(), spell.needsCommand(), spell.vanishesAtZero(), hasFamiliar,
                candidates, preview);
    }

    /**
     * The DM's creatures for a summoning (D182): one waiting for the DM gets them, and a placed one's creatures
     * disappear for the new ones. The option is the one the casting chose unless the DM names another; Summon Lesser
     * Demons keeps the one its d6 rolled (XGE p.167), and naming another is a 400.
     */
    @Transactional
    public EncounterResponse place(UUID encounterId, PlaceSummonRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        Map.Entry<EncounterParticipant, SpellEffectEntry> found = Summoner.effect(encounter, request.effectId())
                .filter(entry -> !EncounterTurns.dismissed(entry.getKey()))
                .orElseThrow(() -> new NotFoundException("Spell Effect not found"));
        EncounterParticipant caster = found.getKey();
        SpellEffectEntry effect = found.getValue();
        if (effect.summon() == null) {
            throw new ConflictException(effect.spell() + " summons nothing");
        }
        SummonRules.Spell spell = Summoner.spell(effect.spell());
        int slot = effect.slotLevel() != null ? effect.slotLevel() : spell.level();
        var choice = request.choice();
        // Summon Lesser Demons' d6 sets how many demons come and their challenge rating; the DM chooses only which
        // demons (XGE p.167), so the rolled option stays
        if (spell.kind() == SummonRules.Kind.LESSER_DEMONS && choice.optionId() != null
                && !choice.optionId().trim().equals(effect.summon().optionId())) {
            throw new ValidationException(spell.name() + "'s d6 set its option to " + effect.summon().optionId()
                    + ", so the DM chooses demons for that, not " + choice.optionId().trim());
        }
        if (choice.optionId() == null && effect.summon().optionId() != null) {
            choice = new com.tabletophelper.encounter.dto.SummonChoice(effect.summon().optionId(), choice.creatures(),
                    choice.variant(), choice.initiativeMode(), choice.creatureType());
        }
        Summoner.Plan plan = summoner.check(encounter, caster, spell, slot, choice, true);
        summoner.placeForDm(encounter, caster, effect, plan);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * A summoning the DM places with no slot spent (D182), while the encounter is prepared or under way: a familiar
     * or steed found before the fight (PHB p.240), Animate Dead's undead (PHB p.212), a tiny servant (XGE p.168) or any
     * other summoning cast outside the fight. Linked to the caster's concentration when asked, or when the spell needs
     * it.
     */
    @Transactional
    public EncounterResponse companion(UUID encounterId, CompanionSummonRequest request, UUID userId) {
        Encounter encounter = beginPreparedOrActive(encounterId, userId);
        summoner.companion(encounter, request);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /** The DM dismisses summoned creatures, while their spell goes on (D182). */
    @Transactional
    public EncounterResponse dismiss(UUID encounterId, DismissSummonsRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        List<EncounterParticipant> creatures = request.participantIds().stream().distinct()
                .map(id -> CombatAccess.participant(encounter, id))
                .toList();
        for (EncounterParticipant creature : creatures) {
            if (creature.getSummon() == null) {
                throw new ValidationException(creature.getDisplayName() + " wasn't summoned, so it can't be dismissed;"
                        + " remove it instead");
            }
        }
        creatures.forEach(creature -> effectApplier.dismiss(encounter, creature, "dismissed by the DM"));
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * A caster's bonus action commanding any or all of the creatures its Animate Dead or Tiny Servant made (PHB p.212,
     * XGE p.168): its player may act for them until each one's next turn ends. The DM for anyone, a player for their
     * own caster on its turn; the bonus action is charged as any other (D174).
     */
    @Transactional
    public EncounterResponse command(UUID encounterId, CommandSummonsRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.casterParticipantId(), userId);
        access.requireTurnForActionCost(encounter, request.casterParticipantId(), userId, ActionCost.BONUS_ACTION);
        EncounterParticipant caster = CombatAccess.participant(encounter, request.casterParticipantId());
        List<EncounterParticipant> creatures = request.participantIds().stream().distinct()
                .map(id -> CombatAccess.participant(encounter, id))
                .toList();
        for (EncounterParticipant creature : creatures) {
            SummonLink link = creature.getSummon();
            if (link == null || !caster.getId().equals(creature.getSummonedByParticipantId())) {
                throw new ValidationException(creature.getDisplayName() + " isn't one of "
                        + caster.getDisplayName() + "'s summoned creatures");
            }
            if (!link.needsCommand()) {
                throw new ValidationException(creature.getDisplayName() + " needs no command to act");
            }
        }
        economy.charge(encounter, caster, Charge.of(ActionCost.BONUS_ACTION,
                        EncounterTurns.isCurrentTurn(encounter, caster.getId())), userId,
                new ActionEconomyKeeper.Command(CombatActionType.SUMMON, caster.getDisplayName() + "'s command"));
        creatures.forEach(creature -> effectApplier.command(creature, encounter.getRoundNumber()));
        combatLog.write(encounter, CombatActionType.SUMMON, caster.getDisplayName() + " commands "
                + creatures.stream().map(EncounterParticipant::getDisplayName).collect(Collectors.joining(", "))
                + " (bonus action)", caster, null);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    // The DM's command while the encounter is prepared or under way; recorded for undo once it is Active (M14)
    private Encounter beginPreparedOrActive(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        if (encounter.getStatus() == EncounterStatus.ACTIVE) {
            commandRecorder.record(encounter, userId);
        } else if (encounter.getStatus() != EncounterStatus.PREPARING) {
            throw new ConflictException("A summoning can be placed only while the encounter is prepared or active");
        }
        return encounter;
    }

    // The creatures that fit any of the options, from the rulebooks and with hit points, each name once, by challenge
    // rating then name
    private List<SummonOptions.Candidate> candidates(SummonRules.Spell spell, List<SummonRules.Option> options,
                                                     boolean pact) {
        List<Monster> pool = new ArrayList<>();
        switch (spell.kind()) {
            case CONJURE_ANIMALS -> pool.addAll(monsterRepository.findByTypeIgnoreCase("beast"));
            case LESSER_DEMONS -> pool.addAll(monsterRepository.findByTypeIgnoreCase("fiend"));
            case FAMILIAR, FLOCK_OF_FAMILIARS -> {
                named(pool, SummonRules.FAMILIAR_FORMS);
                if (pact && spell.kind() == SummonRules.Kind.FAMILIAR) {
                    named(pool, SummonRules.PACT_OF_THE_CHAIN_FORMS);
                }
            }
            case STEED -> named(pool, SummonRules.STEED_FORMS);
            case ANIMATE_DEAD -> named(pool, SummonRules.ANIMATE_DEAD_FORMS);
            case TINY_SERVANT -> monsterRepository.findByNameIgnoreCaseAndSource(SummonRules.TINY_SERVANT, "XGE")
                    .ifPresent(pool::add);
            default -> {
            }
        }
        return pool.stream()
                .filter(monster -> BeastShapes.offered(monster.getSource(), monster.getHitPoints()))
                .filter(monster -> options.stream().anyMatch(option -> SummonRules.refusal(spell, option,
                        monster.getName(), monster.getChallengeRating(), monster.getType(), monster.getSubtype(), pact)
                        .isEmpty()))
                // Each creature once: a reprint replaces the older printing, such as MPMM's Maw Demon VGM's
                .collect(Collectors.toMap(monster -> monster.getName().trim().toLowerCase(Locale.ROOT),
                        monster -> monster, (kept, other) -> SummonRules.reprints(other.getSource(), kept.getSource())
                                ? other : kept, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparingDouble((Monster monster) ->
                                BeastShapes.challengeRating(monster.getChallengeRating()).orElse(0))
                        .thenComparing(Monster::getName))
                .map(SummonService::candidate)
                .toList();
    }

    // The Monster Manual's creature of each name
    private void named(List<Monster> pool, List<String> names) {
        names.forEach(name -> monsterRepository.findByNameIgnoreCaseAndSource(name, "MM").ifPresent(pool::add));
    }

    private static SummonOptions.Candidate candidate(Monster monster) {
        return new SummonOptions.Candidate(monster.getId(), monster.getName(), monster.getChallengeRating(),
                BeastShapes.challengeRating(monster.getChallengeRating()).isPresent()
                        ? BeastShapes.challengeRating(monster.getChallengeRating()).getAsDouble() : null,
                monster.getHitPoints() != null ? monster.getHitPoints() : 0,
                monster.getArmourClass() != null ? monster.getArmourClass() : 10,
                monster.getType(), monster.getSize(), monster.getSource());
    }
}
