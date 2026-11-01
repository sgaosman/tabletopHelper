# M25: Comprehensive Testing Suite — Test Plan

*304 total tests (218 backend, 43 frontend utils, 27 frontend components, 16 integration). 53 already exist; 251 new.*

---

## Scope Rules

- Test each *type* of pattern, not every class/race/spell individually (e.g. test full/half/third/pact/artificer caster slots, not Wizard + Cleric + Druid + Bard separately)
- Cover both creation and editing/leveling code paths where they differ
- Only test scenarios that realistically come up in play (no 6-way multiclass edge cases)
- Frontend tests use Vitest + React Testing Library for utility functions and component rendering

## Priority Order

1. FeatEffectResolver (reversal symmetry is the highest-risk untested code)
2. CombatService (most user-facing, most rule-complex)
3. CharacterJsonHelper (JSONB mutation is error-prone)
4. DiceRoller (pure function, easy to test, foundational)
5. CharacterService business logic (creation + rest mechanics)
6. Frontend utils (spellConstants, featPrerequisites, featSpellParser)
7. EncounterService + AuthService + CampaignService
8. Integration tests (require test database setup)
9. Frontend component tests (require React Testing Library setup)

---

## Backend Unit Tests

### 1. SpellSlotCalculator (12 existing + 5 new = 17)

#### 1.1 Full caster at levels 1, 5, 20 [x]

**Given** a single-class Wizard with no multiclass entries
**When** spell slots are calculated at level 1, level 5, and level 20
**Then** the slot tables should match the PHB full-caster progression exactly

**Acceptance criteria:**
- Level 1 Wizard gets 2 first-level slots
- Level 5 Wizard gets 4/3/2 slots at levels 1/2/3
- Level 20 Wizard gets the complete table including a single 9th-level slot
- No pact slots are generated for non-Warlock classes

#### 1.2 Half caster (Paladin/Ranger) slot rounding [x]

**Given** a single-class Paladin at level 5
**When** spell slots are calculated using the half-caster formula (floor(level / 2))
**Then** the Paladin should have caster level 2 and get slots matching a 2nd-level full caster

**Acceptance criteria:**
- Paladin level 5 has caster level 2, giving 3 first-level slots
- Odd levels round down (level 3 = caster level 1, not 2)
- No slots appear for levels below the casting threshold

#### 1.3 Third caster (EK/AT) slot rounding [x]

**Given** a single-class Fighter (Eldritch Knight subclass) at level 7
**When** spell slots are calculated using the third-caster formula (floor(level / 3))
**Then** the Fighter should have caster level 2 and slots matching a 2nd-level full caster

**Acceptance criteria:**
- EK level 7 has caster level 2, giving 3 first-level slots
- Rounding is floor, so level 8 is still caster level 2

#### 1.4 Artificer ceil rounding [x]

**Given** a single-class Artificer at level 1
**When** spell slots are calculated using the Artificer formula (ceil(level / 2))
**Then** the Artificer should have caster level 1 even at class level 1

**Acceptance criteria:**
- Artificer level 1 has caster level 1 (ceiling rounds up, unlike half casters)
- Artificer level 2 also has caster level 1

#### 1.5 Warlock pact slots (separate track) [x]

**Given** a single-class Warlock at level 5
**When** spell slots are calculated
**Then** pact magic slots should be returned on a separate track, not merged with regular caster slots

**Acceptance criteria:**
- Level 5 Warlock gets 2 pact slots at 3rd level
- No regular spell slots appear in the standard slot array
- Pact slot level increases with Warlock level (1st at level 1, 2nd at level 3, 3rd at level 5)

#### 1.6 Multiclass full + full caster combined levels [x]

**Given** a character with Wizard 3 / Cleric 2 multiclass entries
**When** spell slots are calculated
**Then** caster levels should be summed (3 + 2 = 5) and slots match a 5th-level full caster

**Acceptance criteria:**
- Combined caster level is 5, giving 4/3/2 slots at levels 1/2/3
- Both classes contribute their full level to caster level calculation

#### 1.7 Multiclass with Warlock (pact slots separate) [x]

**Given** a character with Wizard 3 / Warlock 2 multiclass entries
**When** spell slots are calculated
**Then** Wizard contributes to regular caster slots, Warlock contributes pact slots separately

**Acceptance criteria:**
- Regular slots are based on Wizard 3 alone (caster level 3)
- Pact slots are based on Warlock 2 alone (1 pact slot at 1st level)
- Pact slots do not add to or interfere with regular slot calculation

#### 1.8 Non-caster returns empty [x]

**Given** a single-class Fighter (Champion subclass, not EK) at level 10
**When** spell slots are calculated
**Then** both regular and pact slot arrays should be empty

**Acceptance criteria:**
- No spell slots of any kind are returned
- The result is an empty map, not null

#### 1.9 Empty entries returns empty [x]

**Given** a character with null or empty multiclass entries
**When** spell slots are calculated with no class information
**Then** an empty slot map should be returned without errors

**Acceptance criteria:**
- Null entries do not cause a NullPointerException
- Empty list produces the same result as null

#### 1.10 Multiclass full + half caster combined levels [ ]

**Given** a character with Wizard 5 / Paladin 4 multiclass entries
**When** spell slots are calculated
**Then** the combined caster level should be 7 (Wizard 5 + floor(Paladin 4 / 2) = 5 + 2)

**Acceptance criteria:**
- Wizard contributes full class level (5) to caster level
- Paladin contributes floor(4/2) = 2 to caster level
- Resulting slots match the 7th-level row of the multiclass spellcasting table (4/3/3/1)

#### 1.11 Multiclass full + third caster combined levels [ ]

**Given** a character with Cleric 5 / Fighter (EK) 6 multiclass entries
**When** spell slots are calculated
**Then** the combined caster level should be 7 (Cleric 5 + floor(EK 6 / 3) = 5 + 2)

**Acceptance criteria:**
- Cleric contributes full class level (5)
- EK Fighter contributes floor(6/3) = 2
- Slots match 7th-level multiclass spellcasting table

#### 1.12 Multiclass half + third caster (no full caster) [ ]

**Given** a character with Paladin 6 / Fighter (EK) 6 multiclass entries and no full caster class
**When** spell slots are calculated
**Then** the combined caster level should be 5 (floor(6/2) + floor(6/3) = 3 + 2)

**Acceptance criteria:**
- Paladin contributes floor(6/2) = 3
- EK contributes floor(6/3) = 2
- Combined caster level 5 gives 4/3/2 slots
- No 3rd-level slots or higher appear

#### 1.13 Multiclass with Artificer + another caster [ ]

**Given** a character with Artificer 3 / Wizard 2 multiclass entries
**When** spell slots are calculated
**Then** the Artificer should contribute ceil(3/2) = 2 caster levels while the Wizard contributes its full 2

**Acceptance criteria:**
- Artificer uses ceil, not floor, for its multiclass caster level contribution
- Combined caster level is 4 (ceil(3/2) + 2 = 2 + 2)
- Slots match 4th-level multiclass table (4/3 at levels 1/2)

#### 1.14 Warlock multiclass pact slot level progression [ ]

**Given** a single-class Warlock progressing from level 1 to level 9
**When** pact slots are calculated at each level
**Then** pact slot level should increase at levels 3, 5, 7, and 9

**Acceptance criteria:**
- Levels 1-2: pact slots at 1st-level spells
- Levels 3-4: pact slots at 2nd-level spells
- Levels 5-6: pact slots at 3rd-level spells
- Levels 7-8: pact slots at 4th-level spells
- Level 9+: pact slots at 5th-level spells
- Number of pact slots is 1 at levels 1, then 2 at level 2+

**Considered and excluded:**
- Half caster at level 1 / third caster at level 2 (below casting threshold) — low risk, the formula already handles this via floor rounding

---

### 2. LevelUpCalculator (10 existing + 7 new = 17)

#### 2.1 Level 1 HP = max hit die + CON mod [x]

**Given** a new level 1 Fighter with CON 14 (modifier +2) and a d10 hit die
**When** HP is calculated for level 1
**Then** HP should be 12 (10 max from d10 + 2 CON mod)

**Acceptance criteria:**
- First level always uses the maximum die roll, not average
- CON modifier is added on top of the max die value
- A negative CON mod still applies (e.g. CON 8 gives -1)

#### 2.2 Level 2+ HP = average hit die + CON mod [x]

**Given** a level 1 Fighter with CON 14 (+2) and existing HP of 12
**When** the character levels up to level 2
**Then** HP gained should be 8 (6 average from d10 + 2 CON mod)

**Acceptance criteria:**
- Average die value is used (d10 → 6, d8 → 5, d6 → 4, d12 → 7)
- CON modifier is added to the average
- Total HP = previous HP + average + CON mod

#### 2.3 Negative CON modifier applies [x]

**Given** a level 1 Wizard with CON 8 (modifier -1) and a d6 hit die
**When** HP is calculated for level 2
**Then** HP gained should be 3 (4 average from d6 + (-1) CON mod)

**Acceptance criteria:**
- Negative CON mod reduces HP gained
- HP gained per level is never reduced below 1 (PHB rule)

#### 2.4 Standard ASI levels (4, 8, 12, 16, 19) [x]

**Given** any single-class character at the standard ASI levels
**When** checking if an ASI is available at levels 4, 8, 12, 16, and 19
**Then** ASI should be flagged as available at all five of those levels

**Acceptance criteria:**
- ASI is offered at exactly levels 4, 8, 12, 16, 19 for most classes
- Non-ASI levels do not offer an ASI

#### 2.5 Fighter extra ASI (6, 14) [x]

**Given** a Fighter class character
**When** checking ASI availability at levels 6 and 14
**Then** ASI should be available at both levels in addition to the standard ASI levels

**Acceptance criteria:**
- Fighter gets ASI at 4, 6, 8, 12, 14, 16, 19 (7 total, not 5)
- Other classes do not get ASI at 6 or 14

#### 2.6 Rogue extra ASI (10) [x]

**Given** a Rogue class character
**When** checking ASI availability at level 10
**Then** ASI should be available at level 10 in addition to the standard ASI levels

**Acceptance criteria:**
- Rogue gets ASI at 4, 8, 10, 12, 16, 19 (6 total)
- Other classes (besides Fighter) do not get ASI at level 10

#### 2.7 Single class progression total HP [x]

**Given** a Fighter with CON 16 (+3) starting from level 1
**When** the character is levelled up to level 5
**Then** total HP should equal (10 + 3) + 4 * (6 + 3) = 13 + 36 = 49

**Acceptance criteria:**
- Level 1 uses max die (10), subsequent levels use average (6)
- CON mod (+3) is added at every level
- Running total accumulates correctly across all 5 levels

#### 2.8 collectFeaturesForLevel parses class features JSON [x]

**Given** a Fighter class with features JSON containing "Action Surge" at level 2
**When** features are collected for level 2
**Then** the result should include "Action Surge" with its description

**Acceptance criteria:**
- Features are parsed from the class JSON data
- Each feature has a name, description, and level
- Features from other levels are not included

#### 2.9 collectFeaturesForLevel includes subclass features [x]

**Given** a Fighter (Champion) at level 3 where Champion grants "Improved Critical" at level 3
**When** features are collected for level 3
**Then** both base class features and subclass features for that level should be included

**Acceptance criteria:**
- Subclass features at the matching level are merged with class features
- Subclass features are tagged with the subclass name as their source

#### 2.10 collectFeaturesForLevel handles null JSON [x]

**Given** a character class with null or missing features JSON
**When** features are collected for any level
**Then** an empty list should be returned without errors

**Acceptance criteria:**
- Null features JSON returns an empty list, not an exception
- Empty string features JSON also returns an empty list

#### 2.11 HP calculation with d6 hit die [ ]

**Given** a level 1 Wizard (d6 hit die) with CON 12 (+1)
**When** the character levels up to level 2
**Then** HP gained should be 5 (4 average from d6 + 1 CON mod)

**Acceptance criteria:**
- d6 average is 4 (not 3 or 3.5)
- Level 1 Wizard HP is 7 (6 max + 1 CON)
- Level 2 total HP is 12 (7 + 5)

#### 2.12 HP calculation with d12 hit die [ ]

**Given** a level 1 Barbarian (d12 hit die) with CON 16 (+3)
**When** the character levels up to level 2
**Then** HP gained should be 10 (7 average from d12 + 3 CON mod)

**Acceptance criteria:**
- d12 average is 7
- Level 1 Barbarian HP is 15 (12 max + 3 CON)
- Level 2 total HP is 25 (15 + 10)

#### 2.13 Multiclass progression: class switch uses new class hit die [ ]

**Given** a Fighter 3 (d10) with CON 14 (+2) who multiclasses into Wizard (d6)
**When** the character takes their 4th level as Wizard 1
**Then** HP gained should use the Wizard d6 hit die: 4 average + 2 CON = 6

**Acceptance criteria:**
- HP gain at the new class level uses that class's hit die, not the original class's
- Previous levels' HP is unchanged
- Total HP reflects the mixed hit dice across both classes

#### 2.14 collectFeaturesForLevel filters by level [ ]

**Given** a Fighter class with "Extra Attack" at level 5 and "Indomitable" at level 9
**When** features are collected for level 3
**Then** neither "Extra Attack" nor "Indomitable" should appear

**Acceptance criteria:**
- Only features whose level exactly matches the requested level are returned
- Features from higher levels are not included early
- Features from lower levels are not re-included

#### 2.15 collectFeaturesForLevel with both class and subclass features at same level [ ]

**Given** a Cleric (Life Domain) at level 2 where both the Cleric base class and Life Domain grant features at level 2
**When** features are collected for level 2
**Then** both the base class feature ("Channel Divinity") and subclass feature ("Preserve Life") should be present

**Acceptance criteria:**
- Features from both sources appear in the result
- No deduplication occurs — both distinct features are listed
- Each feature is labelled with its source (class vs subclass)

#### 2.16 buildProgression total levels matches input [ ]

**Given** a request to build a 5-level progression for a Fighter
**When** buildProgression is called with target level 5
**Then** the result should contain exactly 5 level entries

**Acceptance criteria:**
- The returned list has one entry per level from 1 to 5
- Each entry contains HP gained, features, and whether an ASI is available
- The final entry corresponds to level 5

#### 2.17 buildProgression with existing HP (leveling from non-zero base) [ ]

**Given** a level 3 Fighter with 28 HP who is levelling up to level 4
**When** buildProgression is called with existing HP of 28
**Then** the level 4 entry should add HP on top of the existing 28

**Acceptance criteria:**
- The existing HP is treated as the starting point, not recalculated
- Only the new level's HP gain is computed
- Cumulative HP at level 4 = 28 + (6 + CON mod)

---

### 3. MulticlassValidator (9 existing + 5 new = 14)

#### 3.1 Null/empty requirements pass [x]

**Given** a character class with no multiclass prerequisites defined (null or empty)
**When** the prerequisites are validated against any character
**Then** the check should pass

**Acceptance criteria:**
- Null prerequisites return eligible = true
- Empty string prerequisites return eligible = true
- No ability score checks are performed

#### 3.2 AND prerequisites — all met / one fails [x]

**Given** a Paladin with multiclass prerequisites "STR 13 AND CHA 13"
**When** validated against a character with STR 14, CHA 15
**Then** the check should pass

**And when** validated against a character with STR 14, CHA 10
**Then** the check should fail with a reason mentioning Charisma

**Acceptance criteria:**
- Both abilities must meet or exceed the threshold for a pass
- Failing any single requirement causes the whole check to fail
- The failure reason identifies which ability fell short

#### 3.3 OR prerequisites — one met / none met [x]

**Given** a class with OR prerequisites "STR 13 OR DEX 13"
**When** validated against a character with STR 10, DEX 15
**Then** the check should pass (DEX meets threshold)

**And when** validated against a character with STR 10, DEX 10
**Then** the check should fail

**Acceptance criteria:**
- Meeting any one of the OR abilities is sufficient
- Failure only occurs if none of the abilities meet the threshold

#### 3.4 parseMulticlassEntries: null / valid JSON [x]

**Given** a multiclass entries field that is null, or a valid JSON array of class entries
**When** parsed
**Then** null should return an empty list, and valid JSON should return the parsed entries

**Acceptance criteria:**
- Null input returns an empty list, not an exception
- Valid JSON like `[{"classId":"abc","level":3}]` returns a list with one entry
- Malformed JSON returns an empty list

#### 3.5 Paladin dual-ability prereq (STR 13 AND CHA 13) [x]

**Given** the Paladin's specific PHB multiclass prerequisite of STR 13 and CHA 13
**When** a character with STR 13, CHA 13 is checked
**Then** the check should pass

