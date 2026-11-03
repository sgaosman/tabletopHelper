package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.identity.User;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How every character command starts and ends (D158): access by the owner's ID, then the lock wait's limit, then the
 * row lock; the refusal of rests and levelling during an encounter under way (D166); and the derivation and save at
 * the end; and a DM command's checks, before the lock and again under it (D158). CharacterCommandsIT shows the lock and
 * its five-second wait on PostgreSQL.
 */
class CharacterCommandsTest {

    private final CharacterFixture f = new CharacterFixture();

    @Test
    @DisplayName("The owner's command limits the lock wait, then locks the character's row")
    void ownerLocksTheRow() {
        PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
        clearInvocations(f.characterRepository);

        assertThat(f.commands.beginAsOwner(pc.getId(), f.userId)).isSameAs(pc);

        InOrder order = inOrder(f.characterRepository);
        order.verify(f.characterRepository).findOwnerIdById(pc.getId());
        order.verify(f.characterRepository).limitLockWaits();
        order.verify(f.characterRepository).lockById(pc.getId());
    }

    @Test
    @DisplayName("Someone else's command is refused before the row is locked")
    void strangerNeverLocks() {
        PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);

        assertThatThrownBy(() -> f.commands.beginAsOwner(pc.getId(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You do not own this character");
        verify(f.characterRepository, never()).limitLockWaits();
        verify(f.characterRepository, never()).lockById(any());
    }

    @Test
    @DisplayName("A character that doesn't exist is a 404")
    void unknownCharacterIs404() {
        assertThatThrownBy(() -> f.commands.beginAsOwner(UUID.randomUUID(), f.userId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Character not found");
    }

    @Test
    @DisplayName("A command that waits for encounters to end is refused while the character is in an Active or Paused one")
    void waitsForEncountersToEnd() {
        PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);

        assertThat(f.commands.beginOutsideEncounters(pc.getId(), f.userId, "take a long rest")).isSameAs(pc);
        when(f.liveEncounters.includes(pc.getId())).thenReturn(true);
        assertThatThrownBy(() -> f.commands.beginOutsideEncounters(pc.getId(), f.userId, "take a long rest"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Aria is in an encounter that is under way, so can't take a long rest until it ends");
        // Other commands go on: combat changes the participant, not the sheet
        assertThat(f.commands.beginAsOwner(pc.getId(), f.userId)).isSameAs(pc);
    }

    @Test
    @DisplayName("A command ends by working the Derived Stats out again and saving, so a stale stored copy is corrected")
    void finishDerivesAndSaves() {
        PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 3);
        pc.setHpMax(99);
        pc.setProficiencyBonus(6);
        clearInvocations(f.characterRepository);

        var response = f.commands.run(pc.getId(), f.userId, character -> character.setNotes("Rested at the inn"));

        assertThat(response.getHpMax()).isEqualTo(25);
        assertThat(response.getProficiencyBonus()).isEqualTo(2);
        assertThat(response.getNotes()).isEqualTo("Rested at the inn");
        verify(f.characterRepository).save(pc);
    }

    @Test
    @DisplayName("The owner is refused a DM command on their own character, even as the DM of its campaign")
    void ownerIsNotTheirOwnDm() {
        PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
        Campaign campaign = campaign(f.user);
        pc.setCampaign(campaign);
        inCampaign(pc, campaign);

        assertThatThrownBy(() -> f.commands.beginAsDm(pc.getId(), f.userId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You can't use DM actions on your own character");
    }

    @Test
    @DisplayName("A DM command checks the campaign again under the lock, so a character moved meanwhile is refused to a DM who isn't its new campaign's")
    void dmCheckedAgainUnderTheLock() {
        UUID dmId = UUID.randomUUID();
        PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
        Campaign before = campaign(User.builder().id(dmId).username("dm").build());
        Campaign after = campaign(User.builder().id(UUID.randomUUID()).username("other").build());
        // The IDs read before the lock still show the old campaign; the locked row has the new one
        inCampaign(pc, before);
        pc.setCampaign(after);

        assertThatThrownBy(() -> f.commands.beginAsDm(pc.getId(), dmId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the DM of this character's campaign can adjust it");
        verify(f.characterRepository).lockById(pc.getId());

        pc.setCampaign(before);
        assertThat(f.commands.beginAsDm(pc.getId(), dmId)).isSameAs(pc);
    }

    private Campaign campaign(User dm) {
        Campaign campaign = Campaign.builder().id(UUID.randomUUID()).name("Lost Mine").dm(dm).inviteCode("ABCD2345")
                .build();
        when(f.campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        return campaign;
    }

    private void inCampaign(PlayerCharacter pc, Campaign campaign) {
        when(f.characterRepository.findCampaignOfCharacterById(pc.getId())).thenReturn(Optional.of(
                new CharacterRepository.CampaignOfCharacter() {
                    @Override
                    public UUID getCharacterId() {
                        return pc.getId();
                    }

                    @Override
                    public UUID getCampaignId() {
                        return campaign.getId();
                    }
                }));
    }
}
