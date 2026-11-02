package com.tabletophelper.encounter.internal;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.Effect;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads monster action templates into Effects (D030, D160). Every DAMAGE part of an action counts, each with its own
 * damage type, such as a bite's piercing and fire. A multiattack becomes one step per component (D165), each against
 * the targets chosen for it or, as before, every component against the same targets; Eye Rays become one saving
 * throw per ray, each at the next target in turn. An action the template leaves to the DM comes back with the reason
 * and no steps.
 */
@Component
@RequiredArgsConstructor
public class MonsterEffects {

    // Chooses which eye rays fire
    private final Rng rng;

    /** One Effect of an action and the targets it reaches: a multiattack component, or an eye ray. */
    public record Step(String label, Effect effect, List<UUID> targetIds) {
    }

    /**
     * An action as used.
     *
     * @param steps        what it does, in order; none when the DM resolves it by hand
     * @param manualReason why the DM resolves it, or null
     * @param notes        what the combat log should add, such as a multiattack component the DM resolves
     */
    public record MonsterAction(String name, List<Step> steps, String manualReason, List<String> notes) {

        public boolean automated() {
            return manualReason == null;
        }
    }

    /** The DM's numbers in place of an action's own (D104, D131), and the advantage they give it. */
    public record Overrides(Integer attackBonus, Integer saveDc, Boolean advantage) {

        public static final Overrides NONE = new Overrides(null, null, null);
    }

    /**
     * A multiattack's components as its data names them (D165), or why they can't be read.
     *
     * @param components the actions it makes, in order; none when it can't be read
     * @param unreadable why the DM resolves it by hand, or null
     */
    public record Multiattack(String name, String description, List<MultiattackComponent> components,
                              String unreadable) {

        public boolean readable() {
            return unreadable == null;
        }
    }

    /**
     * One action a multiattack makes: the action of that name in the monster's lists.
     *
     * @param effect       what it does, or null when the DM resolves it
     * @param manualReason why the DM resolves it, or null
     * @param notes        what the combat log should add when it resolves, such as healing left to the DM
     * @param maxTargets   how many creatures it can take: an attack's target count, one unless the data says more;
     *                     null for any number
     */
    public record MultiattackComponent(String name, Effect effect, String manualReason, List<String> notes,
                                       Integer maxTargets) {

        public boolean automated() {
            return manualReason == null;
        }
    }

    // An action's Effect, or why the DM resolves it, with what the log should add
    private record Read(Effect effect, String manualReason, List<String> notes) {

        static Read manual(String reason) {
            return new Read(null, reason, List.of());
        }
    }

    /**
     * The action against the named targets. A multiattack makes each of its components against all of them, which
     * suits one target: MonsterActionService refuses more (D165).
     *
     * @param templates the monster's whole templates, where a multiattack finds its components
     */
    public MonsterAction plan(MonsterTemplates.Action action, MonsterTemplates templates, List<UUID> targetIds,
                              Overrides overrides) {
        String name = nameOf(action);
        if (action.eyeRays()) {
            return eyeRays(name, action, targetIds, overrides.saveDc());
        }
        if (action.multiattack()) {
            Multiattack multiattack = multiattack(action, templates, overrides);
            return multiattack(multiattack, multiattack.components().stream().map(component -> targetIds).toList());
        }
        Read read = read(name, action, overrides);
        if (read.effect() == null) {
            return manual(name, read.manualReason());
        }
        return new MonsterAction(name, List.of(new Step(name, read.effect(), List.copyOf(targetIds))), null,
                read.notes());
    }

