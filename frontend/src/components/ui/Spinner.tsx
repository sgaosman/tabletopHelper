import { LoaderCircle } from 'lucide-react';

const SIZES = { sm: 'w-4 h-4', md: 'w-5 h-5', lg: 'w-6 h-6' } as const;

/**
 * A turning circle in the current text colour. With a `label` it is announced (role="status"), for a section that is
 * loading on its own; without one it only decorates something that already says it is busy, such as a Button.
 */
export default function Spinner({ size = 'sm', label, className = '' }: {
  size?: keyof typeof SIZES;
  label?: string;
  className?: string;
}) {
  const icon = <LoaderCircle aria-hidden="true" className={`${SIZES[size]} animate-spin motion-reduce:animate-none shrink-0 ${className}`} />;
  if (!label) return icon;
  return (
    <span role="status" className="inline-flex items-center gap-2 font-body text-[15px] font-semibold text-muted">
      {icon}
      {label}
    </span>
  );
}
