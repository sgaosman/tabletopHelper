package com.tabletophelper.character;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class SpellSlotCalculator {

    /** Subclasses that give a class without Spellcasting its own spell slots from 3rd level. */
    public static final Set<String> THIRD_CASTER_SUBCLASSES = Set.of("Eldritch Knight", "Arcane Trickster");

    /**
     * The progression a class casts with, from its name, its isSpellcaster and isPactMagic flags and its
     * subclass: "pact", "artificer", "half", "third", "full", or "none" for a class without spell slots.
     */
    public static String casterType(String className, boolean isSpellcaster, boolean isPactMagic, String subclassName) {
        if (isPactMagic) return "pact";
        if ("Artificer".equals(className)) return "artificer";
        if ("Paladin".equals(className) || "Ranger".equals(className)) return "half";
        if (!isSpellcaster && subclassName != null && THIRD_CASTER_SUBCLASSES.contains(subclassName)) return "third";
        if (isSpellcaster) return "full";
        return "none";
    }

    public static Map<String, Integer> calculateSlots(Map<String, ClassEntry> classEntries) {
        int multiclassCasterLevel = 0;
        int spellcastingClasses = 0;
        ClassEntry onlySpellcastingClass = null;
        int warlockLevel = 0;

        for (ClassEntry ce : classEntries.values()) {
            if ("pact".equals(ce.casterType())) {
                warlockLevel = ce.level();
                continue;
            }
            // A class has the Spellcasting feature once it contributes: Paladin and Ranger from
            // 2nd level, Eldritch Knight and Arcane Trickster from 3rd
            int contribution = multiclassContribution(ce);
            if (contribution > 0) {
                multiclassCasterLevel += contribution;
                spellcastingClasses++;
                onlySpellcastingClass = ce;
            }
        }

        // PHB p.164: the multiclass table applies only with two or more Spellcasting classes;
        // a single one uses its own progression (D153)
        int[] row = spellcastingClasses == 1
                ? SpellSlotTables.ownTable(onlySpellcastingClass.casterType(), onlySpellcastingClass.level())
                : SpellSlotTables.multiclassRow(multiclassCasterLevel);

        Map<String, Integer> slots = new LinkedHashMap<>();
        for (int i = 0; i < row.length; i++) {
            if (row[i] > 0) {
                slots.put(String.valueOf(i + 1), row[i]);
            }
        }

        int[] pactRow = SpellSlotTables.pactMagicRow(warlockLevel);
        for (int i = 0; i < pactRow.length; i++) {
            if (pactRow[i] > 0) {
                slots.put("pact_" + (i + 1), pactRow[i]);
            }
        }

        return slots;
    }

    static int multiclassContribution(ClassEntry entry) {
        return switch (entry.casterType()) {
            case "full" -> entry.level();
            case "half" -> entry.level() / 2;
            case "artificer" -> (entry.level() + 1) / 2;
            case "third" -> entry.level() / 3;
            default -> 0;
        };
    }

    public record ClassEntry(int level, String casterType) {}
}
