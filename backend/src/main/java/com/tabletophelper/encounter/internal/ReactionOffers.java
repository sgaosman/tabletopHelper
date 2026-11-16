package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterViews;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.state.ParticipantCombatState;
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
    public record Before(LifeState lifeState, List<String> conditions, ParticipantCombatState state) {

        public static Before of(EncounterParticipant participant) {
            return new Before(participant.getLifeState(), List.copyOf(CreatureStats.conditionNames(participant)),
                    ParticipantCombatState.of(participant));
        }
    }

    /**
     * Whether the offer can still be taken (D216): its reactor is still in the fight and not dead, and hasn't spent
     * its reaction or been kept from reactions since. An offer that can't goes from the encounter as the next command
     * starts, and isn't shown meanwhile. A reactor that has since become incapacitated keeps it until then; the cast's
     * own checks refuse the answer.
     */
    public static boolean open(Encounter encounter, ReactionOffer offer) {
        if (offer == null || offer.reactorId() == null) {
            return false;
        }
        EncounterParticipant reactor = encounter.getParticipants().stream()
                .filter(p -> offer.reactorId().equals(p.getId()))
                .findFirst()
                .orElse(null);
        if (reactor == null || reactor.getLifeState() == LifeState.DEAD || EncounterTurns.dismissed(reactor)
                || CombatAccess.banished(reactor)) {
            return false;
        }
        TurnEconomy economy = reactor.getTurnEconomy() != null ? reactor.getTurnEconomy() : TurnEconomy.fresh();
        if (ActionEconomy.check(economy.budget(), Charge.of(ActionCost.REACTION, false), List.of()).isPresent()) {
            return false;
        }
        return ActiveModifiers.on(reactor).stream()
                .noneMatch(found -> found.modifier().kind() == OngoingModifier.Kind.NO_REACTIONS);
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
        if (target == null) {
            return;
        }
        try {
            Optional<CommandRecorder.Recording> recording = CommandRecorder.current();
            // Whether this is the first thing the command did to the target, and whether it did anything: Shield
            // takes a hit back only when that hit is all the command did to the wizard (D216)
            boolean first = before == null || before.state() == null || recording.isEmpty()
                    || recording.get().unchangedSinceStart(target.getId(), before.state());
            boolean touched = hit || damageTaken > 0 || (before != null && before.state() != null
                    && recording.isPresent()
                    && !recording.get().same(before.state(), ParticipantCombatState.of(target)));
            if (touched && recording.isPresent()) {
                notOnlyHit(encounter, recording.get().id(), target);
            }
            if (!hit && damageTaken <= 0) {
                return;
            }
            List<ReactionOffer.ReactionOption> options = harmOptions(actor, target, hit, critical, attackTotal, armourClass,
                    before, damageTaken, damageTypes, first);
            if (!options.isEmpty()) {
                ReactionOffer offer = new ReactionOffer(UUID.randomUUID(), commandId(), target.getId(),
                        target.getDisplayName(), hit ? ReactionOffer.Trigger.ATTACK_HIT : ReactionOffer.Trigger.DAMAGE,
                        actor != null ? actor.getId() : null, actor != null ? actor.getDisplayName() : actorName,
                        hit ? attackTotal : null, hit ? armourClass : null, hit && critical,
                        damageTaken > 0 ? damageTaken : null, damageTaken > 0 ? List.copyOf(damageTypes) : null,
                        null, null, options);
                add(encounter, offer);
                // The wizard as the hit left it, which it must still be as the command ends for Shield to take the
                // hit back (settle)
                if (recording.isPresent() && resolvingShield(offer, offer.commandId(), target.getId())) {
                    recording.get().hitEnded(offer.id(), ParticipantCombatState.of(target));
                }
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
                                                   List<String> damageTypes, boolean first) {
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
                        : misses && first
                        ? "+5 AC makes it " + shielded + ": the attack roll of " + attackTotal
                                + " misses, and the hit is taken back."
                        : misses
                        ? notOnlyHitNote(shielded, attackTotal, "before it")
                        : "+5 AC makes it " + shielded + ", but the attack roll of " + attackTotal
                                + " still hits; the +5 AC lasts until the start of its next turn.";
                spell(target, SHIELD, SHIELD, null, List.of(target.getId()), misses && first, level -> note)
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

    /*
     * The command has done more to the target than an earlier hit it offered Shield against: that hit is no longer all
     * the command did to the wizard, so taking it back would take the rest back too (D216). Its Shield stays offered for
     * the +5 AC, and the DM restores the hit by hand.
     */
    private static void notOnlyHit(Encounter encounter, UUID commandId, EncounterParticipant target) {
        notOnlyHit(encounter, offer -> resolvingShield(offer, commandId, target.getId()));
    }

    /**
     * As the command ends: an offer whose Shield would take a hit back stays so only while the wizard is still as
     * that hit left it, so that nothing else in the command, such as a later attack's rider that reaches it without
     * damage, is taken back with it (D216).
     */
    static void settle(Encounter encounter, CommandRecorder.Recording recording) {
        List<ReactionOffer> offers = encounter.getReactionOffers() != null ? encounter.getReactionOffers() : List.of();
        List<UUID> changed = new ArrayList<>();
        for (ReactionOffer offer : offers) {
            ParticipantCombatState leftBy = recording.hitEnd(offer.id());
            EncounterParticipant reactor = leftBy == null ? null : encounter.getParticipants().stream()
                    .filter(p -> offer.reactorId().equals(p.getId())).findFirst().orElse(null);
            if (leftBy != null && (reactor == null || !recording.same(leftBy, ParticipantCombatState.of(reactor)))) {
                changed.add(offer.id());
            }
        }
        if (!changed.isEmpty()) {
            notOnlyHit(encounter, offer -> changed.contains(offer.id())
                    && resolvingShield(offer, recording.id(), offer.reactorId()));
        }
    }

    private static void notOnlyHit(Encounter encounter, java.util.function.Predicate<ReactionOffer> marking) {
        List<ReactionOffer> offers = encounter.getReactionOffers() != null ? encounter.getReactionOffers() : List.of();
        if (offers.stream().noneMatch(marking)) {
            return;
        }
        List<ReactionOffer> marked = new ArrayList<>();
        for (ReactionOffer offer : offers) {
            if (!marking.test(offer)) {
                marked.add(offer);
                continue;
            }
            int shielded = offer.armourClass() + 5;
            List<ReactionOffer.ReactionOption> options = offer.options().stream()
                    .map(option -> isShield(option)
                            ? ReactionOffer.ReactionOption.spell(option.name(), option.spellName(),
                                    option.spellSource(), option.slotLevels(), option.castOption(), option.targetIds(),
                                    false, notOnlyHitNote(shielded, offer.attackTotal(), "after it"))
                            : option)
                    .toList();
            marked.add(new ReactionOffer(offer.id(), offer.commandId(), offer.reactorId(), offer.reactorName(),
                    offer.trigger(), offer.triggerActorId(), offer.triggerActorName(), offer.attackTotal(),
                    offer.armourClass(), offer.critical(), offer.damageTaken(), offer.damageTypes(), offer.spellName(),
                    offer.spellLevel(), options));
        }
        encounter.setReactionOffers(marked);
    }

    /** Whether the option is Shield. */
    static boolean isShield(ReactionOffer.ReactionOption option) {
        return option.kind() == ReactionOffer.Kind.SPELL && SHIELD.equalsIgnoreCase(option.spellName());
    }

    private static boolean resolvingShield(ReactionOffer offer, UUID commandId, UUID targetId) {
        return offer != null && commandId.equals(offer.commandId()) && targetId.equals(offer.reactorId())
                && offer.trigger() == ReactionOffer.Trigger.ATTACK_HIT && offer.armourClass() != null
                && offer.attackTotal() != null
                && offer.options().stream().anyMatch(option -> isShield(option) && option.resolves());
    }

    private static String notOnlyHitNote(int shielded, int attackTotal, String when) {
        return "+5 AC makes it " + shielded + ": the attack roll of " + attackTotal + " misses, but the same action "
                + "did more to it " + when + ", so the DM restores this hit's damage and effects by hand; the +5 AC "
                + "lasts until the start of its next turn.";
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
            // The reactor's known spells are looked up once a command, however many offers it raises (D216)
            List<SpellSources.Known> spells = CommandRecorder.current()
                    .map(recording -> recording.memo(new KnownSpells(reactor.getId()),
                            () -> casterSpells.known(reactor)))
                    .orElseGet(() -> casterSpells.known(reactor));
            known = casterSpells.options(reactor, spells);
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

    // The key a reactor's known spells are kept under for the command
    private record KnownSpells(UUID reactorId) {
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
