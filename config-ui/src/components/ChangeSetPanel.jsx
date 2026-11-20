import { useEffect, useState } from 'react'
import { api } from '../api'

const LEVELS = ['CLIENT', 'OFFICE', 'SHIFT']
const EMPTY_ROW = { key: '', level: 'OFFICE', entityId: '', value: '' }

/**
 * Builds and publishes an atomic change set. A night window needs two entries in one
 * change set — the form makes that the normal case rather than a special one.
 */
export default function ChangeSetPanel({ token, definitions, tenant, offices, defaultAuthor, onCommitted, onError }) {
  const [author, setAuthor] = useState(defaultAuthor ?? 'operator')
  const [reason, setReason] = useState('')
  const [rows, setRows] = useState([{ ...EMPTY_ROW, key: '', entityId: offices[0] ?? '' }])
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState(null)
  const [lastResult, setLastResult] = useState(null)

  useEffect(() => {
    if (definitions.length > 0) {
      setRows((current) =>
        current.map((row) => (row.key ? row : { ...row, key: definitions[0].key }))
      )
    }
  }, [definitions])

  function updateRow(index, patch) {
    setRows((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)))
  }

  function addRow() {
    setRows((current) => [
      ...current,
      { ...EMPTY_ROW, key: definitions[0]?.key ?? '', entityId: offices[0] ?? '' }
    ])
  }

  function removeRow(index) {
    setRows((current) => (current.length === 1 ? current : current.filter((_, i) => i !== index)))
  }

  function prefillWindow() {
    setRows([
      { key: 'night_safety_window', level: 'OFFICE', entityId: offices[0] ?? '', value: '21:00-23:30' },
      { key: 'booking_cutoff_minutes', level: 'OFFICE', entityId: offices[0] ?? '', value: '25' }
    ])
    setReason('Tighten night window and cut-off for Bengaluru')
  }

  async function submit(event) {
    event.preventDefault()
    setLocalError(null)
    setBusy(true)
    try {
      const changes = rows.map((row) => {
        const entry = {
          key: row.key,
          level: row.level,
          entityId: row.level === 'CLIENT' ? tenant : row.entityId,
          value: row.value.trim() === '' ? null : row.value.trim()
        }
        if (row.level !== 'CLIENT') entry.clientId = tenant
        return entry
      })

      const result = await api.commit(token, {
        author: author.trim() || 'operator-1',
        reason: reason.trim() || 'Change from console',
        changes
      })

      setLastResult(result)
      setReason('')
      onCommitted?.(result)
    } catch (error) {
      setLocalError(error)
      onError?.(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <div className="card-head">
        <h2>Publish a change set</h2>
        <p>Every row lands under one version, or none of them do. Blank value deletes the override.</p>
      </div>

      <form onSubmit={submit}>
        <div className="grid-2">
          <label className="field">
            <span>Author</span>
            <input value={author} onChange={(event) => setAuthor(event.target.value)} />
          </label>
          <label className="field">
            <span>Reason</span>
            <input
              value={reason}
              placeholder="Why is this change being made?"
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
        </div>

        <div className="rows">
          <div className="row-head">
            <span>Setting</span>
            <span>Level</span>
            <span>Entity</span>
            <span>Value</span>
            <span />
          </div>

          {rows.map((row, index) => (
            <div className="row-line" key={index}>
              <select
                value={row.key}
                onChange={(event) => updateRow(index, { key: event.target.value })}
              >
                {definitions.map((entry) => (
                  <option key={entry.key} value={entry.key}>
                    {entry.key}
                  </option>
                ))}
              </select>

              <select
                value={row.level}
                onChange={(event) => updateRow(index, { level: event.target.value })}
              >
                {LEVELS.map((level) => (
                  <option key={level} value={level}>
                    {level}
                  </option>
                ))}
              </select>

              {row.level === 'CLIENT' ? (
                <input value={tenant} readOnly title="Client-level overrides always target the acting tenant" />
              ) : (
                <input
                  value={row.entityId}
                  list="office-ids"
                  placeholder="office or shift id"
                  onChange={(event) => updateRow(index, { entityId: event.target.value })}
                />
              )}

              <input
                value={row.value}
                placeholder="blank deletes"
                onChange={(event) => updateRow(index, { value: event.target.value })}
              />

              <button
                className="icon-button"
                type="button"
                aria-label="Remove row"
                onClick={() => removeRow(index)}
                disabled={rows.length === 1}
              >
                ×
              </button>
            </div>
          ))}

          <datalist id="office-ids">
            {offices.map((id) => (
              <option key={id} value={id} />
            ))}
          </datalist>
        </div>

        <div className="row">
          <button className="button ghost" type="button" onClick={addRow}>
            + Add row
          </button>
          <button className="button ghost" type="button" onClick={prefillWindow}>
            Prefill two-key example
          </button>
          <button className="button" type="submit" disabled={busy}>
            {busy ? 'Publishing…' : 'Publish change set'}
          </button>
        </div>
      </form>

      {localError && (
        <div className="inline-error">
          <strong>
            {localError.status} {localError.code}
          </strong>
          {localError.details?.length > 0 ? (
            <ul>
              {localError.details.map((detail) => (
                <li key={detail}>{detail}</li>
              ))}
            </ul>
          ) : (
            <span>{localError.message}</span>
          )}
        </div>
      )}

      {lastResult && (
        <div className="published">
          <div className="published-head">
            <strong>Published v{lastResult.version}</strong>
            <span className="muted">{lastResult.entries.length} entries</span>
          </div>
          <ul>
            {lastResult.entries.map((entry) => (
              <li key={`${entry.key}-${entry.level}-${entry.entityId}`}>
                <code>{entry.key}</code>
                <span className="muted">
                  {entry.level} · {entry.entityId}
                </span>
                <span>
                  {entry.oldValue ?? '—'} → <strong>{entry.newValue ?? 'deleted'}</strong>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}
