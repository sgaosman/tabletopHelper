import type { ComponentProps } from 'react';

type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';
type ButtonSize = 'sm' | 'md' | 'lg';

// Theme tokens only, so every theme restyles them. In the Handbook theme bg-ink and border-ink are book red
const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-ink text-card border-ink hover:opacity-90',
  secondary: 'bg-page-alt text-muted border-rule hover:bg-rule',
  danger: 'bg-debuff text-card border-debuff hover:opacity-90',
  ghost: 'text-muted border-transparent hover:text-ink hover:bg-page-alt',
};

const SIZES: Record<ButtonSize, string> = {
  sm: 'px-3 py-1 text-[14px]',
  md: 'px-4 py-2 text-[15px]',
  lg: 'px-5 py-2.5 text-[16px]',
};

interface ButtonProps extends ComponentProps<'button'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** While true, such as during a save, the button is disabled and marked busy */
  loading?: boolean;
}

/**
 * A button with visible text, in one of the app's styles (D171). It never submits a form
 * unless given type="submit"; any other prop goes to the button element.
 */
export default function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  type = 'button',
  disabled,
  className = '',
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex items-center justify-center gap-2 border font-body font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${VARIANTS[variant]} ${SIZES[size]} ${className}`}
    />
  );
}
