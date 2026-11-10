package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.SpiritScaling;
import com.tabletophelper.rules.combat.SummonSpirits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Tasha's spirit's statistics at the slot its spell was cast with (TCE p.109–114, D182): its AC, hit points and
 * action templates, from {@code data/content/summon-spirits.json}. The templates take the caster's spell attack bonus
 * and save DC as the cast found them, and their damage the slot level; the Multiattack makes half the slot level of
 * attacks, rounded down ({@link SpiritScaling}). They are stored on the spirit's participant, so its actions resolve as
 * a monster's do.
 */
@Component
@RequiredArgsConstructor
public class SpiritTemplates {

    private static final String SPELL_ATTACK = "SPELL_ATTACK";
    private static final String SPELL_DC = "SPELL_DC";
    private static final String DAMAGE_DICE = "damageDice";

    private final SummonSpirits spirits;
    private final ObjectMapper objectMapper;

    /** The spirit the spell summons, if it is one of Tasha's. */
    public Optional<SummonSpirits.Spirit> spirit(String spell) {
        return spirits.forSpell(spell);
    }

    /** The spirit's AC at this slot level. */
    public static int armourClass(SummonSpirits.Spirit spirit, int slotLevel) {
        return SpiritScaling.armourClass(spirit.acBase(), slotLevel);
    }

    /** The variant's hit points at this slot level. */
    public static int hitPoints(SummonSpirits.Spirit spirit, SummonSpirits.Variant variant, int slotLevel) {
        return SpiritScaling.hitPoints(variant.hpBase(), variant.hpPerLevel(), spirit.baseLevel(), slotLevel);
    }

    /** The action the variant's Multiattack repeats. */
    public static String attack(SummonSpirits.Spirit spirit, SummonSpirits.Variant variant) {
        return variant.attack() != null ? variant.attack() : spirit.attack();
    }

    /**
     * The variant's action templates at this slot level, with the caster's spell attack bonus and save DC: the
     * Multiattack first, then the spirit's actions and the variant's, its bonus actions, its reactions and its traits.
     */
    public MonsterTemplates templates(SummonSpirits.Spirit spirit, SummonSpirits.Variant variant, int slotLevel,
                                      int attackBonus, int saveDc) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");
        String attack = attack(spirit, variant);
        int attacks = SpiritScaling.attacks(slotLevel);
        if (attack != null && attacks > 0) {
            ObjectNode multiattack = actions.addObject();
            multiattack.put("name", "Multiattack");
            multiattack.put("actionType", "ACTION");
            multiattack.put("isMultiattack", true);
            ArrayNode components = multiattack.putArray("multiattackComponents");
            Collections.nCopies(attacks, attack).forEach(components::add);
            multiattack.put("multiattackDescription", "The spirit makes " + attacks + " attack"
                    + (attacks != 1 ? "s" : "") + " with " + attack + ", half this spell's level rounded down.");
        }
        add(actions, spirit.actions(), slotLevel, attackBonus, saveDc);
        add(actions, variant.actions(), slotLevel, attackBonus, saveDc);
        List<Map<String, Object>> bonusActions = new ArrayList<>(spirit.bonusActions());
        bonusActions.addAll(variant.bonusActions());
        add(root.putArray("bonusActions"), bonusActions, slotLevel, attackBonus, saveDc);
        add(root.putArray("reactions"), variant.reactions(), slotLevel, attackBonus, saveDc);
        add(root.putArray("traits"), variant.traits(), slotLevel, attackBonus, saveDc);
        return MonsterTemplates.of(root);
    }

    private void add(ArrayNode into, List<Map<String, Object>> templates, int slotLevel, int attackBonus, int saveDc) {
        for (Map<String, Object> template : templates) {
            into.add(filled(objectMapper.valueToTree(template), null, slotLevel, attackBonus, saveDc));
        }
    }

    // The template with its placeholders filled: SPELL_ATTACK and SPELL_DC as numbers where they stand alone and in
    // words in text, and the slot level in damage dice
    private JsonNode filled(JsonNode node, String key, int slotLevel, int attackBonus, int saveDc) {
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(field.getKey(), filled(field.getValue(), field.getKey(), slotLevel, attackBonus, saveDc));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(element -> copy.add(filled(element, key, slotLevel, attackBonus, saveDc)));
            return copy;
        }
        if (!node.isString()) {
            return node;
        }
        String text = node.asString();
        if (SPELL_ATTACK.equals(text)) {
            return objectMapper.getNodeFactory().numberNode(attackBonus);
        }
        if (SPELL_DC.equals(text)) {
            return objectMapper.getNodeFactory().numberNode(saveDc);
        }
        if (DAMAGE_DICE.equals(key)) {
            return objectMapper.getNodeFactory().stringNode(SpiritScaling.damage(text, slotLevel));
        }
        return objectMapper.getNodeFactory().stringNode(text
                .replace(SPELL_DC, String.valueOf(saveDc))
                .replace(SPELL_ATTACK, (attackBonus >= 0 ? "+" : "") + attackBonus));
    }
}
