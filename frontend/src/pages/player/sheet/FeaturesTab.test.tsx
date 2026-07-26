import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../../test/test-utils';
import FeaturesTab from './FeaturesTab';

describe('FeaturesTab', () => {
  const features = [
    { name: 'Darkvision', description: 'You can see in dim light within 60 feet as if it were bright light.', source: 'Race: Elf' },
    { name: 'Action Surge', description: 'Take one additional action.', source: 'Class: Fighter' },
    { name: 'Lucky', description: 'You have 3 luck points.', source: 'Feat' },
  ];

  it('renders all features as expandable details', () => {
    render(<FeaturesTab features={features} />);
    expect(screen.getByText('Darkvision')).toBeInTheDocument();
    expect(screen.getByText('Action Surge')).toBeInTheDocument();
    expect(screen.getByText('Lucky')).toBeInTheDocument();
  });

  it('renders source labels for each feature', () => {
    render(<FeaturesTab features={features} />);
    expect(screen.getByText('Race: Elf')).toBeInTheDocument();
    expect(screen.getByText('Class: Fighter')).toBeInTheDocument();
    expect(screen.getByText('Feat')).toBeInTheDocument();
  });

  it('renders descriptions as expandable content', () => {
    render(<FeaturesTab features={features} />);
    expect(screen.getByText(/60 feet/)).toBeInTheDocument();
  });

  it('shows empty state message when no features', () => {
    render(<FeaturesTab features={[]} />);
    expect(screen.getByText(/No features recorded/)).toBeInTheDocument();
  });

  it('toggles expanded state on click', async () => {
    render(<FeaturesTab features={[features[0]]} />);
    const user = userEvent.setup();
    const summary = screen.getByText('Darkvision');
    // Details should be closed initially (content hidden via browser default)
    expect(screen.getByText('Darkvision')).toBeInTheDocument();
    await user.click(summary);
    // After click, content is visible — the parent <details> should have 'open' attribute
    expect(screen.getByText('Darkvision').closest('summary')?.parentElement).toHaveAttribute('open');
  });
});
