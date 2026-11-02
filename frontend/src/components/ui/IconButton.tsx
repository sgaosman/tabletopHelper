import type { ComponentProps } from 'react';

type IconButtonProps = Omit<ComponentProps<'button'>, 'aria-label' | 'title'> & {
  /** What the button does, such as "Close". Screen readers announce it, and it shows as a tooltip */
  label: string;
};

/**
 * A button that shows only an icon or a symbol (D100, D171). The required label names it for
 * screen readers and as a tooltip, so an IconButton without one doesn't compile. It never submits
 * a form unless given type="submit", and adds no styling of its own.
 */
export default function IconButton({ label, type = 'button', ...props }: IconButtonProps) {
  return <button {...props} type={type} aria-label={label} title={label} />;
}