    /**
     * A multiattack whose components each take their own targets: one list per component, in order, an empty one
     * skipping it. A component the DM resolves takes none, and the log notes it.
     */
    public MonsterAction multiattack(Multiattack multiattack, List<List<UUID>> componentTargets) {
        if (!multiattack.readable()) {
            return manual(multiattack.name(), multiattack.unreadable());
        }
        List<Step> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < multiattack.components().size(); i++) {
            MultiattackComponent component = multiattack.components().get(i);
            if (!component.automated()) {
                notes.add(component.name() + ": " + sentence(component.manualReason()));
                continue;
            }
            List<UUID> targets = i < componentTargets.size() && componentTargets.get(i) != null
                    ? componentTargets.get(i) : List.of();
            if (!targets.isEmpty()) {
                steps.add(new Step(component.name(), component.effect(), List.copyOf(targets)));
                notes.addAll(component.notes());
            }
        }
        return new MonsterAction(multiattack.name(), steps, null, notes.stream().distinct().toList());
    }

    /**
     * A multiattack's components, read from its data (D165): each the action of that name in the monster's lists, in
     * order. It can't be read without components, when one names no action of the monster's, as the fragments the
     * data couldn't resolve do, or when the data leaves an and/or pattern to review; the DM then resolves it by hand.
     * The data's own flag for unresolved components isn't needed, and is stale for Moloch, whose three resolve. A
     * component that is itself a multiattack or Eye Rays is the DM's too.
     */
    public Multiattack multiattack(MonsterTemplates.Action action, MonsterTemplates templates, Overrides overrides) {
        String name = nameOf(action);
        String description = action.multiattackDescription() != null ? action.multiattackDescription()
                : action.description();
        if (action.multiattackComponents().isEmpty()) {
            return new Multiattack(name, description, List.of(), name + " has no components defined");
        }
        if (action.multiattackNote() != null) {
            return new Multiattack(name, description, List.of(),
                    name + "'s attacks can't be read from its stat block");
        }
        List<MultiattackComponent> components = new ArrayList<>();
        for (String componentName : action.multiattackComponents()) {
            Optional<MonsterTemplates.Action> template = templates.component(componentName);
            if (template.isEmpty()) {
                return new Multiattack(name, description, List.of(),
                        name + " names " + componentName + ", which isn't one of its actions");
            }
            components.add(component(template.get(), templates, overrides));
        }
        return new Multiattack(name, description, components, null);
    }

    private MultiattackComponent component(MonsterTemplates.Action template, MonsterTemplates templates,
                                           Overrides overrides) {
        String name = nameOf(template);
        if (template.multiattack() || template.eyeRays()) {
            return new MultiattackComponent(name, null, "it can't be made as part of another action", List.of(), null);
        }
        Read read = read(name, template, overrides);
        Integer maxTargets = read.effect() != null && read.effect().delivery() instanceof Effect.Attack
                ? Math.max(1, template.targetCount() != null ? template.targetCount() : 1) : null;
        return new MultiattackComponent(name, read.effect(), read.manualReason(), read.notes(), maxTargets);
    }

    // What a single action does, or why the DM resolves it
    private Read read(String name, MonsterTemplates.Action action, Overrides overrides) {
        if (Boolean.FALSE.equals(action.automatable())) {
            return Read.manual(action.description() != null ? action.description()
                    : "Non-automatable action requires DM adjudication.");
        }
        List<MonsterTemplates.Part> effects = action.effects();
        String deliveryMethod = action.deliveryMethod();
        if (deliveryMethod == null && effects.isEmpty()) {
            return Read.manual(action.description() != null ? action.description()
                    : name + " requires DM adjudication.");
        }
        if (deliveryMethod == null) {
            deliveryMethod = "AUTO_HIT";
        }

        int saveDc = overrides.saveDc() != null ? overrides.saveDc() : orZero(action.saveDc());
        Parts parts = parts(effects, overrides.saveDc(), saveDc, action.saveAbility());
        if (parts == null) {
            return Read.manual(name + "'s damage dice can't be rolled automatically");
        }
        if (parts.other() && parts.damage().isEmpty() && parts.conditions().isEmpty() && !parts.healing()) {
            String effectType = effects.getFirst().effectType() != null ? effects.getFirst().effectType()
                    : "non-automatable";
            return Read.manual("Action contains " + effectType + " effect type requiring DM adjudication");
        }
        List<String> notes = parts.healing() ? List.of(name + "'s healing is left to the DM.") : List.of();

        int attackBonus = overrides.attackBonus() != null ? overrides.attackBonus() : orZero(action.attackBonus());
        Effect.Delivery delivery;
        List<String> conditions = parts.conditions();
        switch (deliveryMethod.toUpperCase()) {
            case "ATTACK_ROLL" -> delivery = new Effect.Attack(attackBonus, SpellEffects.rollMode(overrides.advantage()),
                    withinFiveFeet(action.attackType()), false);
            case "SAVING_THROW" -> delivery = new Effect.Save(Ability.parse(action.saveAbility()).orElse(null), saveDc,
                    action.halfOnSave());
            case "AUTO_HIT" -> delivery = new Effect.Automatic();
            case "AUTO_DAMAGE" -> {
                delivery = new Effect.Automatic();
                conditions = List.of();
            }
            default -> {
                return Read.manual("Delivery method '" + deliveryMethod + "' requires DM adjudication");
            }
        }
        return new Read(new Effect(name, delivery, parts.damage(), null, conditions, false, null,
                parts.conditionEnds()), null, notes);
    }

    // rayCount rays chosen at random, each a saving throw against the next target in turn
    private MonsterAction eyeRays(String name, MonsterTemplates.Action action, List<UUID> targetIds,
                                  Integer overrideSaveDc) {
        if (action.rays().isEmpty()) {
            return manual(name, "Eye Rays has no rays defined");
        }
        if (targetIds.isEmpty()) {
            return manual(name, "No valid targets for eye rays");
        }
        List<MonsterTemplates.Ray> chosen = new ArrayList<>(action.rays());
        int rayCount = action.rayCount() != null ? action.rayCount() : 3;
        if (rayCount < chosen.size()) {
            // Fisher-Yates with the dice, so tests can choose the rays
            for (int i = chosen.size() - 1; i > 0; i--) {
                int j = rng.roll(i + 1) - 1;
                MonsterTemplates.Ray swap = chosen.get(i);
                chosen.set(i, chosen.get(j));
                chosen.set(j, swap);
            }
            chosen = chosen.subList(0, rayCount);
        }

        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            MonsterTemplates.Ray ray = chosen.get(i);
            String rayName = ray.name() != null ? ray.name() : "Ray " + (i + 1);
            String saveAbility = ray.saveAbility() != null ? ray.saveAbility() : "DEX";
            int saveDc = overrideSaveDc != null ? overrideSaveDc : orZero(ray.saveDc());
            Parts parts = parts(ray.effects(), overrideSaveDc, saveDc, saveAbility);
            if (parts == null) {
                return manual(name, rayName + "'s damage dice can't be rolled automatically");
            }
            Effect.Save save = new Effect.Save(Ability.parse(saveAbility).orElse(null), saveDc, ray.halfOnSave());
            Effect effect = new Effect(rayName, save, parts.damage(), null, parts.conditions(), false, null,
                    parts.conditionEnds());
            steps.add(new Step(rayName, effect, List.of(targetIds.get(i % targetIds.size()))));
        }
        return new MonsterAction(name, steps, null, List.of());
    }

    // Damage parts, condition names and how each condition ends, and whether there is healing or another
    // effect type, both of which are left to the DM; null when a part's dice can't be read
    private record Parts(List<Effect.DamagePart> damage, List<String> conditions,
                         Map<String, Effect.ConditionEnd> conditionEnds, boolean healing, boolean other) {
    }

    /**
     * The effects' parts. A condition lasts the rounds its effect gives, and one its creature saves to end at
     * the end of each of its turns gets that Repeat Save (D162): against the DM's override of the DC, else
     * the effect's own DC, else the action's, in the effect's ability or else the action's.
     */
    private static Parts parts(List<MonsterTemplates.Part> effects, Integer overrideSaveDc, int actionSaveDc,
                               String actionSaveAbility) {
        List<Effect.DamagePart> damage = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        Map<String, Effect.ConditionEnd> conditionEnds = new LinkedHashMap<>();
        boolean healing = false;
        boolean other = false;
        for (MonsterTemplates.Part effect : effects) {
            switch (effect.effectType() != null ? effect.effectType().toUpperCase() : "OTHER") {
                case "DAMAGE" -> {
                    if (effect.damageDice() == null) {
                        continue;
                    }
                    Optional<List<Dice>> dice = TemplateDice.read(effect.damageDice());
                    if (dice.isEmpty()) {
                        return null;
                    }
                    String type = effect.damageType() != null && !effect.damageType().isBlank()
                            ? effect.damageType().trim().toLowerCase() : null;
                    dice.get().forEach(d -> damage.add(new Effect.DamagePart(d, type)));
                }
                case "CONDITION" -> {
                    if (effect.condition() != null && !effect.condition().isBlank()) {
                        String name = effect.condition().trim().toLowerCase();
                        conditions.add(name);
                        Effect.ConditionEnd end = conditionEnd(effect, overrideSaveDc, actionSaveDc, actionSaveAbility);
                        if (end != null) {
                            conditionEnds.put(name, end);
                        }
                    }
                }
                case "HEAL" -> healing = true;
                default -> other = true;
            }
        }
        return new Parts(damage, conditions, conditionEnds, healing, other);
    }

    // Null when the effect gives the condition neither a duration nor a Repeat Save
    private static Effect.ConditionEnd conditionEnd(MonsterTemplates.Part effect, Integer overrideSaveDc,
                                                    int actionSaveDc, String actionSaveAbility) {
        Integer duration = effect.durationRounds() != null && effect.durationRounds() > 0
                ? effect.durationRounds() : null;
        Effect.RepeatSave repeatSave = null;
        if (effect.saveToEndEachTurn()) {
            int dc = overrideSaveDc != null ? overrideSaveDc
                    : effect.saveToEndDc() != null ? effect.saveToEndDc() : actionSaveDc;
            repeatSave = Ability.parse(effect.saveToEndAbility())
                    .or(() -> Ability.parse(actionSaveAbility))
                    .filter(ability -> dc > 0)
                    .map(ability -> new Effect.RepeatSave(ability, dc))
                    .orElse(null);
        }
        return duration != null || repeatSave != null ? new Effect.ConditionEnd(duration, repeatSave) : null;
    }

    // There is no map, so a melee attack counts as within 5 feet and a ranged one as not (D161)
    private static boolean withinFiveFeet(String attackType) {
        return attackType == null || !attackType.toUpperCase().startsWith("RANGED");
    }

    static String nameOf(MonsterTemplates.Action action) {
        return action.name() != null ? action.name() : "Unknown Action";
    }

    // The reason as a sentence, ending with a full stop
    private static String sentence(String reason) {
        String trimmed = reason.trim();
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") ? trimmed : trimmed + ".";
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }

    private static MonsterAction manual(String name, String reason) {
        return new MonsterAction(name, List.of(), reason, List.of());
    }
}
