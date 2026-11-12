import { useState } from 'react'
import { api } from '../api'

/**
 * Canary → promote → rollback. Promotion is version-driven, so the panel reads the
 * latest versions from the server instead of guessing one.
 */
export default function RolloutPanel({ apiKey, tenant, serverVersion, onDone, onError, log }) {
  const [stagedVersion, setStagedVersion] = useState('')
  const [targetVersion, setTargetVersion] = useState('1')
  const [reason, setReason] = useState('Roll back a bad change')
  const [diff, setDiff] = useState(null)
  const [busy, setBusy] = useState(null)

  async function promote() {
    const version = Number(stagedVersion)
    if (!Number.isFinite(version) || version <= 0) {
      onError?.(new Error('Enter the version produced by the canary'))
      return
    }
    setBusy('promote')
    try {
      const result = await api.promote(apiKey, version, 'operator-1', true)
      log?.('commit', `Promoted v${version} → v${result.version}`, `${result.entries.length} entries`)
      onDone?.(`Canary v${version} promoted as v${result.version}`)
      setStagedVersion('')
    } catch (error) {
      onError?.(error)
    } finally {
      setBusy(null)
    }
  }

  async function preview() {
    const target = Number(targetVersion)
    setBusy('preview')
    try {
      const result = await api.rollbackPreview(apiKey, target)
      setDiff(result)
      log?.('info', `Rollback preview to v${target}`, `${result.length} changes`)
    } catch (error) {
      onError?.(error)
    } finally {
      setBusy(null)
    }
  }

  async function applyRollback() {
    const target = Number(targetVersion)
    setBusy('rollback')
    try {
      const result = await api.rollback(apiKey, {
        targetVersion: target,
        author: 'operator-1',
        reason: reason.trim() || 'Rollback from console',
        dryRun: false
      })
      log?.('commit', `Rolled back to v${target}`, `published v${result.publishedVersion}`)
      onDone?.(`Rolled back to v${target} as v${result.publishedVersion}`)
      setDiff(null)
    } catch (error) {
      onError?.(error)
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="card">
      <div className="card-head">
        <h2>Canary, promote, roll back</h2>
        <p>Stage a change on a few offices, widen it to the whole client, or revert to any version.</p>
      </div>

      <div className="rollout-grid">
        <div className="rollout-block">
          <h3>Promote a canary</h3>
          <p className="muted small">
            Enter the version that staged office-level overrides for {tenant}.
          </p>
          <div className="row">
            <label className="field compact">
              <span>Staged version</span>
              <input
                value={stagedVersion}
                placeholder={serverVersion ? `latest is v${serverVersion}` : 'e.g. 3'}
                onChange={(event) => setStagedVersion(event.target.value)}
                inputMode="numeric"
              />
            </label>
            <button className="button" type="button" onClick={promote} disabled={busy === 'promote'}>
              {busy === 'promote' ? 'Promoting…' : 'Promote'}
            </button>
          </div>
        </div>

        <div className="rollout-block">
          <h3>Roll back</h3>
          <p className="muted small">Preview the diff first; applying publishes a new version.</p>
          <div className="row">
            <label className="field compact">
              <span>Target version</span>
              <input
                value={targetVersion}
                onChange={(event) => setTargetVersion(event.target.value)}
                inputMode="numeric"
              />
            </label>
            <button className="button ghost" type="button" onClick={preview} disabled={busy === 'preview'}>
              {busy === 'preview' ? 'Loading…' : 'Preview diff'}
            </button>
          </div>
          <label className="field compact full">
            <span>Reason</span>
            <input value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
          <button
            className="button danger"
            type="button"
            onClick={applyRollback}
            disabled={busy === 'rollback'}
          >
            {busy === 'rollback' ? 'Rolling back…' : 'Apply rollback'}
          </button>
        </div>
      </div>

      {diff && (
        <div className="diff">
          <div className="published-head">
            <strong>Diff vs current</strong>
            <span className="muted">{diff.length} changes</span>
          </div>
          {diff.length === 0 ? (
            <p className="muted">Nothing would change — that version already matches the current state.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Setting</th>
                  <th>Level</th>
                  <th>Entity</th>
                  <th>From</th>
                  <th>To</th>
                </tr>
              </thead>
              <tbody>
                {diff.map((entry) => (
                  <tr key={`${entry.key}-${entry.level}-${entry.entityId}`}>
                    <td>
                      <code>{entry.key}</code>
                    </td>
                    <td>{entry.level}</td>
                    <td>{entry.entityId}</td>
                    <td className="muted">{entry.oldValue ?? '—'}</td>
                    <td>
                      <strong>{entry.newValue ?? 'deleted'}</strong>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </section>
  )
}
