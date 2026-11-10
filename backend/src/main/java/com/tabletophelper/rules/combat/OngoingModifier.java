package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One Ongoing Modifier (D184): a typed change a lasting spell makes to the rolls and numbers of the creatures it
 * affects, such as Bless's +1d4 to attack rolls and saving throws (PHB p.219) or Haste's +2 AC (PHB p.250). It lives in
 * its Spell Effect, so it lasts exactly as long as the effect does, and what applies to a creature is worked out from
 * the encounter's Spell Effects whenever a roll needs it.
 *
 * @param id            identifies it, so a one-shot modifier can be spent once it is used
 * @param kind          what it changes
 * @param scope         whose rolls it changes: the affected creatures', or the caster's against them
 * @param dice          dice it adds, with a minus sign for dice it takes away, such as "-1d4"; null for none
 * @param flat          a flat amount it adds: a bonus, an AC, feet of speed or hit points, by kind
 * @param mode          the advantage or disadvantage it gives, for the *_MODE kinds
 * @param ability       the saving throw it changes, such as "DEX", or "DEATH" for death saves only; null for every save,
 *                      death saves included (PHB p.197)
 * @param damageTypes   the damage types it resists, "all" for every type; or for HIT_DAMAGE the one type it deals, null
 *                      being the weapon's own
 * @param weaponOnly    it changes weapon attacks only, as Magic Weapon (PHB p.257) and Hunter's Mark (PHB p.251) do
 * @param meleeOnly     it changes melee attacks only, as Absorb Elements' extra damage (XGE p.150)
 * @param oneShot       it is spent the first time it applies, as Resistance's die (PHB p.272)
 * @param until         an end of its own, earlier than the spell's; null when it lasts as long as the spell
 * @param armed         for an end at the end of a turn: that turn has started, so its end ends the modifier
 * @param requires      what the attacker needs: "SEEN" (it can see the creature, Faerie Fire PHB p.239) or "SIGHTED"
 *                      (it has neither blindsight nor truesight, Blur PHB p.219); for a RESISTANCE, "NONMAGICAL" when it
 *                      resists only nonmagical damage (Stoneskin, PHB p.278); for a CONDITION_IMMUNITY, "MAGICAL" when it
 *                      guards only against magic (Freedom of Movement, PHB p.244); null for nothing
 * @param attackerTypes the creature types an attacker must have for it to apply, such as Protection from Evil and
 *                      Good's (PHB p.270); empty for any attacker
 * @param condition     the condition it makes the creature immune to, for CONDITION_IMMUNITY; for a SAVE_MODE, the
 *                      conditions, comma-separated, of the effects whose saves it changes, as Aura of Purity's (PHB
 *                      p.216, D185), null for every save
 * @param skill         the skill a CHECK modifier is limited to, such as "Stealth"; null for any ability check the
 *                      engine rolls (Hide and initiative)
 */
