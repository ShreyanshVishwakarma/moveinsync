export default function TopBar({
  session,
  serverVersion,
  liveStatus,
  liveVersion,
  onLogout,
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
        <div className="chips">
          <span className="chip chip-role">{session.role}</span>
          <span className="chip">{session.username}</span>
          <span className="chip">{session.clientId ?? 'all tenants'}</span>
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
        <button className="button ghost" type="button" onClick={onLogout}>
          Sign out
        </button>
      </div>
    </header>
  )
}
