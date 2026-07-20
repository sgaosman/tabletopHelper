export default function StatCard({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-3 text-center">
      <p className="text-gray-500 text-xs">{label}</p>
      <p className="text-white font-bold text-lg">{value}</p>
      {sub && <p className="text-gray-600 text-xs">{sub}</p>}
    </div>
  );
}
