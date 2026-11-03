package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.KnownSpell;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.MonsterPoolTrigger;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ExpressionEvaluator;
import com.tabletophelper.rules.ProficiencyBonus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds every resource pool a monster starts an encounter with, and its spellcasting (D165). The pools are the
 * ones its stat block triggers in {@code monster_pool_triggers} (legendary actions; Legendary Resistance, for a
 * monster with that trait), with the counts its action templates give; one per recharge action, which rolls to
 * recharge at the start of its turn; and one per innate spell, holding its daily uses. The spellcasting is its
 * spell slots, its spells and its spell attack bonus, save DC and ability.
 *
 * <p>Every pool keeps the {@link ResourcePoolEntry} shape: legendary actions reset each turn ({@code resetOn}
 * "turn"), and a recharge pool has no {@code resetOn} but a {@code resetCheck} such as "1d6>=5", which the turn
 * rolls (D146).
 */
@Component
@RequiredArgsConstructor
public class MonsterPools {

    // The pool that counts a monster's legendary actions each round
    private static final String LEGENDARY_ACTIONS = "monster:legendary-actions";

    // A recharge the start of the monster's turn rolls for: "5-6", "6" or "4-6"
    private static final Pattern RECHARGE = Pattern.compile("^([4-6])(?:-[5-6])?$");
    // A note in brackets at the end of a trait's name, as in "Legendary Resistance (3/Day)"
    private static final Pattern TRAIT_NOTE = Pattern.compile("\\s*\\([^()]*\\)\\s*$");
    private static final Pattern WHOLE_RATING = Pattern.compile("^(\\d+)$");

    private final MonsterPoolTriggerRepository triggerRepository;
    private final SpellRepository spellRepository;
    private final ObjectMapper objectMapper;

    /** A spell from the stat block, with its name and level from the spells table when the table lists it. */
    private record Resolved(MonsterSpellbook.Entry entry, UUID id, String name, int level) {
    }

    /** A monster joining an encounter: every pool at full uses, and its spellcasting. */
    public void setUp(EncounterParticipant participant, Monster monster) {
        MonsterTemplates templates = templates(monster);
        MonsterSpellbook book = MonsterSpellbook.read(templates);
        List<Resolved> spells = resolve(book);
        participant.setResourcePoolsCurrent(pools(monster, templates, spells));
        if (book.present()) {
            participant.setSpellSlotsCurrent(freshSlots(book));
            participant.setSpellAttackBonus(book.attackBonus());
            participant.setSpellSaveDc(book.saveDc());
            participant.setSpellcastingAbility(book.ability());
            participant.setSpellsKnown(knownSpells(spells));
        }
    }

    /**
     * Gives a monster that joined before its spellcasting was set up whatever of it is missing: its spells,
     * spellcasting numbers and slots, at full. Anything it has already spent stays spent; a missing innate pool
     * is started when the spell is first cast.
     */
    public void fillIn(EncounterParticipant participant) {
        if (participant.getMonster() == null) {
            return;
        }
        MonsterSpellbook book = MonsterSpellbook.read(templates(participant.getMonster()));
        if (!book.present()) {
            return;
        }
        if (participant.getSpellSlotsCurrent() == null) {
            participant.setSpellSlotsCurrent(freshSlots(book));
        }
        if (participant.getSpellAttackBonus() == null) {
            participant.setSpellAttackBonus(book.attackBonus());
        }
        if (participant.getSpellSaveDc() == null) {
            participant.setSpellSaveDc(book.saveDc());
        }
        if (participant.getSpellcastingAbility() == null) {
            participant.setSpellcastingAbility(book.ability());
        }
        if (participant.getSpellsKnown() == null) {
            participant.setSpellsKnown(knownSpells(resolve(book)));
        }
    }

    /** The monster's spells as the Spell Sources read them: from its stat block, whatever the participant holds. */
    List<SpellSources.Known> spells(Monster monster) {
        return resolve(MonsterSpellbook.read(templates(monster))).stream()
                .map(spell -> new SpellSources.Known(spell.name(), spell.level(), SpellSources.MONSTER, false, false,
                        spell.entry().kind() == MonsterSpellbook.Kind.AT_WILL, uses(spell.entry()), null))
                .toList();
    }

