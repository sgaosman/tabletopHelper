import { type ReactElement } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';

function AllProviders({ children }: { children: React.ReactNode }) {
  return (
    <BrowserRouter>
      <AuthProvider>
        {children}
      </AuthProvider>
    </BrowserRouter>
  );
}

function customRender(ui: ReactElement, options?: Omit<RenderOptions, 'wrapper'>) {
  return render(ui, { wrapper: AllProviders, ...options });
}

// Re-export everything from testing-library so consumers only need one import
export { customRender as render };
export * from '@testing-library/react';

export function setupAuthToken() {
  localStorage.setItem('accessToken', 'test-access-token');
  localStorage.setItem('refreshToken', 'test-refresh-token');
  localStorage.setItem('user', JSON.stringify({ userId: 'user-001', username: 'testuser', displayName: 'Test User' }));
}

export function clearAuthToken() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('user');
}
