package com.tabletophelper.character;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The start and end of every command that changes a character (D158), as EncounterCommands is for an encounter.
 * Only the character's owner may give one, and access is checked by the owner's ID first, so a caller who may not
 * change the character never locks its row. The character is then loaded under a pessimistic write lock held until
 * the transaction ends: commands on one character queue instead of failing, and each starts from the last one's
 * committed state. A wait longer than five seconds is a 409, and so is a write from a copy that someone else has
 * changed since, which the row's version catches. Resting and levelling are refused while the character is in an
 * Active or Paused encounter (D166). Once the command has made its change, {@link #finish} works the Derived Stats
 * out again and saves.
 *
 * <p>Call it from inside the command's transaction.
 */
@Component
@RequiredArgsConstructor
class CharacterCommands {

    private final CharacterRepository characterRepository;
    private final AccessPolicy accessPolicy;
    private final LiveEncounters liveEncounters;
    private final CharacterStats characterStats;
    private final CharacterMapper characterMapper;

    /** A command only the character's owner may give. */
    PlayerCharacter beginAsOwner(UUID characterId, UUID userId) {
        UUID ownerId = characterRepository.findOwnerIdById(characterId)
                .orElseThrow(() -> new NotFoundException("Character not found"));
        accessPolicy.requireCharacterOwner(ownerId, userId);
        characterRepository.limitLockWaits();
        return characterRepository.lockById(characterId)
                .orElseThrow(() -> new NotFoundException("Character not found"));
    }

    /**
     * As {@link #beginAsOwner}, for a command that waits while the character is in an Active or Paused encounter:
     * a rest, a level gained or lost, or the choices a level brings (D166).
     *
     * @param doing what the command does, as in "take a short rest"
     */
    PlayerCharacter beginOutsideEncounters(UUID characterId, UUID userId, String doing) {
        PlayerCharacter character = beginAsOwner(characterId, userId);
        if (liveEncounters.includes(characterId)) {
            throw new ConflictException(character.getName() + " is in an encounter that is under way, so can't "
                    + doing + " until it ends");
        }
        return character;
    }

    /**
     * Works the character's Derived Stats out again, writing their stored copies and syncing its pools, then saves it
     * and returns it as the API does.
     */
    CharacterResponse finish(PlayerCharacter character) {
        characterStats.apply(character);
        return characterMapper.toResponse(characterRepository.save(character));
    }

    /** A whole command by the owner: the lock, the change, then {@link #finish}. */
    CharacterResponse run(UUID characterId, UUID userId, Consumer<PlayerCharacter> change) {
        PlayerCharacter character = beginAsOwner(characterId, userId);
        change.accept(character);
        return finish(character);
    }
}
