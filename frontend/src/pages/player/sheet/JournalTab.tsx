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
        <div key={key} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider">{label}</h3>
            <button
              onClick={() => {
                if (editing === key) { save(key); }
                else { setEditing(key); setEditValue((char[key] as string) || ''); }
              }}
              className="text-indigo-400 text-xs hover:text-indigo-300"
            >
              {editing === key ? 'Save' : 'Edit'}
            </button>
          </div>
          {editing === key ? (
            <textarea
              value={editValue}
              onChange={e => setEditValue(e.target.value)}
              rows={4}
              className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm resize-none focus:outline-none focus:ring-2 focus:ring-indigo-500"
              autoFocus
            />
          ) : (
            <p className="text-gray-300 text-sm whitespace-pre-line">
              {(char[key] as string) || <span className="text-gray-600 italic">Not set</span>}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
