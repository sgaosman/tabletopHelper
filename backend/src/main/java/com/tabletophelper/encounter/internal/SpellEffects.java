package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.EquippedArmour;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.CastingTime;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a spell's effect template into an Effect (D030, D160). A cantrip's dice scale with the caster's level,
 * a spell cast with a higher slot adds its upcast dice, and the template's MOD placeholder becomes the
 * caster's spellcasting modifier as a number. A spell the template leaves to the DM, or whose dice can't be
 * rolled, comes back as a plan without an Effect, with the reason.
 */
@Component
@RequiredArgsConstructor
public class SpellEffects {

    private static final String NO_TEMPLATE = "No effect template available";

    private final SpellRepository spellRepository;
    private final CreatureStats stats;

    /**
     * A spell as cast.
     *
     * @param spellLevel     the spell's level, 0 for a cantrip
     * @param effect         what it does, or null when the DM resolves it by hand
     * @param manualReason   why the DM resolves it, when effect is null
     * @param concentration  whether it needs concentration, which casting starts even when resolved by hand
     * @param durationRounds how many rounds it lasts; null when unknown
     * @param verbal         whether it has a verbal component, which silence prevents
     */
    public record SpellPlan(String spellName, int spellLevel, Effect effect, String manualReason,
                            boolean concentration, Integer durationRounds, boolean verbal) {

        public boolean automated() {
            return effect != null;
        }
    }

    /**
     * The spell cast with a slot of slotLevel, or 0 for none.
     *
     * @param castingAbility the ability the spell is cast with, which gives its spellcasting modifier; null when
     *                       unknown, and then the modifier is worked back from the attack bonus
     * @param attackBonus    the caster's spell attack bonus
     * @param saveDc         the caster's spell save DC
     * @param advantage      true for advantage on a spell attack, false for disadvantage, null for neither
     */
    public SpellPlan forCast(String spellName, int slotLevel, EncounterParticipant caster, String castingAbility,
                             int attackBonus, int saveDc, Boolean advantage) {
        return forCast(spellName, slotLevel, caster, castingAbility, attackBonus, saveDc, advantage, null);
    }

