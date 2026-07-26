import '@testing-library/jest-dom/vitest';
import { server } from './src/test/mocks/server';
import { cleanup } from '@testing-library/react';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));

afterEach(() => {
  cleanup();
  server.resetHandlers();
  localStorage.clear();
});

afterAll(() => server.close());