**And when** a character with STR 12, CHA 13 is checked
**Then** the check should fail

**Acceptance criteria:**
- Exactly 13 is sufficient (threshold is "13 or higher")
- 12 in either ability causes failure
- This validates the real PHB Paladin multiclass requirement

#### 3.6 Ranger prerequisite: DEX 13 AND WIS 13 [ ]

**Given** the Ranger's PHB multiclass prerequisite of DEX 13 and WIS 13
**When** a character with DEX 14, WIS 16 is checked
**Then** the check should pass

**And when** a character with DEX 14, WIS 11 is checked
**Then** the check should fail citing Wisdom

**Acceptance criteria:**
- Both DEX and WIS must be 13+
- Failure message mentions the specific ability that's too low
- This covers a different AND pair than Paladin (different abilities)

#### 3.7 Monk prerequisite: DEX 13 AND WIS 13 [ ]

**Given** the Monk's PHB multiclass prerequisite of DEX 13 and WIS 13
**When** a character with DEX 13, WIS 13 is checked at exact threshold
**Then** the check should pass

**And when** DEX 12, WIS 13 is checked
**Then** the check should fail

**Acceptance criteria:**
- Same dual-ability pattern as Ranger, confirming both classes share the same prerequisite logic
- Exact threshold (13) passes

#### 3.8 Bard/Sorcerer/Warlock prerequisite: CHA 13 only [ ]

**Given** the Bard's PHB multiclass prerequisite of CHA 13
**When** a character with CHA 14 is checked
**Then** the check should pass regardless of other ability scores

**And when** a character with CHA 12 is checked
**Then** the check should fail

**Acceptance criteria:**
- Only Charisma is evaluated — other scores don't matter
- CHA 13 exactly passes, CHA 12 fails
- This validates single-ability prerequisites (most common type)

#### 3.9 Scores exactly at threshold pass; one below fails [ ]

**Given** any class with a prerequisite of "STR 13"
**When** validated against a character with STR exactly 13
**Then** the check should pass

**And when** validated against STR 12
**Then** the check should fail

**Acceptance criteria:**
- The threshold is inclusive (>= 13, not > 13)
- A score of 12 is the highest value that still fails
- This is a boundary value test ensuring the comparison is >= not >

#### 3.10 getEligibleClasses excludes the character's current class [ ]

**Given** a level 3 Fighter with STR 16, DEX 14, CON 14, INT 10, WIS 12, CHA 8
**When** eligible multiclass options are retrieved
**Then** Fighter should not appear in the list of eligible classes

**Acceptance criteria:**
- The character's own class is excluded even if its prerequisites are met
- All other classes still appear (eligible or ineligible based on prerequisites)
- Rogue and Monk should be eligible (DEX 14 meets their requirements)

---

### 4. CharacterService — Static Methods (8 existing + 2 new = 10)

#### 4.1 abilityMod calculation for various scores [x]

**Given** ability scores of 10, 11, 14, 15, 8, 20
**When** the ability modifier is calculated for each
**Then** the results should be 0, 0, +2, +2, -1, +5 respectively

**Acceptance criteria:**
- Modifier formula is floor((score - 10) / 2)
- Even scores and the odd score above them give the same modifier
- Score 10 gives +0 (the baseline)

#### 4.2 abilityMod handles null [x]

**Given** a null ability score value
**When** the ability modifier is calculated
**Then** the result should be 0 (default modifier)

**Acceptance criteria:**
- Null does not throw NullPointerException
- Default return for null is 0, treating it as a score of 10

#### 4.3 proficiencyBonus follows 5e table (levels 1-20) [x]

**Given** character levels from 1 to 20
**When** proficiency bonus is calculated for each level
**Then** the values should match the PHB proficiency bonus table

**Acceptance criteria:**
- Levels 1-4: +2
- Levels 5-8: +3
- Levels 9-12: +4
- Levels 13-16: +5
- Levels 17-20: +6

#### 4.4 Rogue expertise levels (1, 6) [x]

**Given** a Rogue class
**When** expertise availability is checked
**Then** expertise should be offered at levels 1 and 6

**Acceptance criteria:**
- Level 1 Rogue gets their first expertise selection
- Level 6 Rogue gets their second expertise selection
- No other levels offer expertise for Rogue

#### 4.5 Bard expertise levels (3, 10) [x]

**Given** a Bard class
**When** expertise availability is checked
**Then** expertise should be offered at levels 3 and 10

**Acceptance criteria:**
- Level 3 Bard gets their first expertise selection
- Level 10 Bard gets their second expertise selection

#### 4.6 Non-expertise classes [x]

**Given** a Fighter class (no expertise feature)
**When** expertise availability is checked at any level
**Then** no expertise should ever be offered

**Acceptance criteria:**
- Classes without the Expertise feature return false at all levels
- Only Rogue and Bard currently offer expertise

#### 4.7 getAbilityMod extracts from character entity [x]

**Given** a PlayerCharacter entity with STR 16 and DEX 12
**When** getAbilityMod is called with "strength"
**Then** the result should be +3

**Acceptance criteria:**
- The method correctly maps the ability name string to the entity field
- STR 16 → mod +3, DEX 12 → mod +1

#### 4.8 getAbilityMod handles null ability [x]

**Given** a PlayerCharacter entity with a null ability score field
**When** getAbilityMod is called for that ability
**Then** the result should be 0

**Acceptance criteria:**
- Null ability value returns 0 modifier without exception
- Works the same as abilityMod(null)

#### 4.9 abilityMod for extreme values [ ]

**Given** ability scores at the extremes: 1 and 30
**When** the ability modifier is calculated
**Then** score 1 should give -5, and score 30 should give +10

**Acceptance criteria:**
- Score 1: floor((1-10)/2) = floor(-4.5) = -5
- Score 30: floor((30-10)/2) = +10
- These are the D&D-valid extremes (1 is lowest possible, 30 is deity-level)

#### 4.10 proficiencyBonus at level boundary transitions [ ]

**Given** a character at the exact boundary levels where proficiency bonus changes
**When** proficiency bonus is calculated at levels 4 and 5, then at levels 8 and 9
**Then** bonus should increase from +2 to +3 at the 4→5 boundary, and from +3 to +4 at the 8→9 boundary

**Acceptance criteria:**
- Level 4 returns +2, level 5 returns +3
- Level 8 returns +3, level 9 returns +4
- The boundary is inclusive on the higher tier (level 5 is already +3)

---

### 5. LevelUpDownRoundTrip (14 existing + 4 new = 18)

#### 5.1 Fighter 1→2: HP, features, proficiency [x]

**Given** a level 1 Fighter with 12 HP, CON 14 (+2)
**When** the character is levelled up to level 2
**Then** HP should increase by 8 (d10 avg 6 + 2 CON), and "Action Surge" should appear in features

