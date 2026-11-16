package com.tabletophelper.character.dto;

/**
 * The Song of Rest a resting character could hear (PHB p.54, D223): the best one a living bard of 2nd level or higher
 * in its campaign, itself included, can perform, so the short-rest dialog can offer it. The server can't know whether
 * the bard performed during the rest, so the player still chooses whether it applies.
 *
 * @param dieSize   the bard's die, 6, 8, 10 or 12; null when no bard in the campaign has Song of Rest
 * @param bardName  the bard's name; null with no die
 * @param bardLevel the bard's level in the Bard class; null with no die
 */
public record SongOfRestResponse(Integer dieSize, String bardName, Integer bardLevel) {

    /** No bard can perform Song of Rest. */
    public static final SongOfRestResponse NONE = new SongOfRestResponse(null, null, null);
}