    /** The participant's slots, or the stat block's at full for a monster that joined without them. */
    Map<String, SpellSlot> slots(EncounterParticipant participant) {
        if (participant.getSpellSlotsCurrent() != null || participant.getMonster() == null) {
            return participant.getSpellSlotsCurrent();
        }
        return freshSlots(MonsterSpellbook.read(templates(participant.getMonster())));
    }

    // ── Pools ────────────────────────────────────────────────────

    /** Every pool the monster starts with, full. */
    List<ResourcePoolEntry> pools(Monster monster) {
        MonsterTemplates templates = templates(monster);
        return pools(monster, templates, resolve(MonsterSpellbook.read(templates)));
    }

    private List<ResourcePoolEntry> pools(Monster monster, MonsterTemplates templates, List<Resolved> spells) {
        Map<String, ResourcePoolEntry> pools = new LinkedHashMap<>();
        for (MonsterPoolTrigger trigger : triggerRepository.findAllByOrderByPriorityAsc()) {
            ResourcePoolDefinition definition = trigger.getPoolDefinition();
            if (definition != null && triggers(trigger, monster)) {
                pools.putIfAbsent(definition.getPoolId(), fromDefinition(definition, monster));
            }
        }

        // The action templates' counts replace the definitions' defaults
        if (templates.legendaryActionCount() != null) {
            int count = templates.legendaryActionCount();
            pools.put(LEGENDARY_ACTIONS, new ResourcePoolEntry(LEGENDARY_ACTIONS, "Legendary Actions", "MONSTER",
                    monster.getName(), count, null, count, "turn", null, null, "FREE", "crown", Map.of()));
        }
        if (templates.legendaryResistanceCount() != null) {
            int count = templates.legendaryResistanceCount();
            pools.put(EffectApplier.LEGENDARY_RESISTANCE_POOL, new ResourcePoolEntry(
                    EffectApplier.LEGENDARY_RESISTANCE_POOL, "Legendary Resistance", "MONSTER", monster.getName(),
                    count, null, count, "longRest", null, null, "FREE", "shield", Map.of()));
        }

        for (MonsterTemplates.Action action : templates.actions()) {
            Matcher recharge = RECHARGE.matcher(action.recharge() != null ? action.recharge().trim() : "");
            String name = action.name() != null ? action.name() : "";
            if (recharge.matches() && !name.isBlank()) {
                String poolId = rechargePoolId(name);
                pools.putIfAbsent(poolId, new ResourcePoolEntry(poolId, name, "MONSTER", monster.getName(), 1, null,
                        1, null, null, "1d6>=" + recharge.group(1), "ACTION", "zap", Map.of()));
            }
        }

        for (Resolved spell : spells) {
            Integer uses = uses(spell.entry());
            if (uses != null && spell.level() > 0) {
                String poolId = SpellSources.innatePoolId(spell.name());
                pools.putIfAbsent(poolId, SpellSources.innatePool(spell.name(), uses, "MONSTER", monster.getName()));
            }
        }
        return new ArrayList<>(pools.values());
    }

