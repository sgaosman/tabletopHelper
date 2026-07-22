import { useNavigate } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

interface PlaceholderPageProps {
  title: string;
  milestone: number;
  description: string;
}

export default function PlaceholderPage({ title, milestone, description }: PlaceholderPageProps) {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-page">
      <header className="border-b border-rule px-6 py-4">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-2 text-muted hover:text-ink font-body text-[13px] font-medium transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          Back
        </button>
      </header>

      <main className="max-w-2xl mx-auto px-6 py-20 text-center">
        <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink mb-4">{title}</h1>
        <p className="font-body text-[14px] font-medium text-muted mb-6">{description}</p>
        <span className="inline-block px-3 py-1 bg-page-alt border border-rule font-heading text-[9px] font-medium tracking-[0.02em] text-faint">
          Milestone {milestone}
        </span>
      </main>
    </div>
  );
}
