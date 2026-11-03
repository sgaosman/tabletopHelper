package com.tabletophelper.character;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.dto.KnownSpellsRequest;
import com.tabletophelper.character.dto.PreparedSpellsRequest;
import com.tabletophelper.character.dto.SpellbookRequest;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.rules.SpellSlotCalculator;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static com.tabletophelper.character.CharacterUpdates.copy;

/**
 * The spells a player chooses for a class between levels (D158): the spells a prepared caster prepares, a wizard's
 * spellbook, and, until workstream F's Pending Choices replace it (D159), the cantrips and spells a class knows. Each
 * spell must be on the class's spell list, which reads the spells table's class lists (a subclass's additions appear
 * as "Warlock (The Fiend)", and an Eldritch Knight or Arcane Trickster takes the wizard's), and of a level the class
 * can cast, within the numbers ClassProgression gives the class (D167). Race and feat spells are left as they are.
 */
@Service
@RequiredArgsConstructor
public class CharacterSpellService {

    private static final String WIZARD = "Wizard";
    private static final TypeReference<List<String>> CLASS_LISTS = new TypeReference<>() {};

    private final CharacterCommands characterCommands;
    private final CharacterStats characterStats;
    private final SpellRepository spellRepository;
    private final ObjectMapper objectMapper;

