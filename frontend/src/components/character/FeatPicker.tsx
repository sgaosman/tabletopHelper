import { useState } from 'react';
import { apiErrorMessage } from '../../api/errors';
import { useFeatOptions } from '../../queries/characters';
import { useFeats } from '../../queries/reference';
import type { AsiChoice, PlayerCharacter } from '../../types/character';
import FeatChoices from './FeatChoices';
import FeatList from './FeatList';

interface FeatPickerProps {
  character: PlayerCharacter;
  onSelectionChange: (selection: AsiChoice | null) => void;
  /** For a DM's grant (D158): a feat whose prerequisites the character doesn't meet can be picked, with them shown */
  ignorePrerequisites?: boolean;
  /** For a background's feat (D159): only the feats it offers, by name in any case, are listed */
  only?: string[] | null;
}

/**
 * Picks a feat and makes the choices it asks for. The feats come from the reference data, and whether the character
 * meets each prerequisite from the server's feat options (D159), so a player is offered only what the server would
 * accept. The choices are keyed by the feat, so picking another starts them afresh.
 */
export default function FeatPicker({ character, onSelectionChange, ignorePrerequisites = false, only }: FeatPickerProps) {
  const feats = useFeats();
  const options = useFeatOptions(character.id);
  const [featId, setFeatId] = useState<string | null>(null);

  const error = feats.error ?? options.error;
  if (error) {
    return <div role="alert" className="font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(error)}</div>;
  }
  if (!feats.data || !options.data) {
    return <p className="font-body text-[15px] text-faint text-center py-4">Loading feats...</p>;
  }

  const feat = featId ? feats.data.find(f => f.id === featId) : undefined;
  if (!feat) {
    return (
      <FeatList feats={feats.data} options={options.data} onPick={picked => setFeatId(picked.id)}
        ignorePrerequisites={ignorePrerequisites} only={only} />
    );
  }
  return (
    <FeatChoices key={feat.id} feat={feat} character={character} onSelectionChange={onSelectionChange}
      onBack={() => { setFeatId(null); onSelectionChange(null); }} />
  );
}
