export default function StatCard({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="bg-card border border-rule p-3 text-center">
      <p className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">{label}</p>
      <p className="font-heading text-[17px] font-bold text-ink">{value}</p>
      {sub && <p className="font-body text-[11px] font-medium text-muted">{sub}</p>}
    </div>
  );
}
