package com.tabletophelper.encounter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.HealthStatus;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.encounter.state.SummonSpec;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.reference.dto.RepeatCost;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.SpellTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The full encounter state, which the DM sees (the DM View). {@code EncounterViews} derives the
 * Player View from it (D157).
 */
@Data
@Builder(toBuilder = true)
public class EncounterResponse {

    @Schema(requiredMode = REQUIRED)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    private UUID campaignId;

    @Schema(requiredMode = REQUIRED)
    private String campaignName;

    @Schema(requiredMode = REQUIRED)
    private String name;

    private String description;

    @Schema(requiredMode = REQUIRED, implementation = EncounterStatus.class)
    private String status;

    // The current turn's place in turnOrder, computed for each view (D157, D163): null before the
    // encounter starts, and in the Player View while a participant hidden from players acts
    private Integer currentTurnIndex;

    @Schema(requiredMode = REQUIRED)
    private Integer roundNumber;

    // The turn the encounter is on, as the turn economies' records of a turn name it (M17): what Sneak Attack's turn and
    // a melee hit's turn are compared with, to tell whether they are this turn's. In the Player View a hidden
    // participant's turn reads as "<round>:hidden"
    private String turnStamp;

    // Set when the encounter starts
    private String sessionCode;

    // In turn order
    @Schema(requiredMode = REQUIRED)
    private List<ParticipantResponse> participants;

    // Every turn in order, the Lair's included while it has one (D163). The Player View leaves out the
    // turns of participants hidden from players, and the Lair's unless they can see a monster in its lair.
    @Schema(requiredMode = REQUIRED)
    private List<TurnResponse> turnOrder;

    // The monster participants fighting in their lair, which gives the Lair a turn while any is alive
    @Schema(requiredMode = REQUIRED)
    private List<UUID> monstersInLair;

    // Null in the response that creates the encounter: @CreationTimestamp only fills it in when the
    // insert is flushed, after the response is built
    private Instant createdAt;

    // The two campaign facts that decide which view a user gets. They stay on the server, so
    // choosing a view needs no further lookup (D157).
    @JsonIgnore
    private UUID dmUserId;
    @JsonIgnore
    private boolean showExactMonsterHp;

    // The campaign's setting, which the session pages read to offer players Manual Modifiers (D164)
    @Schema(requiredMode = REQUIRED)
    private boolean allowPlayerManualModifiers;

    // What the DM's Undo would take back (M14): null unless the encounter is Active and has a command to undo since
    // the last barrier, and always null in the Player View
    private UndoInfo undo;

    @Data
    @Builder(toBuilder = true)
    public static class ParticipantResponse {

        @Schema(requiredMode = REQUIRED)
        private UUID id;

        @Schema(requiredMode = REQUIRED, implementation = ParticipantType.class)
        private String participantType;

        private UUID characterId;
        private UUID monsterId;

        // A player character's class as its sheet names it, which picks its class colour; null for a monster.
        // It comes from the sheet, so no request sets it
        private String characterClass;

        @Schema(requiredMode = REQUIRED)
        private String displayName;

        private Integer initiative;
        private Integer initiativeModifier;

        // The participant's turn's place in turnOrder, counted again over the turns the view shows
        @Schema(requiredMode = REQUIRED)
        private Integer sortOrder;

        // Null in the Player View for a monster whose HP it hides behind healthStatus (D157). Exhaustion level 4 or
        // more halves it (PHB p.291, D183): this is the maximum now, and hpMaxBase the one without exhaustion
        private Integer hpMax;
        private Integer hpMaxBase;
        private Integer hpCurrent;
        private Integer hpTemp;

        // Set only in the Player View, for a monster whose HP it hides (D157)
        @Schema(implementation = HealthStatus.class)
        private String healthStatus;

        @Schema(requiredMode = REQUIRED)
        private Integer armourClass;

        private List<ConditionEntry> activeConditions;

        // Each frightened entry's source and whether it is in sight (PHB p.290, D201); null for none
        private List<FearResponse> fears;