    /**
     * As {@link #forCast(String, int, EncounterParticipant, String, int, int, Boolean)}, with the option chosen for a
     * spell that has them (D184): an option the template lists among its noSaveOptions reaches its target with no
     * saving throw, as Enlarge does a willing creature (PHB p.237).
     * <p>
     * A spell without a template is the DM's to resolve, but one the spells data says needs concentration still starts
     * it, lasting as long as the spell's duration says when that can be read, and until dropped otherwise.
     */
    public SpellPlan forCast(String spellName, int slotLevel, EncounterParticipant caster, String castingAbility,
                             int attackBonus, int saveDc, Boolean advantage, String option) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty()) {
            Optional<Spell> spell = spellRepository.findByNameIgnoreCase(spellName);
            boolean concentration = spell.map(Spell::getConcentration).map(Boolean.TRUE::equals).orElse(false);
            return manual(spellName, spell.map(Spell::getLevel).orElse(0), NO_TEMPLATE, concentration,
                    concentration ? spell.map(Spell::getDuration).map(SpellEffects::durationRounds).orElse(null)
                            : null,
                    false);
        }
        SpellTemplate template = found.get();
        int spellLevel = template.level();
        boolean concentration = Boolean.TRUE.equals(template.concentration());
        // A duration that grows with the slot, as Dominate Beast's (PHB p.234, D185)
        Integer durationRounds = template.durationRounds(slotLevel);
        boolean verbal = template.verbal();

        if (Boolean.TRUE.equals(template.requiresManualResolution())) {
            String reason = template.manualResolutionReason() != null ? template.manualResolutionReason()
                    : "Complex spell requires DM adjudication";
            return manual(spellName, spellLevel, reason, concentration, durationRounds, verbal);
        }
        String deliveryMethod = orNone(template.deliveryMethod());
        if (option != null && template.noSaveOptions().stream().anyMatch(option::equalsIgnoreCase)) {
            deliveryMethod = "AUTO_HIT";
        }
        Effect.Delivery delivery = delivery(deliveryMethod, template.spellAttackType(), template.saveAbility(),
                template.halfOnSave(), attackBonus, saveDc, advantage);
        if (delivery == null) {
            return manual(spellName, spellLevel, "Delivery method '" + deliveryMethod + "' requires DM adjudication",
                    concentration, durationRounds, verbal);
        }
        // A spell attack is no weapon attack, so weapon-only Ongoing Modifiers leave it alone (D184)
        if (delivery instanceof Effect.Attack attack) {
            delivery = attack.withWeapon(false);
        }

        int modifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);
        // A spell on its caster heals or gives conditions; any damage in its template is for others to take
        Optional<List<Effect.DamagePart>> damage = delivery instanceof Effect.Self
                ? Optional.of(List.of())
                : damage(template.damageEffects(), spellLevel, slotLevel, caster, modifier);
        SpellTemplate.Healing healingData = template.healing();
        Optional<Healing> healing = healing(healingData != null ? healingData.anyDice() : null,
                healingData != null ? healingData.upcastScaling() : null,
                spellLevel > 0 ? slotLevel - spellLevel : 0, modifier);
        if (damage.isEmpty() || healing.isEmpty()) {
            return manual(spellName, spellLevel, spellName + "'s dice can't be rolled automatically",
                    concentration, durationRounds, verbal);
        }

        Map<String, Effect.ConditionEnd> ends = conditionEnds(template.conditionsInflicted(), template, saveDc);
        if (!java.util.Objects.equals(durationRounds, template.durationRounds())) {
            ends = scaled(ends, template.durationRounds(), durationRounds);
        }
        Effect effect = new Effect(spellName, delivery, damage.get(), healing.get().dice(), conditions(template),
                concentration, durationRounds, ends);
        return new SpellPlan(spellName, spellLevel, effect, null, concentration, durationRounds, verbal);
    }

    // The conditions that lasted as long as the spell's own duration, lasting as long as the slot's (D185)
    private static Map<String, Effect.ConditionEnd> scaled(Map<String, Effect.ConditionEnd> ends, Integer base,
                                                          Integer rounds) {
        Map<String, Effect.ConditionEnd> changed = new LinkedHashMap<>();
        ends.forEach((name, end) -> changed.put(name, base != null && base.equals(end.durationRounds())
                ? end.lasting(rounds) : end));
        return changed;
    }

    /**
     * A lasting spell's effect used again by its caster on a later turn, such as Spiritual Weapon's attack or Flaming
     * Sphere's ram, as the template's repeatEffect describes it: true for the spell's own effect again, conditions
     * included, or an object that replaces its delivery, dice and save and gives no conditions. It starts no
     * concentration.
     */
    public SpellPlan forRepeat(String spellName, int slotLevel, EncounterParticipant caster, String castingAbility,
                               int attackBonus, int saveDc, Boolean advantage) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty()) {
            return manual(spellName, 0, NO_TEMPLATE, false, null, false);
        }
        SpellTemplate template = found.get();
        if (template.repeatEffect() == null) {
            return manual(spellName, template.level(), "Spell has no repeatable effect", false, null, false);
        }
        return planFor(template, spellName, template.repeatEffect(), true, List.of(), slotLevel, caster,
                castingAbility, attackBonus, saveDc, advantage);
    }

    /**
     * What a lasting spell's area does by itself at a moment of a creature's turn (M15), such as Moonbeam's save
     * against its damage when a creature starts its turn in the beam (PHB p.261): the trigger's own effect, or the
     * spell's. It starts no concentration, and gives only the conditions the trigger lists, as Evard's Black Tentacles'
     * restrained on a failed save (PHB p.238, D185).
     */
    public SpellPlan forTrigger(String spellName, SpellTrigger when, int slotLevel, EncounterParticipant caster,
                                String castingAbility, int attackBonus, int saveDc) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty()) {
            return manual(spellName, 0, NO_TEMPLATE, false, null, false);
        }
        SpellTemplate template = found.get();
        Optional<SpellTemplate.EffectTrigger> trigger = template.trigger(when);
        if (trigger.isEmpty()) {
            return manual(spellName, template.level(), spellName + " does nothing by itself at that moment",
                    false, null, false);
        }
        return planFor(template, spellName, trigger.get().effect(), false, trigger.get().conditions(), slotLevel,
                caster, castingAbility, attackBonus, saveDc, null);
    }

    /**
     * What a trigger does to a creature that already has the condition the trigger names from this spell, as Evard's
     * Black Tentacles' 3d6 bludgeoning to a creature already restrained by them, with no save (PHB p.238, D185): the
     * trigger's damage, automatically, with no conditions. Empty when the trigger names no condition.
     */
    public Optional<SpellPlan> forConditionedTrigger(String spellName, SpellTrigger when, int slotLevel,
                                                     EncounterParticipant caster, String castingAbility,
                                                     int attackBonus, int saveDc) {
        Optional<SpellTemplate> found = template(spellName);
        Optional<SpellTemplate.EffectTrigger> trigger = found.flatMap(template -> template.trigger(when))
                .filter(each -> each.whenConditioned() != null);
        if (trigger.isEmpty()) {
            return Optional.empty();
        }
        SpellPlan plan = planFor(found.get(), spellName, trigger.get().effect(), false, List.of(), slotLevel, caster,
                castingAbility, attackBonus, saveDc, null);
        if (!plan.automated()) {
            return Optional.of(plan);
        }
        Effect effect = plan.effect();
        return Optional.of(new SpellPlan(spellName, plan.spellLevel(), new Effect(spellName, new Effect.Automatic(),
                effect.damage(), effect.healing(), List.of(), false, null), null, false, null, false));
    }

    /**
     * The trigger at that moment, if the spell has one (D185): the condition that makes it automatic, the exhaustion
     * it gives and whether a creature it reaches comes under the spell's Ongoing Modifiers.
     */
    public Optional<SpellTemplate.EffectTrigger> trigger(String spellName, SpellTrigger when) {
        return template(spellName).flatMap(template -> template.trigger(when));
    }

    /**
     * What a cast of the spell with a slot of slotLevel leaves for later (D185): its delayed damage with the upcast
     * dice added, its damage budget, its retaliation for the option chosen, and its turn reminder.
     *
     * @param delayedDice the dice of the delayed damage, or null for none
     * @param delayedOn   the outcome that brings it: FAILED_SAVE or HIT
     * @param retaliationDice the retaliation's dice, or null for none
     */
    public record Later(String delayedDice, String delayedType, String delayedOn, Integer damageBudget,
                        String retaliationDice, String retaliationType, String turnReminder) {

        public static final Later NONE = new Later(null, null, null, null, null, null, null);

        public boolean any() {
            return delayedDice != null || damageBudget != null || retaliationDice != null || turnReminder != null;
        }
    }

    /** What a cast of the spell leaves for later (D185); nothing for a spell without a template. */
    public Later later(String spellName, int slotLevel, String option) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty()) {
            return Later.NONE;
        }
        SpellTemplate template = found.get();
        String delayedDice = null;
        String delayedType = null;
        String delayedOn = null;
        SpellTemplate.DelayedDamage delayed = template.delayedDamage();
        if (delayed != null && delayed.damageDice() != null) {
            int levelsAbove = template.level() > 0 ? slotLevel - template.level() : 0;
            Optional<List<Dice>> dice = TemplateDice.read(delayed.damageDice())
                    .flatMap(groups -> upcast(groups, delayed.upcastScaling(), levelsAbove));
            if (dice.isPresent() && dice.get().size() == 1) {
                delayedDice = dice.get().getFirst().toString();
                delayedType = delayed.damageType();
                delayedOn = delayed.on();
            }
        }
        SpellTemplate.Retaliation retaliation = template.retaliation();
        return new Later(delayedDice, delayedType, delayedOn, template.damageBudget(),
                retaliation != null ? retaliation.damageDice() : null,
                retaliation != null ? retaliation.typeFor(option) : null, template.turnReminder());
    }

    /**
     * The spell's creature-type rules (D185): the types it can target alone, those it has no effect on and those that
     * save with disadvantage and take its most damage, as Blight's (PHB p.219).
     */
    public record TypeRules(List<String> targetTypes, List<String> noEffectOn, List<String> maximumDamageTo) {

        public static final TypeRules NONE = new TypeRules(List.of(), List.of(), List.of());

        public boolean any() {
            return !targetTypes.isEmpty() || !noEffectOn.isEmpty() || !maximumDamageTo.isEmpty();
        }
    }

    /** The temporary hit points a cast with the option gives each creature it affects (D185); null for none. */
    public Integer tempHitPoints(String spellName, String option) {
        return template(spellName).map(template -> template.tempHitPoints(option)).orElse(null);
    }

    /** The spell's creature-type rules; none for a spell without a template. */
    public TypeRules typeRules(String spellName) {
        return template(spellName).map(template -> new TypeRules(template.targetTypes(), template.noEffectOn(),
                template.maximumDamageTo())).orElse(TypeRules.NONE);
    }

    /**
     * Whether a successful save against what the spell's area does at that moment ends the spell, as a burning target's
     * Constitution save does Searing Smite's (PHB p.274): the template's trigger says so with endsSpellOnSave.
     */
    public boolean triggerSaveEndsSpell(String spellName, SpellTrigger when) {
        return template(spellName).flatMap(template -> template.trigger(when))
                .map(SpellTemplate.EffectTrigger::endsSpellOnSave)
                .orElse(false);
    }

    // A repeat effect or a trigger as an Effect: the spell's own effect unless it replaces it, with the trigger's own
    // conditions when it lists them (D185)
    private SpellPlan planFor(SpellTemplate template, String spellName, SpellTemplate.RepeatEffect repeat,
                              boolean ownConditions, List<SpellTemplate.ConditionInflicted> triggerConditions,
                              int slotLevel, EncounterParticipant caster, String castingAbility,
                              int attackBonus, int saveDc, Boolean advantage) {
        int spellLevel = template.level();
        int modifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);

        // The repeat effect's delivery and save, where it gives them, in place of the spell's
        String deliveryMethod;
        String spellAttackType;
        String saveAbility;
        Boolean halfOnSave;
        Optional<List<Effect.DamagePart>> damage;
        Optional<Healing> healing;
        if (repeat.replaces()) {
            deliveryMethod = orNone(repeat.deliveryMethod() != null ? repeat.deliveryMethod()
                    : template.deliveryMethod());
            spellAttackType = repeat.spellAttackType() != null ? repeat.spellAttackType() : template.spellAttackType();
            saveAbility = repeat.saveAbility() != null ? repeat.saveAbility() : template.saveAbility();
            halfOnSave = repeat.halfOnSave() != null ? repeat.halfOnSave() : template.halfOnSave();
            SpellTemplate.Upcast upcastScaling = repeat.upcastScaling() != null ? repeat.upcastScaling()
                    : template.firstDamageEffect().map(SpellTemplate.Part::upcastScaling).orElse(null);
            int levelsAbove = repeat.usesUpcastScaling() && spellLevel > 0 ? slotLevel - spellLevel : 0;
            damage = damageParts(repeat.damageDice(), repeat.damageType(), upcastScaling, levelsAbove, modifier);
            healing = healing(repeat.healingDice(), null, 0, modifier);
        } else {
            deliveryMethod = orNone(template.deliveryMethod());
            spellAttackType = template.spellAttackType();
            saveAbility = template.saveAbility();
            halfOnSave = template.halfOnSave();
            damage = damage(template.damageEffects(), spellLevel, slotLevel, caster, modifier);
            healing = healing(template.healing() != null ? template.healing().anyDice() : null, null, 0, modifier);
        }
        if (damage.isEmpty() || healing.isEmpty()) {
            return manual(spellName, spellLevel, spellName + "'s dice can't be rolled automatically",
                    false, null, false);
        }

        Effect.Delivery delivery;
        if (healing.get().dice() != null && damage.get().isEmpty()) {
            // Healing alone reaches its targets with no roll
            delivery = new Effect.Automatic();
        } else {
            delivery = delivery(deliveryMethod, spellAttackType, saveAbility, halfOnSave, attackBonus, saveDc,
                    advantage);
            if (delivery == null || delivery instanceof Effect.Self) {
                return manual(spellName, spellLevel,
                        "Repeat delivery method '" + deliveryMethod + "' requires DM adjudication", false, null, false);
            }
            // A spell's attack, such as Spiritual Weapon's, is a melee spell attack, not a weapon attack (PHB p.278)
            if (delivery instanceof Effect.Attack attack) {
                delivery = attack.withWeapon(false);
            }
        }
        boolean spellsOwn = ownConditions && !repeat.replaces();
        List<SpellTemplate.ConditionInflicted> given = spellsOwn ? template.conditionsInflicted() : triggerConditions;
        Effect effect = new Effect(spellName, delivery, damage.get(), healing.get().dice(),
                names(given), false, null, conditionEnds(given, template, saveDc));
        return new SpellPlan(spellName, spellLevel, effect, null, false, null, false);
    }

    // ── Ongoing Modifiers (D184) ─────────────────────────────────

    private static final Pattern DURATION = Pattern.compile(
            "^(?:concentration,\\s*up to\\s*)?(\\d{1,4})\\s*(round|minute|hour|day)s?$", Pattern.CASE_INSENSITIVE);

    /**
     * The rounds a duration as the spells data words it lasts, such as 10 for "Concentration, up to 1 minute" or 600
     * for "1 hour" (a round is 6 seconds, PHB p.189); null when it can't be read, such as "Special".
     */
    public static Integer durationRounds(String duration) {
        if (duration == null) {
            return null;
        }
        Matcher matcher = DURATION.matcher(duration.trim());
        if (!matcher.matches()) {
            return null;
        }
        int amount = Integer.parseInt(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "round" -> amount;
            case "minute" -> amount * 10;
            case "hour" -> amount * 600;
            default -> amount * 14400;
        };
    }

    /**
     * What a cast leaves of Ongoing Modifiers (D184).
     *
     * @param modifiers      the modifiers, their numbers fixed for this cast
     * @param option         the option chosen, or null
     * @param repeatSave     the ability each affected creature repeats its save with at the end of its turns, or null
     * @param reminder       what the cast reminds the table of, or null
     * @param endReminder    what the effect's end reminds the table of, or null
     * @param durationRounds how long the spell lasts, when its template says
     * @param notes          what the cast's log adds, such as Mage Armor having no effect on a creature in armour
     */
    public record ModifierPlan(List<OngoingModifier> modifiers, String option, String repeatSave, String reminder,
                               String endReminder, Integer durationRounds, List<String> notes) {

        public static final ModifierPlan NONE = new ModifierPlan(List.of(), null, null, null, null, null, List.of());

        public ModifierPlan {
            modifiers = List.copyOf(modifiers);
            notes = List.copyOf(notes);
        }

        public boolean any() {
            return !modifiers.isEmpty();
        }
    }

    /**
     * The options a cast of the spell chooses between (D184), such as Enlarge/Reduce's "enlarge" and "reduce"; empty
     * for a spell without them or without a template.
     */
    public List<String> castOptions(String spellName) {
        return template(spellName).map(SpellTemplate::castOptions).orElse(List.of());
    }

    /**
     * The option the request gives, checked against the spell's: one of them, in any case, when the spell has options,
     * and none when it hasn't.
     *
     * @throws ValidationException a missing or unknown option, or one for a spell that has none
     */
    public String option(String spellName, String requested) {
        List<String> options = castOptions(spellName);
        if (options.isEmpty()) {
            if (requested != null && !requested.isBlank()) {
                throw new ValidationException(spellName + " has no options to choose");
            }
            return null;
        }
        if (requested == null || requested.isBlank()) {
            throw new ValidationException("Choose an option for " + spellName + ": " + String.join(", ", options));
        }
        return options.stream().filter(requested.trim()::equalsIgnoreCase).findFirst()
                .orElseThrow(() -> new ValidationException(requested + " isn't an option for " + spellName + ": "
                        + String.join(", ", options)));
    }

    /**
     * The Ongoing Modifiers a cast of the spell with a slot of slotLevel leaves (D184): its template's, for the option
     * chosen, with their numbers fixed. A step of slot levels above the spell's own adds the upcast's amount and dice,
     * as Magic Weapon's +2 at 4th level (PHB p.257); a damage type of OPTION is the option; Heroism's amount is the
     * caster's spellcasting modifier (PHB p.250); and Mage Armor's base is 13 + the creature's Dexterity modifier, with
     * a shield's bonus it carries, and does nothing on a creature wearing armour (PHB p.256).
     *
     * @param spellcastingModifier the caster's spellcasting ability modifier
     * @param first                the first creature affected, whose Dexterity and armour Mage Armor reads; null for
     *                             none
     */
    public ModifierPlan modifiers(String spellName, int slotLevel, String option, int spellcastingModifier,
                                  EncounterParticipant first) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty() || found.get().modifiers().isEmpty()) {
            return ModifierPlan.NONE;
        }
        SpellTemplate template = found.get();
        List<OngoingModifier> modifiers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (SpellTemplate.ModifierDefinition definition : template.modifiers()) {
            if (definition.option() != null && !definition.option().equalsIgnoreCase(option)) {
                continue;
            }
            int steps = definition.upcast() != null ? definition.upcast().steps(template.level(), slotLevel) : 0;
            int flat = definition.flatAmount();
            if (definition.upcast() != null && definition.upcast().flat() != null) {
                flat += steps * definition.upcast().flat();
            }
            if (definition.spellcastingModifier()) {
                flat += spellcastingModifier;
            }
            String dice = upcastDice(definition.dice(), definition.upcast(), steps);
            if (definition.kind() == OngoingModifier.Kind.AC_BASE && first != null) {
                EquippedArmour.Worn worn = stats.armour(first);
                if (worn.armour() != null) {
                    notes.add(spellName + " has no effect on " + first.getDisplayName() + ", who wears armour");
                    continue;
                }
                if (worn.shield() != null) {
                    flat += 2 + (worn.shield().getBonusAc() != null ? worn.shield().getBonusAc() : 0);
                }
            }
            if (definition.plusDex()) {
                flat += first != null ? Ability.modifier(stats.abilityScore(first, Ability.DEX)) : 0;
            }
            List<String> types = definition.damageTypes().stream()
                    .map(type -> "OPTION".equalsIgnoreCase(type) && option != null ? option : type)
                    .toList();
            modifiers.add(new OngoingModifier(UUID.randomUUID(), definition.kind(), definition.scope(), dice, flat,
                    definition.mode(), definition.ability(), types, definition.weaponOnly(), definition.meleeOnly(),
                    definition.oneShot(), definition.until(), false, definition.requires(),
                    definition.attackerTypes(), definition.condition(), definition.skill()));
        }
        return new ModifierPlan(modifiers, option, template.modifierRepeatSave(), template.reminder(),
                template.endReminder(), template.durationRounds(slotLevel), notes);
    }

    /** The spell's name as the spells data has it, found in any case; empty for no such spell. */
    public Optional<String> spellName(String requested) {
        return requested == null ? Optional.empty()
                : spellRepository.findByNameIgnoreCase(requested.trim()).map(Spell::getName);
    }

    /** The spell's level, 0 for a cantrip or a spell not in the data. */
    public int spellLevel(String spellName) {
        return spellRepository.findByNameIgnoreCase(spellName).map(Spell::getLevel).orElse(0);
    }

    /** Whether the spell's Spell Effect can be moved to a new creature, as Hunter's Mark's and Hex's can. */
    public boolean retargetable(String spellName) {
        return template(spellName).map(SpellTemplate::retargetable).orElse(false);
    }

    /** Whether the spell reaches only its caster: its template's SELF delivery or selfOnly. */
    public boolean selfOnly(String spellName) {
        return template(spellName)
                .map(template -> "SELF".equals(template.deliveryMethod()) || Boolean.TRUE.equals(template.selfOnly()))
                .orElse(false);
    }

    /** Whether the spell needs concentration: its template says so, or else the spells data. */
    public boolean concentration(String spellName) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isPresent() && found.get().concentration() != null) {
            return found.get().concentration();
        }
        return spellRepository.findByNameIgnoreCase(spellName).map(Spell::getConcentration)
                .map(Boolean.TRUE::equals).orElse(false);
    }

    /** How many rounds the spell lasts: its template's, or else read from the spells data's duration. */
    public Integer durationOf(String spellName) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isPresent() && found.get().durationRounds() != null) {
            return found.get().durationRounds();
        }
        return spellRepository.findByNameIgnoreCase(spellName).map(Spell::getDuration)
                .map(SpellEffects::durationRounds).orElse(null);
    }

    // The dice with each step's dice added, as Elemental Weapon's 1d4 becomes 2d4 at 5th level (XGE p.155); the same
    // sides are assumed, and a sign kept
    private static String upcastDice(String dice, SpellTemplate.ModifierUpcast upcast, int steps) {
        if (dice == null || dice.isBlank() || upcast == null || upcast.dice() == null || steps == 0) {
            return dice;
        }
        String sign = dice.trim().startsWith("-") ? "-" : "";
        Dice base = Dice.parse(dice.trim().replaceFirst("^[+-]", ""));
        Dice each = Dice.parse(upcast.dice().trim());
        return sign + new Dice(base.count() + steps * each.count(), base.sides(), base.modifier());
    }

    /**
     * What a spell's casting leaves behind (M15).
     *
     * @param durationRounds how many rounds it lasts; null when unknown
     * @param uses           what it has to spend, such as Melf's Minute Meteors' meteors; null when it has no count
     */
    public record Lasting(Integer durationRounds, Integer uses) {
    }

    /** What casting the spell with a slot of slotLevel leaves behind, if it leaves a Spell Effect. */
    public Optional<Lasting> lasting(String spellName, int slotLevel) {
        return template(spellName).filter(SpellTemplate::lasting).map(template -> new Lasting(
                template.durationRounds(slotLevel),
                template.effectUses() != null ? template.effectUses().at(template.level(), slotLevel) : null));
    }

    /**
     * How a Spell Effect is used (M15).
     *
     * @param directedCost     what its caster's use of it costs from their turn: ACTION, BONUS_ACTION or NONE; null when
     *                         its caster has nothing to do with it, as for Spirit Guardians
     * @param directedResolves false when that use only moves it, as Moonbeam's action does
     * @param maxUsesEachTurn  the most of its uses one directed use may spend, such as two meteors; null without uses
     * @param triggers         the moments its area acts on a creature by itself, which the DM applies
     */
    public record EffectUse(ActionCost directedCost, boolean directedResolves, Integer maxUsesEachTurn,
                            List<SpellTrigger> triggers) {

        public static final EffectUse NONE = new EffectUse(null, false, null, List.of());
    }

    /** How the spell's Spell Effect is used; nothing to do with it when the spell has no template. */
    public EffectUse effectUse(String spellName) {
        return template(spellName).map(SpellEffects::effectUse).orElse(EffectUse.NONE);
    }

    private static EffectUse effectUse(SpellTemplate template) {
        ActionCost cost = null;
        if (template.repeatEffect() != null) {
            cost = template.repeatActionType() != null ? template.repeatActionType() : ActionCost.ACTION;
        }
        return new EffectUse(cost, template.repeatEffect() != null && template.resolvesWhenDirected(),
                template.effectUses() != null ? template.effectUses().maxEachUse() : null,
                template.effectTriggers().stream().flatMap(trigger -> trigger.when().stream()).distinct().toList());
    }

    /**
     * The most targets the spell may have with a slot of slotLevel: its template's target count, plus any per
     * level above its own. Empty when the template sets no limit.
     */
    public OptionalInt maxTargets(String spellName, int slotLevel) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty() || found.get().targetCount() == null) {
            return OptionalInt.empty();
        }
        SpellTemplate template = found.get();
        int spellLevel = template.level();
        int max = template.targetCount();
        SpellTemplate.TargetScaling scaling = template.targetCountUpcastScaling();
        if (slotLevel > spellLevel && scaling != null && scaling.additionalTargetsPerLevel() != null) {
            max += scaling.additionalTargetsPerLevel() * (slotLevel - spellLevel);
        }
        return OptionalInt.of(max);
    }

    /**
     * What using the spell's effect again costs from its caster's turn (D174): the template's repeatActionType, an
     * action, a bonus action or nothing; an action when the template doesn't say.
     */
    public ActionCost repeatCost(String spellName) {
        return template(spellName).map(SpellTemplate::repeatActionType).orElse(ActionCost.ACTION);
    }

    /**
     * What Metamagic needs to know of the spell (M18), from its effect template and its range in the spells table;
     * empty for a spell without a template, which Metamagic can't change.
     */
    public Optional<Metamagic.SpellFacts> metamagicFacts(String spellName) {
        Optional<Spell> spell = spellRepository.findByNameIgnoreCase(spellName);
        if (spell.isEmpty() || spell.get().getEffectTemplate() == null) {
            return Optional.empty();
        }
        return spell.get().getEffectTemplate().readable(spell.get().getName() + "'s effect template")
                .map(template -> facts(template, spell.get().getCastingTime(), spell.get().getRangeDistance()));
    }

    /**
     * A template's facts for Metamagic, with the spell's range and its casting time as the spells table words them;
     * the template's own casting time comes first.
     */
    static Metamagic.SpellFacts facts(SpellTemplate template, String castingTime, String range) {
        String time = template.json() != null && template.json().path("castingTime").isString()
                ? template.json().path("castingTime").asString() : castingTime;
        boolean forcesSave = template.saveAbility() != null && !template.saveAbility().isBlank();
        // Empowered Spell rerolls the damage rolled when the spell is cast
        boolean dealsDamage = template.firstDamageEffect().isPresent();
        SpellTemplate.TargetScaling scaling = template.targetCountUpcastScaling();
        return new Metamagic.SpellFacts(template.level(), castingTime(time), forcesSave,
                dealsDamage, template.durationRounds(), template.durationText(),
                "SINGLE_TARGET".equals(template.targetType()), Boolean.TRUE.equals(template.selfOnly()),
                template.targetCount(), scaling != null ? scaling.additionalTargetsPerLevel() : null,
                range, template.verbal(), template.somatic());
    }

    // The casting time the text names, or null when there is none or it can't be read
    private static CastingTime castingTime(String text) {
        try {
            return text != null ? CastingTime.of(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static RollMode rollMode(Boolean advantage) {
        if (advantage == null) return RollMode.NORMAL;
        return advantage ? RollMode.ADVANTAGE : RollMode.DISADVANTAGE;
    }

    // ── Template reading ─────────────────────────────────────────

    // Healing dice, or null dice for none
    private record Healing(Dice dice) {
    }

    // The spell's template; empty without one, or when it isn't an object, and a 409 when it doesn't fit the records
    private Optional<SpellTemplate> template(String spellName) {
        Optional<Spell> spell = spellRepository.findByNameIgnoreCase(spellName);
        if (spell.isEmpty() || spell.get().getEffectTemplate() == null) {
            return Optional.empty();
        }
        return spell.get().getEffectTemplate().readable(spell.get().getName() + "'s effect template");
    }

    private static String orNone(String deliveryMethod) {
        return deliveryMethod != null ? deliveryMethod : "NONE";
    }

    private static SpellPlan manual(String spellName, int spellLevel, String reason, boolean concentration,
                                    Integer durationRounds, boolean verbal) {
        return new SpellPlan(spellName, spellLevel, null, reason, concentration, durationRounds, verbal);
    }

    // Null for a delivery the app can't resolve
    private static Effect.Delivery delivery(String deliveryMethod, String spellAttackType, String saveAbility,
                                            Boolean halfOnSave, int attackBonus, int saveDc, Boolean advantage) {
        return switch (deliveryMethod) {
            // There is no map, so a melee spell attack counts as within 5 feet and a ranged one as not (D161)
            case "SPELL_ATTACK" -> new Effect.Attack(attackBonus, rollMode(advantage),
                    "MELEE".equalsIgnoreCase(spellAttackType != null ? spellAttackType : ""), false);
            case "SAVING_THROW" -> new Effect.Save(Ability.parse(saveAbility).orElse(null), saveDc,
                    Boolean.TRUE.equals(halfOnSave));
            case "AUTO_HIT" -> new Effect.Automatic();
            case "SELF" -> new Effect.Self();
            default -> null;
        };
    }

    // The damage effects' parts at this slot level, each part's own upcast added to it (D185): none without a damage
    // effect, empty when any is unreadable
    private Optional<List<Effect.DamagePart>> damage(List<SpellTemplate.Part> damageEffects, int spellLevel,
                                                     int slotLevel, EncounterParticipant caster, int modifier) {
        List<Effect.DamagePart> all = new ArrayList<>();
        for (SpellTemplate.Part part : damageEffects) {
            Optional<List<Effect.DamagePart>> parts;
            if (spellLevel == 0) {
                String scaled = cantripDice(part.cantripScaling(), stats.level(caster));
                parts = damageParts(scaled != null ? scaled : part.damageDice(), part.damageType(), null, 0, modifier);
            } else {
                parts = damageParts(part.damageDice(), part.damageType(), part.upcastScaling(),
                        slotLevel - spellLevel, modifier);
            }
            if (parts.isEmpty()) {
                return Optional.empty();
            }
            all.addAll(parts.get());
        }
        return Optional.of(all);
    }

    private static Optional<List<Effect.DamagePart>> damageParts(String dice, String damageType,
                                                                 SpellTemplate.Upcast upcastScaling, int levelsAbove,
                                                                 int modifier) {
        if (dice == null) {
            return Optional.of(List.of());
        }
        String type = damageType != null && !damageType.isBlank() ? damageType.trim().toLowerCase() : null;
        return TemplateDice.read(dice, modifier)
                .flatMap(groups -> upcast(groups, upcastScaling, levelsAbove))
                .map(groups -> groups.stream().map(each -> new Effect.DamagePart(each, type)).toList());
    }

    // A cantrip's dice at its caster's level: the entry for the highest of 17, 11 and 5 the level reaches
    private static String cantripDice(Map<String, String> cantripScaling, int casterLevel) {
        for (int threshold : new int[]{17, 11, 5}) {
            String dice = cantripScaling.get(String.valueOf(threshold));
            if (casterLevel >= threshold && dice != null) {
                return dice;
            }
        }
        return null;
    }

    // Upcast dice and flat amounts for each scalingInterval levels above the spell's own; empty when unreadable
    private static Optional<List<Dice>> upcast(List<Dice> groups, SpellTemplate.Upcast upcastScaling,
                                               int levelsAbove) {
        if (upcastScaling == null || levelsAbove <= 0) {
            return Optional.of(groups);
        }
        int levels = levelsAbove / upcastScaling.interval();
        Dice perLevelDice = null;
        if (upcastScaling.additionalDicePerLevel() != null) {
            Optional<List<Dice>> read = TemplateDice.read(upcastScaling.additionalDicePerLevel());
            if (read.isEmpty() || read.get().size() != 1) {
                return Optional.empty();
            }
            perLevelDice = read.get().getFirst();
        }
        return Optional.of(TemplateDice.upcast(groups, perLevelDice, upcastScaling.flatPerLevel(), levels));
    }

    // The healing at this slot level: null dice for none, empty when unreadable
    private static Optional<Healing> healing(String dice, SpellTemplate.Upcast upcastScaling, int levelsAbove,
                                             int modifier) {
        if (dice == null) {
            return Optional.of(new Healing(null));
        }
        return TemplateDice.read(dice, modifier)
                .filter(groups -> groups.size() == 1)
                .flatMap(groups -> upcast(groups, upcastScaling, levelsAbove))
                .filter(groups -> groups.size() == 1)
                .map(groups -> new Healing(groups.getFirst()));
    }

    // Each condition the spell inflicts, listed by name or as an object naming it
    private static List<String> conditions(SpellTemplate template) {
        return names(template.conditionsInflicted());
    }

    private static List<String> names(List<SpellTemplate.ConditionInflicted> conditions) {
        return conditions.stream()
                .map(SpellTemplate.ConditionInflicted::name)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * How each condition ends where its entry says (D162): its own duration in rounds, and a save repeated at
     * the end of each of its creature's turns against the caster's spell save DC, in the entry's ability or
     * else the spell's. A repeat save the entry makes conditional, such as Fear's on losing sight of the
     * caster, is left to the DM. One the entry ends when its creature attacks or casts a spell, as Invisibility's
     * (PHB p.254), says so. One that ends when its creature takes damage, as Hypnotic Pattern's (PHB p.252), or makes
     * it save again then, as Dominate Beast's (PHB p.234), says so too (D185).
     */
    private static Map<String, Effect.ConditionEnd> conditionEnds(List<SpellTemplate.ConditionInflicted> conditions,
                                                                  SpellTemplate template, int saveDc) {
        Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
        for (SpellTemplate.ConditionInflicted condition : conditions) {
            String name = condition.name();
            if (name == null) {
                continue;
            }
            Effect.RepeatSave repeatSave = null;
            if (condition.saveToEndEachTurn() && !condition.conditionalSave()) {
                repeatSave = Ability.parse(condition.saveToEndAbility())
                        .or(() -> Ability.parse(template.saveAbility()))
                        .map(ability -> new Effect.RepeatSave(ability, saveDc))
                        .orElse(null);
            }
            Effect.DamageSave onDamage = condition.saveToEndOnDamage()
                    ? Ability.parse(condition.saveToEndAbility()).or(() -> Ability.parse(template.saveAbility()))
                            .map(ability -> new Effect.DamageSave(ability, saveDc, condition.damageSaveAdvantage()))
                            .orElse(null)
                    : null;
            if (condition.durationRounds() != null || repeatSave != null || condition.endsOnAttackOrCast()
                    || condition.endsOnDamage() || onDamage != null) {
                Effect.ConditionEnd end = new Effect.ConditionEnd(condition.durationRounds(), repeatSave, null, false,
                        condition.endsOnDamage(), condition.endsOnAttackOrCast(), onDamage);
                ends.put(name, end);
            }
        }
        return ends;
    }
}
