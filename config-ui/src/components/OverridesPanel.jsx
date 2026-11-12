const LEVEL_ORDER = ['SHIFT', 'OFFICE', 'CLIENT']

/**
 * Shows the snapshot the API returned for the current key. Because the server filters
 * by tenant, switching role changes what appears here — that is the isolation demo.
 */
export default function OverridesPanel({ snapshot, loading, onRefresh }) {
  const overrides = snapshot?.overrides ?? {}

  const sections = LEVEL_ORDER
    .map((level) => ({
      level,
      entities: Object.entries(overrides[level] ?? {}).sort(([a], [b]) => a.localeCompare(b))
    }))
    .filter((section) => section.entities.length > 0)

  const total = sections.reduce(
    (sum, section) => sum + section.entities.reduce((inner, [, values]) => inner + Object.keys(values).length, 0),
    0
  )

  return (
    <section className="card side-card">
      <div className="card-head with-action">
        <div>
          <h2>Visible overrides</h2>
          <p>What this API key is allowed to see.</p>
        </div>
        <button className="icon-button" type="button" onClick={onRefresh} aria-label="Reload snapshot">
          ↻
        </button>
      </div>

      <div className="snapshot-meta">
        <span className="chip">v{snapshot?.version ?? '–'}</span>
        <span className="muted">{total} overrides</span>
        {loading && <span className="muted">loading…</span>}
      </div>

      {sections.length === 0 && !loading && (
        <p className="muted">
          No overrides visible. Try the admin key, or publish a change set.
        </p>
      )}

      {sections.map((section) => (
        <div className="override-group" key={section.level}>
          <h3>{section.level}</h3>
          {section.entities.map(([entityId, values]) => (
            <div className="override-entity" key={entityId}>
              <span className="entity-name">{entityId}</span>
              <ul>
                {Object.entries(values).map(([key, value]) => (
                  <li key={key}>
                    <code>{key}</code>
                    <span>{value}</span>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      ))}
    </section>
  )
}
