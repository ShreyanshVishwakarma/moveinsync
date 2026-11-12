const KIND_LABEL = {
  delta: 'PUSH',
  snapshot: 'SYNC',
  commit: 'WRITE',
  reject: 'REJECT',
  read: 'READ',
  info: 'INFO'
}

export default function ActivityFeed({ entries, onClear }) {
  return (
    <section className="card side-card">
      <div className="card-head with-action">
        <div>
          <h2>Activity</h2>
          <p>Reads, writes and live pushes.</p>
        </div>
        <button className="icon-button" type="button" onClick={onClear} aria-label="Clear activity">
          ⌫
        </button>
      </div>

      {entries.length === 0 ? (
        <p className="muted">Nothing yet. Resolve a setting or publish a change.</p>
      ) : (
        <ul className="activity">
          {entries.map((entry) => (
            <li key={entry.id} className={`activity-${entry.kind}`}>
              <span className={`activity-kind kind-${entry.kind}`}>
                {KIND_LABEL[entry.kind] ?? entry.kind}
              </span>
              <div className="activity-body">
                <strong>{entry.message}</strong>
                {entry.detail && <span className="muted">{entry.detail}</span>}
              </div>
              <time>{entry.at.toLocaleTimeString()}</time>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
