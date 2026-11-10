package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.ChangeFormRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.FormOptions;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.BeastShapes;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.ShapeSpells;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The spells that turn a creature into another creature (D192, {@link ShapeSpells}): Polymorph (PHB p.266), Mass
 * Polymorph (XGE p.160), True Polymorph (PHB p.283), Shapechange (PHB p.274) and Animal Shapes (PHB p.212). The caster
 * picks each creature's form with the cast, checked before anything is spent; once the cast has resolved, each creature
 * the spell's entry reached (a failed save, a willing creature) takes its form on that entry, so the form's statistics
 * are what every rule reads ({@link CreatureStats}, {@link TurnEconomies}, the monster actions) until the entry goes:
 * the spell's end, its concentration lost, the DM removing it, or the form dropping to 0 hit points, when the creature
 * reverts with the excess damage carried over ({@link EffectApplier}). The form lives among the creature's conditions,
 * with its hit points, maximum and AC, so undo restores it (D176).
 */
@Service
@RequiredArgsConstructor
public class FormSpells {

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final ActionEconomyKeeper economy;
    private final EffectApplier effectApplier;
    private final WildShapeEffects wildShapeEffects;
    private final CombatLogWriter combatLog;
    private final MonsterRepository monsterRepository;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * The forms chosen with a cast, by the creature that takes each.
     *
     * @param rules the spell's rules
     * @param forms each creature's chosen form, by the creature's participant ID
     */
    public record Prepared(ShapeSpells.Rules rules, Map<UUID, Monster> forms) {
    }

    /**
     * Checks the forms chosen for a cast before anything is spent: every creature the spell may transform needs one
     * (the caster for Shapechange), each within the spell's limits for that creature.
     *
     * @return the forms, or null for a spell that gives none
     * @throws ValidationException for forms given to a spell without them, a form missing or for a creature the spell
     *                             doesn't reach, or a creature not found
     * @throws ConflictException   for a form beyond the spell's limits
     */
    public Prepared prepare(EncounterParticipant caster, String spellName, List<EncounterParticipant> targets,
                            Map<UUID, UUID> chosen) {
        Optional<ShapeSpells.Rules> found = ShapeSpells.of(spellName);
        Map<UUID, UUID> picks = chosen != null ? chosen : Map.of();
        if (found.isEmpty()) {
            if (!picks.isEmpty()) {
                throw new ValidationException(spellName + " gives no creature a new form");
            }
            return null;
        }
        ShapeSpells.Rules rules = found.get();
        List<EncounterParticipant> creatures = rules == ShapeSpells.SHAPECHANGE ? List.of(caster) : targets;
        for (UUID id : picks.keySet()) {
            if (creatures.stream().noneMatch(creature -> creature.getId().equals(id))) {
                throw new ValidationException("A form is chosen only for a creature " + spellName + " transforms");
            }
        }
        Map<UUID, Monster> forms = new LinkedHashMap<>();
        for (EncounterParticipant creature : creatures) {
            UUID pick = picks.get(creature.getId());
            if (pick == null) {
                throw new ValidationException("Choose the creature " + creature.getDisplayName() + " becomes with "
                        + spellName);
            }
            Monster form = monsterRepository.findById(pick)
                    .orElseThrow(() -> new ValidationException("Creature not found"));
            refusal(rules, caster, creature, form).ifPresent(reason -> {
                throw new ConflictException(reason + " for " + creature.getDisplayName());
            });
            forms.put(creature.getId(), form);
        }
        return new Prepared(rules, forms);
    }

    /**
     * Once the cast has resolved, each creature the spell's entry reached takes its form; one at 0 hit points is
     * unaffected, and loses the entry (PHB p.266). Returns what the log adds, or nothing.
     */
    public String afterCast(Encounter encounter, EncounterParticipant caster, Prepared prepared) {
        if (prepared == null) {
            return "";
        }
        ShapeSpells.Rules rules = prepared.rules();
        List<String> notes = new ArrayList<>();
        for (Map.Entry<UUID, Monster> pick : prepared.forms().entrySet()) {
            EncounterParticipant creature = CombatAccess.participant(encounter, pick.getKey());
            Optional<ConditionEntry> entry = spellEntry(caster, creature, rules);
            if (entry.isEmpty()) {
                continue;
            }
            if (creature.getLifeState() != LifeState.ALIVE) {
                effectApplier.endSpellConditions(encounter, caster, creature, rules.spell(),
                        "it has 0 hit points, so the spell has no effect on it");
                continue;
            }
            notes.add(transform(encounter, caster, creature, entry.get(), pick.getValue(), rules, false));
        }
        return notes.isEmpty() ? "" : " " + String.join(" ", notes);
    }

