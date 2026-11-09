package com.tabletophelper.encounter.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A caster's spells, and paying for them from their Spell Sources (D164, D165). A monster's spells come from its
 * stat block; a player character's from the spells their sheet had when they joined, which the browser records,
 * with the always-prepared spells of their subclasses, which it doesn't. Each spell's name and level come from the
 * spells table when it lists the spell.
 */
@Component
@RequiredArgsConstructor
class CasterSpells {

    private static final Pattern CLASS_LEVEL = Pattern.compile("^\\d{1,2}$");

    private final MonsterPools monsterPools;
    private final ResourcePoolService resourcePoolService;
    private final CombatLogWriter combatLog;
    private final SpellRepository spellRepository;
    private final SubclassRepository subclassRepository;
    private final ObjectMapper objectMapper;

    // A spell's name, level and casting time, from the spells table when it lists the spell; the casting time is
    // null when it doesn't
    private record Resolved(String name, int level, String castingTime) {
    }

    // A subclass the character has, with its class and that class's level
    private record SubclassLevel(Subclass subclass, String className, int classLevel) {
    }

    /** Each spell the caster can cast now, with what can pay for it. */
    List<SpellOption> options(EncounterParticipant caster) {
        return SpellSources.options(spells(caster), purse(caster));
    }

    /**
     * How the caster pays for casting the spell, which nothing has spent yet; a 400 for a spell it can't cast now
     * or can't pay for. A monster that joined without its spellcasting set up gets it first.
     */
    SpellSources.Payment payment(EncounterParticipant caster, String spellName, SpellSource source, int slotLevel) {
        if (caster.getParticipantType() == ParticipantType.MONSTER) {
            monsterPools.fillIn(caster);
        }
        return SpellSources.pay(caster.getDisplayName(), spells(caster), purse(caster), spellName.trim(), source,
                slotLevel);
    }

    /**
     * Where a player character has a spell from, a class it has the spell from first, as a cast of it would be paid
     * (D164); null for a monster, or a spell it doesn't have.
     */
    String origin(EncounterParticipant caster, String spellName) {
        if (caster.getParticipantType() == ParticipantType.MONSTER || spellName == null) {
            return null;
        }
        String key = spellName.trim();
        return SpellSources.origin(playerSpells(caster).stream()
                .filter(known -> known.name().equalsIgnoreCase(key))
                .toList());
    }

    /**
     * Spends what the payment names, and logs it: a slot or pact slot, or an innate use from the spell's pool,
     * which a caster without the pool yet starts full.
     */
    void spend(Encounter encounter, EncounterParticipant caster, SpellSources.Payment payment) {
        switch (payment.source()) {
            case SLOT, PACT_SLOT -> {
                Map<String, SpellSlot> slots = new LinkedHashMap<>(caster.getSpellSlotsCurrent());
                SpellSlot spent = slots.get(payment.slotKey()).spend();
                slots.put(payment.slotKey(), spent);
                caster.setSpellSlotsCurrent(slots);
                combatLog.write(encounter, CombatActionType.SPELL_SLOT_USE, caster.getDisplayName() + " uses a level "
                        + payment.castLevel() + (payment.source() == SpellSource.PACT_SLOT ? " pact slot" : " spell slot")
                        + " (" + spent.remaining() + "/" + spent.total() + " remaining)", null, caster);
            }
            case INNATE -> {
                String poolId = payment.innatePool().poolId();
                List<ResourcePoolEntry> pools = caster.getResourcePoolsCurrent() != null
                        ? new ArrayList<>(caster.getResourcePoolsCurrent()) : new ArrayList<>();
                if (pools.stream().noneMatch(pool -> poolId.equals(pool.poolId()))) {
                    pools.add(payment.innatePool());
                    caster.setResourcePoolsCurrent(pools);
                }
                resourcePoolService.spendPool(caster, poolId, 1);
                ResourcePoolEntry pool = caster.getResourcePoolsCurrent().stream()
                        .filter(candidate -> poolId.equals(candidate.poolId()))
                        .findFirst()
                        .orElseThrow();
                combatLog.write(encounter, CombatActionType.SPELL_SLOT_USE, caster.getDisplayName()
                        + " uses an innate use of " + payment.spellName() + " (" + pool.currentUses() + "/"
                        + pool.maxUses() + " remaining)", null, caster);
            }
            case NONE -> {
            }
        }
    }

