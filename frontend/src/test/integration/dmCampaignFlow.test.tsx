import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import App from '../../App';

describe('DM Campaign Flow', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('loads campaign list page', async () => {
    window.history.pushState({}, '', '/dm/campaigns');
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText('Test Campaign')).toBeInTheDocument();
    });
  });

  it('can open and close the create campaign form', async () => {
    window.history.pushState({}, '', '/dm/campaigns');
    render(<App />);
    await waitFor(() => screen.getByText('New Campaign'));
    const user = userEvent.setup();
    await user.click(screen.getByText('New Campaign'));
    expect(screen.getByPlaceholderText(/e\.g\. Curse of Strahd/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => {
      expect(screen.queryByPlaceholderText(/e\.g\. Curse of Strahd/)).not.toBeInTheDocument();
    });
  });

  it('shows campaign details with invite code and member count', async () => {
    window.history.pushState({}, '', '/dm/campaigns');
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText('ABC123XY')).toBeInTheDocument();
      expect(screen.getByText(/1 member/)).toBeInTheDocument();
    });
  });
});
