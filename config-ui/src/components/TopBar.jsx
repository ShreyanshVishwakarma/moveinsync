export default function TopBar({
  keys,
  keyId,
  onKeyChange,
  role,
  tenantScope,
  serverVersion,
  liveStatus,
  liveVersion,
  onRefresh
}) {
  const liveLabel = {
    connected: 'Live',
    connecting: 'Connecting',
    disconnected: 'Reconnecting',
    error: 'Error',
    idle: 'Idle'
  }[liveStatus] || liveStatus

  return (
    <header className="topbar">
      <div className="brand">
        <div className="brand-mark">M</div>
        <div>
          <h1>Config Console</h1>
          <p>Hierarchical settings with live propagation</p>
        </div>
      </div>

      <div className="topbar-controls">
        <label className="field">
          <span>Acting as</span>
          <select value={keyId} onChange={(event) => onKeyChange(event.target.value)}>
            {keys.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.label} · {entry.role}
              </option>
            ))}
          </select>
        </label>

        <div className="chips">
          <span className="chip chip-role">{role}</span>
          <span className="chip">{tenantScope}</span>
        </div>

        <div className="version-block">
          <div className="version">
            <span className="version-label">Server</span>
            <strong>v{serverVersion ?? '–'}</strong>
          </div>
          <div className={`live live-${liveStatus}`}>
            <span className="dot" />
            {liveLabel}
            {liveVersion != null && <small>v{liveVersion}</small>}
          </div>
        </div>

        <button className="button ghost" type="button" onClick={onRefresh}>
          Refresh
        </button>
      </div>
    </header>
  )
}
