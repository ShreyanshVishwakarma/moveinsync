import { useEffect, useState } from 'react'
import { api } from '../api'

const LEVEL_COLORS = {
  SHIFT: 'level-shift',
  OFFICE: 'level-office',
  CLIENT: 'level-client',
  GLOBAL: 'level-global'
}

export default function ResolvePanel({
  token,
  definitions,
  tenants,
  offices,
  shifts,
  tenant,
  office,
  shift,
  onTenantChange,
  onOfficeChange,
  onShiftChange,
  onResult,
  onSessionError
}) {
  const [key, setKey] = useState('')
  const [result, setResult] = useState(null)
  const [trace, setTrace] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!key && definitions.length > 0) {
      setKey(definitions[0].key)
    }
  }, [definitions, key])

  const context = { key, clientId: tenant, officeId: office, shiftId: shift || undefined }

  async function run(mode) {
    if (!key) return
    setBusy(true)
    setError(null)
    try {
      if (mode === 'explain') {
        const explained = await api.explain(token, context)
        setTrace(explained)
        setResult({
          key: explained.key,
          value: explained.effectiveValue,
          sourceLevel: explained.effectiveLevel,
          sourceEntityId: explained.trace.find((step) => step.applied)?.entityId
        })
      } else {
        const resolved = await api.resolve(token, context)
        setResult(resolved)
        setTrace(null)
      }
      onResult?.('read', `Resolved ${key}`, mode === 'explain' ? 'explain trace' : 'single lookup')
    } catch (err) {
      setResult(null)
      setTrace(null)
      setError(err)
      if (onSessionError?.(err)) return
    } finally {
      setBusy(false)
    }
  }

  const definition = definitions.find((entry) => entry.key === key)

  return (
    <section className="card">
      <div className="card-head">
        <h2>Resolve a setting</h2>
        <p>Most specific level wins: shift → office → client → global.</p>
      </div>

      <div className="grid-4">
        <label className="field">
          <span>Setting</span>
          <select value={key} onChange={(event) => setKey(event.target.value)}>
            {definitions.map((entry) => (
              <option key={entry.key} value={entry.key}>
                {entry.key}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>Client</span>
          <select value={tenant} onChange={(event) => onTenantChange(event.target.value)}>
            {tenants.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.label}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>Office</span>
          <select value={office} onChange={(event) => onOfficeChange(event.target.value)}>
            {offices.map((entry) => (
              <option key={entry} value={entry}>
                {entry}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>Shift</span>
          <select value={shift} onChange={(event) => onShiftChange(event.target.value)}>
            <option value="">(none)</option>
            {shifts.map((entry) => (
              <option key={entry} value={entry}>
                {entry}
              </option>
            ))}
          </select>
        </label>
      </div>

      {definition && (
        <p className="hint">
          Type <code>{definition.type}</code> · default <code>{definition.defaultValue}</code>
          {definition.minValue != null && ` · min ${definition.minValue}`}
          {definition.maxValue != null && ` · max ${definition.maxValue}`}
        </p>
      )}

      <div className="row">
        <button className="button" type="button" onClick={() => run('resolve')} disabled={busy || !key}>
          {busy ? 'Reading…' : 'Resolve'}
        </button>
        <button className="button ghost" type="button" onClick={() => run('explain')} disabled={busy || !key}>
          Explain
        </button>
      </div>

      {error && (
        <div className="inline-error">
          <strong>{error.status} {error.code}</strong>
          <span>{error.details?.length ? error.details.join(' · ') : error.message}</span>
        </div>
      )}

      {result && (
        <div className="result">
          <div className="result-value">
            <span className="result-label">Effective value</span>
            <strong>{result.value}</strong>
          </div>
          <div className={`level-badge ${LEVEL_COLORS[result.sourceLevel] || ''}`}>
            {result.sourceLevel}
            {result.sourceEntityId && <small>{result.sourceEntityId}</small>}
          </div>
          {result.version != null && <span className="muted">as of v{result.version}</span>}
        </div>
      )}

      {trace && (
        <ol className="trace">
          {trace.trace.map((step) => (
            <li key={step.level} className={step.applied ? 'trace-win' : ''}>
              <span className={`level-badge ${LEVEL_COLORS[step.level] || ''}`}>{step.level}</span>
              <code className="trace-entity">{step.entityId ?? '—'}</code>
              <span className={step.value == null ? 'muted' : ''}>
                {step.value ?? 'no override'}
              </span>
              {step.applied && <span className="trace-flag">wins</span>}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
