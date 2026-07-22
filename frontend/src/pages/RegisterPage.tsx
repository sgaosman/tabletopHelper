import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function RegisterPage() {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { register } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      await register({
        username,
        email,
        password,
        displayName: displayName || undefined,
      });
      navigate('/select-role');
    } catch (err: any) {
      setError(err.response?.data?.error || 'Registration failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-page px-4">
      <div className="w-full max-w-md bg-card border border-rule p-8">
        <h1 className="font-heading text-[20px] font-semibold tracking-[0.02em] text-ink text-center mb-1">
          Tabletop Helper
        </h1>
        <p className="font-body text-[14px] font-medium text-muted text-center mb-8">
          Create your account
        </p>

        {error && (
          <div role="alert" className="bg-debuff-bg border border-debuff-border text-debuff text-[13px] font-body p-3 mb-4">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="username" className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">
              Username
            </label>
            <input
              id="username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              minLength={3}
              maxLength={50}
              className="w-full px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              placeholder="Choose a username"
            />
          </div>

          <div>
            <label htmlFor="email" className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">
              Email
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              className="w-full px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              placeholder="you@example.com"
            />
          </div>

          <div>
            <label htmlFor="displayName" className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">
              Display Name <span className="text-faint normal-case tracking-normal font-body">(optional)</span>
            </label>
            <input
              id="displayName"
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              maxLength={100}
              className="w-full px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              placeholder="How others see you"
            />
          </div>

          <div>
            <label htmlFor="password" className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">
              Password
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={8}
              className="w-full px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              placeholder="At least 8 characters"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full py-2.5 bg-ink text-card font-body text-[14px] font-medium border border-ink hover:bg-muted hover:border-muted disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {loading ? 'Creating account...' : 'Create Account'}
          </button>
        </form>

        <p className="font-body text-[13px] font-medium text-muted text-center mt-6">
          Already have an account?{' '}
          <Link to="/login" className="text-ink font-semibold hover:text-muted">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}