**Acceptance criteria:**
- HP goes from 12 to 20
- Level field changes from 1 to 2
- New features from level 2 are appended to the features JSON
- Proficiency bonus stays at +2 (doesn't change until level 5)

#### 5.2 Fighter level 4 ASI available [x]

**Given** a level 3 Fighter
**When** the character is levelled up to level 4
**Then** the response should indicate that an ASI choice is available

**Acceptance criteria:**
- The level-up response flags `asiAvailable: true`
- The character's level is now 4
- HP increased correctly for the new level

#### 5.3 Fighter 4→5 proficiency bonus increase [x]

**Given** a level 4 Fighter with proficiency bonus +2
**When** the character is levelled up to level 5
**Then** proficiency bonus should increase from +2 to +3

**Acceptance criteria:**
- Proficiency bonus on the character entity changes to 3
- All derived stats that use proficiency bonus are recalculated (saving throws, skill bonuses, spell attack, spell DC)

#### 5.4 Cannot exceed level 20 [x]

**Given** a level 20 character
**When** a level up is attempted
**Then** the operation should be rejected with an error

**Acceptance criteria:**
- An exception is thrown (IllegalStateException or similar)
- The character's level remains at 20
- No HP is added, no features are appended

#### 5.5 Level down 2→1: reverts HP and features [x]

**Given** a level 2 Fighter who gained 8 HP and "Action Surge" at level 2
**When** the character is levelled down from 2 to 1
**Then** HP should decrease by 8 and "Action Surge" should be removed from features

**Acceptance criteria:**
- HP returns to the level 1 value
- Features added at level 2 are removed
- Level field changes from 2 to 1

#### 5.6 Cannot level down from level 1 [x]

**Given** a level 1 character
**When** a level down is attempted
**Then** the operation should be rejected with an error

**Acceptance criteria:**
- An exception is thrown
- The character remains at level 1
- No data is modified

#### 5.7 Level down updates hit dice [x]

**Given** a level 3 Fighter with hitDiceTotal "3d10" and hitDiceRemaining 3
**When** the character is levelled down to level 2
**Then** hitDiceTotal should become "2d10" and hitDiceRemaining should be 2

**Acceptance criteria:**
- Total hit dice decrease by 1
- Remaining hit dice are capped at the new total if they exceed it
- The hit dice map reflects the updated count

#### 5.8 Round trip: up then down restores HP [x]

**Given** a level 3 Fighter with 28 HP
**When** the character is levelled up to 4 and then levelled down back to 3
**Then** HP should return to exactly 28

**Acceptance criteria:**
- The HP added at level 4 is subtracted exactly on level down
- No rounding errors or off-by-one issues
- The character is in the same state as before the round trip

#### 5.9 Round trip: up then down restores proficiency bonus [x]

**Given** a level 4 Fighter with proficiency bonus +2
**When** levelled up to 5 (bonus becomes +3) then levelled down back to 4
**Then** proficiency bonus should return to +2

**Acceptance criteria:**
- Proficiency bonus is recalculated on level down, not just decremented
- Derived stats that depend on proficiency bonus are also restored

#### 5.10 Round trip: up then down restores hit dice [x]

**Given** a level 3 Fighter with 3d10 hit dice
**When** levelled up to 4 (4d10) then levelled down back to 3
**Then** hit dice should return to 3d10

**Acceptance criteria:**
- Hit dice total and remaining both revert correctly
- The hit dice map shows the original count

#### 5.11 Multiple round trips restore original state [x]

**Given** a level 3 Fighter with known HP, features, hit dice, and proficiency
**When** the character is levelled up and down 3 times in succession
**Then** all character stats should be identical to the original state

**Acceptance criteria:**
- HP, features, hit dice, proficiency bonus, level history all match the starting values
- No accumulation of rounding errors or orphaned features
- This is a stress test for the round-trip symmetry

#### 5.12 Ownership validation (level up / level down) [x]

**Given** a character owned by User A
**When** User B attempts to level up or level down the character
**Then** the operation should be rejected with an authorization error

**Acceptance criteria:**
- Only the character's owner can trigger level up/down
- The error message indicates an access/ownership issue
- The character remains unchanged

#### 5.13 Level history: up appends / down removes [x]

**Given** a level 2 Fighter with a level history containing entries for levels 1 and 2
**When** levelled up to 3, then levelled down back to 2
**Then** level history should have 3 entries after level up, and 2 entries after level down

**Acceptance criteria:**
- Level up appends a new entry with HP gained, features, class info
- Level down removes the last entry from the history
- The remaining entries are untouched

#### 5.14 Wizard level up recalculates spell slots [x]

**Given** a level 1 Wizard with 2 first-level spell slots
**When** levelled up to level 2
**Then** spell slots should be recalculated to 3 first-level slots

**Acceptance criteria:**
- Spell slot JSON is regenerated based on the new caster level
- The new slot count matches the PHB table for the new level

#### 5.15 Multiclass level up: Fighter 3 adds Wizard [ ]

**Given** a level 3 Fighter with 28 HP, hit dice "3d10", and no spell slots
**When** the player levels up by adding Wizard as a new class (character level 4, Wizard 1)
**Then** HP should increase using d6 (Wizard die), spell slots should appear, and hit dice map should show "3d10 + 1d6"

**Acceptance criteria:**
- HP gain uses Wizard's d6, not Fighter's d10
- Spell slots are calculated based on Wizard caster level 1 (2 first-level slots)
- Hit dice map has both Fighter (3d10) and Wizard (1d6) entries
- Multiclass entries JSON now includes the Wizard class entry
- Character level is 4, Fighter level is still 3, Wizard level is 1

#### 5.16 Multiclass level down: removes last-added class level [ ]

**Given** a Fighter 3 / Wizard 1 character at level 4
**When** the character is levelled down
**Then** the Wizard level should be removed, reverting to a single-class Fighter 3

**Acceptance criteria:**
- Wizard class entry is removed from multiclass entries (level was 1, so removing it drops the class entirely)
- Spell slots are recalculated (Fighter has none, so slots become empty)
- Hit dice map reverts to only Fighter d10
- HP decreases by the amount gained at the Wizard level

#### 5.17 Subclass at threshold: leveling to subclass level sets flag [ ]

**Given** a level 2 Fighter (no subclass chosen yet)
**When** the character is levelled up to level 3 (Fighter's subclass selection level)
**Then** the response should indicate that a subclass selection is available

**Acceptance criteria:**
- The level-up response includes `subclassAvailable: true` (or equivalent flag)
- This applies at the correct level for each class (Fighter 3, Wizard 2, Cleric 1, etc.)
- If a subclass is already chosen, the flag is not set again

#### 5.18 Spellcaster level down: spell slots recalculated [ ]

**Given** a level 5 Wizard with 4/3/2 spell slots at levels 1/2/3
**When** the character is levelled down to level 4
**Then** spell slots should be recalculated to 4/3 at levels 1/2 (no 3rd-level slots)

**Acceptance criteria:**
- Spell slots are fully regenerated, not just modified
- The new slot counts match level 4 of the full caster table
- Any prepared spells of a level the character can no longer cast are not removed (that's the player's responsibility)

---

### 6. FeatEffectResolver (0 existing — 19 new)

This is the highest-risk untested code. Feat application mutates ~15 character fields and must be perfectly reversible.

#### 6.1 Ability score increase: fixed bonus [ ]

**Given** a character with STR 15 and the "Heavily Armored" feat (grants +1 STR)
**When** the feat is applied
**Then** the character's STR should increase to 16

**Acceptance criteria:**
- The specific ability score field on the character entity is incremented
- Derived stats (STR modifier, STR save, athletics bonus) are not recalculated here — that happens at a higher level
- The ability score does not exceed 20

#### 6.2 Half-feat choice: choose one ability [ ]

**Given** a character with DEX 15, and the "Skill Expert" feat which grants +1 to a chosen ability
**When** the feat is applied with the choice of DEX
**Then** the character's DEX should increase to 16, and the choice should be recorded

**Acceptance criteria:**
- The chosen ability is the one that gets incremented
- The choice is persisted in the ASI record in level history (so it can be reversed)
- Other abilities are not affected

#### 6.3 Proficiency grant [ ]

**Given** a character with no medium armor proficiency, and the "Moderately Armored" feat
**When** the feat is applied
**Then** medium armor and shields should be added to the character's armor proficiencies

**Acceptance criteria:**
- The proficiency JSON array is updated to include the new proficiencies
- Existing proficiencies are preserved (no duplicates, no removals)
- Works for all proficiency types: armor, weapon, tool, skill, language, saving throw

#### 6.4 Expertise grant: Skill Expert [ ]

**Given** a character proficient in Perception but not having expertise in it, and the "Skill Expert" feat
**When** the feat is applied with expertise choice "Perception"
**Then** "Perception" should be added to the character's skill expertises

**Acceptance criteria:**
- The chosen skill must already be in the character's skill proficiencies
- The skill is added to the `skillExpertises` JSON array
- An already-expert skill cannot be chosen again

#### 6.5 Speed bonus: Mobile [ ]

**Given** a character with speed 30, and the "Mobile" feat (+10 speed)
**When** the feat is applied
**Then** the character's speed should increase to 40

**Acceptance criteria:**
- Speed field is incremented by the feat's bonus value
- Stacks with any existing speed bonuses (e.g. racial traits)

#### 6.6 HP per level: Tough [ ]

**Given** a level 5 character with 38 HP, and the "Tough" feat (+2 HP per level)
**When** the feat is applied
**Then** the character's HP max should increase by 10 (2 * 5 levels) to 48

**Acceptance criteria:**
- The retroactive HP calculation uses the character's total level, not class level
- HP max and HP current are both increased
- If the character levels up later, the Tough bonus is already baked into the HP-per-level tracking

#### 6.7 Passive stat bonus: Observant [ ]

**Given** a character with passive Perception 13, and the "Observant" feat (+5 to passive Perception and Investigation)
**When** the feat is applied
**Then** the `passivePerceptionBonus` on the character should increase by 5

**Acceptance criteria:**
- The passive bonus is stored as a separate field, not baked into the score
- It stacks with any existing passive bonuses

#### 6.8 Initiative bonus: Alert [ ]

**Given** a character with initiative bonus +2 (from DEX 14), and the "Alert" feat (+5 initiative)
**When** the feat is applied
**Then** the character's `initiativeBonus` should increase by 5 to +7

**Acceptance criteria:**
- The initiative bonus field on the entity is incremented
- This stacks with the DEX modifier

#### 6.9 Resistance grant [ ]

**Given** a character with no damage resistances, and the "Dragon Hide" feat granting fire resistance
**When** the feat is applied
**Then** "fire" should appear in the character's `damageResistances`

**Acceptance criteria:**
- The resistance is appended to the damageResistances JSON array
- For feats with a choice of resistance (e.g. Dragon Ancestry), the chosen type is recorded
- Duplicate resistances are not added if the character already has that resistance

#### 6.10 Resource pool: Lucky [ ]

**Given** a character with no feat resources, and the "Lucky" feat (3 luck points per long rest)
**When** the feat is applied
**Then** a new feat resource entry should be created with name "Luck Points", maxUses 3, currentUses 3, resetOn "longRest"

**Acceptance criteria:**
- The resource appears in the `featResources` JSON array
- maxUses and currentUses are both set to the feat's defined amount
- resetOn is set based on the feat's recharge type

#### 6.11 Spell grant: Magic Initiate [ ]

**Given** a non-caster Fighter, and the "Magic Initiate" feat granting 2 cantrips and 1 first-level spell from the Wizard list
**When** the feat is applied with chosen spells
**Then** the chosen spells should be added to the character's `spellsKnown`

**Acceptance criteria:**
- The spells are tagged with source "Feat" and the feat name
- The cantrips and spell are added to the spellsKnown JSON
- The character doesn't gain spell slots from this feat — the spell can be cast once per long rest

#### 6.12 Optional feature: Eldritch Adept [ ]

**Given** a character and the "Eldritch Adept" feat which grants one Eldritch Invocation
**When** the feat is applied with a chosen invocation (e.g. "Devil's Sight")
**Then** the invocation should be added to the character's features list

**Acceptance criteria:**
- The invocation appears in the features JSON with source "Feat: Eldritch Adept"
- The invocation's description is included

#### 6.13 Reversal — ability score [ ]

**Given** a character who previously gained +1 STR from the "Heavily Armored" feat, currently at STR 16
**When** the feat is reversed (removed via level down)
**Then** STR should decrease back to 15

**Acceptance criteria:**
- The exact bonus that was applied is subtracted
- The ability score doesn't go below its pre-feat value
- The reversal uses the recorded choice to know which ability to decrement

#### 6.14 Reversal — proficiency [ ]

**Given** a character who gained medium armor proficiency from the "Moderately Armored" feat
**When** the feat is reversed
**Then** medium armor proficiency should be removed from the character's armor proficiencies

**Acceptance criteria:**
- Only the proficiencies granted by that specific feat are removed
- Proficiencies from other sources (class, race) are preserved
- Works across all proficiency types

#### 6.15 Reversal — HP per level: Tough [ ]

**Given** a level 5 character who gained +10 HP from the "Tough" feat
**When** the feat is reversed
**Then** HP max should decrease by 10 (back to the pre-Tough value)

**Acceptance criteria:**
- The retroactive HP is fully removed: 2 * current_level at time of removal
- HP current is also reduced (but not below 1)
- If the character is at full HP, both max and current decrease together

#### 6.16 Reversal — speed/initiative/passive [ ]

**Given** a character with +10 speed from Mobile, +5 initiative from Alert, and +5 passive Perception from Observant
**When** each feat is reversed individually
**Then** the corresponding stat should decrease by the feat's bonus amount

**Acceptance criteria:**
- Speed, initiative, and passive perception are each independently reversible
- Reversing one feat doesn't affect the others
- Stats return to their pre-feat values

#### 6.17 Reversal — resistance [ ]

**Given** a character with fire resistance granted by the "Dragon Hide" feat
**When** the feat is reversed
**Then** "fire" should be removed from `damageResistances`

**Acceptance criteria:**
- Only the feat-granted resistance is removed
- If the character has fire resistance from another source (race), it should remain
- The resistance type to remove is looked up from the feat's recorded effects

#### 6.18 Reversal — spell grant [ ]

**Given** a character who gained 2 cantrips and 1 spell from "Magic Initiate"
**When** the feat is reversed
**Then** those specific spells should be removed from `spellsKnown`

**Acceptance criteria:**
- Only spells tagged with source "Feat: Magic Initiate" are removed
- Class-learned spells, race spells, and other feat spells are preserved
- The feat resource (if any, e.g. once-per-long-rest casting) is also removed

#### 6.19 Reversal symmetry: apply then reverse restores identical state [ ]

**Given** a character with a known, serialized state before any feat is applied
**When** any feat is applied and then immediately reversed
**Then** the character's serialized state should be byte-for-byte identical to the original

**Acceptance criteria:**
- This is a property-based test: every field on the character entity matches the original
- Covers all feat effect types: ASI, proficiency, speed, HP, initiative, resistance, spells, resources
- JSON field ordering may differ, so comparison should be semantic, not string-based
- At minimum, test with Alert, Tough, Skill Expert, and Magic Initiate as representative feats

---

### 7. CharacterJsonHelper (0 existing — 16 new)

All JSONB mutation logic lives here. Incorrect JSON manipulation corrupts character data silently.

#### 7.1 appendFeatures: adds features to existing array [ ]

**Given** a character with existing features `[{"name":"Second Wind","source":"Fighter","level":1}]`
**When** appendFeatures is called with `[{"name":"Action Surge","source":"Fighter","level":2}]`
**Then** the features JSON should contain both "Second Wind" and "Action Surge"

**Acceptance criteria:**
- The existing feature is preserved
- The new feature is appended to the end of the array
- The resulting JSON is valid and parseable

#### 7.2 appendFeatures: handles null/empty existing features [ ]

**Given** a character with null features field
**When** appendFeatures is called with `[{"name":"Second Wind","source":"Fighter","level":1}]`
**Then** the features JSON should be a single-element array with "Second Wind"

**Acceptance criteria:**
- Null is treated as an empty array, not an error
- Empty string is also handled the same way
- The result is a valid JSON array

#### 7.3 removeFeatures: removes matching features by name/source [ ]

**Given** a character with features `[{"name":"Second Wind","source":"Fighter","level":1},{"name":"Action Surge","source":"Fighter","level":2}]`
**When** removeFeatures is called to remove features from Fighter at level 2
**Then** only "Action Surge" should be removed; "Second Wind" remains

**Acceptance criteria:**
- Removal matches on both name/source and level
- Non-matching features are untouched
- If no features match, the array is returned unchanged

#### 7.4 updateHitDiceMap: increment on level up [ ]

**Given** a hit dice map `{"Fighter": {"count": 3, "sides": 10}}`
**When** updateHitDiceMap is called to increment Fighter
**Then** the map should show `{"Fighter": {"count": 4, "sides": 10}}`

**Acceptance criteria:**
- The count for the specified class is incremented by 1
- The sides value is unchanged
- Other classes in the map are unaffected

#### 7.5 updateHitDiceMap: decrement on level down [ ]

**Given** a hit dice map `{"Fighter": {"count": 4, "sides": 10}}`
**When** updateHitDiceMap is called to decrement Fighter
**Then** the map should show `{"Fighter": {"count": 3, "sides": 10}}`

**Acceptance criteria:**
- The count decreases by 1
- Count cannot go below 0

#### 7.6 updateHitDiceMap: remove class entry at count 0 [ ]

**Given** a hit dice map `{"Fighter": {"count": 3, "sides": 10}, "Wizard": {"count": 1, "sides": 6}}`
**When** updateHitDiceMap is called to decrement Wizard
**Then** the Wizard entry should be removed entirely, leaving only Fighter

**Acceptance criteria:**
- When count reaches 0, the entire class entry is removed from the map
- Fighter entry is untouched
- This simulates fully removing a multiclass dip via level down

#### 7.7 buildHitDiceTotal: multi-class format [ ]

**Given** a hit dice map `{"Fighter": {"count": 2, "sides": 10}, "Wizard": {"count": 3, "sides": 6}}`
**When** buildHitDiceTotal is called
**Then** the result should be "2d10 + 3d6"

**Acceptance criteria:**
- Format is "{count}d{sides}" for each class, joined with " + "
- Ordering should be consistent (e.g. alphabetical by class name)

#### 7.8 buildHitDiceTotal: single class format [ ]

**Given** a hit dice map `{"Fighter": {"count": 5, "sides": 10}}`
**When** buildHitDiceTotal is called
**Then** the result should be "5d10"

**Acceptance criteria:**
- No " + " separator for a single class
- Format is simply "{count}d{sides}"

#### 7.9 updateMulticlassEntries: add new class entry [ ]

**Given** a character with multiclass entries `[{"classId":"fighter-id","className":"Fighter","level":3}]`
**When** a new class (Wizard) is added via multiclass
**Then** the entries should include both Fighter and Wizard

**Acceptance criteria:**
- The new entry has the correct classId, className, and level (1 for a brand new class)
- The existing Fighter entry is unchanged
- The JSON array now has 2 elements

#### 7.10 updateMulticlassEntries: increment existing class level [ ]

**Given** a character with multiclass entries including `{"classId":"wizard-id","className":"Wizard","level":1}`
**When** the character takes another Wizard level
**Then** the Wizard entry's level should increase to 2

**Acceptance criteria:**
- The existing entry is updated in place, not duplicated
- Only the level field changes; classId and className remain the same

#### 7.11 rebuildMulticlassEntries: rebuild after level down [ ]

**Given** a character with Wizard 3 / Fighter 2 multiclass entries and a class level map after level down
**When** rebuildMulticlassEntries is called with the updated map `{"Wizard": 3, "Fighter": 1}`
**Then** the entries should reflect Fighter at level 1 (not 2)

**Acceptance criteria:**
- Each entry's level matches the updated map
- Classes with level 0 are removed entirely
- The rebuild preserves classId, className, subclassId, and other metadata

#### 7.12 mergeJsonArray: merge without duplicates [ ]

**Given** existing proficiencies `["Athletics","Perception"]` and new proficiencies `["Perception","Stealth"]`
**When** mergeJsonArray is called
**Then** the result should be `["Athletics","Perception","Stealth"]`

**Acceptance criteria:**
- Duplicates are eliminated (case-sensitive match)
- Original order is preserved for existing items
- New unique items are appended

#### 7.13 appendLevelHistory: adds correct entry [ ]

**Given** a character with existing level history for levels 1 and 2
**When** appendLevelHistory is called for level 3 with HP gained 8, class "Fighter", and features ["Martial Archetype"]
**Then** a new entry should be appended with characterLevel 3, classLevel 3, hpGained 8, and the feature list

**Acceptance criteria:**
- The entry includes both character level and class level (these differ in multiclass)
- HP gained is the exact amount added at this level
- Features are the names of features gained at this level
- The existing entries for levels 1 and 2 are untouched

#### 7.14 recordAsiInHistory: ASI choice recorded [ ]

**Given** a level 4 entry in the level history with no ASI choice yet
**When** recordAsiInHistory is called with choice `{"type":"ability","increases":[{"ability":"strength","bonus":2}]}`
**Then** the level 4 history entry should include the ASI choice data

**Acceptance criteria:**
- The ASI is recorded on the correct level entry (level 4, not any other)
- The choice type ("ability" or "feat") and details are preserved
- This data is used for reversal when levelling down

#### 7.15 recordFeatInHistory: feat choice recorded [ ]

**Given** a level 4 entry in the level history
**When** recordFeatInHistory is called with feat "Alert" and effects `{initiativeBonus: 5}`
**Then** the level 4 entry should include the feat name and its applied effects

**Acceptance criteria:**
- The feat name and all applied effects are serialized into the history entry
- On level down, this data tells FeatEffectResolver exactly what to reverse
- Multiple feat choices at the same level (if any) are stored as a list

#### 7.16 updateMulticlassEntrySubclass: sets subclass [ ]

**Given** multiclass entries including `{"classId":"fighter-id","className":"Fighter","level":3,"subclassId":null}`
**When** updateMulticlassEntrySubclass is called with classId "fighter-id" and subclassId "champion-id"
**Then** the Fighter entry should now have subclassId "champion-id"

**Acceptance criteria:**
- Only the matching class entry is updated
- Other multiclass entries are untouched
- Setting a subclass on a class that already has one overwrites the previous value

---

### 8. CharacterMapper (0 existing — 4 new)

#### 8.1 toResponse: maps all scalar fields [ ]

**Given** a PlayerCharacter entity with name "Thorin", level 5, HP 45/45, STR 16, DEX 12, CON 14
**When** toResponse is called
**Then** the CharacterResponse should have all those values in the matching fields

**Acceptance criteria:**
- Name, level, hpMax, hpCurrent, and all six ability scores are copied
- The userId comes from the associated User entity (user.getId())
- The ownerDisplayName comes from user.getDisplayName()

#### 8.2 toResponse: parses JSONB strings into response fields [ ]

**Given** a PlayerCharacter entity with multiclassEntries, levelHistory, and hitDiceMap stored as JSONB strings
**When** toResponse is called
**Then** those fields should appear as their string (JSON) representation in the response

**Acceptance criteria:**
- JSONB fields that are raw strings are passed through as-is
- @JsonRawValue fields that come back as pre-parsed objects are also handled correctly
- The response includes multiclassEntries, levelHistory, hitDiceMap, features, spellsKnown

#### 8.3 toResponse: handles null JSONB fields gracefully [ ]

**Given** a PlayerCharacter entity with null features, null spellsKnown, and null multiclassEntries
**When** toResponse is called
**Then** those fields should be null in the response, not throw an exception

**Acceptance criteria:**
- Null JSONB fields produce null in the response
- No NullPointerException is thrown
- All other fields are still mapped correctly

#### 8.4 toResponse: includes subclass always-prepared spells [ ]

**Given** a level 5 Cleric (Life Domain) where the Life Domain subclass has always-prepared spells at levels 3 and 5
**When** toResponse is called
**Then** the `subclassAlwaysPreparedSpells` field should contain the Life Domain's spells organized by subclass name

**Acceptance criteria:**
- The mapper queries SubclassRepository for the subclass's always-prepared spells
- For multiclass characters with subclasses in multiple classes, spells from all subclasses are merged
- If the character has no subclass, the field is null

---

### 9. DiceRoller (0 existing — 8 new)

Pure functions, easy to test, foundational for combat.

#### 9.1 roll("1d6"): basic roll [ ]

**Given** a dice expression "1d6"
**When** the expression is rolled
**Then** the total should be between 1 and 6, with modifier 0

**Acceptance criteria:**
- The total is within [1, 6] inclusive
- diceCount is 1, diceSides is 6
- Modifier is 0 (no modifier specified)
- Run multiple times to verify range (statistical test)

#### 9.2 roll("2d6+3"): multiple dice with positive modifier [ ]

**Given** a dice expression "2d6+3"
**When** the expression is rolled
**Then** the total should be between 5 and 15, with modifier 3

**Acceptance criteria:**
- The minimum is 2 (from dice) + 3 (modifier) = 5
- The maximum is 12 (from dice) + 3 (modifier) = 15
- diceCount is 2, diceSides is 6, modifier is 3

#### 9.3 roll("1d20-2"): negative modifier [ ]

**Given** a dice expression "1d20-2"
**When** the expression is rolled
**Then** the total should be between -1 and 18, with modifier -2

**Acceptance criteria:**
- Negative modifiers are supported
- Total can be negative (1 - 2 = -1)
- modifier is stored as -2

#### 9.4 roll("1d8"): parses dice count and sides [ ]

**Given** a dice expression "1d8"
**When** the expression is parsed
**Then** diceCount should be 1 and diceSides should be 8

**Acceptance criteria:**
- The parser correctly splits the expression on "d"
- Works for all standard D&D dice: d4, d6, d8, d10, d12, d20, d100

#### 9.5 rollCritical: doubles the dice count [ ]

**Given** a dice expression "1d8+3"
**When** a critical roll is made
**Then** diceCount should be doubled to 2, making the effective expression "2d8+3"

**Acceptance criteria:**
- Only the dice count is doubled, not the modifier
- "1d8+3" critical → 2d8+3 (min 5, max 19)
- "2d6+4" critical → 4d6+4

#### 9.6 rollCritical: modifier is NOT doubled [ ]

**Given** a dice expression "1d10+5"
**When** a critical roll is made
**Then** the modifier should remain +5, not +10

**Acceptance criteria:**
- Critical hit doubles dice (1d10 → 2d10), keeps modifier unchanged (+5)
- This matches PHB critical hit rules: "roll all of the attack's damage dice twice"
- Total range is 2+5=7 to 20+5=25, not 2+10=12 to 20+10=30

#### 9.7 roll with zero modifier [ ]

**Given** a dice expression "1d20" (no modifier)
**When** the expression is rolled
**Then** modifier should be 0 and total should equal the raw dice result

**Acceptance criteria:**
- No "+" or "-" in the expression means modifier is 0
- Total is purely from the dice

#### 9.8 Invalid expression: graceful error handling [ ]

**Given** an invalid dice expression like "abc", "", or "d6d8"
**When** the expression is parsed
**Then** an appropriate error should be thrown or a default result returned

**Acceptance criteria:**
- Null or empty strings do not crash
- Malformed expressions (missing dice count, invalid characters) are handled
- The error message is descriptive enough to debug

---

### 10. CombatService (0 existing — 32 new, mock-based)

Most user-facing and most rule-complex code. All tests mock the repositories.

#### 10.1 Normal damage reduces HP [ ]

**Given** a Goblin participant with 7 HP and 0 temp HP in an active encounter
**When** 3 points of slashing damage are applied
**Then** the Goblin's HP should be reduced to 4

**Acceptance criteria:**
- Damage is subtracted from hpCurrent
- A combat log entry records "Goblin takes 3 slashing damage"
- The participant remains alive (isAlive = true)

#### 10.2 Damage with temp HP: absorbs first [ ]

**Given** a Fighter participant with 30 HP, 10 temp HP in an active encounter
**When** 15 points of damage are applied
**Then** temp HP should drop to 0, and real HP should drop to 25 (15 - 10 = 5 overflow → 30 - 5 = 25)

**Acceptance criteria:**
- Temp HP absorbs as much damage as possible before real HP takes any
- Temp HP cannot go below 0
- Total actual damage recorded includes both temp HP and real HP absorbed
- If damage is less than temp HP, real HP is untouched

#### 10.3 Damage to 0 HP: monster killed, PC enters dying [ ]

**Given** a Goblin (monster) with 3 HP and a PC Fighter with 3 HP, both in an active encounter
**When** 5 points of damage are applied to each
**Then** the Goblin should be killed (isAlive = false) and the Fighter should enter dying state

**Acceptance criteria:**
- Monster: HP set to 0, isAlive = false (monsters die outright at 0 HP)
- PC: HP set to 0, isAlive = false (represents unconscious), deathSaveSuccesses = 0, deathSaveFailures = 0
- PCs do not die at 0 HP — they start making death saves
- Concentration is dropped for both (if concentrating)

#### 10.4 Massive damage: PC instant death [ ]

**Given** a Fighter PC with 30 HP max and 15 HP current in an encounter
**When** 45 points of damage are applied (15 current + 30 max = 45, excess equals max HP)
**Then** the Fighter should be instantly killed with 3 death save failures

**Acceptance criteria:**
- If remaining damage after hitting 0 HP >= the character's HP max, instant death occurs
- deathSaveFailures is set to 3 immediately
- The combat log records "massive damage — instant death"
- This only applies to PCs — monsters just die at 0 HP regardless

#### 10.5 Damage to dying PC: adds death save failure [ ]

**Given** a Fighter PC already at 0 HP with 1 death save failure
**When** 5 points of damage are applied to the dying PC
**Then** death save failures should increase to 2

**Acceptance criteria:**
- Any damage to a dying (0 HP) PC counts as a failed death save
- A critical hit (melee attack on unconscious target) counts as 2 failures instead of 1
- If failures reach 3, the PC dies

#### 10.6 Damage to concentrating creature: triggers concentration check [ ]

**Given** a Wizard PC concentrating on a spell and currently at 30 HP
**When** 14 points of damage are applied
**Then** a concentration check should be triggered with DC 10 (max of 10, 14/2=7)

**Acceptance criteria:**
- The concentration DC is max(10, floor(damage/2))
- A CON saving throw is rolled against that DC
- If the save fails, concentration is dropped and related conditions are removed
- If the save passes, concentration is maintained

#### 10.7 Healing capped at maxHp [ ]

**Given** a Fighter with 20/45 HP (current/max)
**When** 30 points of healing are applied
**Then** HP should increase to 45 (not 50)

**Acceptance criteria:**
- Healing never causes HP to exceed maxHp
- The actual amount healed is 25 (45 - 20), not 30
- The combat log records the actual amount healed

#### 10.8 Healing a dying PC: revives [ ]

**Given** a Fighter PC at 0 HP with 2 death save successes and 1 failure, isAlive = false
**When** 5 points of healing are applied
**Then** the Fighter should be revived with 5 HP, death saves reset, isAlive = true

**Acceptance criteria:**
- HP is set to the healing amount (0 + 5 = 5)
- Death save successes and failures are both reset to 0
- isAlive becomes true
- The Unconscious condition (if tracked) is cleared

#### 10.9 Healing a dead PC: revives with Prone [ ]

**Given** a Fighter PC at 0 HP with 3 death save failures (dead)
**When** healing magic is applied (e.g. Revivify)
**Then** the Fighter should be revived with HP from the healing, death saves cleared, and Prone condition added

**Acceptance criteria:**
- Dead PCs (3 failures) can still be healed back
- Death save counters reset to 0
- The Prone condition is added (they were lying dead on the ground)
- Unconscious condition is removed

#### 10.10 Death save: roll 10+ is a success [ ]

**Given** a dying PC with 0 death save successes
**When** a death save is rolled and the d20 result is 14
**Then** death save successes should increase to 1

**Acceptance criteria:**
- A roll of 10 or higher counts as a success
- If successes reach 3, the PC stabilises (no longer rolling death saves)
- The combat log records the death save result

#### 10.11 Death save: roll < 10 is a failure [ ]

**Given** a dying PC with 0 death save failures
**When** a death save is rolled and the d20 result is 7
**Then** death save failures should increase to 1

**Acceptance criteria:**
- A roll of 9 or lower counts as a failure
- If failures reach 3, the PC dies
- Roll of exactly 10 is a success, not a failure (boundary check)

#### 10.12 Death save: natural 20 revives [ ]

**Given** a dying PC at 0 HP with 1 success and 2 failures
**When** a death save is rolled and the d20 result is a natural 20
**Then** the PC should regain 1 HP, all death saves should reset, and they are revived

**Acceptance criteria:**
- HP becomes 1 (regardless of previous death save state)
- Both successes and failures reset to 0
- isAlive becomes true
- This overrides the normal "3 successes to stabilise" rule

#### 10.13 Death save: natural 1 is two failures [ ]

**Given** a dying PC with 1 death save failure
**When** a death save is rolled and the d20 result is a natural 1
**Then** death save failures should increase by 2 (from 1 to 3), killing the PC

**Acceptance criteria:**
- Natural 1 counts as 2 failures, not 1
- If this brings failures to 3 or more, the PC dies immediately
- The combat log records "Natural 1 — 2 death save failures"

#### 10.14 Attack roll: normal hit [ ]

**Given** an attacker with +5 to hit targeting a creature with AC 15
**When** an attack roll is made and the d20 shows 12 (total 17)
**Then** the attack should hit (17 >= 15)

**Acceptance criteria:**
- Hit is determined by comparing d20 + modifier to target AC
- Total >= AC is a hit, total < AC is a miss
- Damage is rolled and applied on a hit

#### 10.15 Attack roll: normal miss [ ]

**Given** an attacker with +5 to hit targeting a creature with AC 18
**When** an attack roll is made and the d20 shows 8 (total 13)
**Then** the attack should miss (13 < 18)

**Acceptance criteria:**
- No damage is dealt on a miss
- The combat log records "Miss" with the roll total
- The target's HP is unchanged

#### 10.16 Attack roll: natural 20 always hits with critical damage [ ]

**Given** an attacker with +2 to hit targeting a creature with AC 25
**When** an attack roll is made and the d20 shows 20
**Then** the attack should hit regardless of AC, and damage dice should be doubled

**Acceptance criteria:**
- Natural 20 always hits, even if total (22) is below AC (25)
- Damage dice are doubled (1d8 becomes 2d8), but modifier is not doubled
- The combat log marks this as a "Critical Hit"

#### 10.17 Attack roll: natural 1 always misses [ ]

**Given** an attacker with +15 to hit targeting a creature with AC 10
**When** an attack roll is made and the d20 shows 1
**Then** the attack should miss regardless of the total (16)

**Acceptance criteria:**
- Natural 1 always misses, even if total exceeds AC
- No damage is dealt
- The combat log marks this as a "Critical Miss"

#### 10.18 Attack roll: advantage [ ]

**Given** an attacker rolling with advantage
**When** two d20s are rolled showing 8 and 15
**Then** the higher roll (15) should be used for the attack

**Acceptance criteria:**
- Two dice are rolled and the higher value is selected
- The modifier is applied to the higher roll only
- Both rolls should be visible in the combat log for transparency

#### 10.19 Attack roll: disadvantage [ ]

**Given** an attacker rolling with disadvantage
**When** two d20s are rolled showing 8 and 15
**Then** the lower roll (8) should be used for the attack

**Acceptance criteria:**
- Two dice are rolled and the lower value is selected
- Advantage and disadvantage cancel each other out if both apply

#### 10.20 Attack roll: force crit toggle [ ]

**Given** an attacker with force-crit enabled (DM override)
**When** the attack roll is made with any d20 result
**Then** the attack should be treated as a critical hit with doubled damage dice

**Acceptance criteria:**
- The forceCrit flag overrides the actual d20 roll
- Damage dice are doubled as if a natural 20 were rolled
- The d20 roll still determines hit/miss for AC purposes (or auto-hits if you prefer)

#### 10.21 Attack roll: multi-attack [ ]

**Given** an attacker with 3 attacks per action
**When** a multi-attack action is performed
**Then** 3 separate attack rolls should be made, each independently hitting or missing

**Acceptance criteria:**
- Each attack has its own d20 roll, hit/miss determination, and damage calculation
- A critical on one attack doesn't affect the others
- All results are recorded in the combat log in order

#### 10.22 Concentration check: CON save vs DC [ ]

**Given** a Wizard concentrating on a spell with CON 12 (+1 modifier, not proficient in CON saves)
**When** the Wizard takes 20 damage (DC = max(10, 20/2) = 10)
**Then** a CON saving throw should be rolled against DC 10

**Acceptance criteria:**
- DC is calculated as max(10, floor(damage / 2))
- For 20 damage, DC is 10 (20/2 = 10, max(10,10) = 10)
- For 30 damage, DC would be 15 (30/2 = 15, max(10,15) = 15)
- The save roll is d20 + CON modifier

#### 10.23 Concentration check includes CON save proficiency [ ]

**Given** a Sorcerer concentrating on a spell with CON 14 (+2 modifier) and proficiency in CON saves, proficiency bonus +3
**When** a concentration check is made
**Then** the CON save roll should include the proficiency bonus (d20 + 2 + 3 = d20 + 5)

**Acceptance criteria:**
- If the character has CON save proficiency, the proficiency bonus is added to the roll
- The proficiency status is checked against the character's saving throw proficiencies
- Total save = d20 + CON modifier + (proficiency bonus if proficient)

#### 10.24 Failed concentration: drops concentration and removes conditions [ ]

**Given** a Wizard concentrating on "Hold Person" which applied the Paralyzed condition to a target
**When** the Wizard fails a concentration check
**Then** the Wizard's concentration should be cleared, and the Paralyzed condition on the target should be removed

**Acceptance criteria:**
- The concentrating flag is cleared on the caster
- Conditions tied to that spell are removed from their targets
- The combat log records "Wizard loses concentration on Hold Person"

#### 10.25 Dropping to 0 HP auto-drops concentration [ ]

**Given** a Wizard concentrating on Haste with 5 HP remaining
**When** the Wizard takes 10 damage (drops to 0 HP)
**Then** concentration should be dropped automatically, without rolling a concentration check

**Acceptance criteria:**
- No concentration save is rolled — dropping to 0 auto-fails
- Concentration is cleared before death saves begin
- Any spell-related conditions are removed from targets

#### 10.26 Add condition with duration [ ]

**Given** a creature in an active encounter with no conditions
**When** the "Frightened" condition is applied with a duration of 3 rounds
**Then** the creature should have the Frightened condition with 3 rounds remaining

**Acceptance criteria:**
- The condition appears in the participant's conditions list
- Duration is stored as rounds remaining (not end-round number)
- The condition name and any source information are preserved

#### 10.27 Condition auto-expires after N rounds [ ]

**Given** a creature with the "Blinded" condition applied 2 rounds ago with duration 2
**When** the creature's turn starts at the beginning of the 3rd round
**Then** the Blinded condition should be automatically removed

**Acceptance criteria:**
- Expiry is checked at the start of the creature's turn
- The combat log records "Blinded expires on [creature]"
- Other conditions with remaining duration are decremented but not removed

#### 10.28 Remove condition manually [ ]

**Given** a creature with the "Restrained" and "Poisoned" conditions
**When** the DM manually removes the "Restrained" condition
**Then** only "Restrained" should be removed; "Poisoned" remains

**Acceptance criteria:**
- Removal targets a specific condition by name (or ID)
- Other conditions on the same creature are untouched

#### 10.29 Multiple conditions on same creature [ ]

**Given** a creature with no conditions
**When** "Poisoned" and "Prone" are both applied
**Then** the creature should have both conditions listed

**Acceptance criteria:**
- A creature can have multiple simultaneous conditions
- Each condition tracks its own duration independently
- Duplicate conditions of the same type are allowed (different sources/durations)

#### 10.30 Advance turn: next participant in initiative order [ ]

**Given** an encounter with participants in initiative order: Fighter (18), Wizard (12), Goblin (8), and it's currently the Fighter's turn
**When** the turn is advanced
**Then** it should become the Wizard's turn

**Acceptance criteria:**
- Turn advances to the next participant in descending initiative order
- The current participant index increments by 1
- The "active turn" indicator moves to the Wizard

#### 10.31 Advance turn at end of round: wrap and increment round [ ]

**Given** an encounter in round 1, currently on the last participant's turn (Goblin at initiative 8)
**When** the turn is advanced
**Then** the round counter should increment to 2, and it should be the Fighter's turn again (first in initiative)

**Acceptance criteria:**
- The round counter increases by 1
- The turn wraps back to the first participant
- Condition durations that expire "at the start of your turn" are checked for the first participant

#### 10.32 Previous turn: move back [ ]

**Given** an encounter currently on the Wizard's turn (second in initiative)
**When** the turn is moved back
**Then** it should become the Fighter's turn (first in initiative)

**Acceptance criteria:**
- Turn index decrements by 1
- If at the beginning of the round, it wraps to the last participant of the previous round
- The round counter decrements if wrapping backwards

#### 10.33 useSpellSlot: decrement available slot [ ]

**Given** a caster with 3 available 1st-level spell slots
**When** a 1st-level spell slot is used
**Then** available 1st-level slots should decrease to 2

**Acceptance criteria:**
- Only the specified spell level's slot count decreases
- Other spell level slots are unaffected
- The encounter participant's spell slot data is updated

#### 10.34 restoreSpellSlot: increment available slot [ ]

**Given** a caster with 1 available 2nd-level spell slot (out of 3 max)
**When** a 2nd-level spell slot is restored
**Then** available 2nd-level slots should increase to 2

**Acceptance criteria:**
- Available slots cannot exceed the maximum for that level
- Other spell level slots are unaffected

#### 10.35 Cannot use slot below 0 [ ]

**Given** a caster with 0 available 3rd-level spell slots
**When** a 3rd-level spell slot use is attempted
**Then** the operation should be rejected

**Acceptance criteria:**
- An error or exception is thrown
- Available slots remain at 0
- The combat log does not record a spell use

---

### 11. EncounterService (0 existing — 12 new, mock-based)

#### 11.1 Create encounter: sets status and generates session code [ ]

**Given** a DM user creating a new encounter for their campaign
**When** the encounter is created
**Then** the status should be PREPARING and a unique 6-character session code should be generated

**Acceptance criteria:**
- Status is PREPARING (not ACTIVE or COMPLETED)
- Session code is alphanumeric, uppercase, 6 characters
- The DM's userId is associated with the encounter

#### 11.2 Add monster participant: auto-populates from monster stats [ ]

**Given** an encounter in PREPARING status and a monster "Goblin" with HP 7, AC 15, DEX mod +2, quantity 3
**When** 3 Goblins are added
**Then** 3 participants should be created named "Goblin 1", "Goblin 2", "Goblin 3" with HP 7, AC 15, initiative bonus +2

**Acceptance criteria:**
- Each participant gets the monster's base stats
- Names are auto-suffixed with incrementing numbers when quantity > 1
- HP is set to both hpMax and hpCurrent
- The monsterId FK links back to the monster reference data

#### 11.3 Add PC participant: auto-populates from character sheet [ ]

**Given** an encounter in PREPARING status and a character "Thorin" (Fighter, HP 45, AC 18, initiative +2)
**When** the PC is added to the encounter
**Then** a participant should be created with Thorin's current stats

**Acceptance criteria:**
- HP, AC, and initiative bonus are copied from the character sheet
- The characterId FK links back to the PlayerCharacter entity
- participantType is PLAYER (not MONSTER)

#### 11.4 Remove participant [ ]

**Given** an encounter with 4 participants including "Goblin 2"
**When** "Goblin 2" is removed
**Then** the encounter should have 3 participants, and "Goblin 2" should be gone

**Acceptance criteria:**
- The specific participant is deleted
- Other participants are not affected
- Initiative order is preserved for remaining participants

#### 11.5 Rename participant [ ]

**Given** an encounter with a participant named "Goblin 1"
**When** the DM renames it to "Snaggle the Goblin"
**Then** the displayName should be updated, but monsterId should remain unchanged

**Acceptance criteria:**
- Only the display name changes
- The reference to the monster data is preserved for stat lookups
- Empty or null names should be rejected

#### 11.6 Set initiatives: bulk update [ ]

**Given** an encounter with 4 participants, each with unset initiative values
**When** initiatives are set in bulk: Fighter=18, Wizard=12, Goblin 1=15, Goblin 2=8
**Then** each participant's initiative should be updated to the provided values

**Acceptance criteria:**
- All participants are updated in a single operation
- Missing participants in the bulk update are left unchanged
- Initiative values can be any integer (including negative for very low DEX)

#### 11.7 Roll all initiatives [ ]

**Given** an encounter with 3 participants: Fighter (init bonus +3), Wizard (+1), Goblin (+2)
**When** all initiatives are rolled
**Then** each participant should have an initiative value of d20 + their initiative bonus

**Acceptance criteria:**
- Each participant's initiative is independently rolled
- The result is d20 + initiativeBonus for each
- Results are stored on each participant's initiative field

#### 11.8 Start encounter: PREPARING → ACTIVE, sorted by initiative [ ]

**Given** an encounter in PREPARING status with participants having initiative values: Wizard 12, Fighter 18, Goblin 8
**When** the encounter is started
**Then** status should change to ACTIVE, participants should be sorted by initiative descending, and the first participant (Fighter, init 18) should be the current turn

**Acceptance criteria:**
- Status changes from PREPARING to ACTIVE
- Participants are reordered: Fighter (18), Wizard (12), Goblin (8)
- currentTurnIndex is set to 0 (first participant)
- Round counter is set to 1

#### 11.9 Start encounter fails if no participants [ ]

**Given** an encounter in PREPARING status with 0 participants
**When** starting the encounter is attempted
**Then** the operation should be rejected with an error

**Acceptance criteria:**
- An exception is thrown with a clear message about needing participants
- Status remains PREPARING
- At least one participant is required to start

#### 11.10 Pause/resume encounter: status transitions [ ]

**Given** an encounter in ACTIVE status
**When** the encounter is paused, then resumed
**Then** status should go ACTIVE → PAUSED → ACTIVE, preserving turn order and round count

**Acceptance criteria:**
- Pause sets status to PAUSED
- Resume sets status back to ACTIVE
- Current turn, round, and all participant states are preserved across pause/resume
- Cannot pause an already-paused encounter

#### 11.11 End encounter: status → COMPLETED [ ]

**Given** an encounter in ACTIVE status in round 5
**When** the DM ends the encounter
**Then** status should change to COMPLETED

**Acceptance criteria:**
- Status is set to COMPLETED
- The encounter data is preserved (participants, logs, round count)
- No further combat actions can be performed on a COMPLETED encounter

#### 11.12 Session code lookup [ ]

**Given** an encounter with session code "ABC123"
**When** getEncounterBySessionCode("ABC123") is called
**Then** the correct encounter should be returned

**Acceptance criteria:**
- Lookup is case-insensitive (or consistently uppercased)
- Non-existent session codes return null or throw a not-found error
- Session codes are unique across all encounters

---

### 12. AuthService (0 existing — 6 new)

#### 12.1 Register: creates user and returns tokens [ ]

**Given** a registration request with username "thorin", email "thorin@shire.com", password "Mithril123!"
**When** the user is registered
**Then** a new user should be created, and both access and refresh tokens should be returned

**Acceptance criteria:**
- The user entity is persisted with hashed password (not plaintext)
- An access token and refresh token are generated
- The tokens contain the user's ID and username as claims

#### 12.2 Register: duplicate username fails [ ]

**Given** an existing user with username "thorin"
**When** another registration with username "thorin" is attempted
**Then** the registration should fail with a duplicate username error

**Acceptance criteria:**
- The error message indicates the username is already taken
- No new user is created
- The existing user's data is not modified

#### 12.3 Login: correct credentials return tokens [ ]

**Given** an existing user "thorin" with password "Mithril123!"
**When** login is attempted with correct credentials
**Then** access and refresh tokens should be returned

**Acceptance criteria:**
- Password is verified against the stored hash
- Both tokens are freshly generated (not reused from a previous session)
- The access token has a short expiry (minutes/hours)

#### 12.4 Login: wrong password fails [ ]

**Given** an existing user "thorin" with password "Mithril123!"
**When** login is attempted with password "WrongPassword"
**Then** authentication should fail with a generic error

**Acceptance criteria:**
- The error message does not reveal whether the username or password was wrong
- No tokens are returned
- Failed attempts don't lock the account (for now)

#### 12.5 Refresh: valid refresh token returns new access token [ ]

**Given** a valid, non-expired refresh token for user "thorin"
**When** the refresh endpoint is called
**Then** a new access token should be returned

**Acceptance criteria:**
- The new access token has a fresh expiry time
- The refresh token is validated before issuing a new access token
- The user's ID is extracted from the refresh token and embedded in the new access token

#### 12.6 Refresh: expired refresh token fails [ ]

**Given** a refresh token that has expired
**When** the refresh endpoint is called
**Then** the request should be rejected with an authentication error

**Acceptance criteria:**
- Expired tokens are caught during validation
- The error response indicates the token is expired
- The user must re-authenticate via login

---

### 13. JwtTokenProvider (0 existing — 4 new)

#### 13.1 generateAccessToken: valid JWT with claims [ ]

**Given** a user with ID "550e8400-e29b-41d4-a716-446655440000" and username "thorin"
**When** an access token is generated
**Then** the JWT should be valid and contain the userId and username claims

**Acceptance criteria:**
- The token is a properly formatted JWT (header.payload.signature)
- The subject claim contains the userId
- The username is available as a custom claim
- The token has an expiry timestamp set

#### 13.2 validateToken: valid and expired/malformed [ ]

**Given** a freshly generated token and an expired token
**When** validateToken is called on each
**Then** the fresh token should return true, the expired token should return false

**Acceptance criteria:**
- Valid, non-expired tokens return true
- Expired tokens return false
- Malformed strings (not JWT format) return false
- Tokens signed with a different secret return false

#### 13.3 getUserIdFromToken: extracts UUID [ ]

**Given** a valid JWT containing userId "550e8400-e29b-41d4-a716-446655440000"
**When** getUserIdFromToken is called
**Then** the UUID "550e8400-e29b-41d4-a716-446655440000" should be returned

**Acceptance criteria:**
- The UUID is correctly parsed from the token's subject claim
- The return type is a UUID, not a String

#### 13.4 getUsernameFromToken: extracts username [ ]

**Given** a valid JWT containing username "thorin"
**When** getUsernameFromToken is called
**Then** "thorin" should be returned

**Acceptance criteria:**
- The username is extracted from the custom claim
- Works for usernames with special characters (underscores, numbers)

---

### 14. CampaignService (0 existing — 4 new)

#### 14.1 Create campaign: DM auto-added as member [ ]

**Given** a DM user creating a campaign named "Mines of Phandelver"
**When** the campaign is created
**Then** the DM should be automatically added as a member, and a unique invite code should be generated

**Acceptance criteria:**
- The campaign entity is persisted with the DM's userId
- A CampaignMember entry is created linking the DM to the campaign
- The invite code is 6-8 characters, alphanumeric, uppercase

#### 14.2 Join campaign: player added, no duplicate joins [ ]

**Given** a campaign with invite code "ABC123" and a player who is not yet a member
**When** the player joins using the invite code
**Then** the player should be added as a campaign member

**And when** the same player tries to join again
**Then** the second join should fail with a "already a member" error

**Acceptance criteria:**
- First join creates a new CampaignMember entry
- Second join is rejected — no duplicate membership rows
- The error message is clear about already being a member

#### 14.3 Join campaign: invalid invite code fails [ ]

**Given** a non-existent invite code "ZZZZZ9"
**When** a player tries to join using that code
**Then** the operation should fail with a "campaign not found" error

**Acceptance criteria:**
- Invalid codes do not create any membership entries
- The error message does not reveal whether the code is unused vs. nonexistent

#### 14.4 getMyCampaigns: returns only member campaigns [ ]

**Given** 3 campaigns total, where User A is a member of 2 and not a member of 1
**When** getMyCampaigns is called for User A
**Then** only the 2 campaigns where User A is a member should be returned

**Acceptance criteria:**
- Campaigns where the user has no CampaignMember entry are excluded
- The result includes campaign name, invite code, and member count
- DM-created campaigns and player-joined campaigns are both included

---

### 15. CharacterService — Business Logic (0 existing — 22 new, mock-based)

#### 15.1 Create single-class non-caster (Fighter) [ ]

**Given** a character creation request for a Human Fighter, level 1, STR 16, DEX 14, CON 14, with standard array scores
**When** the character is created
**Then** HP should be 12 (d10 max + CON mod 2), proficiency bonus +2, hit dice "1d10", and no spell slots

**Acceptance criteria:**
- HP max = 10 + 2 = 12
- HP current = HP max
- Hit dice total is "1d10", remaining is 1
- Spell slots are empty/null
- Proficiency bonus is +2
- Saving throw proficiencies include STR and CON (Fighter)

#### 15.2 Create single-class full caster (Wizard) [ ]

**Given** a character creation request for an Elf Wizard, level 1, INT 16 (+3)
**When** the character is created
**Then** HP should use d6, spell slots should be 2 first-level slots, and spellcasting ability should be INT

**Acceptance criteria:**
- HP max = 6 + CON mod
- Spell slots JSON has 2 first-level slots
- spellcastingAbility = "intelligence"
- spellSaveDc = 8 + proficiency (2) + INT mod (3) = 13
- spellAttackBonus = proficiency (2) + INT mod (3) = 5

#### 15.3 Create single-class half caster (Paladin) [ ]

**Given** a character creation request for a Paladin, level 2, CHA 16
**When** the character is created
**Then** spell slots should be calculated based on half-caster rules (Paladin gets spells at level 2)

**Acceptance criteria:**
- Level 2 Paladin has caster level 1 (floor(2/2) = 1)
- Gets 2 first-level spell slots
- spellcastingAbility = "charisma"

#### 15.4 Create single-class pact caster (Warlock) [ ]

**Given** a character creation request for a Warlock, level 1, CHA 16
**When** the character is created
**Then** pact slots should appear (1 slot at 1st level), and regular spell slots should be empty

**Acceptance criteria:**
- Regular spell slot array is empty
- Pact slot track shows 1 slot at 1st-level spells
- spellcastingAbility = "charisma"

#### 15.5 Create multiclass at creation (Fighter 3 / Wizard 2) [ ]

**Given** a character creation request for a Fighter 3 / Wizard 2, total level 5
**When** the character is created
**Then** combined HP should use mixed hit dice, spell slots should reflect Wizard caster level 2, and hit dice map should have both classes

**Acceptance criteria:**
- HP = (10 + CON) + 2*(6 + CON) for Fighter levels + 2*(4 + CON) for Wizard levels
- Spell slots from Wizard caster level 2 (3 first-level slots)
- Hit dice map: {"Fighter": 3d10, "Wizard": 2d6}
- Multiclass entries JSON has both class entries with correct levels

#### 15.6 Ability scores saved correctly [ ]

**Given** a character creation request with ability scores STR 15, DEX 14, CON 13, INT 12, WIS 10, CHA 8 (standard array)
**When** the character is created
**Then** all six ability scores should be persisted exactly as provided

**Acceptance criteria:**
- Each ability score field matches the input
- Racial ability bonuses (if any) are applied on top of the base scores
- The abilityScoreMethod field records "Standard Array" (or equivalent)

#### 15.7 Race proficiencies merged with class proficiencies [ ]

**Given** an Elf (proficient in Perception, longsword, longbow) creating a Fighter (proficient in all armor, all weapons, STR/CON saves)
**When** the character is created
**Then** proficiency lists should include both Elf and Fighter proficiencies without duplicates

**Acceptance criteria:**
- Perception appears once (from Elf), not twice
- All Fighter armor proficiencies are present
- Weapon proficiencies include both Elf weapons and Fighter's "all simple and martial"
- Saving throw proficiencies come from the class only (STR, CON for Fighter)

#### 15.8 Background proficiencies merged with deduplication [ ]

**Given** a character with class proficiency in Athletics and a background (Soldier) also granting Athletics
**When** the character is created
**Then** Athletics should appear once in the skill proficiencies

**Acceptance criteria:**
- Duplicate skills are eliminated
- The player may choose a replacement skill for the duplicate (depending on implementation)
- Tool and language proficiencies from the background are also merged

#### 15.9 Tasha's ability score reassignment [ ]

**Given** a Hill Dwarf (normally +2 CON, +1 WIS) who uses Tasha's optional rule to move the +2 to STR instead
**When** the character is created with the reassignment
**Then** STR should get +2 (moved from CON) and WIS should still get +1

**Acceptance criteria:**
- The racial bonus is applied to the chosen ability, not the default
- The original default ability (CON) does not get the bonus
- The reassignment is recorded in racialAbilityBonuses field

#### 15.10 Update ability scores recalculates derived stats [ ]

**Given** a Wizard with INT 16 (mod +3), proficiency +2, spell save DC 13, spell attack +5
**When** INT is updated to 18 (mod +4)
**Then** spell save DC should change to 14 (8 + 2 + 4) and spell attack should change to +6 (2 + 4)

**Acceptance criteria:**
- Saving throw bonuses for all six abilities are recalculated
- Skill bonuses that use the changed ability are recalculated
- Spell DC and attack bonus update if the spellcasting ability changed
- Ability modifiers update

#### 15.11 Update with @Valid constraints [ ]

**Given** an update request with invalid values: level 0, HP -5, STR 31
**When** the update is submitted
**Then** validation should fail with specific field error messages

**Acceptance criteria:**
- Level must be 1-20 (0 and 21 rejected)
- HP cannot be negative
- Ability scores must be 1-30 (0 and 31 rejected)
- Multiple validation errors are returned together, not one at a time

#### 15.12 Short rest: spending 1 hit die [ ]

**Given** a Fighter with 20/45 HP, CON 14 (+2), 3/5 hit dice remaining (d10)
**When** 1 hit die is spent during a short rest
**Then** HP should increase by d10 average (6) + CON mod (2) = 8 (total 28), and remaining hit dice should decrease to 2

**Acceptance criteria:**
- HP gained = average die roll + CON modifier
- Hit dice remaining decreases by 1
- HP does not exceed max HP
- The specific class die size (d10 for Fighter) is used

#### 15.13 Short rest: multiple hit dice from multiclass [ ]

**Given** a Fighter 3 / Wizard 2 with 15/35 HP, CON 12 (+1), Fighter d10 remaining: 2, Wizard d6 remaining: 1
**When** 1 Fighter d10 and 1 Wizard d6 are spent
**Then** HP should increase by (6+1) + (4+1) = 12, Fighter remaining = 1, Wizard remaining = 0

**Acceptance criteria:**
- Each spent die uses the correct die size for its class
- Fighter d10 average is 6, Wizard d6 average is 4
- CON modifier is added to each die separately
- Remaining hit dice are tracked per class

#### 15.14 Short rest: cannot spend more than remaining [ ]

**Given** a Fighter with 0/3 hit dice remaining
**When** the player tries to spend 1 hit die
**Then** the operation should fail

**Acceptance criteria:**
- An error is returned indicating no hit dice are available
- HP is unchanged
- Hit dice count stays at 0

#### 15.15 Short rest: Warlock pact slots reset [ ]

**Given** a Warlock with 0/2 pact slots remaining and 3/3 regular spell slots (from multiclass)
**When** a short rest is completed
**Then** pact slots should reset to 2/2, but regular spell slots should remain at 3/3

**Acceptance criteria:**
- Pact slots are fully restored to maximum
- Regular spell slots are not affected by short rest
- This distinguishes pact magic from standard spellcasting

#### 15.16 Short rest: feat resources with shortRestReset restored [ ]

**Given** a character with feat resource "Superiority Dice" (4 max, 1 remaining, resetOn: "shortRest")
**When** a short rest is completed
**Then** Superiority Dice should reset to 4/4

**Acceptance criteria:**
- Resources with resetOn "shortRest" are restored to maxUses
- Resources with resetOn "longRest" are NOT restored on short rest
- The currentUses field is updated in the featResources JSON

#### 15.17 Long rest: full HP restored [ ]

**Given** a Fighter with 20/45 HP
**When** a long rest is completed
**Then** HP should be restored to 45/45

**Acceptance criteria:**
- hpCurrent is set to hpMax
- Temp HP is NOT affected (temp HP persists through long rest)

#### 15.18 Long rest: all spell slots restored [ ]

**Given** a Wizard with 0/4 first-level slots and 0/3 second-level slots, and a Warlock multiclass with 0/2 pact slots
**When** a long rest is completed
**Then** all spell slots (regular and pact) should be fully restored

**Acceptance criteria:**
- Every spell slot level is restored to maximum
- Pact slots are also restored (long rest includes short rest benefits)
- The spell slot JSON is updated with all levels at max

#### 15.19 Long rest: hit dice recovery (half, rounded down, min 1) [ ]

**Given** a level 7 character with 0/7 hit dice remaining
**When** a long rest is completed
**Then** the character should regain 3 hit dice (floor(7/2) = 3)

**Acceptance criteria:**
- Recovery amount is floor(total hit dice / 2)
- Minimum recovery is 1 (a level 1 character always regains at least 1)
- Hit dice remaining cannot exceed total hit dice
- If the character already has some remaining, the recovery adds to that (but capped at total)

#### 15.20 Long rest: all feat resources restored [ ]

**Given** a character with "Luck Points" (0/3, longRest) and "Superiority Dice" (2/4, shortRest)
**When** a long rest is completed
**Then** both resources should be restored to maximum (3/3 and 4/4)

**Acceptance criteria:**
- Long rest restores both longRest and shortRest resources (long rest includes short rest benefits)
- currentUses is set to maxUses for all feat resources

#### 15.21 Character deletion: soft delete [ ]

**Given** an active character owned by the requesting user
**When** the character is deleted
**Then** isActive should be set to false, and the character should no longer appear in "my characters" list

**Acceptance criteria:**
- The database row is not physically deleted
- isActive flag is set to false
- findByUserIdAndIsActiveTrue no longer returns this character
- The character can still be looked up by ID (for historical purposes)

#### 15.22 Character deletion: non-owner cannot delete [ ]

**Given** a character owned by User A
**When** User B attempts to delete it
**Then** the operation should be rejected with an authorization error

**Acceptance criteria:**
- Only the character's owner can delete it
- The error indicates insufficient permissions
- The character remains active and unchanged

---

### 16. FiveEToolsMarkupParser (0 existing — 11 new)

#### 16.1 {@atk mw} → "Melee Weapon Attack" [ ]

**Given** the 5etools markup string `{@atk mw}`
**When** it is parsed
**Then** the output should be "Melee Weapon Attack"

**Acceptance criteria:**
- The `{@atk}` tag is recognized and translated
- `mw` maps to "Melee Weapon Attack", `rw` maps to "Ranged Weapon Attack"

#### 16.2 {@atk rw} → "Ranged Weapon Attack" [ ]

**Given** the 5etools markup string `{@atk rw}`
**When** it is parsed
**Then** the output should be "Ranged Weapon Attack"

**Acceptance criteria:**
- Ranged attack type is correctly translated
- `rw,mw` (ranged or melee) should also be handled

#### 16.3 {@hit 5} → "+5" [ ]

**Given** the 5etools markup string `{@hit 5}`
**When** it is parsed
**Then** the output should be "+5"

**Acceptance criteria:**
- The number is prefixed with "+"
- Negative values should show "-" (e.g. `{@hit -1}` → "-1")

#### 16.4 {@damage 2d6+3} → "2d6 + 3" [ ]

**Given** the 5etools markup string `{@damage 2d6+3}`
**When** it is parsed
**Then** the output should be "2d6 + 3"

**Acceptance criteria:**
- The damage expression is formatted with spaces around operators
- Works with various dice expressions (1d8, 3d6+5, 2d10-2)

#### 16.5 {@dc 15} → "DC 15" [ ]

**Given** the 5etools markup string `{@dc 15}`
**When** it is parsed
**Then** the output should be "DC 15"

**Acceptance criteria:**
- "DC" prefix is added
- Works for any DC value

#### 16.6 {@spell fireball} → "fireball" [ ]

**Given** the 5etools markup string `{@spell fireball}`
**When** it is parsed
**Then** the output should be "fireball"

**Acceptance criteria:**
- The tag wrapper is stripped, leaving just the spell name
- Works for multi-word spells: `{@spell cure wounds}` → "cure wounds"

#### 16.7 {@creature goblin} → "goblin" [ ]

**Given** the 5etools markup string `{@creature goblin}`
**When** it is parsed
**Then** the output should be "goblin"

**Acceptance criteria:**
- Same stripping behavior as {@spell}
- Works for creatures with sources: `{@creature goblin|mm}` → "goblin"

#### 16.8 {@condition poisoned} → "poisoned" [ ]

**Given** the 5etools markup string `{@condition poisoned}`
**When** it is parsed
**Then** the output should be "poisoned"

**Acceptance criteria:**
- Condition names are extracted from the tag
- All PHB conditions should work: blinded, charmed, deafened, frightened, grappled, incapacitated, invisible, paralyzed, petrified, poisoned, prone, restrained, stunned, unconscious

#### 16.9 Nested/multiple tags in one string [ ]

**Given** the string `{@atk mw} {@hit 7} to hit, {@damage 2d6+4} slashing damage`
**When** it is parsed
**Then** the output should be "Melee Weapon Attack +7 to hit, 2d6 + 4 slashing damage"

**Acceptance criteria:**
- All tags in a single string are processed
- Plain text between tags is preserved
- Multiple different tag types in one string all work correctly

#### 16.10 Plain text with no tags passes through [ ]

**Given** the string "The goblin swings its scimitar wildly."
**When** it is parsed
**Then** the output should be the same string unchanged

**Acceptance criteria:**
- Strings without any `{@...}` tags are returned as-is
- No unnecessary processing or modification occurs

#### 16.11 Null input handled gracefully [ ]

**Given** a null input string
**When** it is parsed
**Then** null or empty string should be returned without throwing an exception

**Acceptance criteria:**
- Null returns null (or empty string, depending on convention)
- Empty string returns empty string
- No NullPointerException

---

### 17. GlobalExceptionHandler (0 existing — 4 new)

#### 17.1 IllegalArgumentException → 400 Bad Request [ ]

**Given** a controller method that throws IllegalArgumentException("Character not found")
**When** the request is processed
**Then** the response should have HTTP status 400 and a body containing the error message

**Acceptance criteria:**
- Status code is 400 Bad Request
- Response body has an "error" field with the exception message
- Stack trace is not exposed to the client

#### 17.2 IllegalStateException → 409 Conflict [ ]

**Given** a controller method that throws IllegalStateException("Cannot level up: already at max level")
**When** the request is processed
**Then** the response should have HTTP status 409 Conflict

**Acceptance criteria:**
- Status code is 409 Conflict
- Error message is included in the response body
- This maps to business logic state violations (not input errors)

#### 17.3 ConstraintViolationException → 400 with field errors [ ]

**Given** a @Valid annotated request body with level = 0 and strength = 31
**When** the request is submitted
**Then** the response should have HTTP status 400 with field-level error details

**Acceptance criteria:**
- Status code is 400 Bad Request
- Response includes a list of field errors (field name + violation message)
- Multiple violations are reported together, not just the first one
- Error messages are human-readable (e.g. "must be greater than 0")

#### 17.4 EntityNotFoundException → 404 Not Found [ ]

**Given** a request for a character ID that does not exist
**When** the controller throws EntityNotFoundException
**Then** the response should have HTTP status 404 Not Found

**Acceptance criteria:**
- Status code is 404 Not Found
- Error message indicates the entity was not found
- The response does not reveal internal details (table names, SQL)

---

## Frontend Unit Tests (Vitest)

### 18. dndRules.ts (6 tests)

#### 18.1 abilityMod: standard score calculations [ ]

**Given** ability scores of 10, 8, 14, and 20
**When** abilityMod is called for each score
**Then** the results should be 0, -1, +2, and +5 respectively

**Acceptance criteria:**
- Score 10 (D&D baseline) gives modifier 0
- Score 8 gives -1
- Score 14 gives +2
- Score 20 (max normal) gives +5
- Formula is floor((score - 10) / 2)

#### 18.2 formatMod: plus/minus/zero formatting [ ]

**Given** modifier values of +3, -2, and 0
**When** formatMod is called for each
**Then** the results should be "+3", "-2", and "+0"

**Acceptance criteria:**
- Positive values are prefixed with "+"
- Negative values use "-" (no double sign)
- Zero is formatted as "+0" (not just "0")

#### 18.3 formatAbilityMod: score to formatted modifier [ ]

**Given** an ability score of 16
**When** formatAbilityMod is called
**Then** the result should be "+3"

**Acceptance criteria:**
- Combines abilityMod and formatMod in one call
- Score 16 → mod +3 → "+3"

#### 18.4 safeJsonParse: valid JSON string [ ]

**Given** a JSON string `'{"name":"Thorin","level":5}'`
**When** safeJsonParse is called
**Then** a parsed object with name "Thorin" and level 5 should be returned

**Acceptance criteria:**
- Valid JSON strings are parsed to objects
- Arrays also parse correctly: `'["Athletics","Perception"]'` → array of 2 strings

#### 18.5 safeJsonParse: invalid/null/undefined returns fallback [ ]

**Given** inputs of null, undefined, empty string, and "not valid json {"
**When** safeJsonParse is called with a fallback value of `[]`
**Then** the fallback `[]` should be returned for all invalid inputs

**Acceptance criteria:**
- Null → fallback
- Undefined → fallback
- Empty string → fallback
- Malformed JSON → fallback (no thrown exception)

#### 18.6 safeJsonParse: already-parsed object returned as-is [ ]

**Given** an already-parsed JavaScript object `{name: "Thorin"}` (not a string)
**When** safeJsonParse is called
**Then** the same object should be returned without attempting to parse it

**Acceptance criteria:**
- This handles the @JsonRawValue pattern where some fields come pre-parsed from the backend
- typeof input !== 'string' → return input directly
- Works for both objects and arrays

---

### 19. spellConstants.ts (7 tests)

#### 19.1 wizardSpellbookCount at various levels [ ]

**Given** a Wizard at levels 1, 2, 5, and 20
**When** wizardSpellbookCount is calculated
**Then** the counts should be 6, 8, 14, and 44

**Acceptance criteria:**
- Level 1 starts with 6 spells
- Each level adds 2 spells (6 + 2*(level-1))
- Level 20 Wizard has 44 total spellbook spells

#### 19.2 getPreparedCount for full caster (Cleric) [ ]

**Given** a level 5 Cleric with WIS 16 (modifier +3)
**When** getPreparedCount is calculated
**Then** the result should be 8 (5 + 3)

**Acceptance criteria:**
- Formula for full casters: class level + ability modifier
- Cleric uses WIS modifier
- Minimum is always 1

#### 19.3 getPreparedCount for half caster (Paladin) [ ]

**Given** a level 6 Paladin with CHA 14 (modifier +2)
**When** getPreparedCount is calculated
**Then** the result should be 5 (floor(6/2) + 2 = 3 + 2)

**Acceptance criteria:**
- Half casters use floor(level/2) instead of full level
- Paladin uses CHA modifier
- Result is at least 1

#### 19.4 getPreparedCount: minimum 1 with negative modifier [ ]

**Given** a level 1 Cleric with WIS 8 (modifier -1)
**When** getPreparedCount is calculated
**Then** the result should be 1 (minimum), not 0

**Acceptance criteria:**
- Even with a negative ability modifier, you can always prepare at least 1 spell
- 1 + (-1) = 0, clamped to 1

#### 19.5 maxSpellLevel for full caster [ ]

**Given** a full caster at levels 1, 3, 5, and 9
**When** maxSpellLevel is calculated
**Then** the results should be 1st, 2nd, 3rd, and 5th respectively

**Acceptance criteria:**
- Level 1: max 1st-level spells
- Level 3: max 2nd-level spells
- Level 5: max 3rd-level spells
- Level 9: max 5th-level spells
- Matches the PHB spell slot table (spell slots appear = you can learn spells of that level)

#### 19.6 maxSpellLevel for half caster [ ]

**Given** a Paladin (half caster) at levels 2, 5, and 9
**When** maxSpellLevel is calculated
**Then** the results should be 1st, 2nd, and 3rd

**Acceptance criteria:**
- Half casters access new spell levels later than full casters
- Level 2 Paladin can learn 1st-level spells (caster level 1)
- Level 5 Paladin can learn 2nd-level spells (caster level 2)

#### 19.7 maxSpellLevel for 1/3 caster (EK/AT) [ ]

**Given** an Eldritch Knight (1/3 caster) at levels 3 and 7
**When** maxSpellLevel is calculated
**Then** the results should be 1st and 2nd

**Acceptance criteria:**
- EK gets 1st-level spells at class level 3 (caster level 1)
- EK gets 2nd-level spells at class level 7 (caster level 2)
- This is the slowest spell progression in the game

---

### 20. featPrerequisites.ts (8 tests)

#### 20.1 checkFeatPrerequisites: ability score met [ ]

**Given** the "Heavily Armored" feat requiring STR 13 or higher, and a character with STR 14
**When** prerequisites are checked
**Then** the result should be eligible = true

**Acceptance criteria:**
- Score at or above the threshold passes
- The check identifies the correct ability from the feat's prerequisite data

#### 20.2 checkFeatPrerequisites: ability score not met [ ]

**Given** the "Heavily Armored" feat requiring STR 13, and a character with STR 10
**When** prerequisites are checked
**Then** the result should be eligible = false with reason "Requires Strength 13"

**Acceptance criteria:**
- Score below threshold fails
- The reason message names the specific ability and threshold
- The character's actual score is not revealed in the reason

#### 20.3 checkFeatPrerequisites: proficiency prerequisite [ ]

**Given** the "Heavy Armor Master" feat requiring heavy armor proficiency, and a character proficient in heavy armor
**When** prerequisites are checked
**Then** the result should be eligible = true

**Acceptance criteria:**
- Proficiency prerequisites are checked against the character's proficiency lists
- Matching is case-insensitive

#### 20.4 checkFeatPrerequisites: spellcasting prerequisite [ ]

**Given** the "Ritual Caster" feat requiring a spellcasting ability, and a Fighter with no spellcasting
**When** prerequisites are checked
**Then** the result should be eligible = false with reason "Requires spellcasting ability"

**Acceptance criteria:**
- The spellcasting check looks for a non-null spellcastingAbility on the character
- Non-casters fail this check; any caster (full, half, third, pact) passes

#### 20.5 checkFeatPrerequisites: no prerequisites always passes [ ]

**Given** the "Alert" feat which has no prerequisites
**When** prerequisites are checked for any character
**Then** the result should always be eligible = true

**Acceptance criteria:**
- Feats with null or empty prerequisites always pass
- No ability scores or proficiencies are checked

#### 20.6 parseFeatEffects: ability score increase [ ]

**Given** the "Resilient" feat data with an abilityScoreIncrease field
**When** parseFeatEffects is called
**Then** the result should include the ASI data (choose one ability for +1)

**Acceptance criteria:**
- The parsed effects include the abilityScoreIncrease object
- Fixed and choice-based ASIs are both represented

#### 20.7 parseFeatEffects: proficiency list [ ]

**Given** the "Moderately Armored" feat data with proficiency grants
**When** parseFeatEffects is called
**Then** the result should include armor proficiencies (medium armor, shields)

**Acceptance criteria:**
- All proficiency types are parsed: armor, weapon, tool, skill, language, saving throw
- Each proficiency type is an array of granted items

#### 20.8 parseAbilityScoreIncrease: fixed and choice-based [ ]

**Given** feat data for "Heavily Armored" (fixed +1 STR) and "Resilient" (choose one ability +1)
**When** parseAbilityScoreIncrease is called for each
**Then** Heavily Armored should return `{fixed: {strength: 1}}` and Resilient should return `{choose: {amount: 1, from: [all 6 abilities]}}`

**Acceptance criteria:**
- Fixed ASIs specify the exact ability and bonus
- Choice ASIs specify the bonus amount and the list of options
- Some feats have both fixed and choice components

---

### 21. featSpellParser.ts (7 tests)

#### 21.1 parseFeatOptions: known spells [ ]

**Given** a feat with grantsFeatures containing known spells (e.g. Magic Initiate granting specific cantrips)
**When** parseFeatOptions is called
**Then** the result should include spell entries with names and levels

**Acceptance criteria:**
- Fixed spells have their names and levels extracted
- The spell reference format `spell_name#c` (for cantrips) is parsed correctly

#### 21.2 parseFeatOptions: innate spells [ ]

**Given** a feat with grantsFeatures containing innate/daily spells
**When** parseFeatOptions is called
**Then** the result should include innate spell entries with daily use limits

**Acceptance criteria:**
- Innate spells have a daily use count (e.g. "1/day")
- The spell name and level are extracted

#### 21.3 parseFeatOptions: choose from list [ ]

**Given** a feat where the player chooses a spell from a specific list of options
**When** parseFeatOptions is called
**Then** the result should include a choices array with the available spell names

**Acceptance criteria:**
- The `choose.from` array is parsed into a list of available spell names
- Each choice has an associated count (how many to pick)

#### 21.4 parseFeatOptions: choose by filter (class/school) [ ]

**Given** a feat where the player chooses a spell filtered by class and/or school (e.g. Magic Initiate: "Choose a Wizard cantrip")
**When** parseFeatOptions is called
**Then** the result should include filter criteria with class name, spell level, and school restrictions

**Acceptance criteria:**
- The filter includes classes (e.g. ["Wizard"]), level (e.g. 0 for cantrip), and optionally schools
- This is used to populate the spell search when the user is picking spells

#### 21.5 parseFeatOptions: ability choice [ ]

**Given** a feat with grantsFeatures that includes an ability score choice
**When** parseFeatOptions is called
**Then** the result should include the ability choice options

**Acceptance criteria:**
- The available abilities for the choice are listed
- The bonus amount is specified

#### 21.6 parseFeatOptions: null grantsFeatures [ ]

**Given** a feat with null or undefined grantsFeatures field
**When** parseFeatOptions is called
**Then** an empty array should be returned

**Acceptance criteria:**
- No error is thrown
- The result is an empty array, not null

#### 21.7 parseFeatOptions: daily-use spells [ ]

**Given** a feat granting a spell that can be used once per day (e.g. Fey Touched granting Misty Step 1/day)
**When** parseFeatOptions is called
**Then** the result should include the daily use limit

**Acceptance criteria:**
- The daily limit is parsed from the `innate._.daily` structure
- The spell is marked with its use frequency

---

### 22. parseMarkup.ts (6 tests)

#### 22.1 {@bold text} → bold text [ ]

**Given** the markup string `{@bold important rules}`
**When** parseMarkup is called
**Then** the output should render "important rules" as bold (or return it stripped of the tag)

**Acceptance criteria:**
- The {@bold} wrapper is removed
- The inner text is preserved

#### 22.2 {@spell fireball} → "fireball" [ ]

**Given** the markup string `{@spell fireball}`
**When** parseMarkup is called
**Then** the output should be "fireball"

**Acceptance criteria:**
- The tag and its wrapper are stripped
- Just the spell name remains

#### 22.3 {@item longsword} → "longsword" [ ]

**Given** the markup string `{@item longsword}`
**When** parseMarkup is called
**Then** the output should be "longsword"

**Acceptance criteria:**
- Item tags are handled the same as spell tags

#### 22.4 Nested/chained tags [ ]

**Given** the markup string `{@bold {@spell fireball}} deals {@ damage 8d6} fire damage`
**When** parseMarkup is called
**Then** all tags should be processed and the plain text result returned

**Acceptance criteria:**
- Nested tags are handled (inner tag processed first, then outer)
- Multiple tags in sequence are all processed
- Text between tags is preserved

#### 22.5 Plain text passes through [ ]

**Given** the string "The dragon breathes fire in a 60-foot cone."
**When** parseMarkup is called
**Then** the string should be returned unchanged

**Acceptance criteria:**
- No modification to strings without `{@...}` patterns
- Performance is reasonable (no unnecessary regex scanning)

#### 22.6 Pipe-separated display name [ ]

**Given** the markup string `{@spell fireball|PHB}`
**When** parseMarkup is called
**Then** the output should be "fireball" (first part before the pipe)

**Acceptance criteria:**
- The pipe-separated source info (e.g. "|PHB") is stripped
- Only the display name (first segment) is kept
- Works for all tag types: `{@creature goblin|mm}` → "goblin"

---

### 23. wizard/constants — Helper Functions (9 tests)

#### 23.1 checkMulticlassEligibility: exit and entry prerequisites [ ]

**Given** a level 3 Fighter (STR 16) wanting to multiclass into Wizard (requires INT 13), with INT 14
**When** checkMulticlassEligibility is called
**Then** the result should be eligible = true (meets Fighter exit prereqs and Wizard entry prereqs)

**Acceptance criteria:**
- Both the current class's exit requirements and the target class's entry requirements are checked
- Fighter exit requires STR 13 (met with 16)
- Wizard entry requires INT 13 (met with 14)
- Both must pass for the multiclass to be eligible

#### 23.2 checkMulticlassEligibility: OR prerequisites [ ]

**Given** a class with OR prerequisites "STR 13 or DEX 13" and a character with STR 10, DEX 15
**When** eligibility is checked
**Then** the result should be eligible (DEX meets the threshold)

**Acceptance criteria:**
- Only one of the OR conditions needs to be met
- The passing ability is identified correctly

#### 23.3 checkMulticlassEligibility: AND prerequisites [ ]

**Given** Paladin entry requiring STR 13 AND CHA 13, and a character with STR 14, CHA 11
**When** eligibility is checked
**Then** the result should be ineligible (CHA fails)

**Acceptance criteria:**
- All AND conditions must be met
- The failing ability is identified in the reason

#### 23.4 isAsiLevel: standard + Fighter/Rogue extras [ ]

**Given** the class names "Fighter", "Rogue", and "Wizard"
**When** isAsiLevel is checked at level 4, 6, 10, and 14
**Then** level 4 should be true for all three; level 6 true only for Fighter; level 10 true only for Rogue; level 14 true only for Fighter

**Acceptance criteria:**
- Standard ASI levels (4, 8, 12, 16, 19) return true for all classes
- Fighter extra ASI at 6 and 14
- Rogue extra ASI at 10
- All other levels return false

#### 23.5 countAsiLevels: total ASIs up to a level [ ]

**Given** a Fighter at level 8
**When** countAsiLevels is called
**Then** the result should be 3 (ASIs at levels 4, 6, 8)

**Acceptance criteria:**
- Counts all ASI levels from 1 up to and including the target level
- Fighter level 8: levels 4, 6, 8 = 3 ASIs
- Wizard level 8: levels 4, 8 = 2 ASIs

#### 23.6 expandToolFrom: "Any Artisan's Tool" [ ]

**Given** the string "Any Artisan's Tool"
**When** expandToolFrom is called
**Then** a list of concrete artisan tools should be returned (e.g. "Smith's Tools", "Brewer's Supplies", etc.)

**Acceptance criteria:**
- The expanded list contains all PHB artisan tools
- Each tool is a specific, selectable option
- Other "Any" categories are also handled (e.g. "Any Musical Instrument")

#### 23.7 getToolAnyOptions: category options [ ]

**Given** a tool proficiency entry with type "any"
**When** getToolAnyOptions is called
**Then** the available tool categories should be returned

**Acceptance criteria:**
- Returns the list of tool categories available for "any" selection
- Includes Artisan's Tools, Gaming Sets, Musical Instruments, etc.

#### 23.8 proficiencyBonusForLevel: matches backend [ ]

**Given** levels 1, 5, 9, 13, and 17
**When** proficiencyBonusForLevel is called
**Then** the results should be 2, 3, 4, 5, and 6

**Acceptance criteria:**
- Values match the backend CharacterService.proficiencyBonus exactly
- Levels 1-4 → +2, 5-8 → +3, 9-12 → +4, 13-16 → +5, 17-20 → +6

#### 23.9 thirdCasterMulticlassContribution [ ]

**Given** an Eldritch Knight at class levels 3, 6, and 9
**When** thirdCasterMulticlassContribution is calculated
**Then** the caster level contributions should be 1, 2, and 3

**Acceptance criteria:**
- Formula is floor(classLevel / 3)
- Level 3 → 1, level 6 → 2, level 9 → 3
- Level 2 and below contribute 0

---

## Frontend Component Tests (React Testing Library)

### 24. Character Sheet Rendering (8 tests)

#### 24.1 Stats tab: ability scores, modifiers, saves, skills [ ]

**Given** a character with STR 16 (+3), DEX 12 (+1), proficiency in Athletics, and CON save proficiency
**When** the Stats tab is rendered
**Then** all six ability scores, their modifiers, saving throw values, and skill bonuses should be displayed

**Acceptance criteria:**
- STR shows "16" with modifier "+3"
- Athletics shows "+5" (3 mod + 2 proficiency) with a proficiency indicator
- CON save shows the proficiency indicator
- Non-proficient saves show only the ability modifier

#### 24.2 Stats tab: expertise with star indicator [ ]

**Given** a Rogue with expertise in Stealth (DEX 16, proficiency +2)
**When** the Stats tab is rendered
**Then** Stealth should show "+9" (3 mod + 4 double proficiency) with a star/special indicator

**Acceptance criteria:**
- Expertise skills double the proficiency bonus
- A visual indicator (star, double dot, or similar) distinguishes expertise from regular proficiency
- The calculated bonus is correct: ability mod + (proficiency bonus * 2)

#### 24.3 Spells tab: spell slot pips [ ]

**Given** a level 3 Wizard with 4/4 first-level slots and 2/2 second-level slots
**When** the Spells tab is rendered
**Then** 4 first-level pips and 2 second-level pips should appear, all filled/active

**Acceptance criteria:**
- Each spell level has the correct number of pips
- Filled pips indicate available slots, empty pips indicate used slots
- Clicking a pip toggles it (uses/restores the slot)

#### 24.4 Spells tab: source-grouped spell boxes [ ]

**Given** a character with spells from class (Wizard), race (High Elf cantrip), and feat (Magic Initiate)
**When** the Spells tab is rendered
**Then** spells should be grouped into separate sections by source

**Acceptance criteria:**
- Class spells appear in a "Wizard Spells" section
- Race spells appear in a "Racial Spells" section
- Feat spells appear in a "Feat Spells" section
- Each section is visually distinct

#### 24.5 Spells tab: non-caster with feat spells [ ]

**Given** a Fighter with no spellcasting class but the Magic Initiate feat granting 2 cantrips and 1 spell
**When** the Spells tab is rendered
**Then** only the feat spells section should appear (no class spell section, no spell slots)

**Acceptance criteria:**
- The Spells tab is not hidden — feat spells are still displayed
- No spell slot pips appear (feat spells are cast without slots)
- The "no spells" empty state is not shown

#### 24.6 Spells tab: Wizard spellbook management [ ]

**Given** a Wizard character with a spellbook
**When** the Spells tab is rendered
**Then** spellbook management buttons (add/remove spells, prepare from spellbook) should be visible

**Acceptance criteria:**
- "Manage Spellbook" or equivalent button is present
- Only Wizards see spellbook management (other classes don't have spellbooks)
- The button opens the spellbook management modal

#### 24.7 Short rest modal: multi-dice selection [ ]

**Given** a Fighter 3 / Wizard 2 opening the short rest modal with 3 d10 and 2 d6 available
**When** the short rest modal is rendered
**Then** separate selectors for d10 (Fighter) and d6 (Wizard) should appear

**Acceptance criteria:**
- Each class's hit die type is shown with its remaining count
- The player can select how many of each type to spend
- The expected healing amount is previewed
- Cannot select more dice than remaining

#### 24.8 Long rest: resources restored [ ]

**Given** a character with 20/45 HP, 0/4 spell slots, 1/3 feat resources
**When** a long rest is performed
**Then** HP should be 45/45, spell slots 4/4, and feat resources 3/3

**Acceptance criteria:**
- All resources are visually updated to their maximum values
- A success message or indicator confirms the rest
- Hit dice recovery (half rounded down) is applied

---

### 25. Character Creation Wizard (11 tests)

#### 25.1 Step navigation: forward/back [ ]

**Given** the character creation wizard on step 1 (Race)
**When** the user clicks "Next" and then "Back"
**Then** the wizard should advance to step 2 and then return to step 1 with all data preserved

**Acceptance criteria:**
- All 7 steps are reachable via forward navigation
- Going back preserves all previously entered data
- The step indicator shows the current position

#### 25.2 Race step: ASI preview [ ]

**Given** the wizard on the Race step
**When** "Hill Dwarf" is selected
**Then** an ASI preview should show "+2 Constitution, +1 Wisdom"

**Acceptance criteria:**
- The preview updates immediately on selection
- Tasha's optional rule toggle changes the preview (e.g. reassignable bonuses)
- Racial traits and proficiencies are also previewed

#### 25.3 Class step: multiclass eligibility [ ]

**Given** the wizard on the Class step with a character that has STR 8
**When** the multiclass section is shown
**Then** classes requiring STR 13 (Fighter, Paladin) should be greyed out with an ineligibility reason

**Acceptance criteria:**
- Eligible classes are selectable
- Ineligible classes show why they can't be selected
- Prerequisite checking matches the backend MulticlassValidator logic

#### 25.4 Ability scores: standard array [ ]

**Given** the wizard on the Ability Scores step with "Standard Array" selected
**When** the user allocates scores
**Then** each ability should be assigned one value from {15, 14, 13, 12, 10, 8}

**Acceptance criteria:**
- Each value can only be used once (drag-and-drop or dropdown with used values removed)
- All 6 values must be assigned before proceeding
- Racial bonuses are shown separately from the base allocation

#### 25.5 Ability scores: point buy [ ]

**Given** the wizard on the Ability Scores step with "Point Buy" selected and 27 points
**When** the user adjusts ability scores
**Then** scores should be constrained between 8 and 15, with costs matching the PHB table

**Acceptance criteria:**
- Minimum score is 8, maximum is 15
- Points remaining updates as scores change
- Cost table: 8=0, 9=1, 10=2, 11=3, 12=4, 13=5, 14=7, 15=9
- Cannot exceed 27 points total

#### 25.6 Background step: "Any" tool/language pickers [ ]

**Given** a background that grants "Any Artisan's Tool" and "Any Language"
**When** the Background step is rendered
**Then** dropdown pickers should appear for the player to choose specific tools and languages

**Acceptance criteria:**
- "Any Artisan's Tool" expands to a dropdown with all artisan tools
- "Any Language" expands to a dropdown with available languages
- The player must select a concrete option before proceeding

#### 25.7 Spells step: cantrip count [ ]

**Given** a level 1 Wizard (3 cantrips known)
**When** the Spells step is rendered
**Then** the cantrip picker should show "Choose 3 cantrips" and enforce that limit

**Acceptance criteria:**
- The number of cantrips matches the class/level table
- Cannot select more than the allowed number
- Selected cantrips are visually highlighted

#### 25.8 Spells step: Wizard spellbook picker [ ]

**Given** a level 1 Wizard
**When** the Spells step is rendered
**Then** a spellbook picker should appear (choose 6 first-level spells) instead of the standard prepared spells info

**Acceptance criteria:**
- Wizards see "Choose 6 spells for your spellbook" instead of prepared spell guidance
- The picker shows all available Wizard spells of the appropriate level
- Non-Wizard casters see their class-specific spell selection UI instead

#### 25.9 Review step: all values displayed [ ]

**Given** a fully filled-out character (race, class, background, ability scores, skills, spells)
**When** the Review step is rendered
**Then** all selected values should be displayed in a summary view

**Acceptance criteria:**
- Race, class, background names are shown
- All 6 ability scores with racial bonuses are displayed
- Selected proficiencies, languages, and spells are listed
- A "Create Character" button is available to submit

#### 25.10 localStorage draft saving [ ]

**Given** a partially completed wizard (through step 3)
**When** the browser page is reloaded
**Then** the wizard should restore the draft and return to step 3 with all data intact

**Acceptance criteria:**
- Draft is saved to localStorage on each step change
- Reloading recovers the exact state (selected race, class, scores, etc.)
- Starting a brand new character clears the draft

#### 25.11 beforeunload guard [ ]

**Given** a wizard with unsaved changes (at least one step completed)
**When** the user tries to navigate away or close the tab
**Then** a browser confirmation dialog should appear warning about unsaved changes

**Acceptance criteria:**
- The `beforeunload` event is intercepted
- The browser's native "Leave page?" dialog appears
- No warning appears if the form is clean (no changes since last save/load)

---

### 26. Level Up Flow (5 tests)

#### 26.1 LevelUpModal: eligible classes with greyed ineligible [ ]

**Given** a level 3 Fighter (STR 16, INT 8) opening the level up modal
**When** the modal is rendered
**Then** Fighter should be highlighted as the current class, Wizard should be greyed out (INT 8 < 13), and Rogue should be selectable (DEX meets threshold)

**Acceptance criteria:**
- Current class is pre-selected and visually distinguished
- Ineligible classes show the failing prerequisite
- Eligible multiclass options are clearly selectable
- The modal shows the class hit die and features gained at the next level

#### 26.2 AsiModal: ability score allocation [ ]

**Given** a level 4 Fighter with an ASI available and STR 16, DEX 14
**When** the ASI modal is opened in "Ability Scores" mode
**Then** the player should be able to distribute 2 points across abilities

**Acceptance criteria:**
- 2 points are available to distribute
- Points can be split (e.g. +1 STR, +1 DEX) or stacked (+2 STR)
- No ability can exceed 20
- Remaining points are displayed and the apply button enables when all points are spent

#### 26.3 AsiModal: feat selection with prerequisite filtering [ ]

**Given** a level 4 Fighter with STR 16 and no heavy armor proficiency
**When** the ASI modal is opened in "Feat" mode
**Then** feats should be listed with eligible ones first and ineligible ones greyed out

**Acceptance criteria:**
- "Alert" (no prereqs) is eligible and selectable
- "Heavy Armor Master" (requires heavy armor proficiency) is greyed out if not proficient
- Already-taken feats are greyed out with "Already taken" label
- A search box filters the feat list

#### 26.4 AsiModal: feat spell selection [ ]

**Given** selecting "Magic Initiate (Wizard)" as a feat
**When** the feat detail is shown
**Then** spell pickers should appear for choosing 2 Wizard cantrips and 1 first-level Wizard spell

**Acceptance criteria:**
- The correct number of spell slots appears (2 cantrips + 1 first-level)
- Spell search filters by the correct class and level
- Selected spells are displayed with check marks
- All required spells must be chosen before the feat can be applied

#### 26.5 SubclassModal: subclass list with features [ ]

**Given** a level 3 Fighter opening the subclass selection modal
**When** the modal is rendered
**Then** all Fighter subclasses should be listed (Champion, Battle Master, Eldritch Knight) with their level 3 features previewed

**Acceptance criteria:**
- Each subclass shows its name and a brief feature preview
- Selecting a subclass highlights it and shows full feature descriptions
- The "Confirm" button submits the selection

---

### 27. Encounter Session (8 tests)

#### 27.1 DM view: participant list sorted by initiative [ ]

**Given** an active encounter with Fighter (init 18), Goblin (init 15), Wizard (init 12)
**When** the DM encounter page is rendered
**Then** participants should appear in order: Fighter, Goblin, Wizard

**Acceptance criteria:**
- Participants are sorted by initiative value, highest first
- The current turn participant is visually highlighted
- Each participant shows name, HP bar, AC, and conditions

#### 27.2 DM view: action panel controls [ ]

**Given** the DM viewing an active encounter with a selected target
**When** the action panel is rendered
**Then** damage, healing, and condition controls should be visible

**Acceptance criteria:**
- Damage input with dice expression field and apply button
- Healing input with amount field
- Condition dropdown with duration input
- All actions target the selected participant

#### 27.3 DM view: death save display [ ]

**Given** a PC at 0 HP with 2 successes and 1 failure
**When** the DM view is rendered
**Then** the death save tracker should show 2 filled success pips and 1 filled failure pip

**Acceptance criteria:**
- 3 success pips (2 filled, 1 empty) and 3 failure pips (1 filled, 2 empty)
- Roll Death Save button is visible
- Stabilised or dead states are clearly indicated

#### 27.4 Player view: "It's your turn" highlight [ ]

**Given** a player whose character's turn it currently is
**When** the player encounter page is rendered
**Then** a prominent "It's your turn!" indicator should be displayed

**Acceptance criteria:**
- The highlight is clearly visible (colour change, animation, or banner)
- When it's not the player's turn, the indicator shows whose turn it is
- Turn changes update in real-time via WebSocket

#### 27.5 Player view: own character controls [ ]

**Given** a player viewing an encounter with their character at 20/45 HP
**When** the player encounter page is rendered
**Then** their character's HP bar and death save controls (if dying) should be visible

**Acceptance criteria:**
- HP bar shows current/max with colour coding (green → yellow → red)
- If at 0 HP, death save controls appear
- Other participants' detailed stats are not exposed to the player

#### 27.6 Combat log: colour-coded entries [ ]

**Given** an encounter with several combat actions completed
**When** the combat log is rendered
**Then** entries should be colour-coded by type (damage in red, healing in green, death saves, etc.)

**Acceptance criteria:**
- Damage entries show in red/orange
- Healing entries show in green
- Death save entries show in distinct colours (success/failure)
- Round and turn headers separate the log entries

#### 27.7 Attack roll: multi-attack UI [ ]

**Given** the DM making an attack with a creature that has multiple attacks
**When** the attack panel is used
**Then** multiple attack rows should be configurable with individual modifiers and damage

**Acceptance criteria:**
- The DM can add/clone attack rows for multi-attack
- Each row has its own hit modifier and damage expression
- All attacks are submitted together
- Results show hit/miss and damage for each attack

#### 27.8 WebSocket reconnect: state resyncs [ ]

**Given** a player connected to an encounter session via WebSocket
**When** the WebSocket connection drops and reconnects
**Then** the full encounter state should be resynced from the server

**Acceptance criteria:**
- Reconnection happens automatically (with backoff)
- After reconnection, the displayed state matches the current server state
- No combat actions are lost during the disconnection
- A brief "Reconnecting..." indicator is shown during the outage

---

## Integration Tests (Spring Boot @SpringBootTest with test database)

### 28. Character Lifecycle Integration (8 tests)

#### 28.1 Create single-class character: verify derived stats [ ]

**Given** a full character creation request for a Human Fighter with STR 16, DEX 14, CON 14, INT 10, WIS 12, CHA 8
**When** the character is created through the API
**Then** all derived stats (HP, proficiency, saves, skills, AC) should match manual PHB calculation

**Acceptance criteria:**
- HP = 12 (d10 + CON 2)
- Proficiency bonus = +2
- STR save = +5 (3 mod + 2 prof), CON save = +4 (2 mod + 2 prof)
- Non-proficient saves = ability modifier only
- Hit dice = "1d10", remaining = 1

#### 28.2 Create multiclass character: combined stats [ ]

**Given** a creation request for a Fighter 3 / Wizard 2 with INT 16
**When** the character is created
**Then** spell slots, HP, hit dice, and proficiency should reflect the multiclass combination

**Acceptance criteria:**
- HP uses mixed hit dice (3 levels of d10 + 2 levels of d6)
- Spell slots from Wizard caster level 2
- Hit dice map has both classes
- Proficiency bonus based on total character level (5 → +3)

#### 28.3 Level up 1→5: cumulative changes [ ]

**Given** a freshly created level 1 Fighter
**When** the character is levelled up 4 times to reach level 5
**Then** each level should show the correct HP gain, features, and proficiency changes

**Acceptance criteria:**
- Level 2: +8 HP, gains Action Surge
- Level 3: +8 HP, gains Martial Archetype (subclass available)
- Level 4: +8 HP, ASI available
- Level 5: +8 HP, proficiency increases to +3, gains Extra Attack
- Total HP at level 5 = 12 + 4*8 = 44

#### 28.4 Level up to ASI → apply feat → verify effects [ ]

**Given** a level 3 Fighter being levelled up to level 4 (ASI available)
**When** the Alert feat is applied as the ASI choice
**Then** initiative bonus should increase by 5, and the feat should appear in features

**Acceptance criteria:**
- The feat is recorded in features with source "Feat"
- Initiative bonus reflects the +5
- Level history records the feat choice for potential reversal
- The character's response includes the updated initiative bonus

#### 28.5 Level down 5→1: exact reversal [ ]

**Given** a level 5 Fighter
**When** the character is levelled down 4 times to level 1
**Then** all stats should match the original level 1 state exactly

**Acceptance criteria:**
- HP returns to level 1 value
- Features from levels 2-5 are removed
- Proficiency bonus reverts to +2
- Hit dice return to "1d10"
- Level history has only the level 1 entry

#### 28.6 Short rest: hit dice spending [ ]

**Given** a Fighter at 20/45 HP with 5/5 hit dice remaining, CON 14 (+2)
**When** 2 hit dice are spent during short rest
**Then** HP should increase by 2*(6+2) = 16 (to 36), and remaining hit dice should be 3

**Acceptance criteria:**
- HP increases by the correct amount
- Hit dice remaining decreases by the number spent
- HP is capped at max (if spending would exceed max, the excess is wasted)

#### 28.7 Long rest: full recovery [ ]

**Given** a character at 20/45 HP, 0/4 spell slots, 1/5 hit dice remaining, 0/3 feat resources
**When** a long rest is performed
**Then** HP = 45/45, spell slots = 4/4, hit dice remaining = 3/5 (regain half rounded down = 2, so 1+2=3), feat resources = 3/3

**Acceptance criteria:**
- Full HP restoration
- All spell slots restored
- Hit dice: regain floor(5/2) = 2, so 1+2 = 3 remaining
- All feat resources restored (both short-rest and long-rest types)

#### 28.8 Create character with background feat: spells in spellsKnown [ ]

**Given** a character creation request where the background grants the Magic Initiate feat with selected spells
**When** the character is created
**Then** the feat spells should appear in spellsKnown with source "Feat"

**Acceptance criteria:**
- The spells are persisted in the spellsKnown JSON
- Each spell has source "Feat: Magic Initiate" (or equivalent)
- The feat resource for the once-per-long-rest casting is created

---

### 29. Combat Integration (5 tests)

#### 29.1 Full combat round [ ]

**Given** an active encounter with a Wizard concentrating on Haste and a Goblin
**When** the Goblin attacks the Wizard, hits, deals damage → concentration check → Wizard makes death save → another participant heals the Wizard → turn advances
**Then** each step should produce the correct game state changes

**Acceptance criteria:**
- Damage reduces Wizard HP, triggers concentration check
- If concentration fails, Haste conditions are removed
- Death saves follow PHB rules if Wizard hits 0 HP
- Healing revives the Wizard if at 0 HP
- Turn advance moves to the next participant in initiative order

#### 29.2 PC death saves → natural 20 revives [ ]

**Given** a PC knocked to 0 HP with 1 success and 1 failure
**When** the PC rolls a death save and gets a natural 20
**Then** the PC should revive with 1 HP, death saves reset to 0/0

**Acceptance criteria:**
- HP becomes exactly 1
- Both success and failure counters reset
- isAlive becomes true
- The PC can act on their next turn

#### 29.3 Massive damage → instant death → healing revives with Prone [ ]

**Given** a PC with 30 HP max and 10 HP current
**When** 40 damage is dealt (excess 30 = max HP, so instant death), then Revivify healing is applied
**Then** the PC should die (3 failures), then revive with Prone condition

**Acceptance criteria:**
- Damage: HP drops to 0, remaining 30 damage >= max HP 30 → instant death
- Death save failures set to 3
- Healing: HP set to healing amount, death saves cleared, Prone added, Unconscious removed
- The PC can act on their next turn (while Prone)

#### 29.4 Condition with duration → auto-expires [ ]

**Given** a creature with the "Blinded" condition (duration 2 rounds) applied at the start of round 1
**When** turns advance through round 1 and into round 3 (creature's turn)
**Then** the Blinded condition should automatically expire

**Acceptance criteria:**
- Duration decrements each round at the start of the creature's turn
- After 2 rounds, the condition is removed
- Other conditions with remaining duration are unaffected
- The combat log records the expiry

#### 29.5 Attack → hit → damage with temp HP [ ]

**Given** a PC with 30 HP and 10 temp HP, targeted by an attack with +5 to hit against AC 15
**When** the attack roll hits (d20+5 >= 15) and deals 12 damage
**Then** temp HP should absorb 10, and real HP should take 2 (30 → 28)

**Acceptance criteria:**
- Hit determination follows the attack roll rules
- Temp HP absorbs first, then real HP
- Combat log shows the breakdown: "10 absorbed by temp HP, 2 to real HP"
- Total damage logged is 12

---

### 30. Encounter Lifecycle Integration (3 tests)

#### 30.1 Full encounter lifecycle [ ]

**Given** a DM user with a campaign
**When** an encounter is created → participants added → initiatives rolled → encounter started → combat round → encounter ended
**Then** the encounter should transition through all states correctly

**Acceptance criteria:**
- Status transitions: PREPARING → ACTIVE → COMPLETED
- Participants are sorted by initiative when started
- Combat actions work during ACTIVE state
- After ending, no further combat actions are allowed
- All data is persisted and retrievable

#### 30.2 Session code: generate and lookup [ ]

**Given** a newly created encounter
**When** the session code is generated, and then looked up by that code
**Then** the correct encounter should be returned

**Acceptance criteria:**
- The generated code is unique and 6 characters
- Looking up by session code returns the correct encounter with all participants
- Case-insensitive lookup works (if applicable)
- Non-existent codes return an appropriate error

#### 30.3 Character deletion blocked in active encounter [ ]

**Given** a PC character participating in an encounter with status ACTIVE
**When** the player attempts to delete their character
**Then** the deletion should be blocked with an error

**Acceptance criteria:**
- The error message explains that the character is in an active encounter
- Characters in PREPARING or PAUSED encounters are also blocked
- Characters in COMPLETED encounters can be deleted
- The character data is unchanged after the failed deletion

---

## Test Count Summary

| # | Test File | Existing | New | Total |
|---|---|---|---|---|
| 1 | SpellSlotCalculator | 12 | 5 | 17 |
| 2 | LevelUpCalculator | 10 | 7 | 17 |
| 3 | MulticlassValidator | 9 | 5 | 14 |
| 4 | CharacterServiceStatic | 8 | 2 | 10 |
| 5 | LevelUpDownRoundTrip | 14 | 4 | 18 |
| 6 | FeatEffectResolver | 0 | 19 | 19 |
| 7 | CharacterJsonHelper | 0 | 16 | 16 |
| 8 | CharacterMapper | 0 | 4 | 4 |
| 9 | DiceRoller | 0 | 8 | 8 |
| 10 | CombatService | 0 | 32 | 32 |
| 11 | EncounterService | 0 | 12 | 12 |
| 12 | AuthService | 0 | 6 | 6 |
| 13 | JwtTokenProvider | 0 | 4 | 4 |
| 14 | CampaignService | 0 | 4 | 4 |
| 15 | CharacterService (business) | 0 | 22 | 22 |
| 16 | FiveEToolsMarkupParser | 0 | 11 | 11 |
| 17 | GlobalExceptionHandler | 0 | 4 | 4 |
| | **Backend subtotal** | **53** | **165** | **218** |
| 18 | dndRules.ts | 0 | 6 | 6 |
| 19 | spellConstants.ts | 0 | 7 | 7 |
| 20 | featPrerequisites.ts | 0 | 8 | 8 |
| 21 | featSpellParser.ts | 0 | 7 | 7 |
| 22 | parseMarkup.ts | 0 | 6 | 6 |
| 23 | wizard/constants | 0 | 9 | 9 |
| | **Frontend utils subtotal** | **0** | **43** | **43** |
| 24 | Character Sheet | 0 | 8 | 8 |
| 25 | Creation Wizard | 0 | 11 | 11 |
| 26 | Level Up Flow | 0 | 5 | 5 |
| 27 | Encounter Session | 0 | 8 | 8 |
| | **Frontend component subtotal** | **0** | **32** | **32** |
| 28 | Character Lifecycle | 0 | 8 | 8 |
| 29 | Combat Integration | 0 | 5 | 5 |
| 30 | Encounter Lifecycle | 0 | 3 | 3 |
| | **Integration subtotal** | **0** | **16** | **16** |
| | **Grand total** | **53** | **256** | **309** |

## Infrastructure Setup Required

- **Backend unit tests:** Already configured (JUnit 5 + Mockito via spring-boot-starter-test)
- **Frontend unit tests:** Add Vitest to the frontend project, configure for TypeScript + React
- **Frontend component tests:** Add React Testing Library + jsdom environment
- **Integration tests:** Add Testcontainers (PostgreSQL) or configure H2 with PostgreSQL compatibility mode for @SpringBootTest
