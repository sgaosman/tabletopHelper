import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Button from './Button';

describe('Button', () => {
  it('is a plain button by default, so it never submits the form around it', async () => {
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(<form onSubmit={onSubmit}><Button>Roll</Button></form>);

    await userEvent.click(screen.getByRole('button', { name: 'Roll' }));

    expect(screen.getByRole('button', { name: 'Roll' })).toHaveAttribute('type', 'button');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the form when given type="submit"', async () => {
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(<form onSubmit={onSubmit}><Button type="submit">Save</Button></form>);

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('styles each variant with theme tokens', () => {
    render(
      <>
        <Button>Primary</Button>
        <Button variant="secondary">Secondary</Button>
        <Button variant="danger">Danger</Button>
        <Button variant="ghost">Ghost</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'Primary' })).toHaveClass('bg-ink', 'text-card');
    expect(screen.getByRole('button', { name: 'Secondary' })).toHaveClass('bg-page-alt', 'text-muted', 'border-rule');
    expect(screen.getByRole('button', { name: 'Danger' })).toHaveClass('bg-debuff', 'text-card');
    expect(screen.getByRole('button', { name: 'Ghost' })).toHaveClass('text-muted', 'border-transparent');
  });

  it('is disabled and marked busy while loading', async () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>Saving...</Button>);
    const button = screen.getByRole('button', { name: 'Saving...' });

    await userEvent.click(button);

    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(onClick).not.toHaveBeenCalled();
  });

  it('is neither busy nor disabled when idle', () => {
    render(<Button>Save</Button>);

    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Save' })).not.toHaveAttribute('aria-busy');
  });

  it('passes other props through to the button', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick} aria-pressed="true" className="w-full" data-step="confirm" disabled={false}>Advantage</Button>);
    const button = screen.getByRole('button', { name: 'Advantage' });

    await userEvent.click(button);

    expect(onClick).toHaveBeenCalledTimes(1);
    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(button).toHaveAttribute('data-step', 'confirm');
    expect(button).toHaveClass('w-full', 'bg-ink');
  });
});
