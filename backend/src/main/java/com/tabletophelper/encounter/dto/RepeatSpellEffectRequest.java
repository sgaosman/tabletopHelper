package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.SpellTrigger;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * A use of one of the actor's Spell Effects (M15). Without a trigger it is the caster's own use on their turn, at what
 * the spell's data says it costs, such as Spiritual Weapon's attack for a bonus action; with one it is what the
 * effect's area does by itself to the targets at that moment of a turn, such as Moonbeam's damage to a creature that
 * starts its turn in the beam, which the DM applies and which costs nothing.
 */
@Data
public class RepeatSpellEffectRequest {

    @NotNull
    private List<UUID> targetIds;

    private Boolean advantage;

    private Integer overrideSpellAttackBonus;

    private Integer overrideSpellSaveDC;

    // The Spell Effect to use; may be left out when the actor has only one that can be used this way
    private UUID effectId;

    // The moment of a turn the effect acts at, for the DM's use; null for the caster's own use
    private SpellTrigger trigger;

    // How many of the effect's uses to spend, such as one or two meteors; one when left out
    @Min(1)
    @Max(10)
    private Integer uses;
}
