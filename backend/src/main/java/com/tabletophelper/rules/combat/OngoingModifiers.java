package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Speeds with the modifiers, each kind the same way: feet added or taken first, such as Longstrider's +10 (PHB
     * p.256) or Ray of Frost's -10 (PHB p.271), never below 0; then doubled for Haste (PHB p.250) and halved, rounding
     * down, for Slow (PHB p.277), so both together leave it as it was. Exhaustion is applied after these (D183).
     */
    public static Map<String, Integer> speeds(Map<String, Integer> speeds, List<OngoingModifier> modifiers) {
        int added = 0;
        int doubled = 0;
        int halved = 0;
        for (OngoingModifier modifier : modifiers) {
            switch (modifier.kind()) {
                case SPEED -> added += modifier.flat();
                case SPEED_DOUBLED -> doubled++;
                case SPEED_HALVED -> halved++;
                default -> {
                    // Not a speed
                }
            }
        }
        Map<String, Integer> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> speed : speeds.entrySet()) {
            changed.put(speed.getKey(), speed(speed.getValue(), added, doubled, halved));
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
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.HP_MAX) {
                total += modifier.flat();
            }
        }
        return Math.max(1, total);
    }

    /** Whether the modifiers resist the damage type. */
    public static boolean resists(String damageType, List<OngoingModifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.RESISTANCE
                && modifier.resists(damageType));
    }

    /** Whether the modifiers keep the creature from regaining hit points (Chill Touch, PHB p.221). */
    public static boolean noHealing(List<OngoingModifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.NO_HEALING);
    }

    /** Whether the modifiers make the creature immune to the condition (Heroism's frightened, PHB p.250). */
    public static boolean immuneTo(String condition, List<OngoingModifier> modifiers) {
        return condition != null && modifiers.stream()
                .anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.CONDITION_IMMUNITY
                        && condition.trim().equalsIgnoreCase(modifier.condition()));
    }

    /**
     * The modes the modifiers give a saving throw of the ability, a death save when ability is null (PHB p.197):
     * Haste's advantage on Dexterity saves (PHB p.250), Beacon of Hope's on Wisdom and death saves (PHB p.217).
     */
    public static List<RollMode> saveModes(String ability, List<OngoingModifier> modifiers) {
        List<RollMode> modes = new ArrayList<>();
        for (OngoingModifier modifier : modifiers) {
            if (modifier.kind() == OngoingModifier.Kind.SAVE_MODE && modifier.mode() != null
                    && modifier.coversSave(ability)) {
                modes.add(modifier.mode());
            }
        }
        return modes;
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
                    || (modifier.meleeOnly() && !melee)) {
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
        }
        return damage;
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
