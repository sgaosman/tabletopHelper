import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Input, { Select, Textarea } from './Input';

describe('Input', () => {
  it('ties its label to the field', () => {
    render(<><Input label="Character name" /><Input label="Player name" /></>);
    const character = screen.getByLabelText('Character name');
    const player = screen.getByLabelText('Player name');

    expect(character.tagName).toBe('INPUT');
    expect(character.id).not.toBe('');
    expect(character.id).not.toBe(player.id);
  });

  it('keeps an id it is given', () => {
    render(<Input label="Character name" id="character-name" />);

    expect(screen.getByLabelText('Character name')).toHaveAttribute('id', 'character-name');
  });

  it('marks the field invalid and describes it with an announced error', () => {
    render(<Input label="Spell Save DC" error="Enter a number from 1 to 30" />);
    const field = screen.getByLabelText('Spell Save DC');
    const error = screen.getByRole('alert');

    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveAttribute('aria-describedby', error.id);
    expect(field).toHaveAccessibleDescription('Enter a number from 1 to 30');
    expect(error).toHaveTextContent('Enter a number from 1 to 30');
  });

  it('is valid and undescribed without an error', () => {
    render(<Input label="Spell Save DC" />);
    const field = screen.getByLabelText('Spell Save DC');

    expect(field).not.toHaveAttribute('aria-invalid');
    expect(field).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps a description it is given alongside the error', () => {
    render(<><p id="dc-hint">8 + proficiency + ability modifier</p><Input label="Spell Save DC" aria-describedby="dc-hint" error="Too high" /></>);

    expect(screen.getByLabelText('Spell Save DC')).toHaveAccessibleDescription('8 + proficiency + ability modifier Too high');
  });

  it('keeps a hidden label for screen readers', () => {
    render(<Input label="Search spells" hideLabel placeholder="Search spells..." />);

    expect(screen.getByLabelText('Search spells')).toBe(screen.getByPlaceholderText('Search spells...'));
    expect(screen.getByText('Search spells')).toHaveClass('sr-only');
  });

  it('passes other props to the field and its className to the wrapper', async () => {
    const onChange = vi.fn();
    const { container } = render(<Input label="Notes" className="mb-4" onChange={onChange} type="text" />);

    await userEvent.type(screen.getByLabelText('Notes'), 'Hi');

    expect(onChange).toHaveBeenCalledTimes(2);
    expect(container.firstElementChild).toHaveClass('mb-4');
    expect(screen.getByLabelText('Notes')).not.toHaveClass('mb-4');
  });

  it('gives a Select and a Textarea the same label and error wiring', () => {
    render(
      <>
        <Select label="Campaign" error="Choose a campaign">
          <option value="">No campaign</option>
        </Select>
        <Textarea label="Journal" error="Too long" />
      </>,
    );
    const select = screen.getByLabelText('Campaign');
    const textarea = screen.getByLabelText('Journal');

    expect(select.tagName).toBe('SELECT');
    expect(select).toHaveAttribute('aria-invalid', 'true');
    expect(select).toHaveAccessibleDescription('Choose a campaign');
    expect(textarea.tagName).toBe('TEXTAREA');
    expect(textarea).toHaveAccessibleDescription('Too long');
    expect(select.className).toBe(textarea.className);
  });
});
