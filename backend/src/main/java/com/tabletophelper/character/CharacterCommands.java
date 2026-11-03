package com.tabletophelper.character;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The start and end of every command that changes a character (D158), as EncounterCommands is for an encounter.
 * Only the character's owner may give one, except a DM Adjustment, which only the DM of the character's campaign may
 * give, and access is checked by ID first, so a caller who may not change the character never locks its row. The
 * character is then loaded under a pessimistic write lock held until the transaction ends: commands on one character
 * queue instead of failing, and each starts from the last one's committed state. A wait longer than five seconds is a
 * 409, and so is a write from a copy that someone else has changed since, which the row's version catches. Resting and
 * levelling are refused while the character is in an Active or Paused encounter (D166), and for a dead character. Once
 * the command has made its change, {@link #finish} works the Derived Stats out again and saves. Write-back locks the
 * characters an encounter's results apply to through {@link #beginWriteBack}.
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
     * A command only the DM of the character's campaign may give: a DM Adjustment (D158). Its owner may not, and a
     * character in no campaign has no DM. The campaign is checked again under the lock, in case the owner moved the
     * character while the command waited for it.
     */
    PlayerCharacter beginAsDm(UUID characterId, UUID userId) {
        UUID campaignId = characterRepository.findCampaignOfCharacterById(characterId)
                .orElseThrow(() -> new NotFoundException("Character not found"))
                .getCampaignId();
        accessPolicy.requireCharacterDm(campaignId, userId);
        characterRepository.limitLockWaits();
        PlayerCharacter character = characterRepository.lockById(characterId)
                .orElseThrow(() -> new NotFoundException("Character not found"));
        UUID lockedCampaignId = character.getCampaign() != null ? character.getCampaign().getId() : null;
        if (!Objects.equals(lockedCampaignId, campaignId)) {
            accessPolicy.requireCharacterDm(lockedCampaignId, userId);
        }
        return character;
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
     * Refuses with a 409 what a dead character can't do until the DM revives it (D166): a rest, healing, damage or
     * temporary hit points on the sheet, or a level gained or lost.
     *
     * @param doing what the command does, as in "take a short rest"
     */
    static void requireAlive(PlayerCharacter character, String doing) {
        if (character.isDead()) {
            throw new ConflictException(character.getName() + " is dead, so can't " + doing
                    + " until the DM revives them");
        }
    }

    /**
     * The characters an encounter's results are written back to (D166), each locked with the five-second wait in ID
     * order, the order PostgreSQL sorts UUIDs in, after the encounter's own row, which the command completing the
     * encounter or removing the participant already holds. So two completions that share characters, or a completion
     * and a character command, never wait on each other in a circle. Access was the encounter's to check: only its DM
     * completes it or removes a participant. A character loaded earlier in the transaction and changed since by
     * someone else fails the lock's version check with a 409 rather than being written back stale. A character that
     * no longer exists, which its participant's foreign key rules out, is left out.
     */
    List<PlayerCharacter> beginWriteBack(Collection<UUID> characterIds) {
        List<UUID> inOrder = characterIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        if (inOrder.isEmpty()) {
            return List.of();
        }
        characterRepository.limitLockWaits();
        return inOrder.stream()
                .flatMap(characterId -> characterRepository.lockById(characterId).stream())
                .toList();
    }

    /**
     * Works the character's Derived Stats out again, writing their stored copies and syncing its pools, then saves it
     * and returns it as the API does.
     */
    CharacterResponse finish(PlayerCharacter character) {
        return characterMapper.toResponse(save(character));
    }

    /** As {@link #finish}, for a change that answers with no character: Write-back's. */
    PlayerCharacter save(PlayerCharacter character) {
        characterStats.apply(character);
        return characterRepository.save(character);
    }

    /** A whole command by the owner: the lock, the change, then {@link #finish}. */
    CharacterResponse run(UUID characterId, UUID userId, Consumer<PlayerCharacter> change) {
        PlayerCharacter character = beginAsOwner(characterId, userId);
        change.accept(character);
        return finish(character);
    }
}
