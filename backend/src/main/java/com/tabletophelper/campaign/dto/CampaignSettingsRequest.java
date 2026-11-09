package com.tabletophelper.campaign.dto;

/**
 * The DM's campaign settings. Each one is optional: a setting left out, or sent as null, keeps its
 * current value.
 *
 * @param showExactMonsterHp         whether players see exact monster HP rather than a Health Status (D157)
 * @param promptPlayerRepeatSaves    whether players roll their characters' Repeat Saves rather than the server
 *                                   (D162)
 * @param allowPlayerManualModifiers whether players may enter Manual Modifiers in combat (D164)
 * @param dmChoosesConjuredCreatures whether the DM chooses Conjure Animals' creatures rather than the caster (PHB
 *                                   p.225: "The DM has the creatures' statistics"; D182)
 */
public record CampaignSettingsRequest(Boolean showExactMonsterHp, Boolean promptPlayerRepeatSaves,
                                      Boolean allowPlayerManualModifiers, Boolean dmChoosesConjuredCreatures) {

    /** The settings before the conjured creatures' one. */
    public CampaignSettingsRequest(Boolean showExactMonsterHp, Boolean promptPlayerRepeatSaves,
                                   Boolean allowPlayerManualModifiers) {
        this(showExactMonsterHp, promptPlayerRepeatSaves, allowPlayerManualModifiers, null);
    }
}
