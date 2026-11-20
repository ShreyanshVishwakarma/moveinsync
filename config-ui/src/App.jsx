import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from './api'
import { useLiveStream } from './useLiveStream'
import TopBar from './components/TopBar'
import LoginScreen from './components/LoginScreen'
import ResolvePanel from './components/ResolvePanel'
import ChangeSetPanel from './components/ChangeSetPanel'
import RolloutPanel from './components/RolloutPanel'
import OverridesPanel from './components/OverridesPanel'
import ActivityFeed from './components/ActivityFeed'
import './styles.css'

const TENANTS = [
  { id: 'acme-corp', label: 'acme-corp' },
  { id: 'globex-corp', label: 'globex-corp' }
]

const OFFICES = ['blr-office-1', 'blr-office-2', 'hyd-office-1', 'del-office-1']
const SHIFTS = ['morning', 'evening', 'night']
const SESSION_KEY = 'moveinsync.session'

export default function App() {
  const [session, setSession] = useState(() => {
    try {
      const stored = window.localStorage.getItem(SESSION_KEY)
      return stored ? JSON.parse(stored) : null
    } catch {
      return null
    }
  })

  const [tenant, setTenant] = useState(session?.clientId ?? 'acme-corp')
  const [office, setOffice] = useState('blr-office-1')
  const [shift, setShift] = useState('')
  const [definitions, setDefinitions] = useState([])
  const [snapshot, setSnapshot] = useState(null)
  const [serverVersion, setServerVersion] = useState(null)
  const [activity, setActivity] = useState([])
  const [banner, setBanner] = useState(null)
  const [loading, setLoading] = useState({ definitions: false, snapshot: false })
  const lastDeltaRef = useRef(null)

  const token = session?.accessToken
  const isAdmin = session?.role === 'ADMIN'

  const log = useCallback((kind, message, detail) => {
    setActivity((current) => [
      { id: `${Date.now()}-${Math.random().toString(16).slice(2)}`, kind, message, detail, at: new Date() },
      ...current
    ].slice(0, 40))
  }, [])

  const notify = useCallback((type, text) => {
    setBanner({ type, text, at: Date.now() })
  }, [])

  const endSession = useCallback((reason) => {
    window.localStorage.removeItem(SESSION_KEY)
    setSession(null)
    setSnapshot(null)
    setDefinitions([])
    setServerVersion(null)
    setActivity([])
    if (reason) notify('error', reason)
  }, [notify])

  /** Any 401 means the token is gone; send the user back to login. */
  const guard = useCallback((error) => {
    if (error instanceof ApiError && error.isSessionExpired) {
      endSession(error.code === 'TOKEN_EXPIRED'
        ? 'Session expired — please sign in again'
        : 'Session is no longer valid — please sign in again')
      return true
    }
    return false
  }, [endSession])

  const startSession = useCallback((issued) => {
    const next = {
      accessToken: issued.accessToken,
      username: issued.username,
      role: issued.role,
      clientId: issued.clientId,
      expiresAt: issued.expiresAt,
      expiresInSeconds: issued.expiresInSeconds
    }
    window.localStorage.setItem(SESSION_KEY, JSON.stringify(next))
    setSession(next)
    setTenant(next.clientId ?? 'acme-corp')
    setActivity([])
    notify('success', `Signed in as ${next.username} (${next.role})`)
  }, [notify])

  const logout = useCallback(async () => {
    try {
      await api.logout(token)
    } catch {
      // Already invalid; clearing locally is enough.
    }
    endSession()
  }, [token, endSession])

  const refreshVersion = useCallback(async () => {
    if (!token) return
    try {
      const result = await api.version(token)
      setServerVersion(result.version)
    } catch (error) {
      if (!guard(error)) setServerVersion(null)
    }
  }, [token, guard])

  const refreshDefinitions = useCallback(async () => {
    if (!token) return
    setLoading((current) => ({ ...current, definitions: true }))
    try {
      setDefinitions(await api.definitions(token))
    } catch (error) {
      if (!guard(error)) notify('error', `Could not load definitions: ${error.message}`)
    } finally {
      setLoading((current) => ({ ...current, definitions: false }))
    }
  }, [token, guard, notify])

  const refreshSnapshot = useCallback(async () => {
    if (!token) return
    setLoading((current) => ({ ...current, snapshot: true }))
    try {
      setSnapshot(await api.snapshot(token))
    } catch (error) {
      if (!guard(error)) notify('error', `Could not load snapshot: ${error.message}`)
    } finally {
      setLoading((current) => ({ ...current, snapshot: false }))
    }
  }, [token, guard, notify])

  useEffect(() => {
    if (!token) return
    refreshVersion()
    refreshDefinitions()
    refreshSnapshot()
  }, [token, refreshVersion, refreshDefinitions, refreshSnapshot])

  useEffect(() => {
    if (!banner) return undefined
    const timer = window.setTimeout(() => setBanner(null), 6000)
    return () => window.clearTimeout(timer)
  }, [banner])

  const onFrame = useCallback((frame) => {
    if (frame.type === 'delta') {
      if (lastDeltaRef.current === frame.version) return
      lastDeltaRef.current = frame.version
      const count = frame.entries?.length ?? 0
      log('delta', `Push received · v${frame.version}`, `${count} ${count === 1 ? 'entry' : 'entries'}`)
      setServerVersion(frame.version)
      refreshSnapshot()
    } else if (frame.type === 'snapshot') {
      setServerVersion(frame.snapshot.version)
      log('snapshot', `Snapshot · v${frame.snapshot.version}`, 'gap or cold start healed')
      refreshSnapshot()
    } else if (frame.type === 'up-to-date') {
      log('info', 'Already up to date', `v${frame.version}`)
    } else if (frame.type === 'error') {
      notify('error', `Server rejected a frame: ${frame.code}`)
    }
  }, [log, notify, refreshSnapshot])

  const live = useLiveStream({ token, enabled: Boolean(token), onFrame })

  const syncAfterWrite = useCallback((version) => {
    live.subscribe(version)
    refreshVersion()
    refreshSnapshot()
  }, [live, refreshVersion, refreshSnapshot])

  if (!session) {
    return (
      <div className="app">
        {banner && <div className={`banner banner-${banner.type}`}>{banner.text}</div>}
        <LoginScreen onLogin={startSession} />
        <footer className="foot">
          <span>Config server on :8080 · dashboard on :5173</span>
          <span>Tokens are valid for up to 7 days and can be revoked instantly.</span>
        </footer>
      </div>
    )
  }

  return (
    <div className="app">
      <TopBar
        session={session}
        serverVersion={serverVersion}
        liveStatus={live.status}
        liveVersion={live.version}
        onLogout={logout}
        onRefresh={() => {
          refreshVersion()
          refreshSnapshot()
          refreshDefinitions()
          log('info', 'Manual refresh', 'version, snapshot, definitions')
        }}
      />

      {banner && (
        <div className={`banner banner-${banner.type}`} role="status">
          {banner.text}
        </div>
      )}

      <main className="layout">
        <section className="column main-column">
          <ResolvePanel
            token={token}
            definitions={definitions}
            tenants={TENANTS}
            offices={OFFICES}
            shifts={SHIFTS}
            tenant={tenant}
            office={office}
            shift={shift}
            onTenantChange={setTenant}
            onOfficeChange={setOffice}
            onShiftChange={setShift}
            onResult={log}
            onSessionError={guard}
          />

          <ChangeSetPanel
            token={token}
            definitions={definitions}
            tenant={tenant}
            offices={OFFICES}
            defaultAuthor={session.username}
            onCommitted={(result) => {
              log('commit', `Published v${result.version}`, `${result.entries.length} entries`)
              notify('success', `Change set published as version ${result.version}`)
              syncAfterWrite(result.version)
            }}
            onError={(error) => {
              if (guard(error)) return
              const text = error.details?.length ? error.details.join(' · ') : error.message
              notify('error', `${error.status || ''} ${error.code}: ${text}`)
              log('reject', 'Change set rejected', error.code)
            }}
          />

          <RolloutPanel
            token={token}
            tenant={tenant}
            author={session.username}
            serverVersion={serverVersion}
            onDone={(message) => {
              notify('success', message)
              refreshVersion()
              refreshSnapshot()
            }}
            onError={(error) => {
              if (!guard(error)) notify('error', error.message)
            }}
            log={log}
          />
        </section>

        <aside className="column side-column">
          <OverridesPanel snapshot={snapshot} loading={loading.snapshot} onRefresh={refreshSnapshot} />
          <ActivityFeed entries={activity} onClear={() => setActivity([])} />
        </aside>
      </main>

      <footer className="foot">
        <span>Config server on :8080 · dashboard on :5173</span>
        <span>
          Signed in as <strong>{session.username}</strong> · {session.role}
          {session.clientId ? ` · ${session.clientId}` : ' · all tenants'}
          {isAdmin && ' · admin endpoints enabled'}
        </span>
      </footer>
    </div>
  )
}
