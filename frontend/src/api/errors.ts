import { isAxiosError } from 'axios';

/** The server's own wording for an unexpected failure (GlobalExceptionHandler). */
export const SERVER_ERROR_MESSAGE = 'Something went wrong. Please try again.';

export const NETWORK_ERROR_MESSAGE = 'Could not reach the server. Check your connection and try again.';

/** The HTTP status of a failed request, or undefined when no response arrived. */
export function apiErrorStatus(error: unknown): number | undefined {
  return isAxiosError(error) ? error.response?.status : undefined;
}

/**
 * What to tell the user about a failed request. A 4xx carries the server's `{"error": ...}` message,
 * which is written for users (D155). A 5xx, or a request that never reached the server, gets a
 * generic message, since the server only explains unexpected errors in dev.
 */
export function apiErrorMessage(error: unknown): string {
  if (!isAxiosError(error)) return SERVER_ERROR_MESSAGE;
  if (!error.response) return NETWORK_ERROR_MESSAGE;
  if (error.response.status >= 500) return SERVER_ERROR_MESSAGE;
  return serverMessage(error.response.data) ?? SERVER_ERROR_MESSAGE;
}

function serverMessage(body: unknown): string | undefined {
  if (typeof body !== 'object' || body === null || !('error' in body)) return undefined;
  const { error } = body;
  return typeof error === 'string' && error.trim() !== '' ? error : undefined;
}
