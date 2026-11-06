import { useContext } from 'react';
import { AuthContext, type AuthContextType } from '../context/authSession';

/** The signed-in session from the nearest AuthProvider. */
export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