    private List<SpellSources.Known> spells(EncounterParticipant caster) {
        if (caster.getParticipantType() == ParticipantType.MONSTER) {
            return caster.getMonster() != null ? monsterPools.spells(caster.getMonster()) : List.of();
        }
        return playerSpells(caster);
    }

    private SpellSources.Purse purse(EncounterParticipant caster) {
        PlayerCharacter character = caster.getCharacter();
        return new SpellSources.Purse(monsterPools.slots(caster), caster.getResourcePoolsCurrent(),
                character != null ? character.getLevel() : null);
    }

    // A spell without a source comes from the character's class, as the character sheet reads it
    private List<SpellSources.Known> playerSpells(EncounterParticipant caster) {
        PlayerCharacter character = caster.getCharacter();
        String classOrigin = character != null && character.getCharacterClass() != null
                ? "class:" + character.getCharacterClass() : null;
        List<SpellSources.Known> spells = new ArrayList<>();
        for (KnownSpell spell : caster.getSpellsKnown() != null ? caster.getSpellsKnown() : List.<KnownSpell>of()) {
            if (spell.name() == null || spell.name().isBlank()) {
                continue;
            }
            Resolved resolved = resolve(spell.name(), spell.level());
            spells.add(new SpellSources.Known(resolved.name(), resolved.level(),
                    spell.source() != null && !spell.source().isBlank() ? spell.source() : classOrigin,
                    Boolean.TRUE.equals(spell.prepared()), Boolean.TRUE.equals(spell.alwaysPrepared()),
                    Boolean.TRUE.equals(spell.atWill()), spell.usesPerLongRest(), spell.unlocksAtLevel(),
                    resolved.castingTime()));
        }
        if (character != null) {
            spells.addAll(subclassSpells(character));
        }
        return spells;
    }

    // The always-prepared spells of each subclass the character has, from the class level each is gained at
    private List<SpellSources.Known> subclassSpells(PlayerCharacter character) {
        Map<UUID, SubclassLevel> subclasses = new LinkedHashMap<>();
        for (ClassEntry entry : character.getMulticlassEntries() != null
                ? character.getMulticlassEntries() : List.<ClassEntry>of()) {
            UUID id = entry.subclassId();
            if (id != null) {
                subclassRepository.findById(id).ifPresent(subclass -> subclasses.putIfAbsent(id,
                        new SubclassLevel(subclass, entry.className(), entry.level())));
            }
        }
        Subclass primary = character.getSubclassRef();
        if (primary != null && !subclasses.containsKey(primary.getId())) {
            subclasses.put(primary.getId(), new SubclassLevel(primary, character.getCharacterClass(),
                    character.getLevel() != null ? character.getLevel() : 1));
        }

        List<SpellSources.Known> spells = new ArrayList<>();
        for (SubclassLevel granted : subclasses.values()) {
            String origin = granted.className() != null ? "class:" + granted.className() : null;
            JsonNode byLevel = StoredJson.read(objectMapper, granted.subclass().getAlwaysPreparedSpells(),
                    granted.subclass().getName() + "'s always-prepared spells");
            byLevel.properties().forEach(group -> {
                String classLevel = group.getKey().trim();
                if (!CLASS_LEVEL.matcher(classLevel).matches()
                        || Integer.parseInt(classLevel) > granted.classLevel()) {
                    return;
                }
                for (JsonNode name : group.getValue()) {
                    if (name.isString() && !name.asString("").isBlank()) {
                        Resolved resolved = resolve(name.asString(""), null);
                        spells.add(new SpellSources.Known(resolved.name(), resolved.level(), origin, false, true,
                                false, null, null, resolved.castingTime()));
                    }
                }
            });
        }
        return spells;
    }

    private Resolved resolve(String name, Integer recordedLevel) {
        return spellRepository.findByNameIgnoreCase(name.trim())
                .map(spell -> new Resolved(spell.getName(), spell.getLevel() != null ? spell.getLevel() : 0,
                        spell.getCastingTime()))
                .orElseGet(() -> new Resolved(name.trim(), recordedLevel != null ? recordedLevel : 0, null));
    }
}
