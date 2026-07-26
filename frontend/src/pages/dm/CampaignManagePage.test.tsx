import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import CampaignManagePage from './CampaignManagePage';

describe('CampaignManagePage', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('shows loading state initially', () => {
    render(<CampaignManagePage />);
    expect(screen.getByText('Loading campaigns...')).toBeInTheDocument();
  });

  it('shows empty state when no campaigns exist', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(http.get('/api/campaigns', () => HttpResponse.json([])));
    render(<CampaignManagePage />);
    await waitFor(() => {
      expect(screen.getByText('No campaigns yet')).toBeInTheDocument();
      expect(screen.getByText('Create your first campaign to get started')).toBeInTheDocument();
    });
  });

  it('renders campaign list with name, invite code, and members', async () => {
    render(<CampaignManagePage />);
    await waitFor(() => {
      expect(screen.getByText('Test Campaign')).toBeInTheDocument();
      expect(screen.getByText('ABC123XY')).toBeInTheDocument();
      expect(screen.getByText(/1 member/)).toBeInTheDocument();
    });
  });

  it('shows create form on clicking New Campaign', async () => {
    render(<CampaignManagePage />);
    await waitFor(() => screen.getByText('New Campaign'));
    const user = userEvent.setup();
    await user.click(screen.getByText('New Campaign'));
    expect(screen.getByText('Create Campaign')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/e\.g\. Curse of Strahd/)).toBeInTheDocument();
  });

  it('hides create form on Cancel', async () => {
    render(<CampaignManagePage />);
    await waitFor(() => screen.getByText('New Campaign'));
    const user = userEvent.setup();
    await user.click(screen.getByText('New Campaign'));
    expect(screen.getByText('Create Campaign')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Create Campaign')).not.toBeInTheDocument();
  });

  it('submits create form and closes it', async () => {
    render(<CampaignManagePage />);
    await waitFor(() => screen.getByText('New Campaign'));
    const user = userEvent.setup();
    await user.click(screen.getByText('New Campaign'));
    await user.type(screen.getByPlaceholderText(/e\.g\. Curse of Strahd/), 'My New Campaign');
    await user.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => {
      expect(screen.queryByText('Create Campaign')).not.toBeInTheDocument();
    });
  });

  it('shows error when campaign load fails', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/campaigns', () =>
        HttpResponse.json({ error: 'Server error' }, { status: 500 })
      ),
    );
    render(<CampaignManagePage />);
    await waitFor(() => {
      expect(screen.getByText('Failed to load campaigns')).toBeInTheDocument();
    });
  });

  it('renders member badges with name and role', async () => {
    render(<CampaignManagePage />);
    await waitFor(() => screen.getByText('Test Campaign'));
    expect(screen.getByText('Test User')).toBeInTheDocument();
    expect(screen.getByText('DM')).toBeInTheDocument();
  });
});
