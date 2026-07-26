import { describe, it, expect } from 'vitest';
import { render, screen } from '../test/test-utils';
import FormattedDescription from './FormattedDescription';

describe('FormattedDescription', () => {
  it('returns null for empty text', () => {
    const { container } = render(<FormattedDescription text="" />);
    expect(container.innerHTML).toBe('');
  });

  it('returns null for null/undefined text', () => {
    const html = render(<FormattedDescription text={''} />);
    expect(html.container.textContent).toBe('');
  });

  it('renders plain text paragraphs', () => {
    render(<FormattedDescription text="First paragraph.\nSecond paragraph." />);
    expect(screen.getByText(/First paragraph/)).toBeInTheDocument();
    expect(screen.getByText(/Second paragraph/)).toBeInTheDocument();
  });

  it('renders bold text for D&D mechanics terms', () => {
    render(<FormattedDescription text="Make a Strength saving throw." />);
    const bold = screen.getByText('Strength');
    expect(bold.tagName).toBe('STRONG');
  });

  it('renders bold for dice expressions', () => {
    render(<FormattedDescription text="Deals 2d6+3 fire damage." />);
    const bold = screen.getByText('2d6+3');
    expect(bold.tagName).toBe('STRONG');
  });

  it('cleans 5e.tools markup tags', () => {
    render(<FormattedDescription text="{@spell Fireball} deals damage." />);
    expect(screen.getByText('Fireball deals damage.')).toBeInTheDocument();
  });

  it('converts {@atk mw} to melee weapon attack label', () => {
    render(<FormattedDescription text="{@atk mw} +5 to hit, reach 5 ft." />);
    expect(screen.getByText(/Melee Weapon Attack/)).toBeInTheDocument();
    expect(screen.getByText(/5 to hit/)).toBeInTheDocument();
  });

  it('converts {@hit 5} to +5', () => {
    render(<FormattedDescription text="{@hit 5} to hit." />);
    expect(screen.getByText(/\+5 to hit/)).toBeInTheDocument();
  });

  it('converts {@dc 13} to DC 13', () => {
    render(<FormattedDescription text="Save {@dc 13} Wisdom." />);
    expect(screen.getByText(/DC 13/)).toBeInTheDocument();
    expect(screen.getByText(/Save/)).toBeInTheDocument();
  });

  it('handles multiple tags in a single string', () => {
    render(<FormattedDescription text="The {@creature goblin} makes a {@atk mw} with {@hit 4}." />);
    expect(screen.getByText(/The goblin makes a/)).toBeInTheDocument();
    expect(screen.getByText(/Melee Weapon Attack/)).toBeInTheDocument();
    expect(screen.getByText(/with \+4/)).toBeInTheDocument();
  });
});