    /**
     * The creatures the spell can turn the target into now, for the cast's picker: the DM may read anyone's, a player
     * only for a cast of their own character's. It changes nothing, so it needs no lock and any status will do.
     */
    @Transactional(readOnly = true)
    public FormOptions options(UUID encounterId, UUID casterId, String spellName, UUID targetId, UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        accessPolicy.requireMember(campaignId, userId);
        EncounterParticipant caster = CombatAccess.target(encounter, casterId, userId);
        accessPolicy.requireControls(campaignId, caster.getControlledByUserId(), userId);
        ShapeSpells.Rules rules = ShapeSpells.of(spellName)
                .orElseThrow(() -> new ValidationException(spellName + " gives no creature a new form"));
        EncounterParticipant target = rules == ShapeSpells.SHAPECHANGE ? caster
                : CombatAccess.target(encounter, targetId != null ? targetId : casterId, userId);
        double max = maxRating(rules, caster, target);
        Iterable<Monster> pool = rules.beastsOnly() ? monsterRepository.findByTypeIgnoreCase("beast")
                : monsterRepository.findAll();
        return new FormOptions(rules.spell(), target.getId(), ShapeSpells.label(max), rules.beastsOnly(),
                rules.largestSize(), wildShapeEffects.offered(pool, monster -> refusal(rules, max, monster)));
    }

