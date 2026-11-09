package com.tabletophelper.rules.combat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The spirits of Tasha's summoning spells (TCE p.109–114, D182), as {@code data/content/summon-spirits.json} holds them
 * and the content module reads them. Each spirit's statistics scale with the spell's slot level ({@link SpiritScaling});
 * its actions are kept in the monster action template schema, with the placeholders SPELL_ATTACK and SPELL_DC for the
 * caster's spell attack bonus and save DC, and LEVEL for the slot level, which the encounter fills in at the cast.
 * The templates are plain maps, so the rules need no JSON library.
 *
 * @param spirits one per spell
 */
public record SummonSpirits(List<Spirit> spirits) {

    public SummonSpirits {
        spirits = spirits != null ? List.copyOf(spirits) : List.of();
    }

    /**
     * One spell's spirit.
     *
     * @param spell        the spell, such as "Summon Beast"
     * @param monster      the bestiary row its stat block is, such as "Bestial Spirit"
     * @param source       that row's source
     * @param page         the stat block's page
     * @param baseLevel    the spell's level, from which its hit points grow
     * @param acBase       its AC less the spell's level: 11, or 12 for the fey (TCE p.112)
     * @param attack       the action its Multiattack repeats, unless the variant names its own
     * @param actions      the actions every variant has, in the template schema
     * @param bonusActions the bonus actions every variant has
     * @param variants     the environments, moods or kinds the caster chooses among
     */
    public record Spirit(String spell, String monster, String source, int page, int baseLevel, int acBase,
                         String attack, List<Map<String, Object>> actions, List<Map<String, Object>> bonusActions,
                         List<Variant> variants) {

        public Spirit {
            actions = actions != null ? List.copyOf(actions) : List.of();
            bonusActions = bonusActions != null ? List.copyOf(bonusActions) : List.of();
            variants = variants != null ? List.copyOf(variants) : List.of();
        }

        /** The variant of that name, in any case. */
        public Optional<Variant> variant(String name) {
            return name == null ? Optional.empty() : variants.stream()
                    .filter(variant -> variant.name().equalsIgnoreCase(name.trim()))
                    .findFirst();
        }

        /** The variants' names, in the data's order. */
        public List<String> variantNames() {
            return variants.stream().map(Variant::name).toList();
        }
    }

    /**
     * One variant of a spirit.
     *
     * @param name         such as "Land"
     * @param hpBase       its hit points at the spell's own level
     * @param hpPerLevel   the hit points it gains for each slot level above
     * @param attack       the action its Multiattack repeats, or null for the spirit's
     * @param actions      its own actions, beside the spirit's
     * @param bonusActions its own bonus actions
     * @param traits       its own traits, which the DM applies by hand, such as an aura
     */
    public record Variant(String name, int hpBase, int hpPerLevel, String attack, List<Map<String, Object>> actions,
                          List<Map<String, Object>> bonusActions, List<Map<String, Object>> traits) {

        public Variant {
            actions = actions != null ? List.copyOf(actions) : List.of();
            bonusActions = bonusActions != null ? List.copyOf(bonusActions) : List.of();
            traits = traits != null ? List.copyOf(traits) : List.of();
        }
    }

    /** The spirit of that spell, in any case. */
    public Optional<Spirit> forSpell(String spell) {
        return spell == null ? Optional.empty() : spirits.stream()
                .filter(spirit -> spirit.spell().toLowerCase(Locale.ROOT).equals(spell.trim().toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
