import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios';
import { NETWORK_ERROR_MESSAGE } from '../api/errors';
import { useToast } from '../hooks/useToast';
import { ToastProvider } from './ToastContext';
import { MAX_TOASTS, TOAST_DURATION_MS, type ToastApi } from './toast';

let toast: ToastApi;

function Grab() {
  toast = useToast();
  return null;
}

function renderToasts() {
  render(<ToastProvider><Grab /></ToastProvider>);
}

function serverError(status: number, body: unknown) {
  const config = { headers: new AxiosHeaders() };
  const response = { status, data: body, statusText: '', headers: {}, config } as AxiosResponse;
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, null, response);
}

describe('ToastProvider', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('announces a success politely and an error at once', () => {
    renderToasts();

    act(() => toast.success('Campaign settings saved'));
    act(() => toast.error('Not your turn'));

    expect(screen.getByRole('status')).toHaveTextContent('Campaign settings saved');
    expect(screen.getByRole('alert')).toHaveTextContent('Not your turn');
    expect(screen.getByRole('region', { name: 'Notifications' })).toBeInTheDocument();
  });

  it('goes away on its own, an error after longer than a success', () => {
    vi.useFakeTimers();
    renderToasts();

    act(() => {
      toast.success('Saved');
      toast.error('Failed');
    });
    act(() => vi.advanceTimersByTime(TOAST_DURATION_MS.success));
    expect(screen.queryByText('Saved')).not.toBeInTheDocument();
    expect(screen.getByText('Failed')).toBeInTheDocument();

    act(() => vi.advanceTimersByTime(TOAST_DURATION_MS.error - TOAST_DURATION_MS.success));
    expect(screen.queryByText('Failed')).not.toBeInTheDocument();
  });

  it('closes from its dismiss button, which is a 44px target', () => {
    renderToasts();
    act(() => toast.info('Heads up'));

    const close = screen.getByRole('button', { name: 'Dismiss notification' });
    expect(close).toHaveClass('size-11');
    fireEvent.click(close);

    expect(screen.queryByText('Heads up')).not.toBeInTheDocument();
  });

  it('shows a repeated message once, restarting its time', () => {
    vi.useFakeTimers();
    renderToasts();

    act(() => toast.error('Could not reach the server'));
    act(() => vi.advanceTimersByTime(TOAST_DURATION_MS.error - 1000));
    act(() => toast.error('Could not reach the server'));
    expect(screen.getAllByText('Could not reach the server')).toHaveLength(1);

    act(() => vi.advanceTimersByTime(2000));
    expect(screen.getByText('Could not reach the server')).toBeInTheDocument();
  });

  it('stacks a few at a time, the oldest making way', () => {
    renderToasts();

    act(() => {
      for (let i = 1; i <= MAX_TOASTS + 1; i++) toast.info(`Notice ${i}`);
    });

    expect(screen.getAllByRole('status')).toHaveLength(MAX_TOASTS);
    expect(screen.queryByText('Notice 1')).not.toBeInTheDocument();
    expect(screen.getByText(`Notice ${MAX_TOASTS + 1}`)).toBeInTheDocument();
  });

  it('words a failed request as the server does', () => {
    renderToasts();

    act(() => toast.errorFrom(serverError(409, { error: 'The turn has moved on' })));
    act(() => toast.errorFrom(new AxiosError('Network Error', 'ERR_NETWORK')));

    expect(screen.getByText('The turn has moved on')).toBeInTheDocument();
    expect(screen.getByText(NETWORK_ERROR_MESSAGE)).toBeInTheDocument();
  });

  it('does nothing outside a provider', () => {
    render(<Grab />);
    expect(() => toast.error('Nobody hears this')).not.toThrow();
    expect(screen.queryByText('Nobody hears this')).not.toBeInTheDocument();
  });
});
