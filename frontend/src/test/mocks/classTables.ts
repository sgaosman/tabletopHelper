import type { ClassTables } from '../../utils/classTables';

/**
 * GET /api/reference/class-tables as the server answers it: the class tables of
 * backend/src/main/resources/data/content/class-tables.json, with the proficiency bonus by character level.
 */
export const CLASS_TABLES: ClassTables = {
  "spellSlots": {
    "artificer": [
        [],
        [2],
        [2],
        [3],
        [3],
        [4, 2],
        [4, 2],
        [4, 3],
        [4, 3],
        [4, 3, 2],
        [4, 3, 2],
        [4, 3, 3],
        [4, 3, 3],
        [4, 3, 3, 1],
        [4, 3, 3, 1],
        [4, 3, 3, 2],
        [4, 3, 3, 2],
        [4, 3, 3, 3, 1],
        [4, 3, 3, 3, 1],
        [4, 3, 3, 3, 2],
        [4, 3, 3, 3, 2],
      ],
    "full": [
        [],
        [2],
        [3],
        [4, 2],
        [4, 3],
        [4, 3, 2],
        [4, 3, 3],
        [4, 3, 3, 1],
        [4, 3, 3, 2],
        [4, 3, 3, 3, 1],
        [4, 3, 3, 3, 2],
        [4, 3, 3, 3, 2, 1],
        [4, 3, 3, 3, 2, 1],
        [4, 3, 3, 3, 2, 1, 1],
        [4, 3, 3, 3, 2, 1, 1],
        [4, 3, 3, 3, 2, 1, 1, 1],
        [4, 3, 3, 3, 2, 1, 1, 1],
        [4, 3, 3, 3, 2, 1, 1, 1, 1],
        [4, 3, 3, 3, 3, 1, 1, 1, 1],
        [4, 3, 3, 3, 3, 2, 1, 1, 1],
        [4, 3, 3, 3, 3, 2, 2, 1, 1],
      ],
    "half": [
        [],
        [],
        [2],
        [3],
        [3],
        [4, 2],
        [4, 2],
        [4, 3],
        [4, 3],
        [4, 3, 2],
        [4, 3, 2],
        [4, 3, 3],
        [4, 3, 3],
        [4, 3, 3, 1],
        [4, 3, 3, 1],
        [4, 3, 3, 2],
        [4, 3, 3, 2],
        [4, 3, 3, 3, 1],
        [4, 3, 3, 3, 1],
        [4, 3, 3, 3, 2],
        [4, 3, 3, 3, 2],
      ],
    "pact": [
        [],
        [1],
        [2],
        [0, 2],
        [0, 2],
        [0, 0, 2],
        [0, 0, 2],
        [0, 0, 0, 2],
        [0, 0, 0, 2],
        [0, 0, 0, 0, 2],
        [0, 0, 0, 0, 2],
        [0, 0, 0, 0, 3],
        [0, 0, 0, 0, 3],
        [0, 0, 0, 0, 3],
        [0, 0, 0, 0, 3],
        [0, 0, 0, 0, 3],
        [0, 0, 0, 0, 3],
        [0, 0, 0, 0, 4],
        [0, 0, 0, 0, 4],
        [0, 0, 0, 0, 4],
        [0, 0, 0, 0, 4],
      ],
    "third": [
        [],
        [],
        [],
        [2],
        [3],
        [3],
        [3],
        [4, 2],
        [4, 2],
        [4, 2],
        [4, 3],
        [4, 3],
        [4, 3],
        [4, 3, 2],
        [4, 3, 2],
        [4, 3, 2],
        [4, 3, 3],
        [4, 3, 3],
        [4, 3, 3],
        [4, 3, 3, 1],
        [4, 3, 3, 1],
      ]
  },
  "proficiencyBonus": [0, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6],
  "classes": {
    "Artificer": {
      "subclassLevel": 3,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": {
        "casterType": "artificer",
        "spellList": "Artificer",
        "cantripsKnown": [0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4],
        "spellsKnown": [],
        "preparing": "HALF_LEVEL"
      },
      "spellbook": null
    },
    "Barbarian": {
      "subclassLevel": 3,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": null,
      "spellbook": null
    },
    "Bard": {
      "subclassLevel": 3,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {
        "3": 2,
        "10": 2
      },
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": {
        "casterType": "full",
        "spellList": "Bard",
        "cantripsKnown": [0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4],
        "spellsKnown": [0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 15, 16, 18, 19, 19, 20, 22, 22, 22],
        "preparing": "NONE"
      },
      "spellbook": null
    },
    "Cleric": {
      "subclassLevel": 1,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": {
        "casterType": "full",
        "spellList": "Cleric",
        "cantripsKnown": [0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5],
        "spellsKnown": [],
        "preparing": "LEVEL"
      },
      "spellbook": null
    },
    "Druid": {
      "subclassLevel": 2,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": {
        "casterType": "full",
        "spellList": "Druid",
        "cantripsKnown": [0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4],
        "spellsKnown": [],
        "preparing": "LEVEL"
      },
      "spellbook": null
    },
    "Fighter": {
      "subclassLevel": 3,
      "asiLevels": [4, 6, 8, 12, 14, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [1],
      "fightingStyles": [
        "Archery",
        "Defense",
        "Dueling",
        "Great Weapon Fighting",
        "Protection",
        "Two-Weapon Fighting",
        "Blind Fighting",
        "Interception",
        "Superior Technique",
        "Thrown Weapon Fighting",
        "Unarmed Fighting"
      ],
      "spellcasting": null,
      "spellbook": null
    },
    "Monk": {
      "subclassLevel": 3,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": null,
      "spellbook": null
    },
    "Paladin": {
      "subclassLevel": 3,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [2],
      "fightingStyles": [
        "Defense",
        "Dueling",
        "Great Weapon Fighting",
        "Protection",
        "Blessed Warrior",
        "Blind Fighting",
        "Interception"
      ],
      "spellcasting": {
        "casterType": "half",
        "spellList": "Paladin",
        "cantripsKnown": [],
        "spellsKnown": [],
        "preparing": "HALF_LEVEL"
      },
      "spellbook": null
    },
    "Ranger": {
      "subclassLevel": 3,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [2],
      "fightingStyles": [
        "Archery",
        "Defense",
        "Dueling",
        "Two-Weapon Fighting",
        "Blind Fighting",
        "Druidic Warrior",
        "Thrown Weapon Fighting"
      ],
      "spellcasting": {
        "casterType": "half",
        "spellList": "Ranger",
        "cantripsKnown": [],
        "spellsKnown": [0, 0, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11],
        "preparing": "NONE"
      },
      "spellbook": null
    },
    "Rogue": {
      "subclassLevel": 3,
      "asiLevels": [4, 8, 10, 12, 16, 19],
      "expertise": {
        "1": 2,
        "6": 2
      },
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": null,
      "spellbook": null
    },
    "Sorcerer": {
      "subclassLevel": 1,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": {
        "casterType": "full",
        "spellList": "Sorcerer",
        "cantripsKnown": [0, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6],
        "spellsKnown": [0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 13, 13, 14, 14, 15, 15, 15, 15],
        "preparing": "NONE"
      },
      "spellbook": null
    },
    "Warlock": {
      "subclassLevel": 1,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": {
        "casterType": "pact",
        "spellList": "Warlock",
        "cantripsKnown": [0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4],
        "spellsKnown": [0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15],
        "preparing": "NONE"
      },
      "spellbook": null
    },
    "Wizard": {
      "subclassLevel": 2,
      "asiLevels": [4, 8, 12, 16, 19],
      "expertise": {},
      "fightingStyleLevels": [],
      "fightingStyles": [],
      "spellcasting": {
        "casterType": "full",
        "spellList": "Wizard",
        "cantripsKnown": [0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5],
        "spellsKnown": [],
        "preparing": "LEVEL"
      },
      "spellbook": {
        "firstLevel": 6,
        "laterLevels": 2
      }
    }
  },
  "subclasses": {
    "Arcane Trickster": {
      "className": "Rogue",
      "fightingStyleLevels": [],
      "spellcasting": {
        "casterType": "third",
        "spellList": "Wizard",
        "cantripsKnown": [0, 0, 0, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4],
        "spellsKnown": [0, 0, 0, 3, 4, 4, 4, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 11, 11, 12, 13],
        "preparing": "NONE"
      }
    },
    "Champion": {
      "className": "Fighter",
      "fightingStyleLevels": [10],
      "spellcasting": null
    },
    "Eldritch Knight": {
      "className": "Fighter",
      "fightingStyleLevels": [],
      "spellcasting": {
        "casterType": "third",
        "spellList": "Wizard",
        "cantripsKnown": [0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3],
        "spellsKnown": [0, 0, 0, 3, 4, 4, 4, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 11, 11, 12, 13],
        "preparing": "NONE"
      }
    }
  }
};