        // Its exhaustion level, 0 to 6 (PHB p.291, D183), shown to players for every creature they see
        @Schema(requiredMode = REQUIRED)
        private Integer exhaustionLevel;

        // The spell and slot level of the Concentration Instance it holds, if any (D162)
        private String concentrationSpell;
        private Integer concentrationSlotLevel;

        // The Spell Effects it cast that are still going, such as Spiritual Weapon or Moonbeam (M15)
        @Schema(requiredMode = REQUIRED)
        private List<SpellEffectResponse> activeEffects;

        // The spells whose Ongoing Modifiers are on it, such as Bless or Faerie Fire, one each (D184); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<SpellModifierResponse> spellModifiers;

        // Keyed "1" to "9" by spell level, and "pact_N" for pact slots (D168)
        private Map<String, SpellSlot> spellSlotsCurrent;

        // Null counts as visible (D157)
        private Boolean isVisibleToPlayers;

        // Alive, Dying, Stable or Dead (D161); the column is NOT NULL
        @Schema(requiredMode = REQUIRED, implementation = LifeState.class)
        private String lifeState;

        // Computed from the encounter's current turn (D163)
        @Schema(requiredMode = REQUIRED)
        private Boolean isCurrentTurn;

        // A Dying PC whose turn has started and who hasn't yet rolled the death save it is due (D163)
        @Schema(requiredMode = REQUIRED)
        private Boolean deathSaveDue;

        // DM View only: whether a monster's action templates have lair actions, so it can fight in its lair
        private Boolean hasLairActions;

        private UUID controlledByUserId;

        // A trait that keeps spells off it "unless it wishes to be" affected, as a rakshasa's Limited Magic Immunity
        // (MM p.257, D198), by name, and which spells it lets through, NEVER, HELPFUL or ALWAYS; both null for a
        // creature without one. Players see them only for a creature a player controls
        private String spellConsentTrait;
        private String spellConsent;

        // The columns allow null, but participants are only created through the entity's builder,
        // which starts each of these, and combat code reads them without a null check
        @Schema(requiredMode = REQUIRED)
        private Integer deathSaveSuccesses;

        @Schema(requiredMode = REQUIRED)
        private Integer deathSaveFailures;

        private String notes;
        private Integer spellAttackBonus;
        private Integer spellSaveDc;
        private String spellcastingAbility;
        private List<KnownSpell> spellsKnown;
        private List<ResourcePoolEntry> resourcePoolsCurrent;

        // The beast a druid has become through Wild Shape (PHB p.66), with its own hit points, maximum and AC kept
        // while hpCurrent, hpMax and armourClass are the beast's; null when it isn't transformed
        private WildShapeForm wildShape;

        // The form the creature is in, Wild Shape's or a spell's such as Polymorph's (D192), with its own hit points,
        // maximum and AC kept while hpCurrent, hpMax and armourClass are the form's; null when it is in its own form.
        // Left out of the Player View for a monster, which shows formName only (D157)
        private WildShapeForm form;

        // The name of the creature it has become, such as "Giant Ape", shown as "Name (as Giant Ape)"; null in its own
        // form (D192)
        private String formName;

        // What it has spent of its turn (D174); null in the Player View for a monster (D157)
        private TurnEconomy turnEconomy;

        // The attacks one Attack action gives it (Extra Attack); null in the Player View for a monster
        private Integer attacksPerAction;

        // Its speeds in feet by kind ("walk", "fly", "swim", "climb", "burrow"), walking first; empty when unknown
        @Schema(requiredMode = REQUIRED)
        private Map<String, Integer> speed;

        // A player character's Fighting Styles from the PHB, such as "Protection", chosen as its features (D159, M17),
        // so the session pages can offer what they allow; null for a monster
        private List<String> fightingStyles;

        // The Channel Divinity options a player character can use now, by its class, level and subclass, such as
        // "turn-undead" or "preserve-life" (PHB p.58-63, p.85-88, M17); null for a monster
        private List<String> channelDivinityOptions;

        // The Metamagic options a player character knows, chosen as its features (PHB p.102, M18), so the session pages
        // can offer them on a cast; null for a monster
        private List<Metamagic.Option> metamagicOptions;

