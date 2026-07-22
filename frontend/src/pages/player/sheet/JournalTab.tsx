import { useState } from 'react';
import type { PlayerCharacter, CharacterUpdateRequest } from '../../../types/character';

export default function JournalTab({ char, saveField }: {
  char: PlayerCharacter;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
}) {
  const [editing, setEditing] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');

  const fields: Array<{ key: keyof PlayerCharacter; label: string }> = [
    { key: 'personalityTraits', label: 'Personality Traits' },
    { key: 'ideals', label: 'Ideals' },
    { key: 'bonds', label: 'Bonds' },
    { key: 'flaws', label: 'Flaws' },
    { key: 'notes', label: 'Notes' },
  ];

  async function save(key: string) {
    await saveField({ [key]: editValue });
    setEditing(null);
  }

  return (
    <div className="space-y-4">
      {fields.map(({ key, label }) => (
        <div key={key} className="bg-card border border-rule p-4">
          <div className="flex items-center justify-between mb-2">
            <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">{label}</h3>
            <button
              onClick={() => {
                if (editing === key) { save(key); }
                else { setEditing(key); setEditValue((char[key] as string) || ''); }
              }}
              className="font-body text-[12px] font-medium text-muted hover:text-ink transition-colors"
            >
              {editing === key ? 'Save' : 'Edit'}
            </button>
          </div>
          {editing === key ? (
            <textarea
              value={editValue}
              onChange={e => setEditValue(e.target.value)}
              rows={4}
              className="w-full px-3 py-2 bg-page border border-rule font-body text-[14px] font-medium text-ink resize-none focus:outline-none focus:border-muted"
              autoFocus
            />
          ) : (
            <p className="font-body text-[13px] font-medium text-muted whitespace-pre-line">
              {(char[key] as string) || <span className="text-faint italic">Not set</span>}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
