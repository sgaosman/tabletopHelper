import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../test/test-utils';
import { makeMockCharacter } from '../../test/mocks/handlers';
import ExpertiseModal from './ExpertiseModal';

describe('ExpertiseModal', () => {
  const character = makeMockCharacter('char-001', 'Rogue', 'Rogue', 6);

  it('renders with the correct count and progress indicator', () => {
    render(<ExpertiseModal character={character} count={2} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('Choose Expertise')).toBeInTheDocument();
    expect(screen.getByText(/Select 2 skills.*0\/2/)).toBeInTheDocument();
  });

  it('shows only proficient skills minus existing expertise', () => {
    // char has 'athletics' and 'perception' as proficiencies, no expertises
    render(<ExpertiseModal character={character} count={1} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('athletics')).toBeInTheDocument();
    expect(screen.getByText('perception')).toBeInTheDocument();
  });

  it('filters out skills already marked as expertise', () => {
    const expertChar = {
      ...character,
      skillExpertises: JSON.stringify(['athletics']),
    };
    render(<ExpertiseModal character={expertChar} count={1} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.queryByText('athletics')).not.toBeInTheDocument();
    expect(screen.getByText('perception')).toBeInTheDocument();
  });

  it('shows empty state when no eligible skills exist', () => {
    const fullExpertChar = {
      ...character,
      skillExpertises: JSON.stringify(['athletics', 'perception']),
    };
    render(<ExpertiseModal character={fullExpertChar} count={1} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('No eligible skills for expertise.')).toBeInTheDocument();
  });

  it('enforces max selection count', async () => {
    const onComplete = () => {};
    render(<ExpertiseModal character={character} count={1} onComplete={onComplete} onClose={() => {}} />);
    const user = userEvent.setup();

    await user.click(screen.getByText('athletics'));
    expect(screen.getByText(/Select 1 skill.*1\/1/)).toBeInTheDocument();

    // Clicking another should not add it (count = 1 limit)
    await user.click(screen.getByText('perception'));
    expect(screen.getByText(/Select 1 skill.*1\/1/)).toBeInTheDocument();
  });

  it('disables confirm when wrong number selected', () => {
    render(<ExpertiseModal character={character} count={2} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('button', { name: 'Confirm Expertise' })).toBeDisabled();
  });

  it('enables confirm when correct count selected', async () => {
    render(<ExpertiseModal character={character} count={1} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('athletics'));
    expect(screen.getByRole('button', { name: 'Confirm Expertise' })).not.toBeDisabled();
  });

  it('calls onComplete with selected skills', async () => {
    let selected: string[] = [];
    render(<ExpertiseModal character={character} count={1} onComplete={(s) => { selected = s; }} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('perception'));
    await user.click(screen.getByRole('button', { name: 'Confirm Expertise' }));
    expect(selected).toEqual(['perception']);
  });

  it('calls onClose when Skip is clicked', async () => {
    let closed = false;
    render(<ExpertiseModal character={character} count={1} onComplete={() => {}} onClose={() => { closed = true; }} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Skip' }));
    expect(closed).toBe(true);
  });

  it('has correct ARIA attributes', () => {
    render(<ExpertiseModal character={character} count={1} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-labelledby', 'expertise-title');
  });
});
