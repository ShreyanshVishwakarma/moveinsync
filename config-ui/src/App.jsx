import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import { useLiveStream } from './useLiveStream'
import TopBar from './components/TopBar'
import ResolvePanel from './components/ResolvePanel'
import ChangeSetPanel from './components/ChangeSetPanel'
import RolloutPanel from './components/RolloutPanel'
import OverridesPanel from './components/OverridesPanel'
import ActivityFeed from './components/ActivityFeed'
import './styles.css'

const KEYS = [
  { id: 'demo-admin-key', label: 'Admin', role: 'ADMIN', client: 'all tenants' },
  { id: 'demo-operator-key', label: 'Operator', role: 'OPERATOR', client: 'acme-corp' },
  { id: 'demo-employee-key', label: 'Employee', role: 'EMPLOYEE', client: 'acme-corp' },
  { id: 'demo-globex-key', label: 'Globex operator', role: 'OPERATOR', client: 'globex-corp' }
]

const TENANTS = [
  { id: 'acme-corp', label: 'acme-corp' },
  { id: 'globex-corp', label: 'globex-corp' }
]

const OFFICES = [
  'blr-office-1',
  'blr-office-2',
  'hyd-office-1',
  'del-office-1'
]

const SHIFTS = ['morning', 'evening', 'night']

export default function App() {
  const [keyId, setKeyId] = useState(KEYS[1].id)
  const [tenant, setTenant] = useState('acme-corp')
  const [office, setOffice] = useState('blr-office-1')
  const [shift, setShift] = useState('')
  const [definitions, setDefinitions] = useState([])
  const [snapshot, setSnapshot] = useState(null)
  const [serverVersion, setServerVersion] = useState(null)
  const [activity, setActivity] = useState([])
  const [banner, setBanner] = useState(null)
  const [loading, setLoading] = useState({ definitions: false, snapshot: false })
  const lastDeltaRef = useRef(null)

  const apiKey = KEYS.find((entry) => entry.id === keyId)

  const log = useCallback((kind, message, detail) => {
    setActivity((current) => [
      { id: `${Date.now()}-${Math.random().toString(16).slice(2)}`, kind, message, detail, at: new Date() },
      ...current
    ].slice(0, 40))
  }, [])

  const notify = useCallback((type, text) => {
    setBanner({ type, text, at: Date.now() })
  }, [])

  const refreshVersion = useCallback(async () => {
    try {
      const result = await api.version(keyId)
      setServerVersion(result.version)
    } catch (error) {
      setServerVersion(null)
      if (error.status === 401) notify('error', 'API key rejected (401). Pick another role.')
    }
  }, [keyId, notify])

  const refreshDefinitions = useCallback(async () => {
    setLoading((current) => ({ ...current, definitions: true }))
    try {
      const list = await api.definitions(keyId)
      setDefinitions(list)
    } catch (error) {
      notify('error', `Could not load definitions: ${error.message}`)
    } finally {
      setLoading((current) => ({ ...current, definitions: false }))
    }
  }, [keyId, notify])

  const refreshSnapshot = useCallback(async () => {
    setLoading((current) => ({ ...current, snapshot: true }))
    try {
      const result = await api.snapshot(keyId)
      setSnapshot(result)
    } catch (error) {
      notify('error', `Could not load snapshot: ${error.message}`)
    } finally {
      setLoading((current) => ({ ...current, snapshot: false }))
    }
  }, [keyId, notify])

  useEffect(() => {
    refreshVersion()
    refreshDefinitions()
    refreshSnapshot()
  }, [refreshVersion, refreshDefinitions, refreshSnapshot])

  useEffect(() => {
    if (!banner) return undefined
    const timer = window.setTimeout(() => setBanner(null), 6000)
    return () => window.clearTimeout(timer)
  }, [banner])

  const onFrame = useCallback((frame) => {
    // A delta must be acted on once per version: the socket can deliver the same frame
    // twice during a dev-mode remount, and the feed should mirror the server's state.
    if (frame.type === 'delta') {
      if (lastDeltaRef.current === frame.version) return
      lastDeltaRef.current = frame.version
      const count = frame.entries?.length ?? 0
      log('delta', `Push received · v${frame.version}`, `${count} ${count === 1 ? 'entry' : 'entries'}`)
      setServerVersion(frame.version)
      refreshSnapshot()
    } else if (frame.type === 'snapshot') {
      const incoming = frame.snapshot
      setServerVersion(incoming.version)
      log('snapshot', `Snapshot · v${incoming.version}`, 'gap or cold start healed')
      refreshSnapshot()
    } else if (frame.type === 'up-to-date') {
      log('info', 'Already up to date', `v${frame.version}`)
    } else if (frame.type === 'error') {
      notify('error', `Server rejected a frame: ${frame.code}`)
    }
  }, [log, notify, refreshSnapshot])

  const live = useLiveStream({ apiKey: keyId, onFrame })

  // After a local write, ask the socket whether we missed anything.
  const syncAfterWrite = useCallback((version) => {
    live.subscribe(version)
    refreshVersion()
    refreshSnapshot()
  }, [live, refreshVersion, refreshSnapshot])

  return (
    <div className="app">
      <TopBar
        keys={KEYS}
        keyId={keyId}
        onKeyChange={(next) => {
          setKeyId(next)
          const picked = KEYS.find((entry) => entry.id === next)
          if (picked.client === 'all tenants') setTenant('acme-corp')
          else setTenant(picked.client)
        }}
        role={apiKey.role}
        tenantScope={apiKey.client}
        serverVersion={serverVersion}
        liveStatus={live.status}
        liveVersion={live.version}
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
            apiKey={keyId}
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
          />

          <ChangeSetPanel
            apiKey={keyId}
            definitions={definitions}
            tenant={tenant}
            offices={OFFICES}
            onCommitted={(result) => {
              log('commit', `Published v${result.version}`, `${result.entries.length} entries`)
              notify('success', `Change set published as version ${result.version}`)
              syncAfterWrite(result.version)
            }}
            onError={(error) => {
              const text = error.details?.length ? error.details.join(' · ') : error.message
              notify('error', `${error.status || ''} ${error.code}: ${text}`)
              log('reject', 'Change set rejected', error.code)
            }}
          />

          <RolloutPanel
            apiKey={keyId}
            tenant={tenant}
            serverVersion={serverVersion}
            onDone={(message) => {
              notify('success', message)
              refreshVersion()
              refreshSnapshot()
            }}
            onError={(error) => notify('error', error.message)}
            log={log}
          />
        </section>

        <aside className="column side-column">
          <OverridesPanel
            snapshot={snapshot}
            loading={loading.snapshot}
            onRefresh={refreshSnapshot}
          />
          <ActivityFeed entries={activity} onClear={() => setActivity([])} />
        </aside>
      </main>

      <footer className="foot">
        <span>Config server on :8080 · dashboard on :5173</span>
        <span>Tenant isolation is enforced server-side; switching keys changes what you can see.</span>
      </footer>
    </div>
  )
}
