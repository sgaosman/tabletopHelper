package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterViews;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.state.ReactionOffer;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Offers creatures the reactions they could take when something happens to them that the server can see (D216): an
 * attack that hits, damage taken and a spell cast by a creature on the other side. Nothing waits for an answer, since
 * the command that triggered it has already resolved; an offer is recorded on the encounter, broadcast with it and
 * shown to the reacting creature's controller and the DM, each option naming the existing command that takes it.
 * <p>
 * Only reactions that already resolve somewhere are offered: Shield, Hellish Rebuke, Absorb Elements and Counterspell
 * through cast-spell, Stone's Endurance through use-pool, and a monster's Parry, Uncanny Dodge or Deflect Missile
 * through its stat-block reactions, which the DM applies by hand. A creature with no reaction left, one that can't take
 * reactions, one incapacitated or dead, and one without the spell, a slot or the pool's use is offered nothing.
 * Working out an offer never stops the command: a failure is logged and the offer left out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionOffers {

    public static final String SHIELD = "Shield";
    public static final String HELLISH_REBUKE = "Hellish Rebuke";
    public static final String ABSORB_ELEMENTS = "Absorb Elements";
    public static final String COUNTERSPELL = SpellCounters.COUNTERSPELL;
    public static final String STONES_ENDURANCE = "Stone's Endurance";

    /** The damage types Absorb Elements answers (XGE p.150). */
    public static final List<String> ABSORBED_TYPES = List.of("acid", "cold", "fire", "lightning", "thunder");

    /** The Stone's Endurance pools (VGM p.109, MPMM p.21, D208). */
    public static final Set<String> STONES_ENDURANCE_POOLS = Set.of("race:stones-endurance",
            "race:stones-endurance-pb");

    // The stat-block reactions that answer an attack hitting the monster, by name in lower case
    private static final Set<String> HIT_REACTIONS = Set.of("parry", "uncanny dodge", "deflect missile",
            "deflect missiles");

    private static final String BLINDED = "blinded";
    private static final String INVISIBLE = "invisible";

    private final CasterSpells casterSpells;
    private final CreatureStats stats;

    /**
     * The reactor as it was just before what triggered the offer, which a reaction that changes that event is judged
     * on: Shield is cast when the attack hits, before its damage drops the wizard (PHB p.275).
     */
    public record Before(LifeState lifeState, List<String> conditions) {

        public static Before of(EncounterParticipant participant) {
            return new Before(participant.getLifeState(), List.copyOf(CreatureStats.conditionNames(participant)));
        }
    }

    /**
     * Offers the target its reactions to being hit by an attack or damaged (D216).
     *
     * @param actor        the creature that attacked or dealt the damage; null for the DM by hand
     * @param actorName    what the log calls it
     * @param hit          an attack roll hit the target
     * @param critical     the hit was a critical hit
     * @param attackTotal  the attack roll's total, for a hit
     * @param armourClass  the target's AC the attack was rolled against
     * @param before       the target just before the attack or damage
     * @param damageTaken  the damage it took, after its defences
     * @param damageTypes  the types of the damage it took
     */
    public void afterHarm(Encounter encounter, EncounterParticipant actor, String actorName,
                          EncounterParticipant target, boolean hit, boolean critical, Integer attackTotal,
                          Integer armourClass, Before before, int damageTaken, List<String> damageTypes) {
        if (target == null || (!hit && damageTaken <= 0)) {
            return;
        }
        try {
            List<ReactionOffer.ReactionOption> options = harmOptions(actor, target, hit, critical, attackTotal, armourClass,
                    before, damageTaken, damageTypes);
            if (!options.isEmpty()) {
                add(encounter, new ReactionOffer(UUID.randomUUID(), commandId(), target.getId(),
                        target.getDisplayName(), hit ? ReactionOffer.Trigger.ATTACK_HIT : ReactionOffer.Trigger.DAMAGE,
                        actor != null ? actor.getId() : null, actor != null ? actor.getDisplayName() : actorName,
                        hit ? attackTotal : null, hit ? armourClass : null, hit && critical,
                        damageTaken > 0 ? damageTaken : null, damageTaken > 0 ? List.copyOf(damageTypes) : null,
                        null, null, options));
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't work out the reactions offered to {}: {}", target.getDisplayName(), e.getMessage(), e);
        }
    }

    /**
     * Offers Counterspell to each creature on the other side that can cast it when a spell is cast (PHB p.228, D196,
     * D216). A spell cast with Subtle Spell has no components to notice (PHB p.102), and an invisible caster can't be
     * seen, so neither is offered; nor is a Counterspell itself, which the app counters only as the newest cast.
     */
    public void afterCast(Encounter encounter, EncounterParticipant caster, String spellName, int level,
                          boolean subtle) {
        if (subtle || COUNTERSPELL.equalsIgnoreCase(spellName) || has(caster, INVISIBLE)) {
            return;
        }
        boolean casterOnPlayersSide = !EncounterViews.numbersHidden(caster);
        boolean casterHidden = Boolean.FALSE.equals(caster.getIsVisibleToPlayers());
        for (EncounterParticipant reactor : List.copyOf(encounter.getParticipants())) {
            try {
                if (reactor.getId() == null || reactor.getId().equals(caster.getId())) {
                    continue;
                }
                boolean onPlayersSide = !EncounterViews.numbersHidden(reactor);
                if (onPlayersSide == casterOnPlayersSide || (onPlayersSide && casterHidden)
                        || !canReact(reactor, reactor.getLifeState(), CreatureStats.conditionNames(reactor))
                        || has(reactor, BLINDED)) {
                    continue;
                }
                String levelText = level == 0 ? "the cantrip" : "the level " + level + " spell";
                Optional<ReactionOffer.ReactionOption> counter = spell(reactor, COUNTERSPELL, COUNTERSPELL, null,
                        List.of(caster.getId()), true, slotLevel -> slotLevel >= level
                                ? "At level " + slotLevel + " it stops " + levelText + " outright."
                                : "At level " + slotLevel + " " + levelText + " needs a spellcasting ability check "
                                        + "against DC " + (10 + level) + "; at level " + level
                                        + " or higher it stops it outright.");
                counter.ifPresent(option -> add(encounter, new ReactionOffer(UUID.randomUUID(), commandId(),
                        reactor.getId(), reactor.getDisplayName(), ReactionOffer.Trigger.SPELL_CAST, caster.getId(),
                        caster.getDisplayName(), null, null, false, null, null, spellName, level, List.of(option))));
            } catch (RuntimeException e) {
                log.warn("Couldn't work out the reactions offered to {}: {}", reactor.getDisplayName(),
                        e.getMessage(), e);
            }
        }
    }

    private List<ReactionOffer.ReactionOption> harmOptions(EncounterParticipant actor, EncounterParticipant target,
                                                   boolean hit, boolean critical, Integer attackTotal,
                                                   Integer armourClass, Before before, int damageTaken,
                                                   List<String> damageTypes) {
        List<ReactionOffer.ReactionOption> options = new ArrayList<>();
        if (EncounterTurns.dismissed(target) || CombatAccess.banished(target)) {
            return options;
        }
        boolean reactsNow = canReact(target, target.getLifeState(), CreatureStats.conditionNames(target));
        boolean seesActor = !has(target, BLINDED);
        boolean onPlayersSide = !EncounterViews.numbersHidden(target);
        boolean otherCreature = actor != null && !actor.getId().equals(target.getId());
        boolean actorHiddenFromPlayers = otherCreature && Boolean.FALSE.equals(actor.getIsVisibleToPlayers());

        if (hit) {
            // Shield is cast as the attack hits, so the wizard the hit dropped could still cast it, when it would turn
            // the hit into a miss (PHB p.275)
            int shielded = armourClass != null ? armourClass + 5 : 0;
            boolean misses = !critical && attackTotal != null && armourClass != null && attackTotal < shielded;
            boolean reactedBefore = before != null && canReact(target, before.lifeState(), before.conditions());
            if (reactedBefore && (reactsNow || misses) && !shielded(target)) {
                String note = critical
                        ? "A critical hit still hits; the +5 AC lasts until the start of its next turn."
                        : armourClass == null || attackTotal == null
                        ? "+5 AC until the start of its next turn."
                        : misses
                        ? "+5 AC makes it " + shielded + ": the attack roll of " + attackTotal
                                + " misses, and the hit is taken back."
                        : "+5 AC makes it " + shielded + ", but the attack roll of " + attackTotal
                                + " still hits; the +5 AC lasts until the start of its next turn.";
                spell(target, SHIELD, SHIELD, null, List.of(target.getId()), misses, level -> note)
                        .ifPresent(options::add);
            }
            if (reactsNow && seesActor) {
                options.addAll(monsterHitReactions(target, damageTaken));
            }
        }
        if (damageTaken > 0 && reactsNow) {
            // Hellish Rebuke answers a creature that damaged the warlock and that it can see (PHB p.250)
            if (otherCreature && seesActor && actor.getLifeState() != LifeState.DEAD
                    && !(onPlayersSide && actorHiddenFromPlayers)) {
                spell(target, HELLISH_REBUKE, HELLISH_REBUKE, null, List.of(actor.getId()), true,
                        level -> "2d10 fire, +1d10 a level above 1st, at " + actor.getDisplayName()
                                + ": a DEX save for half.")
                        .ifPresent(options::add);
            }
            // Absorb Elements answers acid, cold, fire, lightning or thunder damage (XGE p.150)
            for (String type : ABSORBED_TYPES) {
                if (damageTypes != null && damageTypes.stream().anyMatch(type::equalsIgnoreCase)) {
                    spell(target, ABSORB_ELEMENTS + " (" + type + ")", ABSORB_ELEMENTS, type,
                            List.of(target.getId()), false, level -> "Resistance to " + type
                                    + " until the start of its next turn and +" + level + "d6 " + type
                                    + " on its next melee hit. The damage already taken isn't reduced here: the DM "
                                    + "restores half of the " + type + " damage by hand.")
                            .ifPresent(options::add);
                }
            }
            stonesEndurance(target, damageTaken).ifPresent(options::add);
        }
        return options;
    }

    // The Stone's Endurance pool with a use left, taken with the damage just taken (D208)
    private static Optional<ReactionOffer.ReactionOption> stonesEndurance(EncounterParticipant target, int damageTaken) {
        List<ResourcePoolEntry> pools = target.getResourcePoolsCurrent() != null ? target.getResourcePoolsCurrent()
                : List.of();
        return pools.stream()
                .filter(pool -> pool != null && STONES_ENDURANCE_POOLS.contains(pool.poolId()))
                .filter(pool -> pool.currentUses() > 0)
                .findFirst()
                .map(pool -> ReactionOffer.ReactionOption.pool(STONES_ENDURANCE, pool.poolId(), damageTaken, true,
                        "Rolls 1d12 + CON and regains up to the " + damageTaken + " damage just taken."));
    }

    // A monster's stat-block reactions to being hit, which the DM applies by hand (D174)
    private static List<ReactionOffer.ReactionOption> monsterHitReactions(EncounterParticipant target, int damageTaken) {
        if (target.getMonster() == null || target.getMonster().getActionTemplates() == null) {
            return List.of();
        }
        List<ReactionOffer.ReactionOption> options = new ArrayList<>();
        for (MonsterTemplates.Action reaction : MonsterTemplates.of(target.getMonster()).reactions()) {
            String name = reaction.name() != null ? reaction.name().trim() : "";
            String key = name.toLowerCase(Locale.ROOT);
            if (!HIT_REACTIONS.contains(key) || (key.equals("uncanny dodge") && damageTaken <= 0)) {
                continue;
            }
            String note = reaction.dmNote() != null ? reaction.dmNote() : reaction.description();
            options.add(ReactionOffer.ReactionOption.monsterReaction(name, false,
                    (note != null ? note + " " : "") + "The DM adjusts the hit by hand."));
        }
        return options;
    }

    /**
     * The spell as a reaction option, when the creature can cast it now and has something to pay with: the first
     * source with a level left, and the levels it offers.
     */
    private Optional<ReactionOffer.ReactionOption> spell(EncounterParticipant reactor, String name, String spellName,
                                                 String castOption, List<UUID> targetIds, boolean resolves,
                                                 java.util.function.IntFunction<String> note) {
        if (SpellcastingService.castingBan(reactor, stats) != null) {
            return Optional.empty();
        }
        List<SpellOption> known;
        try {
            known = casterSpells.options(reactor);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return known.stream()
                .filter(option -> spellName.equalsIgnoreCase(option.name()) && option.unavailableReason() == null)
                .findFirst()
                .flatMap(option -> option.sources().stream()
                        .filter(source -> source.levels() != null && !source.levels().isEmpty())
                        .findFirst()
                        .map(source -> ReactionOffer.ReactionOption.spell(name, option.name(), source.source(),
                                source.levels(), castOption, targetIds, resolves,
                                note.apply(source.levels().getFirst()))));
    }

    /**
     * Whether the creature could take a reaction, judged on its life state and conditions: alive, its reaction not
     * yet taken this round, not incapacitated (PHB p.290) and not kept from reactions, as by Shocking Grasp (D199).
     */
    static boolean canReact(EncounterParticipant participant, LifeState lifeState, Collection<String> conditions) {
        if (lifeState != null && lifeState != LifeState.ALIVE) {
            return false;
        }
        if (EncounterTurns.dismissed(participant) || CombatAccess.banished(participant)) {
            return false;
        }
        TurnEconomy economy = participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
        if (ActionEconomy.check(economy.budget(), Charge.of(ActionCost.REACTION, false), conditions).isPresent()) {
            return false;
        }
        return ActiveModifiers.on(participant).stream()
                .noneMatch(found -> found.modifier().kind() == OngoingModifier.Kind.NO_REACTIONS);
    }

    // Shield already up: casting it again would add nothing (PHB p.275)
    private static boolean shielded(EncounterParticipant participant) {
        return ActiveModifiers.on(participant).stream()
                .anyMatch(found -> found.effect() != null && SHIELD.equalsIgnoreCase(found.effect().spell()));
    }

    private static boolean has(EncounterParticipant participant, String condition) {
        return CreatureStats.conditionNames(participant).stream().anyMatch(condition::equalsIgnoreCase);
    }

    private static UUID commandId() {
        return CommandRecorder.current().map(CommandRecorder.Recording::id).orElse(null);
    }

    private static void add(Encounter encounter, ReactionOffer offer) {
        List<ReactionOffer> offers = new ArrayList<>(encounter.getReactionOffers() != null
                ? encounter.getReactionOffers() : List.of());
        offers.add(offer);
        encounter.setReactionOffers(offers);
    }
}