    /**
     * Sets which of a prepared caster's spells are prepared: those the request names, from the class's list, or for a
     * wizard from its spellbook, up to the number it prepares. A spell the class had but isn't named stays on the
     * sheet unprepared; always-prepared spells stay prepared and don't count.
     *
     * @throws ValidationException for a class that knows its spells, a spell it can't prepare, or too many spells
     */
    @Transactional
    public CharacterResponse setPrepared(UUID characterId, PreparedSpellsRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            ClassSpellcasting casting = casting(character, request.className());
            if (casting.preparedSpells() == null) {
                throw new ValidationException("A " + casting.className() + " knows its spells rather than preparing them");
            }
            boolean spellbook = WIZARD.equals(casting.className());
            List<KnownSpell> known = copy(character.getSpellsKnown());
            Map<String, KnownSpell> prepared = new LinkedHashMap<>();
            for (String name : distinct(request.spells())) {
                KnownSpell entry = find(known, character, casting, name, spell -> spell.level() > 0);
                if (entry != null && Boolean.TRUE.equals(entry.alwaysPrepared())) {
                    continue;
                }
                if (spellbook) {
                    if (entry == null) {
                        throw new ValidationException(name + " isn't in " + character.getName() + "'s spellbook");
                    }
                    requireCastable(casting, entry.name(), entry.level());
                    prepared.put(key(entry.name()), entry);
                } else {
                    Spell spell = classSpell(casting, name);
                    requireLevelled(spell);
                    requireCastable(casting, spell.getName(), spell.getLevel());
                    prepared.put(key(spell.getName()), entry != null ? entry : classEntry(casting, spell, true));
                }
            }
            if (prepared.size() > casting.preparedSpells()) {
                throw new ValidationException(classAtLevel(casting) + " prepares " + casting.preparedSpells()
                        + " spells, not " + prepared.size());
            }

            List<KnownSpell> updated = new ArrayList<>();
            for (KnownSpell spell : known) {
                if (spell.name() != null && ofClass(spell, character, casting) && spell.level() > 0
                        && !Boolean.TRUE.equals(spell.alwaysPrepared())) {
                    updated.add(withPrepared(spell, casting, prepared.remove(key(spell.name())) != null));
                } else {
                    updated.add(spell);
                }
            }
            updated.addAll(prepared.values());
            character.setSpellsKnown(updated);
        });
    }

    /**
     * Copies spells into a wizard's spellbook: wizard spells of 1st level or higher that it can cast. A spell already
     * in the book is left as it is.
     *
     * @throws ValidationException for a character with no wizard levels, or a spell it can't copy
     */
    @Transactional
    public CharacterResponse addToSpellbook(UUID characterId, SpellbookRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            ClassSpellcasting casting = wizard(character);
            List<KnownSpell> known = copy(character.getSpellsKnown());
            for (String name : distinct(request.spells())) {
                Spell spell = classSpell(casting, name);
                if (spell.getLevel() == 0) {
                    throw new ValidationException("Cantrips aren't kept in a spellbook: " + spell.getName()
                            + " is a cantrip");
                }
                requireCastable(casting, spell.getName(), spell.getLevel());
                if (find(known, character, casting, spell.getName(), s -> s.level() > 0) == null) {
                    known.add(classEntry(casting, spell, false));
                }
            }
            character.setSpellsKnown(known);
        });
    }

    /**
     * Takes spells out of a wizard's spellbook.
     *
     * @throws ValidationException for a character with no wizard levels, or a spell that isn't in the book
     */
    @Transactional
    public CharacterResponse removeFromSpellbook(UUID characterId, SpellbookRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            ClassSpellcasting casting = wizard(character);
            List<KnownSpell> known = copy(character.getSpellsKnown());
            for (String name : distinct(request.spells())) {
                KnownSpell entry = find(known, character, casting, name,
                        s -> s.level() > 0 && !Boolean.TRUE.equals(s.alwaysPrepared()));
                if (entry == null) {
                    throw new ValidationException(name + " isn't in " + character.getName() + "'s spellbook");
                }
                known.remove(entry);
            }
            character.setSpellsKnown(known);
        });
    }

    /**
     * Sets the cantrips a class knows, or the spells a class that knows a set number knows, or both, each list
     * replacing what the class knows of its kind. The interim command until Pending Choices (D159).
     *
     * @throws ValidationException for neither list, spells for a class that prepares them, a spell the class can't
     *                             learn, or more than the class knows
     */
    @Transactional
    public CharacterResponse setKnown(UUID characterId, KnownSpellsRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            ClassSpellcasting casting = casting(character, request.className());
            if (request.cantrips() == null && request.spells() == null) {
                throw new ValidationException("Name the cantrips or the spells the " + casting.className() + " knows");
            }
            List<KnownSpell> known = copy(character.getSpellsKnown());
            if (request.cantrips() != null) {
                List<Spell> cantrips = new ArrayList<>();
                for (String name : distinct(request.cantrips())) {
                    Spell spell = classSpell(casting, name);
                    if (spell.getLevel() != 0) {
                        throw new ValidationException(spell.getName() + " is a level " + spell.getLevel()
                                + " spell, not a cantrip");
                    }
                    cantrips.add(spell);
                }
                if (cantrips.size() > casting.cantripsKnown()) {
                    throw new ValidationException(classAtLevel(casting) + " knows " + casting.cantripsKnown()
                            + " cantrips, not " + cantrips.size());
                }
                known = replaced(known, character, casting, spell -> spell.level() == 0, cantrips);
            }
            if (request.spells() != null) {
                if (casting.spellsKnown() == null) {
                    throw new ValidationException("A " + casting.className()
                            + " prepares its spells rather than knowing a set number");
                }
                List<Spell> spells = new ArrayList<>();
                for (String name : distinct(request.spells())) {
                    Spell spell = classSpell(casting, name);
                    requireLevelled(spell);
                    requireCastable(casting, spell.getName(), spell.getLevel());
                    spells.add(spell);
                }
                if (spells.size() > casting.spellsKnown()) {
                    throw new ValidationException(classAtLevel(casting) + " knows " + casting.spellsKnown()
                            + " spells, not " + spells.size());
                }
                known = replaced(known, character, casting, spell -> spell.level() > 0, spells);
            }
            character.setSpellsKnown(known);
        });
    }

    // ── The class and its list ───────────────────────────────────

    private ClassSpellcasting casting(PlayerCharacter character, String className) {
        String wanted = className.trim();
        return characterStats.spellcasting(character).stream()
                .filter(c -> c.className().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new ValidationException(character.getName() + " casts no " + wanted + " spells"));
    }

    private ClassSpellcasting wizard(PlayerCharacter character) {
        return characterStats.spellcasting(character).stream()
                .filter(c -> WIZARD.equals(c.className()))
                .findFirst()
                .orElseThrow(() -> new ValidationException(character.getName()
                        + " has no wizard levels, so has no spellbook"));
    }

    /**
     * The spell of that name, when it is on the class's list: the class's own, its subclass's additions, or the
     * wizard's for an Eldritch Knight or Arcane Trickster.
     */
    private Spell classSpell(ClassSpellcasting casting, String name) {
        Spell spell = spellRepository.findByNameIgnoreCase(name.trim())
                .orElseThrow(() -> new ValidationException("There is no spell called " + name.trim()));
        boolean thirdCaster = casting.subclassName() != null
                && SpellSlotCalculator.THIRD_CASTER_SUBCLASSES.contains(casting.subclassName());
        String list = thirdCaster ? WIZARD : casting.className();
        List<String> lists = classLists(spell);
        boolean onList = lists.contains(list)
                || (casting.subclassName() != null
                    && lists.contains(casting.className() + " (" + casting.subclassName() + ")"));
        if (!onList) {
            throw new ValidationException(spell.getName() + " isn't on the " + list + " spell list");
        }
        return spell;
    }

    // The class lists the spells table gives the spell; one with none is on no class's list
    private List<String> classLists(Spell spell) {
        if (spell.getClasses() == null || spell.getClasses().isBlank()) {
            return List.of();
        }
        try {
            List<String> lists = objectMapper.readValue(spell.getClasses(), CLASS_LISTS);
            return lists != null ? lists : List.of();
        } catch (JsonProcessingException e) {
            throw new ConflictException("The class lists of the spell " + spell.getName() + " can't be read");
        }
    }

    private static void requireLevelled(Spell spell) {
        if (spell.getLevel() == 0) {
            throw new ValidationException(spell.getName() + " is a cantrip");
        }
    }

    // A class learns and prepares spells up to the highest level its own table gives it (PHB p.164)
    private static void requireCastable(ClassSpellcasting casting, String spellName, int spellLevel) {
        if (spellLevel > casting.highestSpellLevel()) {
            throw new ValidationException(classAtLevel(casting) + " casts spells up to level "
                    + casting.highestSpellLevel() + ", so can't take " + spellName + " (level " + spellLevel + ")");
        }
    }

    private static String classAtLevel(ClassSpellcasting casting) {
        return "A level " + casting.classLevel() + " " + casting.className();
    }

    // ── The sheet's entries ──────────────────────────────────────

    // Whether a known spell is one of the class's: its source names the class, or it has none and the class is the
    // character's only one, as the sheet has always read it
    private static boolean ofClass(KnownSpell spell, PlayerCharacter character, ClassSpellcasting casting) {
        if (spell.source() == null || spell.source().isBlank()) {
            return casting.className().equals(character.getCharacterClass());
        }
        return spell.source().equals(source(casting));
    }

    // The class's entry of that name that matches, or null
    private static KnownSpell find(List<KnownSpell> known, PlayerCharacter character, ClassSpellcasting casting,
                                   String name, Predicate<KnownSpell> matches) {
        String wanted = key(name);
        return known.stream()
                .filter(spell -> spell.name() != null && key(spell.name()).equals(wanted))
                .filter(spell -> ofClass(spell, character, casting) && matches.test(spell))
                .findFirst()
                .orElse(null);
    }

    /**
     * The known spells with the class's entries of one kind replaced by those chosen: an entry already on the sheet
     * keeps what it records, and always-prepared entries stay.
     */
    private static List<KnownSpell> replaced(List<KnownSpell> known, PlayerCharacter character,
                                             ClassSpellcasting casting, Predicate<KnownSpell> kind,
                                             List<Spell> chosen) {
        Map<String, Spell> wanted = new LinkedHashMap<>();
        chosen.forEach(spell -> wanted.put(key(spell.getName()), spell));
        List<KnownSpell> updated = new ArrayList<>();
        for (KnownSpell spell : known) {
            boolean replaceable = ofClass(spell, character, casting) && kind.test(spell)
                    && !Boolean.TRUE.equals(spell.alwaysPrepared());
            if (!replaceable) {
                updated.add(spell);
            } else if (spell.name() != null && wanted.remove(key(spell.name())) != null) {
                updated.add(withSource(spell, casting));
            }
        }
        wanted.values().forEach(spell -> updated.add(classEntry(casting, spell, null)));
        return updated;
    }

    private static KnownSpell classEntry(ClassSpellcasting casting, Spell spell, Boolean prepared) {
        return new KnownSpell(null, spell.getName(), spell.getLevel(), source(casting), prepared, null, null, null,
                null);
    }

    private static KnownSpell withPrepared(KnownSpell spell, ClassSpellcasting casting, boolean prepared) {
        return new KnownSpell(spell.id(), spell.name(), spell.level(), source(casting), prepared, spell.alwaysPrepared(),
                spell.atWill(), spell.usesPerLongRest(), spell.unlocksAtLevel());
    }

    private static KnownSpell withSource(KnownSpell spell, ClassSpellcasting casting) {
        return new KnownSpell(spell.id(), spell.name(), spell.level(), source(casting), spell.prepared(),
                spell.alwaysPrepared(), spell.atWill(), spell.usesPerLongRest(), spell.unlocksAtLevel());
    }

    private static String source(ClassSpellcasting casting) {
        return "class:" + casting.className();
    }

    // The names given, trimmed, each once whatever its case
    private static List<String> distinct(List<String> names) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                distinct.putIfAbsent(key(name), name.trim());
            }
        }
        return new ArrayList<>(distinct.values());
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
