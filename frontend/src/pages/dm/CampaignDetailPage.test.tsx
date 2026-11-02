import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeCampaign } from '../../test/mocks/handlers';
import CampaignDetailPage from './CampaignDetailPage';

function renderPage() {
  window.history.pushState({}, '', '/dm/campaigns/camp-001');
  return render(
    <Routes>
      <Route path="/dm/campaigns/:campaignId" element={<CampaignDetailPage />} />
    </Routes>,
  );
}

describe('CampaignDetailPage', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('turns on showing exact monster HP to players', async () => {
    let settingsRequest: unknown;
    server.use(
      http.patch('/api/campaigns/:id/settings', async ({ params, request }) => {
        settingsRequest = await request.json();
        return HttpResponse.json({ ...makeCampaign(params.id as string, 'Test Campaign'), showExactMonsterHp: true });
      }),
    );
    const user = userEvent.setup();
    renderPage();

    const setting = await screen.findByRole('checkbox', { name: /Show exact monster HP/ });
    expect(setting).not.toBeChecked();
    await user.click(setting);

    await waitFor(() => expect(setting).toBeChecked());
    expect(settingsRequest).toEqual({ showExactMonsterHp: true });
  });

  it('shows the setting as saved on the campaign', async () => {
    server.use(
      http.get('/api/campaigns/:id', ({ params }) =>
        HttpResponse.json({ ...makeCampaign(params.id as string, 'Test Campaign'), showExactMonsterHp: true })),
    );
    renderPage();

    expect(await screen.findByRole('checkbox', { name: /Show exact monster HP/ })).toBeChecked();
  });

  it('shows why the setting could not be saved and leaves it unchanged', async () => {
    server.use(
      http.patch('/api/campaigns/:id/settings', () =>
        HttpResponse.json({ error: 'Only the DM can perform this action' }, { status: 403 })),
    );
    const user = userEvent.setup();
    renderPage();

    const setting = await screen.findByRole('checkbox', { name: /Show exact monster HP/ });
    await user.click(setting);

    expect(await screen.findByRole('alert')).toHaveTextContent('Only the DM can perform this action');
    expect(setting).not.toBeChecked();
  });
});
