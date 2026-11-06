package com.tabletophelper.character;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.dto.PreparedSpellsRequest;
import com.tabletophelper.character.dto.SpellbookRequest;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.SpellsLearned;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import static com.tabletophelper.character.CharacterUpdates.copy;
import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * The spells a player chooses for a class (D158): the spells a prepared caster prepares, a wizard's spellbook, and the
 * cantrips and spells a class learns as its levels' CANTRIPS and SPELLS Pending Choices are made (D159), with a known
 * caster's swap of one spell it knows. Each spell must be on the class's spell list, which reads the spells table's
 * class lists (a subclass's additions appear as "Warlock (The Fiend)"), on the list the class tables name for the class
 * (the wizard's for an Eldritch Knight or Arcane Trickster), and of a level the class can cast, within the numbers ClassProgression gives the class (D167). Race
 * and feat spells are left as they are.
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

    // ── Pending Choices (D159) ───────────────────────────────────

    /**
     * Learns the cantrips a CANTRIPS choice owes the class: as many as it counts, each a cantrip on the class's list
     * that the class doesn't know yet, and no more than the class knows now. The caller records them on the choice's
     * level, so levelling down past it forgets them.
     *
     * @return the cantrips learned, by name
     * @throws ValidationException for another number of cantrips, or one the class can't learn
     */
    List<String> learnCantrips(PlayerCharacter character, PendingChoice choice, List<String> names) {
        ClassSpellcasting casting = choiceCasting(character, choice);
        List<String> chosen = distinct(orEmpty(names));
        requireCount(chosen, choice, "cantrip");
        List<KnownSpell> known = copy(character.getSpellsKnown());
        List<Spell> cantrips = new ArrayList<>();
        for (String name : chosen) {
            Spell spell = classSpell(casting, name);
            if (spell.getLevel() != 0) {
                throw new ValidationException(spell.getName() + " is a level " + spell.getLevel()
                        + " spell, not a cantrip");
            }
            requireUnknown(known, character, casting, spell.getName(), "knows");
            cantrips.add(spell);
        }
        long knownBefore = known.stream().filter(s -> ofClass(s, character, casting) && s.level() == 0).count();
        if (knownBefore + cantrips.size() > casting.cantripsKnown()) {
            throw new ValidationException(classAtLevel(casting) + " knows " + casting.cantripsKnown()
                    + " cantrips, not " + (knownBefore + cantrips.size()));
        }
        cantrips.forEach(spell -> known.add(classEntry(casting, spell, null)));
        character.setSpellsKnown(known);
        return cantrips.stream().map(Spell::getName).toList();
    }

    /**
     * Learns the spells a SPELLS choice owes the class: as many as it counts, each a spell on the class's list of 1st
     * level or higher, up to the highest level the class could learn at the choice's class level, that the class
     * doesn't know yet. A wizard copies them into its spellbook. A class that knows a set number of spells may also
     * replace one it knows with another of those levels, when the choice offers the swap, and knows no more than its
     * Spells Known after. The caller records what this returns on the choice's level, so levelling down past it
     * forgets the spells learned and gives back the one replaced.
     *
     * @throws ValidationException for another number of spells, one the class can't learn, or a swap the choice
     *                             doesn't offer or the class can't make
     */
    SpellsLearned learnSpells(PlayerCharacter character, PendingChoice choice, List<String> names,
                              ApplyChoicesRequest.SpellSwap swap) {
        ClassSpellcasting casting = choiceCasting(character, choice);
        boolean spellbook = WIZARD.equals(casting.className());
        int highest = choice.maxSpellLevel() != null ? choice.maxSpellLevel() : casting.highestSpellLevel();
        List<String> chosen = distinct(orEmpty(names));
        requireCount(chosen, choice, "spell");
        List<KnownSpell> known = copy(character.getSpellsKnown());
        String alreadyHas = spellbook ? "has in its spellbook" : "knows";
        List<Spell> spells = new ArrayList<>();
        for (String name : chosen) {
            Spell spell = classSpell(casting, name);
            requireLevelled(spell);
            requireLearnable(choice, spell, highest);
            requireUnknown(known, character, casting, spell.getName(), alreadyHas);
            spells.add(spell);
        }

        KnownSpell replaced = null;
        Spell replacement = null;
        if (swap != null) {
            if (!Boolean.TRUE.equals(choice.swap())) {
                throw new ValidationException("This choice doesn't let the " + casting.className()
                        + " replace a spell it knows");
            }
            replaced = find(known, character, casting, swap.getReplace(),
                    s -> s.level() > 0 && !Boolean.TRUE.equals(s.alwaysPrepared()));
            if (replaced == null) {
                throw new ValidationException(character.getName() + " knows no " + casting.className()
                        + " spell called " + swap.getReplace().trim());
            }
            requireKnownBefore(character, choice, replaced.name());
            replacement = classSpell(casting, swap.getWith());
            requireLevelled(replacement);
            requireLearnable(choice, replacement, highest);
            requireUnknown(known, character, casting, replacement.getName(), alreadyHas);
            String replacementKey = key(replacement.getName());
            if (spells.stream().anyMatch(spell -> key(spell.getName()).equals(replacementKey))) {
                throw new ValidationException(replacement.getName() + " is already among the spells chosen");
            }
        }

        if (!spellbook && casting.spellsKnown() != null) {
            long knownBefore = known.stream()
                    .filter(s -> ofClass(s, character, casting) && s.level() > 0
                            && !Boolean.TRUE.equals(s.alwaysPrepared()))
                    .count();
            if (knownBefore + spells.size() > casting.spellsKnown()) {
                throw new ValidationException(classAtLevel(casting) + " knows " + casting.spellsKnown()
                        + " spells, not " + (knownBefore + spells.size()));
            }
        }

        if (replaced != null) {
            known.remove(replaced);
            known.add(classEntry(casting, replacement, null));
        }
        spells.forEach(spell -> known.add(classEntry(casting, spell, spellbook ? Boolean.FALSE : null)));
        character.setSpellsKnown(known);
        return new SpellsLearned(spells.stream().map(Spell::getName).toList(), replaced,
                replacement != null ? replacement.getName() : null);
    }

    /**
     * Forgets what a level's CANTRIPS and SPELLS choices gave one of the character's classes, as levelling down past
     * the level does: the cantrips and spells learned, and a swap's replacement, whose replaced spell comes back as
     * the sheet had it.
     */
    static void forget(PlayerCharacter character, String className, List<String> cantrips, SpellsLearned spells) {
        if ((cantrips == null || cantrips.isEmpty()) && spells == null) {
            return;
        }
        String source = "class:" + className;
        List<KnownSpell> known = copy(character.getSpellsKnown());
        removeNamed(known, source, orEmpty(cantrips), s -> s.level() == 0);
        if (spells != null) {
            removeNamed(known, source, orEmpty(spells.learned()), s -> s.level() > 0);
            if (spells.replacement() != null) {
                removeNamed(known, source, List.of(spells.replacement()), s -> s.level() > 0);
            }
            if (spells.replaced() != null) {
                known.add(spells.replaced());
            }
        }
        character.setSpellsKnown(known);
    }

    /**
     * Refuses a swap of a spell a later level of the class learned (D159): with choices made out of order, it would
     * leave a lower level knowing a spell it couldn't have once the later levels were undone.
     */
    private static void requireKnownBefore(PlayerCharacter character, PendingChoice choice, String spellName) {
        String spellKey = key(spellName);
        for (LevelHistoryEntry entry : orEmpty(character.getLevelHistory())) {
            SpellsLearned learned = entry.choices() != null ? entry.choices().spells() : null;
            if (entry.characterLevel() <= choice.characterLevel() || learned == null
                    || !Objects.equals(entry.className(), choice.className())) {
                continue;
            }
            boolean laterSpell = orEmpty(learned.learned()).stream().anyMatch(name -> key(name).equals(spellKey))
                    || (learned.replacement() != null && key(learned.replacement()).equals(spellKey));
            if (laterSpell) {
                throw new ValidationException(spellName + " was learned at level " + entry.characterLevel()
                        + ", after this choice's level, so this choice's swap can't replace it");
            }
        }
    }

    // The class a choice is for, as it casts now; a choice whose class doesn't cast is one the sheet can't make
    private ClassSpellcasting choiceCasting(PlayerCharacter character, PendingChoice choice) {
        return characterStats.spellcasting(character).stream()
                .filter(c -> c.className().equals(choice.className()))
                .findFirst()
                .orElseThrow(() -> new ConflictException(character.getName() + " casts no " + choice.className()
                        + " spells, so can't learn the spells this choice owes"));
    }

    private static void requireCount(List<String> chosen, PendingChoice choice, String what) {
        if (chosen.size() != choice.count()) {
            throw new ValidationException("Choose " + choice.count() + " " + choice.className() + " " + what
                    + (choice.count() == 1 ? "" : "s") + ", not " + chosen.size());
        }
    }

    // A level's spells are of a level the class could learn at that class level, from its own table (PHB p.164)
    private static void requireLearnable(PendingChoice choice, Spell spell, int highest) {
        if (spell.getLevel() > highest) {
            throw new ValidationException("A level " + choice.classLevel() + " " + choice.className()
                    + " learns spells up to level " + highest + ", so can't take " + spell.getName() + " (level "
                    + spell.getLevel() + ")");
        }
    }

    private static void requireUnknown(List<KnownSpell> known, PlayerCharacter character, ClassSpellcasting casting,
                                       String name, String alreadyHas) {
        if (find(known, character, casting, name, spell -> true) != null) {
            throw new ValidationException(character.getName() + " already " + alreadyHas + " " + name);
        }
    }

    // Takes away the class's entries of those names that match
    private static void removeNamed(List<KnownSpell> known, String source, List<String> names,
                                    Predicate<KnownSpell> matches) {
        if (names.isEmpty()) {
            return;
        }
        List<String> keys = names.stream().map(CharacterSpellService::key).toList();
        known.removeIf(spell -> source.equals(spell.source()) && spell.name() != null
                && keys.contains(key(spell.name())) && matches.test(spell));
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
     * The spell of that name, when it is on the class's list: the list its class tables name (the class's own, or the
     * wizard's for an Eldritch Knight or Arcane Trickster), or its subclass's additions.
     */
    private Spell classSpell(ClassSpellcasting casting, String name) {
        Spell spell = spellRepository.findByNameIgnoreCase(name.trim())
                .orElseThrow(() -> new ValidationException("There is no spell called " + name.trim()));
        String list = casting.spellList();
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
        } catch (JacksonException e) {
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

    private static KnownSpell classEntry(ClassSpellcasting casting, Spell spell, Boolean prepared) {
        return new KnownSpell(null, spell.getName(), spell.getLevel(), source(casting), prepared, null, null, null,
                null);
    }

    private static KnownSpell withPrepared(KnownSpell spell, ClassSpellcasting casting, boolean prepared) {
        return new KnownSpell(spell.id(), spell.name(), spell.level(), source(casting), prepared, spell.alwaysPrepared(),
                spell.atWill(), spell.usesPerLongRest(), spell.unlocksAtLevel());
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
