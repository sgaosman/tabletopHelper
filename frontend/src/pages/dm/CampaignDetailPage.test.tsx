import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeCampaign, makeMockAdjustment, makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
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

  it('has players roll their own Repeat Saves, leaving the HP setting alone (D162)', async () => {
    let settingsRequest: unknown;
    server.use(
      http.patch('/api/campaigns/:id/settings', async ({ params, request }) => {
        settingsRequest = await request.json();
        return HttpResponse.json({ ...makeCampaign(params.id as string, 'Test Campaign'), promptPlayerRepeatSaves: true });
      }),
    );
    const user = userEvent.setup();
    renderPage();

    const setting = await screen.findByRole('checkbox', { name: /Players roll their own Repeat Saves/ });
    expect(setting).not.toBeChecked();
    await user.click(setting);

    await waitFor(() => expect(setting).toBeChecked());
    expect(settingsRequest).toEqual({ promptPlayerRepeatSaves: true });
    expect(screen.getByRole('checkbox', { name: /Show exact monster HP/ })).not.toBeChecked();
  });

  it("turns off players' Manual Modifiers, sending only that setting (D164)", async () => {
    let settingsRequest: unknown;
    server.use(
      http.patch('/api/campaigns/:id/settings', async ({ params, request }) => {
        settingsRequest = await request.json();
        return HttpResponse.json({ ...makeCampaign(params.id as string, 'Test Campaign'), allowPlayerManualModifiers: false });
      }),
    );
    const user = userEvent.setup();
    renderPage();

    const setting = await screen.findByRole('checkbox', { name: /Allow players Manual Modifiers/ });
    expect(setting).toBeChecked();
    await user.click(setting);

    await waitFor(() => expect(setting).not.toBeChecked());
    expect(settingsRequest).toEqual({ allowPlayerManualModifiers: false });
    expect(screen.getByRole('checkbox', { name: /Show exact monster HP/ })).not.toBeChecked();
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

  it('shows a conflict beside the setting and fetches the campaign again', async () => {
    let campaignRequests = 0;
    server.use(
      http.get('/api/campaigns/:id', ({ params }) => {
        campaignRequests += 1;
        // Another DM tab turned the setting on after this page loaded
        return HttpResponse.json({ ...makeCampaign(params.id as string, 'Test Campaign'), showExactMonsterHp: campaignRequests > 1 });
      }),
      http.patch('/api/campaigns/:id/settings', () =>
        HttpResponse.json({ error: 'Someone else changed this at the same time. Please try again.' }, { status: 409 })),
    );
    const user = userEvent.setup();
    renderPage();

    const setting = await screen.findByRole('checkbox', { name: /Show exact monster HP/ });
    expect(setting).not.toBeChecked();
    await user.click(setting);

    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else changed this at the same time. Please try again.');
    await waitFor(() => expect(setting).toBeChecked());
    expect(campaignRequests).toBe(2);
  });

  it("replaces the page with the server's message when the campaign can't be opened", async () => {
    server.use(
      http.get('/api/campaigns/:id', () => HttpResponse.json({ error: 'Campaign not found' }, { status: 404 })),
    );
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Campaign not found');
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it("opens a character's DM Adjustments from its card, and the card shows the character the server returns (D158)", async () => {
    const aria = makeMockCharacter('char-001', 'Aria', 'Fighter', 3);
    const ring = makeMockAdjustment({ id: 'adj-ring' });
    let added: unknown;
    server.use(
      http.get('/api/characters/campaign/:campaignId', () => HttpResponse.json([aria])),
      http.post('/api/characters/:id/adjustments', async ({ request }) => {
        added = await request.json();
        return HttpResponse.json({ ...aria, armourClass: 17, dmAdjustments: [ring] });
      }),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Adjustments for Aria' }));
    const dialog = screen.getByRole('dialog', { name: 'DM Adjustments' });
    expect(within(dialog).getByText('No adjustments yet.')).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText('Amount'), '1');
    await user.type(within(dialog).getByLabelText('Reason'), 'Ring of Protection');
    await user.click(within(dialog).getByRole('button', { name: 'Add Adjustment' }));

    expect(await within(dialog).findByText('+1 AC')).toBeInTheDocument();
    expect(added).toEqual({ kind: 'STAT', target: 'ARMOUR_CLASS', amount: 1, reason: 'Ring of Protection' });
    expect(screen.getByText('AC 17')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Adjustments (1) for Aria' })).toHaveTextContent('Adjustments (1)');

    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it("shows how many choices each character's levels still owe, which only its player makes (D159)", async () => {
    const aria = {
      ...makeMockCharacter('char-001', 'Aria', 'Fighter', 4),
      pendingChoices: [
        makeMockPendingChoice({ id: 'choice-asi' }),
        makeMockPendingChoice({ id: 'choice-style', type: 'FIGHTING_STYLE', characterLevel: 1, classLevel: 1 }),
      ],
    };
    const bram = { ...makeMockCharacter('char-002', 'Bram', 'Wizard', 2), pendingChoices: [makeMockPendingChoice({ id: 'choice-spells', type: 'SPELLS', className: 'Wizard', classLevel: 2, characterLevel: 2, count: 2 })] };
    const cora = makeMockCharacter('char-003', 'Cora', 'Cleric', 1);
    server.use(http.get('/api/characters/campaign/:campaignId', () => HttpResponse.json([aria, bram, cora])));
    renderPage();

    expect(await screen.findByText('2 choices pending')).toBeInTheDocument();
    expect(screen.getByText('1 choice pending')).toBeInTheDocument();
    expect(screen.getAllByText(/choices? pending/)).toHaveLength(2);
    // The DM sees the count, but has nothing to make or waive the choices with
    expect(screen.queryByRole('button', { name: /choice/i })).not.toBeInTheDocument();
  });

  describe('polling', () => {
    afterEach(() => {
      vi.restoreAllMocks();
      vi.useRealTimers();
    });

    it('fetches the campaign and its characters again every 10 seconds until the page closes', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      let campaign = makeCampaign('camp-001', 'Test Campaign');
      const characters = [makeMockCharacter('char-001', 'Gimli', 'Fighter', 5)];
      const requests = { campaign: 0, characters: 0 };
      server.use(
        http.get('/api/campaigns/:id', () => {
          requests.campaign += 1;
          return HttpResponse.json(campaign);
        }),
        http.get('/api/characters/campaign/:campaignId', () => {
          requests.characters += 1;
          return HttpResponse.json(characters);
        }),
      );
      const { unmount } = renderPage();
      expect(await screen.findByText('Gimli')).toBeInTheDocument();
      expect(requests).toEqual({ campaign: 1, characters: 1 });

      // A player joins with a new character
      campaign = makeCampaign('camp-001', 'Test Campaign', 'user-001', 2);
      characters.push(makeMockCharacter('char-002', 'Legolas', 'Ranger', 5));
      await act(() => vi.advanceTimersByTimeAsync(9999));
      expect(requests).toEqual({ campaign: 1, characters: 1 });
      await act(() => vi.advanceTimersByTimeAsync(1));

      expect(await screen.findByText('Player 1')).toBeInTheDocument();
      expect(await screen.findByText('Legolas')).toBeInTheDocument();
      expect(requests).toEqual({ campaign: 2, characters: 2 });

      unmount();
      expect(vi.getTimerCount()).toBe(0);
    });

    it('does not poll while the tab is hidden', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      let campaignRequests = 0;
      server.use(
        http.get('/api/campaigns/:id', ({ params }) => {
          campaignRequests += 1;
          return HttpResponse.json(makeCampaign(params.id as string, 'Test Campaign'));
        }),
      );
      renderPage();
      await screen.findByRole('checkbox', { name: /Show exact monster HP/ });

      vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
      await act(() => vi.advanceTimersByTimeAsync(30000));

      expect(campaignRequests).toBe(1);
    });
  });
});
