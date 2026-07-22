import type { PlayerCharacter } from '../../../types/character';
import FormattedDescription from '../../../components/FormattedDescription';

export default function FeaturesTab({ features }: {
  features: Array<{ name: string; description: string; source?: string }>;
  char?: PlayerCharacter;
}) {
  return (
    <div className="space-y-3">
      {features.length > 0 ? (
        features.map((f, i) => (
          <details key={i} className="bg-card border border-rule group">
            <summary className="px-4 py-3 cursor-pointer list-none flex items-center justify-between">
              <div>
                <h4 className="font-heading text-[13px] font-semibold text-ink">{f.name}</h4>
                {f.source && <span className="font-body text-[11px] text-faint">{f.source}</span>}
              </div>
              <svg className="w-4 h-4 text-faint group-open:rotate-180 transition-transform" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </summary>
            <div className="px-4 pb-3 border-t border-rule-light">
              <FormattedDescription text={f.description} className="mt-2 font-body text-[13px] [&_p]:text-muted [&_strong]:text-ink" />
            </div>
          </details>
        ))
      ) : (
        <p className="font-body text-[13px] text-faint">No features recorded. Features will be populated based on your race, class, and background choices.</p>
      )}
    </div>
  );
}
