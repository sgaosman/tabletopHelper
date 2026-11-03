package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PendingChoiceService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Adds the Pending Choices existing characters' levels missed (D159): an Ability Score Improvement at a level that
 * gives one and records none, a subclass for a class at or past its subclass level without one, and Expertise at a
 * level that gives it and records none, less the Expertise the sheet has that no level records. Fighting Styles and
 * known spells aren't backfilled. Each character that would gain choices is reported with each choice, and one whose
 * classes can't be found is skipped with a warning. A second run adds nothing, since the choices it added are
 * pending. Dry run by default; {@code --maintenance.apply=true} writes.
 */
@Component
@RequiredArgsConstructor
public class BackfillPendingChoicesJob implements MaintenanceJob {

    private final CharacterRepository characterRepository;
    private final PendingChoiceService pendingChoiceService;

    @Override
    public String name() {
        return "backfill-pending-choices";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<PlayerCharacter> characters = new ArrayList<>(characterRepository.findAll());
        characters.sort(Comparator.comparing((PlayerCharacter pc) -> String.valueOf(pc.getName()),
                String.CASE_INSENSITIVE_ORDER).thenComparing(pc -> String.valueOf(pc.getId())));

        for (PlayerCharacter pc : characters) {
            String who = describe(pc);
            List<PendingChoice> missed;
            try {
                missed = pendingChoiceService.missedChoices(pc);
            } catch (ConflictException e) {
                warnings.add(who + ": skipped (" + e.getMessage() + ")");
                continue;
            }
            if (missed.isEmpty()) continue;
            changes.add(who + String.join("", missed.stream().map(choice -> "\n      + " + describe(choice)).toList()));
            if (apply) {
                pendingChoiceService.addPending(pc, missed);
                characterRepository.save(pc);
            }
        }
        return new MaintenanceReport(name(), apply, "characters", characters.size(), changes, warnings);
    }

    private static String describe(PlayerCharacter pc) {
        return pc.getName() + " (" + pc.getId() + ", level " + pc.getLevel() + " " + pc.getCharacterClass()
                + (Boolean.FALSE.equals(pc.getIsActive()) ? ", deleted" : "") + ")";
    }

    // Such as "ASI at Fighter 6 (level 6)" or "EXPERTISE ×2 at Bard 3 (level 3)"
    private static String describe(PendingChoice choice) {
        return choice.type() + (choice.count() > 1 ? " ×" + choice.count() : "") + " at " + choice.className() + " "
                + choice.classLevel() + " (level " + choice.characterLevel() + ")";
    }
}