    // The pool a recharge action spends, as MonsterActionService looks it up: "monster:recharge:" and the
    // action's name in kebab case
    private static String rechargePoolId(String actionName) {
        return "monster:recharge:" + actionName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    // A trigger matches legendary actions, or a trait by its exact name, notes in brackets aside (D165)
    private boolean triggers(MonsterPoolTrigger trigger, Monster monster) {
        JsonNode condition = StoredJson.read(objectMapper, trigger.getTriggerCondition(),
                "The trigger for the " + trigger.getPoolDefinition().getPoolId() + " pool");
        if (condition.path("hasField").isTextual()) {
            return "legendary".equals(condition.path("hasField").asText()) && hasLegendaryActions(monster);
        }
        if (condition.path("hasFeature").isTextual()) {
            return hasTrait(monster, condition.path("hasFeature").asText());
        }
        return false;
    }

    private boolean hasLegendaryActions(Monster monster) {
        JsonNode actions = StoredJson.read(objectMapper, monster.getLegendaryActions(),
                monster.getName() + "'s legendary actions");
        return (actions.isArray() || actions.isObject()) && !actions.isEmpty();
    }

    private boolean hasTrait(Monster monster, String feature) {
        for (JsonNode trait : StoredJson.read(objectMapper, monster.getTraits(), monster.getName() + "'s traits")) {
            String name = trait.path("name").asText("");
            if (TRAIT_NOTE.matcher(name).replaceFirst("").trim().equalsIgnoreCase(feature.trim())) {
                return true;
            }
        }
        return false;
    }

    private ResourcePoolEntry fromDefinition(ResourcePoolDefinition definition, Monster monster) {
        int maxUses = definition.getMaxUsesFormula() != null && !definition.getMaxUsesFormula().isBlank()
                ? ExpressionEvaluator.evaluate(definition.getMaxUsesFormula(), formulaContext(monster)) : 1;
        Object metadata = definition.getMetadata() != null
                ? objectMapper.convertValue(StoredJson.read(objectMapper, definition.getMetadata(),
                        "The " + definition.getPoolId() + " pool's metadata"), Object.class)
                : Map.of();
        return new ResourcePoolEntry(definition.getPoolId(), definition.getDisplayName(), definition.getSourceType(),
                monster.getName(), maxUses, definition.getMaxUsesFormula(), maxUses, definition.getResetOn(),
                definition.getResetAmount(), definition.getResetCheck(), definition.getSpendActionType(),
                definition.getIcon(), metadata);
    }

    // What a definition's formula can name for a monster: its proficiency bonus, its challenge rating rounded up
    // as its level, and its ability modifiers
    private static Map<String, Integer> formulaContext(Monster monster) {
        Map<String, Integer> context = new LinkedHashMap<>();
        context.put("proficiencyBonus", ProficiencyBonus.forChallengeRating(monster.getChallengeRating()));
        context.put("totalLevel", ratingLevel(monster.getChallengeRating()));
        context.put("strengthModifier", modifier(monster.getStrength()));
        context.put("dexterityModifier", modifier(monster.getDexterity()));
        context.put("constitutionModifier", modifier(monster.getConstitution()));
        context.put("intelligenceModifier", modifier(monster.getIntelligence()));
        context.put("wisdomModifier", modifier(monster.getWisdom()));
        context.put("charismaModifier", modifier(monster.getCharisma()));
        return context;
    }

    // A whole challenge rating, at least 1; a fractional one, below 1, counts as 1
    private static int ratingLevel(String rating) {
        Matcher whole = WHOLE_RATING.matcher(rating != null ? rating.trim() : "");
        return whole.matches() ? Math.max(1, Integer.parseInt(whole.group(1))) : 1;
    }

    private static int modifier(Integer score) {
        return Ability.modifier(score != null ? score : 10);
    }

    // ── Spells ───────────────────────────────────────────────────

    // Each spell with its name and level from the spells table; one the table doesn't list keeps the name the
    // stat block gives and the level it is listed under, or 0
    private List<Resolved> resolve(MonsterSpellbook book) {
        List<Resolved> resolved = new ArrayList<>();
        for (MonsterSpellbook.Entry entry : book.spells()) {
            Optional<Spell> spell = spellRepository.findByNameIgnoreCase(entry.lookupName());
            resolved.add(spell
                    .map(found -> new Resolved(entry, found.getId(), found.getName(),
                            found.getLevel() != null ? found.getLevel() : 0))
                    .orElseGet(() -> new Resolved(entry, null, entry.lookupName(),
                            entry.listedLevel() != null ? entry.listedLevel() : 0)));
        }
        return resolved;
    }

    // The participant's known spells, marked as the Spell Sources read them; a spell the block lists twice the same
    // way is listed once
    private static List<KnownSpell> knownSpells(List<Resolved> spells) {
        List<KnownSpell> known = new ArrayList<>();
        for (Resolved spell : spells) {
            boolean atWill = spell.entry().kind() == MonsterSpellbook.Kind.AT_WILL;
            KnownSpell entry = new KnownSpell(spell.id(), spell.name(), spell.level(), SpellSources.MONSTER, null,
                    null, atWill ? Boolean.TRUE : null, uses(spell.entry()), null);
            if (!known.contains(entry)) {
                known.add(entry);
            }
        }
        return known;
    }

    private static Integer uses(MonsterSpellbook.Entry entry) {
        return entry.kind() == MonsterSpellbook.Kind.DAILY ? entry.uses() : null;
    }

    private static Map<String, SpellSlot> freshSlots(MonsterSpellbook book) {
        if (book.slots().isEmpty()) {
            return null;
        }
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        book.slots().forEach((level, count) -> slots.put(level, new SpellSlot(count, 0)));
        return slots;
    }

    private MonsterTemplates templates(Monster monster) {
        return MonsterTemplates.of(objectMapper, monster);
    }
}