    /**
     * A new form with the caster's action (D192): Shapechange's caster takes another form (PHB p.274), keeping its
     * current hit points when the new form has more; Animal Shapes' caster gives a creature it transformed a new beast
     * form (PHB p.212). The DM may act for anyone, a player for their own character on its turn.
     *
     * @throws ConflictException   for a caster not keeping up Shapechange or Animal Shapes, a creature it hasn't
     *                             transformed, a form beyond the limits, or a player's action already taken
     */
    @Transactional
    public EncounterResponse changeForm(UUID encounterId, ChangeFormRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.participantId(), userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, request.participantId());
        access.requireTurnForActionCost(encounter, caster.getId(), userId, ActionCost.ACTION);
        ConcentrationInstance instance = caster.getConcentration();
        ShapeSpells.Rules rules = Optional.ofNullable(instance)
                .flatMap(concentration -> ShapeSpells.of(concentration.spell()))
                .filter(ShapeSpells.Rules::changesWithAction)
                .orElseThrow(() -> new ConflictException(caster.getDisplayName() + " isn't keeping up Shapechange or "
                        + "Animal Shapes, whose caster gives new forms with an action"));
        EncounterParticipant creature = CombatAccess.participant(encounter, request.targetId());
        if (rules == ShapeSpells.SHAPECHANGE && !creature.getId().equals(caster.getId())) {
            throw new ValidationException("Shapechange changes only its caster's form");
        }
        if (creature.getLifeState() != LifeState.ALIVE) {
            throw new ConflictException(creature.getDisplayName() + " can't take a new form at 0 hit points");
        }
        Optional<ConditionEntry> entry = spellEntry(caster, creature, rules);
        // Shapechange goes on after its caster reverts at 0 hit points, so a new form brings a new entry (PHB p.274);
        // a creature Animal Shapes no longer holds stays itself (PHB p.212)
        if (entry.isEmpty() && rules == ShapeSpells.SHAPECHANGE) {
            ConditionEntry fresh = ConditionEntry.fromEffect(rules.entry(), null, encounter.getRoundNumber(),
                    rules.spell(), caster.getId(), instance);
            List<ConditionEntry> conditions = new ArrayList<>(caster.getActiveConditions() != null
                    ? caster.getActiveConditions() : List.of());
            conditions.add(fresh);
            caster.setActiveConditions(conditions);
            entry = Optional.of(fresh);
        }
        ConditionEntry held = entry.orElseThrow(() -> new ConflictException(creature.getDisplayName()
                + " isn't transformed by " + caster.getDisplayName() + "'s " + rules.spell()));
        Monster form = monsterRepository.findById(request.creatureId())
                .orElseThrow(() -> new ValidationException("Creature not found"));
        refusal(rules, caster, creature, form).ifPresent(reason -> {
            throw new ConflictException(reason + " for " + creature.getDisplayName());
        });
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, caster.getId());
        economy.charge(encounter, caster, Charge.of(ActionCost.ACTION, ownTurn), userId,
                new ActionEconomyKeeper.Command(CombatActionType.SPELL_CAST,
                        caster.getDisplayName() + "'s " + rules.spell() + ": a new form"));
        transform(encounter, caster, creature, held, form, rules, rules == ShapeSpells.SHAPECHANGE);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    // ── Rules ────────────────────────────────────────────────────

    private String transform(Encounter encounter, EncounterParticipant caster, EncounterParticipant creature,
                             ConditionEntry entry, Monster form, ShapeSpells.Rules rules, boolean keepCurrentHp) {
        WildShapeForm taken = wildShapeEffects.spellForm(form, creature, rules);
        effectApplier.startForm(creature, entry.id(), taken, keepCurrentHp);
        String note = creature.getDisplayName() + " becomes " + article(form.getName()) + " " + form.getName()
                + " (CR " + form.getChallengeRating() + ", " + creature.getHpCurrent() + "/" + creature.getHpMax()
                + " HP, AC " + creature.getArmourClass() + ") through " + caster.getDisplayName() + "'s "
                + rules.spell() + ".";
        combatLog.write(encounter, CombatActionType.CONDITION_ADD, note, caster, creature);
        return note;
    }

    // The entry the caster's spell left on the creature, which carries or will carry the form
    private static Optional<ConditionEntry> spellEntry(EncounterParticipant caster, EncounterParticipant creature,
                                                       ShapeSpells.Rules rules) {
        List<ConditionEntry> conditions = creature.getActiveConditions();
        if (conditions == null) {
            return Optional.empty();
        }
        return conditions.stream()
                .filter(c -> c != null && rules.entry().equalsIgnoreCase(c.name())
                        && rules.spell().equalsIgnoreCase(c.sourceSpellName())
                        && caster.getId().equals(c.sourceParticipantId()))
                .findFirst();
    }

    private Optional<String> refusal(ShapeSpells.Rules rules, EncounterParticipant caster,
                                     EncounterParticipant creature, Monster form) {
        return refusal(rules, maxRating(rules, caster, creature), form);
    }

    private static Optional<String> refusal(ShapeSpells.Rules rules, double max, Monster form) {
        return ShapeSpells.refusal(rules, form.getName(), form.getType(), form.getChallengeRating(), form.getSize(),
                form.getHitPoints(), max);
    }

    /*
     * The highest challenge rating the creature's form may have: from the target's own challenge rating, or its level
     * for a player character (PHB p.266, p.283; half of it for Mass Polymorph, XGE p.160); from the caster's level for
     * Shapechange (PHB p.274), a monster caster's challenge rating standing in for a level (rules call).
     */
    private static double maxRating(ShapeSpells.Rules rules, EncounterParticipant caster,
                                     EncounterParticipant target) {
        String targetRating = target.getMonster() != null ? target.getMonster().getChallengeRating() : null;
        int targetLevel = target.getCharacter() != null && target.getCharacter().getLevel() != null
                ? target.getCharacter().getLevel() : 0;
        double casterLevel;
        if (caster.getCharacter() != null) {
            casterLevel = caster.getCharacter().getLevel() != null ? caster.getCharacter().getLevel() : 1;
        } else {
            casterLevel = caster.getMonster() != null
                    ? BeastShapes.challengeRating(caster.getMonster().getChallengeRating()).orElse(0) : 0;
        }
        return ShapeSpells.maxChallengeRating(rules, targetRating, targetLevel, casterLevel);
    }

    // "a" or "an", by the name's first letter
    private static String article(String name) {
        return name != null && !name.isEmpty() && "AEIOUaeiou".indexOf(name.charAt(0)) >= 0 ? "an" : "a";
    }
}
