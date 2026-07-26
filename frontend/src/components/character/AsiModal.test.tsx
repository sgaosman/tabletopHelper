import { describe, it, expect } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import { makeMockCharacter } from '../../test/mocks/handlers';
import AsiModal from './AsiModal';

describe('AsiModal', () => {
  const character = makeMockCharacter('char-001', 'Gimli', 'Fighter', 4);

  beforeEach(() => {
    setupAuthToken();
  });

  it('renders with title and both mode buttons', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('Ability Score Improvement')).toBeInTheDocument();
    expect(screen.getByText('Ability Scores')).toBeInTheDocument();
    expect(screen.getByText('Feat')).toBeInTheDocument();
  });

  it('shows all six abilities in ability score mode', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('STR')).toBeInTheDocument();
    expect(screen.getByText('DEX')).toBeInTheDocument();
    expect(screen.getByText('CON')).toBeInTheDocument();
    expect(screen.getByText('INT')).toBeInTheDocument();
    expect(screen.getByText('WIS')).toBeInTheDocument();
    expect(screen.getByText('CHA')).toBeInTheDocument();
  });

  it('shows base ability scores from character', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    // character has strength: 14, dex: 12
    const scores = screen.getAllByText(/^1[0-4]$|^10$/);
    expect(scores.length).toBeGreaterThanOrEqual(4); // multiple scores displayed
  });

  it('increments ability and decrements points remaining', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    // Find the first + button (STR)
    const buttons = screen.getAllByRole('button', { name: '' });
    const plusButtons = buttons.filter(b => b.querySelector('.lucide-plus'));
    expect(screen.getByText('2 points remaining')).toBeInTheDocument();
    await user.click(plusButtons[0]);
    expect(screen.getByText('1 point remaining')).toBeInTheDocument();
  });

  it('disables + buttons after spending 2 points', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    const plusButtons = screen.getAllByRole('button', { name: '' })
      .filter(b => b.querySelector('.lucide-plus'));
    await user.click(plusButtons[0]);
    await user.click(plusButtons[0]);
    expect(screen.getByText('0 points remaining')).toBeInTheDocument();
    plusButtons.forEach(b => expect(b).toBeDisabled());
  });

  it('decrement restores a point', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    const plusButtons = screen.getAllByRole('button', { name: '' })
      .filter(b => b.querySelector('.lucide-plus'));
    await user.click(plusButtons[0]);
    expect(screen.getByText('1 point remaining')).toBeInTheDocument();
    const minusButtons = screen.getAllByRole('button', { name: '' })
      .filter(b => b.querySelector('.lucide-minus'));
    await user.click(minusButtons[0]);
    expect(screen.getByText('2 points remaining')).toBeInTheDocument();
  });

  it('disables Apply until exactly 2 points spent', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
  });

  it('enables Apply when 2 points are allocated', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    const plusButtons = screen.getAllByRole('button', { name: '' })
      .filter(b => b.querySelector('.lucide-plus'));
    await user.click(plusButtons[0]);
    await user.click(plusButtons[1]);
    expect(screen.getByRole('button', { name: 'Apply' })).not.toBeDisabled();
  });

  it('calls API on Apply', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    const plusButtons = screen.getAllByRole('button', { name: '' })
      .filter(b => b.querySelector('.lucide-plus'));
    await user.click(plusButtons[0]);
    await user.click(plusButtons[1]);
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Applying...' })).toBeDisabled();
    });
  });
});
