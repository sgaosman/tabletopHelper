package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Reads a monster action template into Effects (D030, D160). Every DAMAGE part of an action counts, each with
 * its own damage type, such as a bite's piercing and fire. A multiattack becomes one step per component
 * against the same targets, and Eye Rays one saving throw per ray, each at the next target in turn. An action
 * the template leaves to the DM comes back with the reason and no steps.
 */
@Component
@RequiredArgsConstructor
public class MonsterEffects {

    private static final List<String> ACTION_ARRAYS =
            List.of("actions", "legendaryActions", "lairActions", "bonusActions", "reactions", "traits");

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
     * @param notes        what the combat log should add, such as a multiattack component that isn't defined
     */
    public record MonsterAction(String name, List<Step> steps, String manualReason, List<String> notes) {

        public boolean automated() {
            return manualReason == null;
        }
    }

    /**
     * The action against the named targets.
     *
     * @param fullTemplates the monster's whole template, where a multiattack finds its components
     */
    public MonsterAction plan(JsonNode action, JsonNode fullTemplates, List<UUID> targetIds,
                              Integer overrideAttackBonus, Integer overrideSaveDc, Boolean advantage) {
        String name = action.path("name").asText("Unknown Action");
        if (action.path("isEyeRays").asBoolean(false)) {
            return eyeRays(name, action, targetIds, overrideSaveDc);
        }
        if (action.path("isMultiattack").asBoolean(false)) {
            return multiattack(name, action, fullTemplates, targetIds, overrideAttackBonus, overrideSaveDc, advantage);
        }
        if (!action.path("automatable").asBoolean(true)) {
            return manual(name, action.path("description").asText("Non-automatable action requires DM adjudication."));
        }

        JsonNode effects = action.path("effects");
        String deliveryMethod = action.path("deliveryMethod").asText(null);
        if (deliveryMethod == null && (!effects.isArray() || effects.isEmpty())) {
            return manual(name, action.path("description").asText(name + " requires DM adjudication."));
        }
        if (deliveryMethod == null) {
            deliveryMethod = "AUTO_HIT";
        }

        int saveDc = overrideSaveDc != null ? overrideSaveDc : action.path("saveDC").asInt(0);
        Parts parts = parts(effects, overrideSaveDc, saveDc, action.path("saveAbility").asText(null));
        if (parts == null) {
            return manual(name, name + "'s damage dice can't be rolled automatically");
        }
        if (parts.other() && parts.damage().isEmpty() && parts.conditions().isEmpty() && !parts.healing()) {
            String effectType = effects.path(0).path("effectType").asText("non-automatable");
            return manual(name, "Action contains " + effectType + " effect type requiring DM adjudication");
        }
        List<String> notes = parts.healing() ? List.of(name + "'s healing is left to the DM.") : List.of();

        int attackBonus = overrideAttackBonus != null ? overrideAttackBonus : action.path("attackBonus").asInt(0);
        Effect.Delivery delivery;
        List<String> conditions = parts.conditions();
        switch (deliveryMethod.toUpperCase()) {
            case "ATTACK_ROLL" -> delivery = new Effect.Attack(attackBonus, SpellEffects.rollMode(advantage),
                    withinFiveFeet(action.path("attackType").asText("")), false);
            case "SAVING_THROW" -> delivery = new Effect.Save(
                    Ability.parse(action.path("saveAbility").asText(null)).orElse(null), saveDc,
                    action.path("halfOnSave").asBoolean(false));
            case "AUTO_HIT" -> delivery = new Effect.Automatic();
            case "AUTO_DAMAGE" -> {
                delivery = new Effect.Automatic();
                conditions = List.of();
            }
            default -> {
                return manual(name, "Delivery method '" + deliveryMethod + "' requires DM adjudication");
            }
        }
        Effect effect = new Effect(name, delivery, parts.damage(), null, conditions, false, null,
                parts.conditionEnds());
        return new MonsterAction(name, List.of(new Step(name, effect, List.copyOf(targetIds))), null, notes);
    }

    private MonsterAction multiattack(String name, JsonNode action, JsonNode fullTemplates, List<UUID> targetIds,
                                      Integer overrideAttackBonus, Integer overrideSaveDc, Boolean advantage) {
        JsonNode components = action.path("multiattackComponents");
        if (!components.isArray() || components.isEmpty()) {
            return manual(name, "Multiattack has no components defined");
        }
        List<Step> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (JsonNode componentName : components) {
            String component = componentName.asText();
            Optional<JsonNode> template = findComponent(fullTemplates, component);
            if (template.isEmpty()) {
                notes.add("Component '" + component + "' not found.");
                continue;
            }
            MonsterAction resolved = plan(template.get(), fullTemplates, targetIds, overrideAttackBonus,
                    overrideSaveDc, advantage);
            if (!resolved.automated()) {
                notes.add(component + ": " + resolved.manualReason() + ".");
            }
            steps.addAll(resolved.steps());
            notes.addAll(resolved.notes());
        }
        return new MonsterAction(name, steps, null, notes);
    }