        // Whether a player character has Pact of the Chain, so its familiar may attack in place of one of its attacks
        // of the Attack action (PHB p.107); null for anyone else
        private Boolean pactOfTheChain;

        // A Way of the Kensei monk's Monk level, so the session pages offer Kensei's Shot from 3rd and Sharpen the Blade
        // from 11th (XGE p.34-35); null for anyone else
        private Integer kenseiLevel;

        // The caster whose spell summoned it (D182); null for anyone else
        private UUID summonedByParticipantId;

        // What ties it to that spell: the caster's Spell Effect, its initiative, what it may do and its latest command
        // (D182); null for a creature nobody summoned
        private SummonLink summon;
    }

    /**
     * One of a participant's Spell Effects (M15): what it is, how long it has, and how it is used, from its spell's data.
     */
    @Data
    @Builder(toBuilder = true)
    public static class SpellEffectResponse {

        @Schema(requiredMode = REQUIRED)
        private UUID id;

        @Schema(requiredMode = REQUIRED)
        private String spell;

        // The slot level it was cast with; null when unknown, and in the Player View for a monster's (D157)
        private Integer slotLevel;

        // Whether it ends with its caster's concentration
        @Schema(requiredMode = REQUIRED)
        private boolean concentration;

        @Schema(requiredMode = REQUIRED)
        private Integer startedRound;

        // How many rounds it lasts; null when unknown
        private Integer durationRounds;

        // What it has left to spend, such as meteors; null when it has no count
        private Integer usesLeft;

        // What its caster's use of it costs from their turn; null when its caster has nothing to do with it
        private RepeatCost directedCost;

        // False when that use only moves it, as Moonbeam's action does
        @Schema(requiredMode = REQUIRED)
        private boolean directedResolves;

        // The most of its uses one use may spend; null without uses
        private Integer maxUsesEachTurn;

        // The moments its area acts on a creature by itself, which the DM applies
        @Schema(requiredMode = REQUIRED)
        private List<SpellTrigger> triggers;

        // The triggers among them that act once for each of several moves at once, as Spike Growth's 2d4 for each 5
        // feet a creature moves through it (PHB p.277), which a trigger's uses count (D199, D200); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<SpellTrigger> repeatableTriggers;

        // The creatures a summoning spell brings and whether they wait for the DM (D182); null for any other spell
        private SummonSpec summon;

        // The creatures it summoned that are still in the fight, in the order they were made (D182); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<UUID> summonedParticipantIds;

        // The creatures its Ongoing Modifiers are on (D184); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<UUID> affectedIds;

        // The option it was cast with, such as "reduce" (D184); null for none
        private String option;

        // What its Ongoing Modifiers do, in words, such as "+1d4 to attack rolls" (D184); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<String> modifiers;

        // Whether its caster can move it to a new creature once its creature drops to 0 HP (Hunter's Mark, Hex; D184)
        @Schema(requiredMode = REQUIRED)
        private boolean retargetable;

        // Damage still to come at the end of a creature's next turn, as Vitriolic Sphere's (D185); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<SpellEffectEntry.DelayedDamage> delayed;

        // The damage it can still deal before it ends, as Guardian of Faith's (D185); null for no limit
        private Integer damageLeft;

        // What it deals back to a creature that hits its caster in melee, such as "2d8 fire" (D185); null for nothing
        private String retaliation;

        // Each creature's count of saves against it, as Contagion's (D187); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<SpellEffectEntry.Tally> tallies;

        // The save the tallies count and how many successes and failures end them, such as "CON 3/3" (D187); null
        // for none
        private String tallyRule;

        // The creatures it put under its caster's control, as Dominate Person's (D187); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<UUID> controlledIds;
        // The options each use of it chooses between, as Eyebite's asleep, panicked or sickened (D188); empty for none
        @Schema(requiredMode = REQUIRED)
        private List<String> useOptions;

        // What it deals when it ends, as Delayed Blast Fireball's bead (D189); null for nothing
        private DetonationResponse detonation;

