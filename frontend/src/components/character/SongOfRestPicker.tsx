import { useEffect } from 'react';
import { useSongOfRest } from '../../queries/characters';
import Input, { Select } from '../ui/Input';
import { SONG_OF_REST_DICE, songOfRestRoll, type SongOfRestChoice } from '../../utils/classFeatures';

/**
 * A bard's Song of Rest in the short-rest dialog (PHB p.54, D223): a character that regains hit points by spending
 * Hit Dice regains the bard's die more, a d6 from Bard 2 to a d12 at 17th. The rest is the character's own, so the
 * player says whether a bard sang; the server offers the best a living bard of its campaign, itself included, can
 * sing, and the picker starts on it. Any die can still be chosen, as for a bard who isn't a character in the app.
 */
export default function SongOfRestPicker({ characterId, choice, onChange }: {
  characterId: string;
  choice: SongOfRestChoice;
  onChange: (choice: SongOfRestChoice) => void;
}) {
  const offered = useSongOfRest(characterId).data;

  // The campaign's best Song of Rest is chosen once it arrives, unless the player chose already
  useEffect(() => {
    if (choice.dieSize === undefined && offered) onChange({ ...choice, dieSize: offered.dieSize ?? null });
  }, [choice, offered, onChange]);

  const rollInvalid = Number.isNaN(songOfRestRoll(choice));

  return (
    <section aria-label="Song of Rest" className="bg-page-alt border border-rule px-3 py-2 space-y-2">
      <p className="font-body text-[15px] font-semibold text-ink">Song of Rest</p>
      <p className="font-body text-[13px] text-faint">
        {offered?.dieSize
          ? `${offered.bardName} (Bard ${offered.bardLevel}) can sing it with a d${offered.dieSize}.`
          : 'No bard of 2nd level or higher in your campaign. Choose a die if a bard sings for you.'}
        {' '}It heals only if you spend a Hit Die and regain hit points.
      </p>
      <div className="grid grid-cols-2 gap-2">
        <Select
          label="Song of Rest die"
          hideLabel
          value={choice.dieSize ?? ''}
          onChange={e => onChange({ dieSize: e.target.value ? Number(e.target.value) : null, roll: '' })}
        >
          <option value="">No Song of Rest</option>
          {SONG_OF_REST_DICE.map(die => <option key={die} value={die}>d{die}</option>)}
        </Select>
        {choice.dieSize ? (
          <Input
            label="Song of Rest roll"
            hideLabel
            type="number"
            inputMode="numeric"
            min={1}
            max={choice.dieSize}
            placeholder="Roll for me"
            value={choice.roll}
            onChange={e => onChange({ ...choice, roll: e.target.value })}
            error={rollInvalid ? `1 to ${choice.dieSize}` : undefined}
          />
        ) : null}
      </div>
    </section>
  );
}
