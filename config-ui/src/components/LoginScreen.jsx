import { useState } from 'react'
import { api } from '../api'

const DEMO_USERS = [
  { username: 'admin', label: 'Admin', hint: 'all tenants · schema changes' },
  { username: 'operator', label: 'Operator', hint: 'acme-corp · publish changes' },
  { username: 'employee', label: 'Employee', hint: 'acme-corp · read only' },
  { username: 'globex', label: 'Globex operator', hint: 'globex-corp · publish changes' }
]

/**
 * Credential exchange. The server decides the role and tenant from the stored user, so
 * this form never asks for one.
 */
export default function LoginScreen({ onLogin }) {
  const [username, setUsername] = useState('operator')
  const [password, setPassword] = useState('password123')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const session = await api.login(username.trim(), password)
      onLogin(session)
    } catch (err) {
      setError(err.status === 401 ? 'Invalid username or password' : err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-shell">
      <form className="card login-card" onSubmit={submit}>
        <div className="brand login-brand">
          <div className="brand-mark">M</div>
          <div>
            <h1>Config Console</h1>
            <p>Sign in to receive an access token</p>
          </div>
        </div>

        <label className="field">
          <span>Username</span>
          <input
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            required
          />
        </label>

        <label className="field">
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>

        {error && <div className="inline-error">{error}</div>}

        <button className="button wide" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>

        <div className="demo-users">
          <p className="muted small">Demo accounts — password <code>password123</code></p>
          <div className="demo-user-grid">
            {DEMO_USERS.map((user) => (
              <button
                key={user.username}
                type="button"
                className={`demo-user ${username === user.username ? 'active' : ''}`}
                onClick={() => {
                  setUsername(user.username)
                  setPassword('password123')
                }}
              >
                <strong>{user.label}</strong>
                <span>{user.hint}</span>
              </button>
            ))}
          </div>
        </div>
      </form>
    </div>
  )
}
