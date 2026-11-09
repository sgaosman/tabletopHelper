import { describe, it, expect } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, renderWithGlossary, screen, waitFor, within } from '../test/test-utils';
import Modal from './ui/Modal';
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

  it('without a GlossaryProvider keeps every bold term plain bold', () => {
    render(<FormattedDescription text="You have advantage on the roll." />);
    expect(screen.getByText('advantage').tagName).toBe('STRONG');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('FormattedDescription with the Glossary (M19)', () => {
  it('makes a bold term the Glossary defines a button, and leaves other bold words plain bold', async () => {
    renderWithGlossary(<FormattedDescription text="You have advantage and deal 2d6 damage. Make a DC 13 saving throw." />);

    const term = await screen.findByRole('button', { name: 'advantage' });
    expect(term).toHaveAttribute('aria-haspopup', 'dialog');
    expect(screen.getByRole('button', { name: 'DC 13' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'saving throw' })).toBeInTheDocument();
    expect(screen.getByText('2d6').tagName).toBe('STRONG');
  });

  it('links conditions in running text, and a damage type only before "damage"', async () => {
    renderWithGlossary(<FormattedDescription text="The target takes fire damage and is knocked prone. The cold night passes." />);

    expect(await screen.findByRole('button', { name: 'fire' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'prone' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'cold' })).not.toBeInTheDocument();
  });

  it('opens the definition with its summary, and the rules text folded away', async () => {
    const user = userEvent.setup();
    renderWithGlossary(<FormattedDescription text="The target is knocked prone." />);

    await user.click(await screen.findByRole('button', { name: 'prone' }));

    const dialog = screen.getByRole('dialog', { name: 'Prone' });
    expect(within(dialog).getByText('Condition · PHB p.292')).toBeInTheDocument();
    expect(within(dialog).getByText(/can only crawl until you stand up/)).toBeInTheDocument();
    const details = within(dialog).getByText('Full rules text').closest('details')!;
    expect(details).not.toHaveAttribute('open');
    await user.click(within(dialog).getByText('Full rules text'));
    expect(details).toHaveAttribute('open');
    expect(within(dialog).getByText(/only movement option is to crawl/)).toBeVisible();
  });

  it('shows the rules text straight away for a term without a summary', async () => {
    const user = userEvent.setup();
    renderWithGlossary(<FormattedDescription text="It deals fire damage." />);

    await user.click(await screen.findByRole('button', { name: 'fire' }));

    const dialog = screen.getByRole('dialog', { name: 'Fire' });
    expect(within(dialog).getByText(/Red dragons breathe fire/)).toBeInTheDocument();
    expect(within(dialog).queryByText('Full rules text')).not.toBeInTheDocument();
  });

  it('swaps to a related term with See also, in the same dialog', async () => {
    const user = userEvent.setup();
    renderWithGlossary(<FormattedDescription text="The target is knocked prone." />);

    await user.click(await screen.findByRole('button', { name: 'prone' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Advantage and Disadvantage' }));

    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(screen.getByRole('dialog', { name: 'Advantage and Disadvantage' })).toBeInTheDocument();
    expect(screen.getByText(/roll two d20s and use the higher/)).toBeInTheDocument();
  });

  it('closes on Escape and returns focus to the term', async () => {
    const user = userEvent.setup();
    renderWithGlossary(<FormattedDescription text="The target is knocked prone." />);

    const term = await screen.findByRole('button', { name: 'prone' });
    await user.click(term);
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(term).toHaveFocus();
  });

  it('opened from inside another dialog, stacks above it, and Escape closes only the definition', async () => {
    const user = userEvent.setup();
    renderWithGlossary(
      <Modal title="Choose a feat" onClose={() => {}}>
        <FormattedDescription text="You have advantage on the check." />
      </Modal>,
    );

    const term = await screen.findByRole('button', { name: 'advantage' });
    await user.click(term);
    expect(screen.getAllByRole('dialog')).toHaveLength(2);
    expect(screen.getByRole('dialog', { name: 'Advantage and Disadvantage' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1));
    expect(screen.getByRole('dialog', { name: 'Choose a feat' })).toBeInTheDocument();
    expect(term).toHaveFocus();
  });
});