        // The creatures it killed that rise at the start of its caster's next turn, as Finger of Death's (D189); empty
        // for none
        @Schema(requiredMode = REQUIRED)
        private List<UUID> risingIds;

        // The creatures that use it with their own action, as the creature Dragon's Breath touched exhales (XGE p.154,
        // D199, D200): its affected creatures for a spell its target uses; empty when only its caster uses it
        @Schema(requiredMode = REQUIRED)
        private List<UUID> usableByIds;
    }

    /**
     * A Spell Effect's detonation (D189): the dice it has grown to, its type, and the save the creatures it marked make,
     * such as Delayed Blast Fireball's "14d6" fire, DEX DC 15 for half.
     */
    @Data
    @Builder(toBuilder = true)
    public static class DetonationResponse {

        @Schema(requiredMode = REQUIRED)
        private String dice;

        private String type;

        @Schema(requiredMode = REQUIRED)
        private String ability;

        @Schema(requiredMode = REQUIRED)
        private int dc;

        @Schema(requiredMode = REQUIRED)
        private boolean halfOnSave;

        // The dice it gains at the end of each of its caster's turns, such as "1d6"; null for none
        private String grows;
    }

    /**
     * A participant's frightened entry, with what it is frightened of and whether that is within its line of sight
     * (PHB p.290, D201): while it is, the creature has disadvantage on its attack rolls and ability checks.
     */
    @Data
    @Builder(toBuilder = true)
    public static class FearResponse {

        // The frightened entry in activeConditions
        @Schema(requiredMode = REQUIRED)
        private UUID conditionId;

        // The creature it fears, or the caster of an illusion it fears; null when none is recorded, the creature has
        // left the encounter, or in the Player View one hidden from players (D157)
        private UUID sourceId;

        // What it fears, such as "Adult Red Dragon" or "Merlin's Illusory Dragon"; null as sourceId is
        private String sourceName;

        // Whether the source is in sight, so the fear gives disadvantage
        @Schema(requiredMode = REQUIRED)
        private boolean inSight;

        // Why, in words: "in sight", "marked out of sight by the DM", "the creature is blinded", "the source isn't
        // there to see" or "the source is invisible"
        @Schema(requiredMode = REQUIRED)
        private String sight;

        // Whether the DM has marked the source out of sight
        @Schema(requiredMode = REQUIRED)
        private boolean markedOutOfSight;
    }

    /** A spell whose Ongoing Modifiers are on a participant (D184), shown as a chip such as "Bless". */
    @Data
    @Builder(toBuilder = true)
    public static class SpellModifierResponse {

        // The caster's Spell Effect that holds them
        @Schema(requiredMode = REQUIRED)
        private UUID effectId;

        // The caster; null in the Player View for a caster hidden from players (D157)
        private UUID casterId;

        @Schema(requiredMode = REQUIRED)
        private String spell;

        // What the chip says: the spell, or the mode chosen for a spell of two, such as "Reduce"
        @Schema(requiredMode = REQUIRED)
        private String label;

        // What they do, in words
        @Schema(requiredMode = REQUIRED)
        private List<String> modifiers;

        // Whether they work against the creature, as Bane or Faerie Fire do
        @Schema(requiredMode = REQUIRED)
        private boolean hinders;
    }

    /** One turn in the order: a participant's, or the Lair's (D163). */
    @Data
    @Builder(toBuilder = true)
    public static class TurnResponse {

        // The participant whose turn it is; null for the Lair's
        private UUID participantId;

        // The participant's name, or "Lair Actions"
        @Schema(requiredMode = REQUIRED)
        private String name;

        // 20 for the Lair; null for a participant without initiative
        private Integer initiative;

        @Schema(requiredMode = REQUIRED)
        private boolean lair;

        // A dead monster's turn, which the order passes over
        @Schema(requiredMode = REQUIRED)
        private boolean skipped;

        @Schema(requiredMode = REQUIRED)
        private boolean current;

        // DM View only: the same number on each turn of a group tied on initiative and DEX, which the DM
        // may reorder; null for a turn tied with no other
        private Integer tieGroup;
    }
}
