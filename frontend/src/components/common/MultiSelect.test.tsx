import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../test/test-utils';
import MultiSelect from './MultiSelect';

describe('MultiSelect', () => {
  const basicProps = {
    options: ['Fighter', 'Wizard', 'Cleric', 'Rogue', 'Barbarian', 'Paladin', 'Ranger'],
    selected: [] as string[],
    onChange: () => {},
    placeholder: 'Choose classes...',
  };

  it('renders with placeholder text when nothing is selected', () => {
    render(<MultiSelect {...basicProps} />);
    expect(screen.getByText('Choose classes...')).toBeInTheDocument();
  });

  it('shows count when multiple items are selected', () => {
    render(<MultiSelect {...basicProps} selected={['Fighter', 'Wizard']} />);
    expect(screen.getByText('2 selected')).toBeInTheDocument();
  });

  it('shows the label when exactly one item is selected', () => {
    render(<MultiSelect {...basicProps} selected={['Wizard']} />);
    expect(screen.getByText('Wizard')).toBeInTheDocument();
  });

  it('opens dropdown on button click', async () => {
    render(<MultiSelect {...basicProps} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { expanded: false }));
    expect(screen.getByRole('listbox')).toBeInTheDocument();
  });

  it('closes dropdown on click outside', async () => {
    render(<MultiSelect {...basicProps} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { expanded: false }));
    expect(screen.getByRole('listbox')).toBeInTheDocument();
    await user.click(document.body);
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('filters options by search text', async () => {
    render(<MultiSelect {...basicProps} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button'));
    await user.type(screen.getByLabelText('Filter options'), 'Wiz');
    expect(screen.getByText('Wizard')).toBeInTheDocument();
    expect(screen.queryByText('Fighter')).not.toBeInTheDocument();
  });

  it('shows "No matches" when search has no results', async () => {
    render(<MultiSelect {...basicProps} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button'));
    await user.type(screen.getByLabelText('Filter options'), 'zzzz');
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('calls onChange when selecting an option', async () => {
    let selected: string[] = [];
    const onChange = (s: string[]) => { selected = s; };
    render(<MultiSelect {...basicProps} onChange={onChange} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button'));
    await user.click(screen.getByLabelText('Wizard'));
    expect(selected).toEqual(['Wizard']);
  });

  it('calls onChange when deselecting an option', async () => {
    const initSelected = ['Fighter', 'Wizard'];
    const onChange = vi.fn();
    render(
      <MultiSelect {...basicProps} selected={initSelected} onChange={onChange} />
    );
    const user = userEvent.setup();
    await user.click(screen.getByText('2 selected'));
    // Click the Wizard checkbox to deselect
    const wizardOption = screen.getByLabelText('Wizard');
    await user.click(wizardOption);
    expect(onChange).toHaveBeenCalledWith(['Fighter']);
  });

  it('clear all button resets selection', async () => {
    const onChange = vi.fn();
    render(<MultiSelect {...basicProps} selected={['Fighter', 'Wizard', 'Cleric']} onChange={onChange} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('3 selected'));
    await user.click(screen.getByText('Clear all (3)'));
    expect(onChange).toHaveBeenCalledWith([]);
  });

  it('clear X button resets selection from main button', async () => {
    let selected: string[] = ['Fighter'];
    const onChange = (s: string[]) => { selected = s; };
    render(<MultiSelect {...basicProps} selected={selected} onChange={onChange} />);
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Clear selection'));
    expect(selected).toEqual([]);
  });

  it('supports custom renderLabel function', () => {
    const renderLabel = (v: string) => `Class: ${v}`;
    render(<MultiSelect {...basicProps} selected={['Wizard']} renderLabel={renderLabel} />);
    expect(screen.getByText('Class: Wizard')).toBeInTheDocument();
  });

  it('does not show search bar when options are six or fewer', async () => {
    render(<MultiSelect options={['Fighter', 'Wizard']} selected={[]} onChange={() => {}} placeholder="Choose..." />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button'));
    expect(screen.queryByLabelText('Filter options')).not.toBeInTheDocument();
  });

  it('has correct ARIA attributes', () => {
    render(<MultiSelect {...basicProps} />);
    const btn = screen.getByRole('button');
    expect(btn).toHaveAttribute('aria-haspopup', 'listbox');
    expect(btn).toHaveAttribute('aria-expanded', 'false');
  });
});
