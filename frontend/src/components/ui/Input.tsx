import { useId, type ComponentProps, type ReactNode } from 'react';

const LABEL = 'block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5';
// Shared by Input, Select and Textarea. An invalid field gets a red border
const CONTROL = 'w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted aria-[invalid=true]:border-debuff disabled:opacity-50';

interface FieldProps {
  /** Names the field. It shows above it, unless hideLabel is set */
  label: ReactNode;
  /** A message under the field, announced when it appears. It also marks the field invalid */
  error?: ReactNode;
  /** Keeps the label for screen readers only, for a field whose placeholder or surroundings already explain it */
  hideLabel?: boolean;
}

/** Ties a field to its label and error message. */
function useFieldIds(id: string | undefined, error: ReactNode, describedBy: string | undefined) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  const errorId = `${fieldId}-error`;
  const hasError = error != null && error !== false && error !== '';
  return {
    fieldId,
    errorId,
    hasError,
    invalid: hasError || undefined,
    describedBy: [describedBy, hasError ? errorId : undefined].filter(Boolean).join(' ') || undefined,
  };
}

function Field({ ids, label, hideLabel, error, className, children }: FieldProps & {
  ids: ReturnType<typeof useFieldIds>;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={className}>
      <label htmlFor={ids.fieldId} className={hideLabel ? 'sr-only' : LABEL}>{label}</label>
      {children}
      {ids.hasError && (
        <p id={ids.errorId} role="alert" className="mt-1.5 font-body text-[14px] font-semibold text-debuff">{error}</p>
      )}
    </div>
  );
}

type InputProps = ComponentProps<'input'> & FieldProps;

/**
 * A labelled text field (D171). className styles the wrapper around the label, field and error;
 * any other prop goes to the input element.
 */
export default function Input({ label, error, hideLabel, id, className, ...props }: InputProps) {
  const ids = useFieldIds(id, error, props['aria-describedby']);
  return (
    <Field ids={ids} label={label} error={error} hideLabel={hideLabel} className={className}>
      <input {...props} id={ids.fieldId} aria-invalid={ids.invalid} aria-describedby={ids.describedBy} className={CONTROL} />
    </Field>
  );
}

type SelectProps = ComponentProps<'select'> & FieldProps;

/** A labelled dropdown that looks and behaves like Input. */
export function Select({ label, error, hideLabel, id, className, ...props }: SelectProps) {
  const ids = useFieldIds(id, error, props['aria-describedby']);
  return (
    <Field ids={ids} label={label} error={error} hideLabel={hideLabel} className={className}>
      <select {...props} id={ids.fieldId} aria-invalid={ids.invalid} aria-describedby={ids.describedBy} className={CONTROL} />
    </Field>
  );
}

type TextareaProps = ComponentProps<'textarea'> & FieldProps;

/** A labelled multi-line field that looks and behaves like Input. */
export function Textarea({ label, error, hideLabel, id, className, ...props }: TextareaProps) {
  const ids = useFieldIds(id, error, props['aria-describedby']);
  return (
    <Field ids={ids} label={label} error={error} hideLabel={hideLabel} className={className}>
      <textarea {...props} id={ids.fieldId} aria-invalid={ids.invalid} aria-describedby={ids.describedBy} className={CONTROL} />
    </Field>
  );
}
