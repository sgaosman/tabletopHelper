package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.FlexibleCasting;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MetamagicChoice;
import com.tabletophelper.encounter.dto.MetamagicOption;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.Metamagic.Option;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A sorcerer's Metamagic on a spell they cast (PHB p.102, M18). The options a player character knows are its features
 * named "Metamagic: X", chosen as a Pending Choice at Sorcerer 3rd, 10th and 17th level. {@link #plan} checks a cast's
 * options before anything is spent: that the caster knows each, that they go together (one, plus Empowered Spell),
 * that each can change the spell at the level it is cast at, by its effect template ({@link Metamagic#ineligible}),
 * the creatures Careful and Heightened Spell name, Twinned Spell's two targets, and that the sorcery points are there.
 * The cast then spends the points ({@link #spend}) and SpellCaster applies what the options do.
 */
@Component
@RequiredArgsConstructor
public class MetamagicCasting {

    private final SpellEffects spellEffects;
    private final CreatureStats stats;

    /**
     * The Metamagic on one cast.
     *
     * @param options       the options, in the order chosen
     * @param cost          the sorcery points they cost together
     * @param careful       the creatures Careful Spell makes succeed on their saves
     * @param heightened    the creature Heightened Spell gives disadvantage on its first save, or null
     * @param empoweredDice the damage dice Empowered Spell rerolls, 0 without it
     */
    public record Plan(Set<Option> options, int cost, Set<UUID> careful, UUID heightened, int empoweredDice) {

        /** No Metamagic. */
        public static final Plan NONE = new Plan(Set.of(), 0, Set.of(), null, 0);

        public Plan {
            options = options.isEmpty() ? Set.of() : EnumSet.copyOf(options);
            careful = Set.copyOf(careful);
        }

        public boolean has(Option option) {
            return options.contains(option);
        }

        public boolean any() {
            return !options.isEmpty();
        }

        /**
         * How the log names the options, such as " (Quickened Spell and Empowered Spell, 3 sorcery points)"; blank
         * without any.
         */
        public String note() {
            if (options.isEmpty()) {
                return "";
            }
            return " (" + String.join(" and ", Metamagic.names(options)) + ", " + cost + " sorcery point"
                    + (cost == 1 ? "" : "s") + ")";
        }
    }

    /** The Metamagic options a player character knows, from its features, in the PHB's order. */
    public static List<Option> known(PlayerCharacter character) {
        Set<Option> known = EnumSet.noneOf(Option.class);
        if (character != null && character.getFeatures() != null) {
            for (Feature feature : character.getFeatures()) {
                if (feature != null && feature.name() != null
                        && feature.name().startsWith(Metamagic.FEATURE_PREFIX)) {
                    Option.named(feature.name().substring(Metamagic.FEATURE_PREFIX.length())).ifPresent(known::add);
                }
            }
        }
        return List.copyOf(known);
    }

    /**
     * Checks the cast's Metamagic and works out what it does; changes nothing.
     *
     * @param targetIds the spell's targets as the request names them
     * @throws ValidationException for options that don't go together, can't change the spell at this level, or name
     *                             creatures that don't fit
     * @throws ConflictException   for an option the caster doesn't know, too few sorcery points, or Empowered Spell
     *                             with a Charisma modifier below +1
     */
    public Plan plan(EncounterParticipant caster, SpellSources.Payment payment, List<MetamagicChoice> choices,
                     List<UUID> targetIds) {
        if (choices == null || choices.isEmpty()) {
            return Plan.NONE;
        }
        List<Option> options = choices.stream().map(MetamagicChoice::option).filter(Objects::nonNull).toList();
        if (options.size() != choices.size()) {
            throw new ValidationException("Each Metamagic choice names its option");
        }
        Metamagic.combinationProblem(options).ifPresent(problem -> {
            throw new ValidationException(problem);
        });
        List<Option> known = known(caster.getCharacter());
        for (Option option : options) {
            if (!known.contains(option)) {
                throw new ConflictException(caster.getDisplayName() + " doesn't know " + option.displayName());
            }
        }
        Metamagic.SpellFacts facts = facts(payment);
        for (Option option : options) {
            Metamagic.ineligible(option, facts, payment.castLevel()).ifPresent(reason -> {
                throw new ValidationException(reason + ", and " + payment.spellName() + " isn't");
            });
        }

        int charismaModifier = Ability.modifier(stats.abilityScore(caster, Ability.CHA));
        Set<UUID> careful = Set.of();
        UUID heightened = null;
        int empoweredDice = 0;
        for (MetamagicChoice choice : choices) {
            List<UUID> named = choice.targetIds() != null ? choice.targetIds() : List.of();
            switch (choice.option()) {
                case CAREFUL -> careful = carefulTargets(named, targetIds, charismaModifier);
                case HEIGHTENED -> heightened = heightenedTarget(named, targetIds);
                case EMPOWERED -> {
                    requireNoTargets(choice);
                    empoweredDice = Metamagic.empoweredDice(charismaModifier);
                    if (empoweredDice < 1) {
                        throw new ConflictException(Option.EMPOWERED.displayName() + " rerolls as many dice as "
                                + caster.getDisplayName() + "'s Charisma modifier, which is below +1");
                    }
                }
                case TWINNED -> {
                    requireNoTargets(choice);
                    if (new LinkedHashSet<>(targetIds).size() != 2 || targetIds.size() != 2) {
                        throw new ValidationException(Option.TWINNED.displayName()
                                + " targets two different creatures with " + payment.spellName());
                    }
                }
                default -> requireNoTargets(choice);
            }
        }

        int cost = options.stream().mapToInt(option -> Metamagic.cost(option, payment.castLevel())).sum();
        ResourcePoolEntry points = FlexibleCasting.sorceryPoints(caster.getResourcePoolsCurrent())
                .orElseThrow(() -> new ConflictException(caster.getDisplayName() + " has no sorcery points"));
        if (!points.unlimited() && points.currentUses() < cost) {
            throw new ConflictException(caster.getDisplayName() + " has " + points.currentUses()
                    + " sorcery points, and " + String.join(" and ", Metamagic.names(options)) + " cost " + cost);
        }
        return new Plan(EnumSet.copyOf(options), cost, careful, heightened, empoweredDice);
    }

    /** Spends the plan's sorcery points from the caster's pool. */
    public void spend(EncounterParticipant caster, Plan plan) {
        if (plan.cost() > 0) {
            caster.setResourcePoolsCurrent(new ArrayList<>(ResourcePoolService.spend(caster.getResourcePoolsCurrent(),
                    FlexibleCasting.SORCERY_POINTS, plan.cost(), caster.getDisplayName())));
        }
    }

    /**
     * Each spell option with the Metamagic the caster knows: its cost and why it can't change the spell, if it can't
     * (M18). The options are unchanged for a caster who knows none.
     */
    public List<SpellOption> withMetamagic(EncounterParticipant caster, List<SpellOption> spells) {
        List<Option> known = known(caster.getCharacter());
        if (known.isEmpty()) {
            return spells;
        }
        return spells.stream().map(spell -> spell.withMetamagic(optionsFor(spell, known))).toList();
    }

    private List<MetamagicOption> optionsFor(SpellOption spell, List<Option> known) {
        Optional<Metamagic.SpellFacts> facts = spellEffects.metamagicFacts(spell.name())
                .map(found -> found.withCastingTime(spell.castingTime()));
        return known.stream().map(option -> {
            String reason = facts.isEmpty() ? "Metamagic needs the spell's effect data, which " + spell.name()
                    + " hasn't" : Metamagic.ineligible(option, facts.get(), spell.level()).orElse(null);
            Integer maxCastLevel = facts.flatMap(found -> Metamagic.maxCastLevel(option, found)).orElse(null);
            return new MetamagicOption(option, option.displayName(), Metamagic.cost(option, spell.level()),
                    option == Option.TWINNED, maxCastLevel, reason);
        }).toList();
    }

    // The spell's facts, with the casting time the payment charges
    private Metamagic.SpellFacts facts(SpellSources.Payment payment) {
        return spellEffects.metamagicFacts(payment.spellName())
                .map(found -> found.withCastingTime(payment.castingTime()))
                .orElseThrow(() -> new ValidationException("Metamagic needs the spell's effect data, which "
                        + payment.spellName() + " hasn't"));
    }

    // Careful Spell (PHB p.102): up to the CHA modifier, at least one, of the spell's targets
    private static Set<UUID> carefulTargets(List<UUID> named, List<UUID> targetIds, int charismaModifier) {
        Set<UUID> chosen = new LinkedHashSet<>(named);
        int most = Metamagic.carefulCreatures(charismaModifier);
        if (chosen.isEmpty() || chosen.size() > most) {
            throw new ValidationException(Option.CAREFUL.displayName() + " protects 1 to " + most + " of the "
                    + "spell's targets");
        }
        requireAmongTargets(chosen, targetIds, Option.CAREFUL);
        return chosen;
    }

    // Heightened Spell (PHB p.102): one of the spell's targets
    private static UUID heightenedTarget(List<UUID> named, List<UUID> targetIds) {
        Set<UUID> chosen = new LinkedHashSet<>(named);
        if (chosen.size() != 1) {
            throw new ValidationException(Option.HEIGHTENED.displayName() + " gives one target disadvantage");
        }
        requireAmongTargets(chosen, targetIds, Option.HEIGHTENED);
        return chosen.iterator().next();
    }

    private static void requireAmongTargets(Set<UUID> chosen, List<UUID> targetIds, Option option) {
        if (!targetIds.containsAll(chosen)) {
            throw new ValidationException(option.displayName() + " chooses among the spell's targets");
        }
    }

    private static void requireNoTargets(MetamagicChoice choice) {
        if (choice.targetIds() != null && !choice.targetIds().isEmpty()) {
            throw new ValidationException(choice.option().displayName() + " names no creatures");
        }
    }
}
