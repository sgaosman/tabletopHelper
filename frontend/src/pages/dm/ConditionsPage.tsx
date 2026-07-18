import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, ChevronDown, ChevronRight } from 'lucide-react';
import { getAllConditions } from '../../api/referenceApi';
import type { Condition } from '../../types/reference';
import { sourceName } from '../../utils/sourceNames';

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
        return <strong key={j} className="text-white font-semibold">{part.slice(2, -2)}</strong>;
      }
      return <span key={j}>{part}</span>;
    });

    if (isBullet) {
      return (
        <li key={i} className="flex gap-2 text-gray-300 leading-relaxed">
          <span className="text-red-400 mt-0.5 flex-shrink-0">&bull;</span>
          <span>{rendered}</span>
        </li>
      );
    }
    return <p key={i} className="text-gray-300 leading-relaxed">{rendered}</p>;
  });
}

export default function ConditionsPage() {
  const navigate = useNavigate();
  const [conditions, setConditions] = useState<Condition[]>([]);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    getAllConditions()
      .then(setConditions)
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="min-h-screen bg-gray-900 text-white">
      <div className="sticky top-0 z-10 bg-gray-900 border-b border-gray-800 px-6 py-4">
        <button onClick={() => navigate('/dm')}
          className="flex items-center gap-2 text-gray-400 hover:text-white">
          <ArrowLeft size={20} /> Back to Dashboard
        </button>
      </div>

      <div className="p-6">
      <h1 className="text-3xl font-bold mb-6">Conditions</h1>

      {loading ? (
        <div className="text-center py-12 text-gray-400">Loading...</div>
      ) : (
        <div className="space-y-3">
          {conditions.map(c => (
            <div key={c.id}
              className="bg-gray-800 rounded-lg overflow-hidden border border-gray-700">
              <button
                onClick={() => setExpanded(expanded === c.id ? null : c.id)}
                className="w-full px-6 py-4 text-left flex justify-between items-center hover:bg-gray-700/50 transition-colors">
                <span className="text-lg font-semibold">{c.name}</span>
                {expanded === c.id
                  ? <ChevronDown size={20} className="text-gray-400" />
                  : <ChevronRight size={20} className="text-gray-400" />}
              </button>

              {expanded === c.id && (
                <div className="px-6 pb-5 border-t border-gray-700 pt-4">
                  {c.effects && c.effects.length > 0 && (
                    <ul className="space-y-2 mb-4">
                      {c.effects.map((effect, i) => {
                        const parts = effect
                          .replace(/\b(advantage|disadvantage)\b/gi, '**$1**')
                          .replace(/\b(can't|cannot|unable to|immune|automatically fails|automatically succeeds)\b/gi, '**$1**')
                          .replace(/\b(incapacitated)\b/gi, '**$1**')
                          .split(/(\*\*[^*]+\*\*)/);
                        return (
                          <li key={i} className="flex gap-3 text-gray-300 leading-relaxed">
                            <span className="text-red-400 mt-0.5 flex-shrink-0">&bull;</span>
                            <span>
                              {parts.map((part, j) => {
                                if (part.startsWith('**') && part.endsWith('**')) {
                                  return <strong key={j} className="text-white font-semibold">{part.slice(2, -2)}</strong>;
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
                    <div className="space-y-3">
                      {formatDescription(c.description)}
                    </div>
                  )}

                  <p className="text-xs text-gray-500 text-right mt-4">Source: {sourceName(c.source || '')}</p>
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