    // rayCount rays chosen at random, each a saving throw against the next target in turn
    private MonsterAction eyeRays(String name, JsonNode action, List<UUID> targetIds, Integer overrideSaveDc) {
        JsonNode rays = action.path("rays");
        if (!rays.isArray() || rays.isEmpty()) {
            return manual(name, "Eye Rays has no rays defined");
        }
        if (targetIds.isEmpty()) {
            return manual(name, "No valid targets for eye rays");
        }
        List<JsonNode> chosen = new ArrayList<>();
        rays.forEach(chosen::add);
        int rayCount = action.path("rayCount").asInt(3);
        if (rayCount < chosen.size()) {
            // Fisher-Yates with the dice, so tests can choose the rays
            for (int i = chosen.size() - 1; i > 0; i--) {
                int j = rng.roll(i + 1) - 1;
                JsonNode swap = chosen.get(i);
                chosen.set(i, chosen.get(j));
                chosen.set(j, swap);
            }
            chosen = chosen.subList(0, rayCount);
        }

        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            JsonNode ray = chosen.get(i);
            String rayName = ray.path("name").asText("Ray " + (i + 1));
            int saveDc = overrideSaveDc != null ? overrideSaveDc : ray.path("saveDC").asInt(0);
            Parts parts = parts(ray.path("effects"), overrideSaveDc, saveDc, ray.path("saveAbility").asText("DEX"));
            if (parts == null) {
                return manual(name, rayName + "'s damage dice can't be rolled automatically");
            }
            Effect.Save save = new Effect.Save(Ability.parse(ray.path("saveAbility").asText("DEX")).orElse(null),
                    saveDc, ray.path("halfOnSave").asBoolean(false));
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
    private static Parts parts(JsonNode effects, Integer overrideSaveDc, int actionSaveDc, String actionSaveAbility) {
        List<Effect.DamagePart> damage = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        Map<String, Effect.ConditionEnd> conditionEnds = new LinkedHashMap<>();
        boolean healing = false;
        boolean other = false;
        for (JsonNode effect : effects) {
            switch (effect.path("effectType").asText("OTHER").toUpperCase()) {
                case "DAMAGE" -> {
                    JsonNode diceNode = effect.path("damageDice").isTextual() ? effect.path("damageDice")
                            : effect.path("flatDamage");
                    if (diceNode.isMissingNode() || diceNode.isNull()) {
                        continue;
                    }
                    Optional<List<Dice>> dice = TemplateDice.read(diceNode.asText());
                    if (dice.isEmpty()) {
                        return null;
                    }
                    JsonNode typeNode = effect.path("damageType");
                    String type = typeNode.isTextual() && !typeNode.asText().isBlank()
                            ? typeNode.asText().trim().toLowerCase() : null;
                    dice.get().forEach(d -> damage.add(new Effect.DamagePart(d, type)));
                }
                case "CONDITION" -> {
                    JsonNode condition = effect.path("condition");
                    if (condition.isTextual() && !condition.asText().isBlank()) {
                        String name = condition.asText().trim().toLowerCase();
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
    private static Effect.ConditionEnd conditionEnd(JsonNode effect, Integer overrideSaveDc, int actionSaveDc,
                                                    String actionSaveAbility) {
        Integer duration = effect.path("durationRounds").isInt() && effect.path("durationRounds").asInt() > 0
                ? effect.path("durationRounds").asInt() : null;
        Effect.RepeatSave repeatSave = null;
        if (effect.path("saveToEndEachTurn").asBoolean(false)) {
            int dc = overrideSaveDc != null ? overrideSaveDc : effect.path("saveToEndDC").asInt(actionSaveDc);
            repeatSave = Ability.parse(effect.path("saveToEndAbility").asText(null))
                    .or(() -> Ability.parse(actionSaveAbility))
                    .filter(ability -> dc > 0)
                    .map(ability -> new Effect.RepeatSave(ability, dc))
                    .orElse(null);
        }
        return duration != null || repeatSave != null ? new Effect.ConditionEnd(duration, repeatSave) : null;
    }

    // There is no map, so a melee attack counts as within 5 feet and a ranged one as not (D161)
    private static boolean withinFiveFeet(String attackType) {
        String type = attackType.toUpperCase();
        return !(type.startsWith("RANGED"));
    }

    private static Optional<JsonNode> findComponent(JsonNode fullTemplates, String component) {
        List<JsonNode> arrays = new ArrayList<>();
        if (fullTemplates.isArray()) {
            arrays.add(fullTemplates);
        } else {
            ACTION_ARRAYS.forEach(key -> arrays.add(fullTemplates.path(key)));
        }
        for (JsonNode array : arrays) {
            for (JsonNode action : array) {
                if (action.path("name").asText("").equalsIgnoreCase(component)) {
                    return Optional.of(action);
                }
            }
        }
        return Optional.empty();
    }

    private static MonsterAction manual(String name, String reason) {
        return new MonsterAction(name, List.of(), reason, List.of());
    }
}
