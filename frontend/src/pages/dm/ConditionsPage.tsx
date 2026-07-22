import { useState, useEffect } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { getAllConditions } from '../../api/referenceApi';
import type { Condition } from '../../types/reference';
import { sourceName } from '../../utils/sourceNames';
import NavBar from '../../components/common/NavBar';

function formatDescription(text: string) {
  const paragraphs = text.split('\n').filter(p => p.trim());
  return paragraphs.map((p, i) => {
    const isBullet = p.trim().startsWith('- ');
    const content = isBullet ? p.trim().slice(2) : p;

    const boldedContent = content
      .replace(/^(\d+):/, '**$1.**')
      .replace(/\b(advantage|disadvantage)\b/gi, '**$1**')
      .replace(/\b(can't|cannot|unable to|immune|automatically fails|automatically succeeds)\b/gi, '**$1**')
      .replace(/\b(level \d)\b/gi, '**$1**')
      .replace(/\b(speed (?:is |becomes )?(?:halved|reduced to 0|0))\b/gi, '**$1**')
      .replace(/\b(hit point maximum)\b/gi, '**$1**')
      .replace(/\b(incapacitated)\b/gi, '**$1**')
      .replace(/\b(dies|death)\b/gi, '**$1**');

    const parts = boldedContent.split(/(\*\*[^*]+\*\*)/);
    const rendered = parts.map((part, j) => {
      if (part.startsWith('**') && part.endsWith('**')) {
        return <strong key={j} className="text-ink font-semibold">{part.slice(2, -2)}</strong>;
      }
      return <span key={j}>{part}</span>;
    });

    if (isBullet) {
      return (
        <li key={i} className="flex gap-2 font-body text-[12px] font-medium text-muted leading-relaxed">
          <span className="text-debuff mt-0.5 flex-shrink-0">&bull;</span>
          <span>{rendered}</span>
        </li>
      );
    }
    return <p key={i} className="font-body text-[12px] font-medium text-muted leading-relaxed">{rendered}</p>;
  });
}

export default function ConditionsPage() {
  const [conditions, setConditions] = useState<Condition[]>([]);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    getAllConditions()
      .then(setConditions)
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <div className="max-w-4xl mx-auto px-6 py-8">
        <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-1">Conditions</h1>
        <p className="font-body text-[13px] font-medium text-muted mb-6">Reference conditions and their effects</p>

        {loading ? (
          <div className="text-center py-12 font-body text-[14px] text-muted">Loading...</div>
        ) : (
          <div className="space-y-1.5">
            {conditions.map(c => (
              <div key={c.id} className="bg-card border border-rule">
                <button
                  onClick={() => setExpanded(expanded === c.id ? null : c.id)}
                  className="w-full px-3 py-2.5 text-left flex justify-between items-center hover:bg-page-alt transition-colors">
                  <span className="font-heading text-[14px] font-bold text-debuff">{c.name}</span>
                  {expanded === c.id
                    ? <ChevronDown size={16} className="text-faint" />
                    : <ChevronRight size={16} className="text-faint" />}
                </button>

                {expanded === c.id && (
                  <div className="px-3 pb-3 border-t border-rule-light pt-3">
                    {c.effects && c.effects.length > 0 && (
                      <ul className="space-y-1.5 mb-3">
                        {c.effects.map((effect, i) => {
                          const parts = effect
                            .replace(/\b(advantage|disadvantage)\b/gi, '**$1**')
                            .replace(/\b(can't|cannot|unable to|immune|automatically fails|automatically succeeds)\b/gi, '**$1**')
                            .replace(/\b(incapacitated)\b/gi, '**$1**')
                            .split(/(\*\*[^*]+\*\*)/);
                          return (
                            <li key={i} className="flex gap-2 font-body text-[12px] font-medium text-muted leading-relaxed">
                              <span className="text-debuff mt-0.5 flex-shrink-0">&bull;</span>
                              <span>
                                {parts.map((part, j) => {
                                  if (part.startsWith('**') && part.endsWith('**')) {
                                    return <strong key={j} className="text-ink font-semibold">{part.slice(2, -2)}</strong>;
                                  }
                                  return <span key={j}>{part}</span>;
                                })}
                              </span>
                            </li>
                          );
                        })}
                      </ul>
                    )}

                    {c.description && (
                      <div className="space-y-2">
                        {formatDescription(c.description)}
                      </div>
                    )}

                    <p className="font-body text-[11px] text-faint text-right mt-3">Source: {sourceName(c.source || '')}</p>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