public record OngoingModifier(UUID id, Kind kind, Scope scope, String dice, int flat, RollMode mode, String ability,
                              List<String> damageTypes, boolean weaponOnly, boolean meleeOnly, boolean oneShot,
                              Until until, boolean armed, String requires, List<String> attackerTypes, String condition,
                              String skill) {

    /** What a modifier changes. */
    public enum Kind {
        /** Dice or a flat amount added to the creature's attack rolls. */
        ATTACK_ROLL,
        /** Advantage or disadvantage on the creature's attack rolls. */
        ATTACK_MODE,
        /** Advantage or disadvantage on attack rolls against the creature. */
        ATTACKED_MODE,
        /** The creature can't benefit from being invisible (Faerie Fire, PHB p.239). */
        REVEALED,
        /** Dice or a flat amount added to the creature's saving throws. */
        SAVE,
        /** Advantage or disadvantage on the creature's saving throws. */
        SAVE_MODE,
        /** A bonus or penalty to the creature's AC. */
        AC,
        /** The lowest AC the creature can have (Barkskin, PHB p.217). */
        AC_FLOOR,
        /** A base AC that replaces a lower one (Mage Armor's 13 + DEX, PHB p.256), worked out when cast. */
        AC_BASE,
        /** Extra damage, or less, on the creature's hits. */
        HIT_DAMAGE,
        /** Feet added to the creature's speeds. */
        SPEED,
        /** The creature's speeds doubled (Haste, PHB p.250). */
        SPEED_DOUBLED,
        /** The creature's speeds halved (Slow, PHB p.277). */
        SPEED_HALVED,
        /** Resistance to damage types. */
        RESISTANCE,
        /** Hit points added to the creature's maximum (Aid, PHB p.211). */
        HP_MAX,
        /** The creature can't regain hit points (Chill Touch, PHB p.221). */
        NO_HEALING,
        /** Temporary hit points at the start of each of the creature's turns (Heroism, PHB p.250). */
        TEMP_HP_EACH_TURN,
        /** Immunity to a condition (Heroism's frightened, PHB p.250). */
        CONDITION_IMMUNITY,
        /** Dice or a flat amount added to the ability checks the engine rolls: Hide and initiative. */
        CHECK,
        /** Dice or a flat amount added to the creature's initiative (Gift of Alacrity, XGE p.157). */
        INITIATIVE,
        /**
         * The first time the creature would drop to 0 hit points from damage it drops to 1 instead, and the spell ends
         * (Death Ward, PHB p.230, D185).
         */
        DEATH_WARD,
        /** Magic can't reduce the creature's speed (Freedom of Movement, PHB p.244, D185). */
        SPEED_PROTECTED,
        /** The creature's hit point maximum can't be reduced (Aura of Life, PHB p.216, D185). */
        HP_MAX_PROTECTED,
        /** A living creature at 0 hit points regains this many at the start of its turn (Aura of Life, PHB p.216). */
        HEAL_AT_ZERO,
        /** The creature loses its resistance to the damage types (Elemental Bane, XGE p.155, D185). */
        LOSE_RESISTANCE,
        /** The creature's speeds are at most this many feet (Power Word Pain, XGE p.163, D189). */
        SPEED_MAX,
        /** The creature regains this many hit points at the start of each of its turns (Regenerate, PHB p.271, D189). */
        HEAL_EACH_TURN,
        /** Immunity to damage types (Investiture of Flame's fire, XGE p.159, D188). */
        DAMAGE_IMMUNITY,
        /**
         * Evasion (D188): on a Dexterity save for half damage the creature takes none on a success and half on a
         * failure, as Fizban's Platinum Shield gives it (FTD p.20); the rogue's and monk's own come from their levels.
         */
        EVASION,
        /** Truesight, as True Seeing gives (PHB p.284, D188), read with the creature's other senses. */
        TRUESIGHT,
        /**
         * Spells of {@link #flat} level or lower cast from outside have no effect on the creature, as inside a Globe of
         * Invulnerability (PHB p.245, D188); a caster under the same Spell Effect is inside with it.
         */
        SPELL_IMMUNITY,
        /**
         * The creature deals only half damage with weapon attacks that use Strength, as under Ray of Enfeeblement (PHB
         * p.271, D197).
         */
        HALF_STRENGTH_DAMAGE,
        /** The creature can't attack or cast spells, as in Gaseous Form (PHB p.242, D199). */
        CANT_ATTACK_OR_CAST
    }

    /** Whose rolls a modifier changes. */
    public enum Scope {
        /** The affected creatures' own rolls and numbers. */
        AFFECTED,
        /** The caster's attacks against an affected creature (Hunter's Mark, Hex, True Strike). */
        CASTER_VS_AFFECTED,
        /** An affected creature's attacks against the caster (Chill Touch on an undead, PHB p.221). */
        AFFECTED_VS_CASTER,
        /** An affected creature's attacks against anyone but the caster (Compelled Duel, PHB p.224). */
        AFFECTED_VS_OTHERS
    }

    /** An end a modifier has of its own, before its spell's. */
    public enum Until {
        /** The start of the caster's next turn. */
        CASTER_TURN_START,
        /** The end of the caster's next turn. */
        CASTER_TURN_END,
        /** The end of the affected creature's next turn. */
        TARGET_TURN_END
    }

    public OngoingModifier {
        Objects.requireNonNull(kind, "kind");
        scope = scope != null ? scope : Scope.AFFECTED;
        damageTypes = damageTypes != null ? List.copyOf(damageTypes) : List.of();
        attackerTypes = attackerTypes != null ? List.copyOf(attackerTypes) : List.of();
        if (dice != null && !dice.isBlank()) {
            // Fails early on dice that can't be rolled
            Dice.parse(dice.trim().replaceFirst("^[+-]", ""));
        }
    }

    /** The same modifier, armed: the turn whose end ends it has started. */
    public OngoingModifier armedNow() {
        return new OngoingModifier(id, kind, scope, dice, flat, mode, ability, damageTypes, weaponOnly, meleeOnly,
                oneShot, until, true, requires, attackerTypes, condition, skill);
    }

    /** Its dice without the sign, or null for none. */
    public Dice diceOnly() {
        return dice == null || dice.isBlank() ? null : Dice.parse(dice.trim().replaceFirst("^[+-]", ""));
    }

    /** -1 when its dice are taken away, as Bane's are (PHB p.216); otherwise 1. */
    public int sign() {
        return dice != null && dice.trim().startsWith("-") ? -1 : 1;
    }

    /** Whether it changes a saving throw of this ability; null asks about a death saving throw. */
    public boolean coversSave(String saveAbility) {
        if (ability == null) {
            return true;
        }
        String asked = saveAbility == null ? DEATH : saveAbility.toUpperCase(Locale.ROOT);
        return ability.equalsIgnoreCase(asked);
    }

    /** Whether it resists this damage type. */
    public boolean resists(String damageType) {
        if (damageType == null) {
            return false;
        }
        return damageTypes.stream().anyMatch(type -> type.equalsIgnoreCase(ALL) || type.equalsIgnoreCase(damageType.trim()));
    }

    /** What a log line or chip calls it, such as "+1d4 to attack rolls" or "AC +2". */
    public String describe() {
        String amount = amount();
        return switch (kind) {
            case ATTACK_ROLL -> amount + " to " + (weaponOnly ? "weapon " : "") + "attack rolls";
            case ATTACK_MODE -> modeWord() + " on " + (oneShot ? "its next " + (weaponOnly ? "weapon " : "")
                    + "attack roll" : (weaponOnly ? "weapon " : "") + "attack rolls")
                    + switch (scope) {
                        case CASTER_VS_AFFECTED -> " by the caster against it";
                        case AFFECTED_VS_CASTER -> " against the caster";
                        case AFFECTED_VS_OTHERS -> " against anyone but the caster";
                        case AFFECTED -> "";
                    };
            case ATTACKED_MODE -> modeWord() + " on attack rolls against it";
            case REVEALED -> "can't benefit from being invisible";
            case SAVE -> amount + " to " + (oneShot ? "its next " + saveWords().replaceFirst("s$", "") : saveWords());
            case SAVE_MODE -> modeWord() + " on " + saveWords()
                    + (condition != null && !condition.isBlank() ? " against " + condition.replace(",", ", ") : "");
            case AC -> "AC " + signed(flat);
            case AC_FLOOR -> "AC at least " + flat;
            case AC_BASE -> "base AC " + flat;
            case HIT_DAMAGE -> amount + (damageTypes.isEmpty() ? "" : " " + damageTypes.getFirst()) + " damage on "
                    + (oneShot ? "its next " : "") + (meleeOnly ? "melee " : weaponOnly ? "weapon " : "") + "hit"
                    + (oneShot ? "" : "s") + (scope == Scope.CASTER_VS_AFFECTED ? " by the caster against it" : "");
            case SPEED -> "speed " + signed(flat) + " ft.";
            case SPEED_DOUBLED -> "speed doubled";
            case SPEED_HALVED -> "speed halved";
            case RESISTANCE -> "resistance to " + ("NONMAGICAL".equalsIgnoreCase(requires) ? "nonmagical " : "")
                    + (damageTypes.stream().anyMatch(ALL::equalsIgnoreCase) ? "all damage"
                    : String.join(", ", damageTypes) + " damage");
            case HP_MAX -> "hit point maximum " + signed(flat);
            case NO_HEALING -> "can't regain hit points";
            case TEMP_HP_EACH_TURN -> flat + " temporary hit points at the start of each of its turns";
            case CONDITION_IMMUNITY -> "immune to being " + condition
                    + ("MAGICAL".equalsIgnoreCase(requires) ? " by magic" : "");
            case CHECK -> amount + " to " + (oneShot ? "its next " : "") + (skill != null ? skill + " checks"
                    : "ability check (Hide or initiative)");
            case INITIATIVE -> amount + " to initiative";
            case DEATH_WARD -> "drops to 1 hit point instead of 0, once";
            case SPEED_PROTECTED -> "magic can't reduce its speed";
            case HP_MAX_PROTECTED -> "hit point maximum can't be reduced";
            case HEAL_AT_ZERO -> "regains " + flat + " hit point" + (flat == 1 ? "" : "s")
                    + " at the start of its turn at 0 hit points";
            case LOSE_RESISTANCE -> "loses resistance to " + String.join(", ", damageTypes) + " damage";
            case SPEED_MAX -> "speed at most " + flat + " ft.";
            case HEAL_EACH_TURN -> "regains " + flat + " hit point" + (flat == 1 ? "" : "s")
                    + " at the start of each of its turns";
            // "all" for every type, as Invulnerability's (XGE p.160, D190)
            case DAMAGE_IMMUNITY -> "immune to " + (damageTypes.stream().anyMatch(ALL::equalsIgnoreCase)
                    ? "all damage" : String.join(", ", damageTypes) + " damage");
            case EVASION -> "Evasion";
            case TRUESIGHT -> "truesight";
            case SPELL_IMMUNITY -> "spells of level " + flat + " or lower cast from outside have no effect";
            case HALF_STRENGTH_DAMAGE -> "half damage with weapon attacks that use Strength";
            case CANT_ATTACK_OR_CAST -> "can't attack or cast spells";
        } + (until != null ? " (" + untilWords() + ")" : "");
    }

    /** The death saving throw's name in {@link #ability}. */
    public static final String DEATH = "DEATH";

    /** The damage type that stands for every type in {@link #damageTypes}. */
    public static final String ALL = "all";

    private String amount() {
        StringBuilder text = new StringBuilder();
        if (dice != null && !dice.isBlank()) {
            text.append(sign() < 0 ? "-" : "+").append(diceOnly());
        }
        if (flat != 0 || text.isEmpty()) {
            text.append(text.isEmpty() ? signed(flat) : " " + signed(flat).replace("+", "+ ").replace("-", "- "));
        }
        return text.toString();
    }

    private String modeWord() {
        return mode == RollMode.DISADVANTAGE ? "disadvantage" : "advantage";
    }

    private String saveWords() {
        if (ability == null) return "saving throws";
        if (DEATH.equalsIgnoreCase(ability)) return "death saving throws";
        return ability.toUpperCase(Locale.ROOT) + " saving throws";
    }

    private String untilWords() {
        return switch (until) {
            case CASTER_TURN_START -> "until the start of the caster's next turn";
            case CASTER_TURN_END -> "until the end of the caster's next turn";
            case TARGET_TURN_END -> "until the end of its next turn";
        };
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }
}
