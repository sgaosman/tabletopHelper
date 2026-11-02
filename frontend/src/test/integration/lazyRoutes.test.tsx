import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, setupAuthToken } from '../../test/test-utils';
import { ErrorBoundary } from '../../components/ErrorBoundary';
import App from '../../App';

// The DM dashboard's code arrives only when a test releases it
const dashboardChunk = vi.hoisted(() => {
  let release = () => {};
  const released = new Promise<void>((resolve) => {
    release = () => resolve();
  });
  return { requested: false, released, release };
});

vi.mock('../../pages/dm/DmDashboard', async (importOriginal) => {
  dashboardChunk.requested = true;
  await dashboardChunk.released;
  return importOriginal();
});

// The conditions page's code fails to download, as it would once an update has replaced it
vi.mock('../../pages/dm/ConditionsPage', () => {
  throw new TypeError('Failed to fetch dynamically imported module');
});

describe('Lazy routes', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('shows the loading status while a page downloads, then the page', async () => {
    window.history.pushState({}, '', '/dm');
    render(<App />);

    // The page is requested once the session is restored
    await waitFor(() => expect(dashboardChunk.requested).toBe(true));
    expect(screen.getByRole('status')).toHaveTextContent('Loading...');
    expect(screen.queryByRole('heading', { name: 'Dashboard' })).not.toBeInTheDocument();

    dashboardChunk.release();

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('offers a reload when a page fails to download', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    window.history.pushState({}, '', '/dm/conditions');
    render(<ErrorBoundary><App /></ErrorBoundary>);

    expect(await screen.findByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload Page' })).toBeInTheDocument();
    consoleError.mockRestore();
  });
});
