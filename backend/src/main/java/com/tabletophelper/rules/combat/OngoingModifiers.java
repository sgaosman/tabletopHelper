package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What Ongoing Modifiers (D184) do to a creature's numbers: its AC, speeds, hit point maximum and saving throws, and
 * the dice they add to its rolls. The encounter works out which modifiers apply to a creature from its Spell Effects;
 * these rules only combine them.
 */
public final class OngoingModifiers {

    private OngoingModifiers() {}

    /** A modifier with the name of the spell it comes from, which the log and the attack sources show. */
    public record Labelled(String label, OngoingModifier modifier) {

        public Labelled {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(modifier, "modifier");
        }
    }

    /**
     * The AC with the modifiers: a base AC such as Mage Armor's is used when it is higher than the AC the creature has,
     * as alternative ways of working out AC never stack (PHB p.14, p.256); then each bonus or penalty is added, Shield of
     * Faith's +2 (PHB p.275) or Slow's -2 (PHB p.277); and last a floor such as Barkskin's 16 (PHB p.217) is the least
     * the total can be, whatever armour or bonus made it.
     */
    public static int armourClass(int armourClass, List<OngoingModifier> modifiers) {
        int base = armourClass;
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.AC_BASE) {
                base = Math.max(base, modifier.flat());
            }
        }
        int total = base;
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.AC) {
                total += modifier.flat();
            }
        }
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.AC_FLOOR) {
                total = Math.max(total, modifier.flat());
            }
        }
        return total;
    }

    /**
     * Speeds with the modifiers, each kind the same way: a speed a spell gives first, as Fly's flying speed of 60 feet
     * (PHB p.243) or Spider Climb's climbing speed equal to the walking speed (PHB p.277), a higher one of that kind
     * staying (D199); then feet added or taken, such as Longstrider's +10 (PHB p.256) or Ray of Frost's -10 (PHB p.271),
     * never below 0, one limited to a speed, as Kinetic Jaunt's to walking (TCE p.110, D199), changing that one alone;
     * then doubled for Haste (PHB p.250) and halved, rounding down, for Slow (PHB p.277), so both together leave it as
     * it was. Exhaustion is applied after these (D183).
     */
    public static Map<String, Integer> speeds(Map<String, Integer> speeds, List<OngoingModifier> modifiers) {
        int doubled = 0;
        int halved = 0;
        // Freedom of Movement: magic can't reduce the creature's speed (PHB p.244, D185)
        boolean protectedSpeed = modifiers.stream().anyMatch(m -> m.kind() == OngoingModifier.Kind.SPEED_PROTECTED);
        Map<String, Integer> base = new LinkedHashMap<>(speeds);
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.GRANTED_SPEED && modifier.requires() != null
                    && !modifier.requires().isBlank()) {
                Integer feet = modifier.flat() > 0 ? Integer.valueOf(modifier.flat()) : speeds.get(OngoingModifier.WALK);
                if (feet != null) {
                    base.merge(modifier.requires().trim().toLowerCase(Locale.ROOT), feet, Math::max);
                }
            }
        }
        for (OngoingModifier modifier : modifiers) {
            if (protectedSpeed && modifier.kind() == OngoingModifier.Kind.SPEED_HALVED) {
                continue;
            }
            switch (modifier.kind()) {
                case SPEED_DOUBLED -> doubled++;
                case SPEED_HALVED -> halved++;
                default -> {
                    // Not a doubling or a halving
                }
            }
        }
        // A cap, as Power Word Pain's 10 feet (XGE p.163, D189), after the rest; Freedom of Movement ignores it
        Integer cap = protectedSpeed ? null : modifiers.stream()
                .filter(m -> m.kind() == OngoingModifier.Kind.SPEED_MAX)
                .map(OngoingModifier::flat)
                .min(Integer::compare)
                .orElse(null);
        Map<String, Integer> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> speed : base.entrySet()) {
            int added = 0;
            for (OngoingModifier modifier : modifiers) {
                if (modifier.kind() == OngoingModifier.Kind.SPEED && !(protectedSpeed && modifier.flat() < 0)
                        && (modifier.requires() == null || modifier.requires().isBlank()
                        || modifier.requires().trim().equalsIgnoreCase(speed.getKey()))) {
                    added += modifier.flat();
                }
            }
            int feet = speed(speed.getValue(), added, doubled, halved);
            changed.put(speed.getKey(), cap != null ? Math.min(feet, Math.max(0, cap)) : feet);
        }
        return changed;
    }

    // Doubling first, so a doubled and halved speed comes back whole
    private static int speed(int feet, int added, int doubled, int halved) {
        int total = Math.max(0, feet + added);
        int cancelled = Math.min(doubled, halved);
        for (int i = cancelled; i < doubled; i++) {
            total *= 2;
        }
        for (int i = cancelled; i < halved; i++) {
            total /= 2;
        }
        return total;
    }

    /** The hit point maximum with the modifiers, such as Aid's +5 (PHB p.211); exhaustion halves the total (D183). */
    public static int hitPointMaximum(int maximum, List<OngoingModifier> modifiers) {
        int total = maximum;
        boolean guarded = maximumProtected(modifiers);
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.HP_MAX && (modifier.flat() > 0 || !guarded)) {
                total += modifier.flat();
            }
        }
        return Math.max(1, total);
    }

    /** Whether the modifiers keep the hit point maximum from being reduced (Aura of Life, PHB p.216, D185). */
    public static boolean maximumProtected(List<OngoingModifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.HP_MAX_PROTECTED);
    }

    /** Whether the modifiers resist the damage type, counting those for nonmagical damage only as resisting it. */
    public static boolean resists(String damageType, List<OngoingModifier> modifiers) {
        return resists(damageType, modifiers, false);
    }

    /**
     * Whether the modifiers resist the damage type, magical or not (D185): one for nonmagical damage only, as
     * Stoneskin's (PHB p.278), doesn't resist magical damage.
     */
    public static boolean resists(String damageType, List<OngoingModifier> modifiers, boolean magical) {
        return modifiers.stream().anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.RESISTANCE
                && modifier.resists(damageType) && !(magical && "NONMAGICAL".equalsIgnoreCase(modifier.requires())));
    }

    /** Whether the modifiers take away the creature's resistance to the type (Elemental Bane, XGE p.155, D185). */
    public static boolean losesResistance(String damageType, List<OngoingModifier> modifiers) {
        return damageType != null && modifiers.stream().anyMatch(modifier ->
                modifier.kind() == OngoingModifier.Kind.LOSE_RESISTANCE && modifier.damageTypes().stream()
                        .anyMatch(type -> type.equalsIgnoreCase(damageType.trim())));
    }

    /** Whether the modifiers keep the creature from regaining hit points (Chill Touch, PHB p.221). */
    public static boolean noHealing(List<OngoingModifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.NO_HEALING);
    }

    /** Whether the modifiers make the creature immune to the condition (Heroism's frightened, PHB p.250). */
    public static boolean immuneTo(String condition, List<OngoingModifier> modifiers) {
        return immuneTo(condition, modifiers, false);
    }

    /**
     * Whether the modifiers make the creature immune to the condition from a magical source or not (D185): one against
     * magic only, as Freedom of Movement's (PHB p.244), guards only against a magical one.
     */
    public static boolean immuneTo(String condition, List<OngoingModifier> modifiers, boolean magical) {
        return condition != null && modifiers.stream()
                .anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.CONDITION_IMMUNITY
                        && condition.trim().equalsIgnoreCase(modifier.condition())
                        && (magical || !"MAGICAL".equalsIgnoreCase(modifier.requires())));
    }

    /**
     * The modes the modifiers give a saving throw of the ability, a death save when ability is null (PHB p.197):
     * Haste's advantage on Dexterity saves (PHB p.250), Beacon of Hope's on Wisdom and death saves (PHB p.217).
     */
    public static List<RollMode> saveModes(String ability, List<OngoingModifier> modifiers) {
        return saveModes(ability, modifiers, List.of());
    }

    /**
     * As {@link #saveModes(String, List)}, against an effect that would give these conditions (D185): a mode kept for
     * effects that give certain conditions, as Aura of Purity's advantage against those that would blind, charm, deafen,
     * frighten, paralyze, poison or stun (PHB p.216), counts only when the effect gives one of them.
     */
    public static List<RollMode> saveModes(String ability, List<OngoingModifier> modifiers, List<String> against) {
        List<RollMode> modes = new ArrayList<>();
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.SAVE_MODE && modifier.mode() != null
                    && modifier.coversSave(ability) && againstOneOf(modifier.condition(), against)) {
                modes.add(modifier.mode());
            }
        }
        return modes;
    }

    // Whether a mode kept for some conditions applies against an effect giving these; one kept for none always does
    private static boolean againstOneOf(String kept, List<String> against) {
        if (kept == null || kept.isBlank()) {
            return true;
        }
        for (String each : kept.split(",")) {
            if (against != null && against.stream().anyMatch(name -> name != null
                    && name.trim().equalsIgnoreCase(each.trim()))) {
                return true;
            }
        }
        return false;
    }

    /** The dice and amounts the modifiers add to a saving throw of the ability, a death save when it is null. */
    public static List<RollModifiers.Bonus> saveBonuses(String ability, List<Labelled> modifiers) {
        return bonuses(modifiers, OngoingModifier.Kind.SAVE, modifier -> modifier.coversSave(ability));
    }

    /**
     * The dice and amounts added to an ability check the engine rolls: Hide's Dexterity (Stealth) check with skill
     * "Stealth", or initiative with skill null (PHB p.189). A CHECK modifier with no skill, as Guidance's (PHB p.248),
     * covers both; one limited to a skill, as Pass without Trace's +10 to Stealth (PHB p.264), covers that skill only.
     * INITIATIVE modifiers, as Gift of Alacrity's (XGE p.157), count for initiative.
     */
    public static List<RollModifiers.Bonus> checkBonuses(String skill, List<Labelled> modifiers) {
        List<RollModifiers.Bonus> bonuses = new ArrayList<>(bonuses(modifiers, OngoingModifier.Kind.CHECK,
                modifier -> modifier.skill() == null || modifier.skill().equalsIgnoreCase(skill)));
        if (skill == null) {
            bonuses.addAll(bonuses(modifiers, OngoingModifier.Kind.INITIATIVE, modifier -> true));
        }
        return bonuses;
    }

    /**
     * The named sources of advantage and disadvantage the modifiers give an ability check the engine rolls (D199): a
     * CHECK_MODE of the check's ability, or of every ability, and of its skill or of none, as Enhance Ability's Owl's
     * Wisdom on any Wisdom check (PHB p.237). Initiative is a Dexterity check (PHB p.189), so it counts a Dexterity mode
     * with no skill and one for initiative alone, as Jim's Glowing Coin's (Acquisitions Incorporated p.76); a mode for a skill, such as
     * Enthrall's Wisdom (Perception) (PHB p.238), counts only for that skill.
     *
     * @param ability the check's ability, such as "WIS"; null when it isn't known, which counts the modes for every
     *                ability alone
     * @param skill   its skill, such as "Stealth", or {@link OngoingModifier#INITIATIVE_CHECK}; null for a plain check
     */
    public static List<AttackRules.Source> checkSources(String ability, String skill, List<Labelled> modifiers) {
        List<AttackRules.Source> sources = new ArrayList<>();
        for (Labelled labelled : modifiers) {
            OngoingModifier modifier = labelled.modifier();
            if (modifier.kind() != OngoingModifier.Kind.CHECK_MODE || modifier.mode() == null) {
                continue;
            }
            boolean abilityMatches = modifier.ability() == null
                    || ability != null && modifier.ability().equalsIgnoreCase(ability);
            boolean skillMatches = modifier.skill() == null || modifier.skill().equalsIgnoreCase(skill);
            if (abilityMatches && skillMatches) {
                sources.add(new AttackRules.Source(modifier.mode(), labelled.label()));
            }
        }
        return sources;
    }

    /** Whether the modifiers keep the creature from taking reactions (Shocking Grasp, PHB p.275, D199). */
    public static boolean noReactions(List<OngoingModifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.NO_REACTIONS);
    }

    /** The dice and amounts the modifiers add to an attack roll, weapon-only ones only for a weapon attack. */
    public static List<RollModifiers.Bonus> attackBonuses(boolean weapon, List<Labelled> modifiers) {
        return bonuses(modifiers, OngoingModifier.Kind.ATTACK_ROLL, modifier -> weapon || !modifier.weaponOnly());
    }

    /**
     * The damage a hit adds with the modifiers: weapon-only damage only for a weapon attack, melee-only only for a melee
     * one; a modifier with both dice and a flat amount, such as Elemental Weapon's, adds both (XGE p.155).
     */
    public static List<RollModifiers.HitDamage> hitDamage(boolean weapon, boolean melee, List<Labelled> modifiers) {
        List<RollModifiers.HitDamage> damage = new ArrayList<>();
        for (Labelled labelled : modifiers) {
            OngoingModifier modifier = labelled.modifier();
            if (modifier.kind() != OngoingModifier.Kind.HIT_DAMAGE || (modifier.weaponOnly() && !weapon)
                    || (modifier.meleeOnly() && !melee) || (melee && rangedOnly(modifier))) {
                continue;
            }
            String type = modifier.damageTypes().isEmpty() ? null : modifier.damageTypes().getFirst();
            Dice dice = modifier.diceOnly();
            if (dice != null) {
                damage.add(new RollModifiers.HitDamage(labelled.label(), dice, modifier.sign(), type, modifier.id(),
                        modifier.oneShot()));
            }
            if (modifier.flat() != 0) {
                damage.add(new RollModifiers.HitDamage(labelled.label(), new Dice(0, 0, Math.abs(modifier.flat())),
                        modifier.flat() < 0 ? -1 : 1, type, modifier.id(), modifier.oneShot()));
            }
            // A one-shot rider with no damage of its own, as Hail of Thorns' or Ensnaring Strike's (PHB p.249, p.237),
            // marks the hit that spends it; it adds nothing (D199)
            if (dice == null && modifier.flat() == 0 && modifier.oneShot()) {
                damage.add(new RollModifiers.HitDamage(labelled.label(), NO_DAMAGE, 1, type, modifier.id(), true));
            }
        }
        return damage;
    }

    /** The dice of a rider that marks the hit spending it and adds no damage (D199). */
    public static final Dice NO_DAMAGE = new Dice(0, 0, 0);

    /**
     * The {@code requires} of a HIT_DAMAGE that only a weapon attack from beyond 5 feet gets, there being no map, so a
     * ranged one, as Hail of Thorns' rider on the next ranged weapon hit (PHB p.249, D199); spent on that hit.
     */
    public static final String RANGED = "RANGED";

    /**
     * The {@code requires} of a one-shot HIT_DAMAGE whose dice replace the damage of the next weapon attack from beyond 5
     * feet, half of them on a miss, spent hit or miss, as Lightning Arrow's 4d8 lightning in place of the weapon's normal
     * damage (PHB p.255, D199).
     */
    public static final String REPLACES_RANGED = "REPLACES_RANGED";

    // Whether the modifier adds only to a weapon attack from beyond 5 feet: magical ammunition and ranged riders
    private static boolean rangedOnly(OngoingModifier modifier) {
        return AMMUNITION.equalsIgnoreCase(modifier.requires()) || RANGED.equalsIgnoreCase(modifier.requires())
                || REPLACES_RANGED.equalsIgnoreCase(modifier.requires());
    }

    /** The {@code requires} of a one-shot HIT_DAMAGE that the next attack it could add to spends, hit or miss (D197). */
    public static final String ON_ATTACK = "ATTACK";

    /**
     * The {@code requires} of a HIT_DAMAGE on magical ammunition, as Flame Arrows' (XGE p.156, D197): it adds to a
     * weapon attack from beyond 5 feet only, and each such attack draws one piece, hit or miss.
     */
    public static final String AMMUNITION = "AMMUNITION";

    /**
     * Whether the modifier is a one-shot rider that this attack spends whether it hits or misses, as Zephyr Strike's
     * 1d8 force, which comes with the one weapon attack it gives advantage to (XGE p.171, D197); other one-shot riders,
     * as Absorb Elements' (XGE p.150), wait for a hit.
     */
    public static boolean spentOnAttack(OngoingModifier modifier, boolean weapon, boolean melee) {
        if (modifier.kind() != OngoingModifier.Kind.HIT_DAMAGE || (modifier.weaponOnly() && !weapon)
                || (modifier.meleeOnly() && !melee)) {
            return false;
        }
        if (AMMUNITION.equalsIgnoreCase(modifier.requires())) {
            return !melee;
        }
        // Lightning Arrow's is spent by the next ranged weapon attack, hit or miss (PHB p.255, D199)
        if (REPLACES_RANGED.equalsIgnoreCase(modifier.requires())) {
            return modifier.oneShot() && !melee;
        }
        return modifier.oneShot() && ON_ATTACK.equalsIgnoreCase(modifier.requires());
    }

    /**
     * The attacker's own sources of advantage and disadvantage from its modifiers, as named sources: Vicious Mockery's
     * disadvantage on its next attack (PHB p.285), Frostbite's on its next weapon attack (XGE p.156), True Strike's
     * advantage against its target (PHB p.284). Weapon-only ones count only for a weapon attack.
     */
    public static List<AttackRules.Source> attackerSources(boolean weapon, List<Labelled> modifiers) {
        List<AttackRules.Source> sources = new ArrayList<>();
        for (Labelled labelled : modifiers) {
            OngoingModifier modifier = labelled.modifier();
            if (modifier.kind() == OngoingModifier.Kind.ATTACK_MODE && modifier.mode() != null
                    && (weapon || !modifier.weaponOnly())) {
                sources.add(new AttackRules.Source(modifier.mode(), labelled.label()));
            }
        }
        return sources;
    }

    /**
     * The sources the target's modifiers give attack rolls against it, as named sources, where the attacker meets what
     * each requires: Faerie Fire's advantage when the attacker can see the target (PHB p.239), Blur's disadvantage
     * unless the attacker has blindsight or truesight (PHB p.219), and Protection from Evil and Good's disadvantage for
     * an aberration, celestial, elemental, fey, fiend or undead attacker (PHB p.270).
     *
     * @param attackerType the attacker's creature type, or null when it has none, as a player character
     */
    public static List<AttackRules.Source> targetSources(List<Labelled> modifiers, AttackRules.Side attacker,
                                                         AttackRules.Side target, String attackerType) {
        List<AttackRules.Source> sources = new ArrayList<>();
        for (Labelled labelled : modifiers) {
            OngoingModifier modifier = labelled.modifier();
            if (modifier.kind() != OngoingModifier.Kind.ATTACKED_MODE || modifier.mode() == null) {
                continue;
            }
            if (!modifier.attackerTypes().isEmpty() && (attackerType == null || modifier.attackerTypes().stream()
                    .noneMatch(type -> attackerType.toLowerCase().contains(type.toLowerCase())))) {
                continue;
            }
            if ("SEEN".equalsIgnoreCase(modifier.requires()) && !attacker.perceives(target)) {
                continue;
            }
            if ("SIGHTED".equalsIgnoreCase(modifier.requires())
                    && (attacker.senses().blindsight() || attacker.senses().truesight())) {
                continue;
            }
            sources.add(new AttackRules.Source(modifier.mode(), labelled.label()));
        }
        return sources;
    }

    /** Whether the modifiers keep the creature from benefiting from being invisible (Faerie Fire, PHB p.239). */
    public static boolean revealed(List<OngoingModifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.REVEALED);
    }

    // Each modifier of the kind that passes, as a bonus: its dice and its flat amount, each signed
    private static List<RollModifiers.Bonus> bonuses(List<Labelled> modifiers, OngoingModifier.Kind kind,
                                                     java.util.function.Predicate<OngoingModifier> applies) {
        List<RollModifiers.Bonus> bonuses = new ArrayList<>();
        for (Labelled labelled : modifiers) {
            OngoingModifier modifier = labelled.modifier();
            if (modifier.kind() != kind || !applies.test(modifier)) {
                continue;
            }
            Dice dice = modifier.diceOnly();
            if (dice != null) {
                bonuses.add(new RollModifiers.Bonus(labelled.label(), dice, modifier.sign(), modifier.id(),
                        modifier.oneShot()));
            }
            if (modifier.flat() != 0) {
                bonuses.add(new RollModifiers.Bonus(labelled.label(), new Dice(0, 0, Math.abs(modifier.flat())),
                        modifier.flat() < 0 ? -1 : 1, modifier.id(), modifier.oneShot()));
            }
        }
        return bonuses;
    }
}
