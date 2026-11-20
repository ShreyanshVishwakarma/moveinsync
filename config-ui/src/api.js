// Thin client for the config server's REST API.
// Every call takes the bearer token explicitly so the UI can switch sessions and handle
// 401s (expired or revoked token) in one place.

const BASE = '/api/v1'

export class ApiError extends Error {
  constructor(status, payload) {
    super(payload?.message || `Request failed (${status})`)
    this.status = status
    this.code = payload?.code || 'UNKNOWN'
    this.details = payload?.details || []
  }

  /** True when the session is gone and the user has to log in again. */
  get isSessionExpired() {
    return this.status === 401 && ['TOKEN_EXPIRED', 'TOKEN_REVOKED', 'TOKEN_INVALID', 'UNAUTHENTICATED'].includes(this.code)
  }
}

async function request(path, { method = 'GET', token, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })

  const text = await response.text()
  const payload = text ? JSON.parse(text) : null

  if (!response.ok) {
    throw new ApiError(response.status, payload)
  }
  return payload
}

export const api = {
  // --- auth -------------------------------------------------------------------
  login: (username, password, ttlSeconds) =>
    request('/auth/login', {
      method: 'POST',
      body: { username, password, ttlSeconds: ttlSeconds ?? null }
    }),

  me: (token) => request('/auth/me', { token }),

  logout: (token) => request('/auth/logout', { method: 'POST', token }),

  // --- reads ------------------------------------------------------------------
  version: (token) => request('/config/version', { token }),

  definitions: (token) => request('/definitions', { token }),

  createDefinition: (token, definition) =>
    request('/definitions', { method: 'POST', token, body: definition }),

  snapshot: (token) => request('/config/snapshot', { token }),

  resolve: (token, { key, clientId, officeId, shiftId }) => {
    const params = new URLSearchParams({ key })
    if (clientId) params.set('clientId', clientId)
    if (officeId) params.set('officeId', officeId)
    if (shiftId) params.set('shiftId', shiftId)
    return request(`/config/resolve?${params}`, { token })
  },

  explain: (token, { key, clientId, officeId, shiftId }) => {
    const params = new URLSearchParams({ key })
    if (clientId) params.set('clientId', clientId)
    if (officeId) params.set('officeId', officeId)
    if (shiftId) params.set('shiftId', shiftId)
    return request(`/config/explain?${params}`, { token })
  },

  // --- writes -----------------------------------------------------------------
  commit: (token, changeSet) =>
    request('/change-sets', { method: 'POST', token, body: changeSet }),

  history: (token, limit = 20) => request(`/change-sets?limit=${limit}`, { token }),

  changeSet: (token, version) => request(`/change-sets/${version}`, { token }),

  promote: (token, version, author, removeSourceOverrides = true) =>
    request(
      `/change-sets/${version}/promote?removeSourceOverrides=${removeSourceOverrides}&author=${encodeURIComponent(author)}`,
      { method: 'POST', token }
    ),

  rollbackPreview: (token, targetVersion) =>
    request(`/rollbacks/${targetVersion}/preview`, { token }),

  rollback: (token, { targetVersion, author, reason, dryRun }) =>
    request('/rollbacks', {
      method: 'POST',
      token,
      body: { targetVersion, author, reason, dryRun }
    })
}
