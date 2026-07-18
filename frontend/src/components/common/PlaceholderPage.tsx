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
    <div className="min-h-screen bg-gray-950">
      <header className="border-b border-gray-800 px-6 py-4">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          Back
        </button>
      </header>

      <main className="max-w-2xl mx-auto px-6 py-20 text-center">
        <h1 className="text-3xl font-bold text-white mb-4">{title}</h1>
        <p className="text-gray-400 mb-6">{description}</p>
        <span className="inline-block px-3 py-1 bg-gray-800 border border-gray-700 rounded-full text-sm text-gray-400">
          Milestone {milestone}
        </span>
      </main>
    </div>
  );
}
