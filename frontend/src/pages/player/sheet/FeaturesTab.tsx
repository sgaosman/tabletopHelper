import type { PlayerCharacter } from '../../../types/character';
import FormattedDescription from '../../../components/FormattedDescription';

export default function FeaturesTab({ features, char }: {
  features: Array<{ name: string; description: string; source?: string }>;
  char: PlayerCharacter;
}) {
  return (
    <div className="space-y-3">
      {features.length > 0 ? (
        features.map((f, i) => (
          <details key={i} className="bg-gray-900 border border-gray-800 rounded-lg group">
            <summary className="px-4 py-3 cursor-pointer list-none flex items-center justify-between">
              <div>
                <h4 className="text-white font-medium text-sm">{f.name}</h4>
                {f.source && <span className="text-gray-500 text-xs">{f.source}</span>}
              </div>
              <svg className="w-4 h-4 text-gray-500 group-open:rotate-180 transition-transform" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </summary>
            <div className="px-4 pb-3">
              <FormattedDescription text={f.description} />
            </div>
          </details>
        ))
      ) : (
        <p className="text-gray-500 text-sm">No features recorded. Features will be populated based on your race, class, and background choices.</p>
      )}
    </div>
  );
}
