// Thin client for the config server's REST API.
// Every call takes the API key explicitly so the UI can switch roles without reloading.

const BASE = '/api/v1'

export class ApiError extends Error {
  constructor(status, payload) {
    super(payload?.message || `Request failed (${status})`)
    this.status = status
    this.code = payload?.code || 'UNKNOWN'
    this.details = payload?.details || []
  }
}

async function request(path, { method = 'GET', apiKey, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (apiKey) headers['X-API-Key'] = apiKey
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
  version: (apiKey) => request('/config/version', { apiKey }),

  definitions: (apiKey) => request('/definitions', { apiKey }),

  createDefinition: (apiKey, definition) =>
    request('/definitions', { method: 'POST', apiKey, body: definition }),

  snapshot: (apiKey) => request('/config/snapshot', { apiKey }),

  resolve: (apiKey, { key, clientId, officeId, shiftId }) => {
    const params = new URLSearchParams({ key })
    if (clientId) params.set('clientId', clientId)
    if (officeId) params.set('officeId', officeId)
    if (shiftId) params.set('shiftId', shiftId)
    return request(`/config/resolve?${params}`, { apiKey })
  },

  explain: (apiKey, { key, clientId, officeId, shiftId }) => {
    const params = new URLSearchParams({ key })
    if (clientId) params.set('clientId', clientId)
    if (officeId) params.set('officeId', officeId)
    if (shiftId) params.set('shiftId', shiftId)
    return request(`/config/explain?${params}`, { apiKey })
  },

  commit: (apiKey, changeSet) =>
    request('/change-sets', { method: 'POST', apiKey, body: changeSet }),

  history: (apiKey, limit = 20) => request(`/change-sets?limit=${limit}`, { apiKey }),

  changeSet: (apiKey, version) => request(`/change-sets/${version}`, { apiKey }),

  promote: (apiKey, version, author, removeSourceOverrides = true) =>
    request(
      `/change-sets/${version}/promote?removeSourceOverrides=${removeSourceOverrides}&author=${encodeURIComponent(author)}`,
      { method: 'POST', apiKey }
    ),

  rollbackPreview: (apiKey, targetVersion) =>
    request(`/rollbacks/${targetVersion}/preview`, { apiKey }),

  rollback: (apiKey, { targetVersion, author, reason, dryRun }) =>
    request('/rollbacks', {
      method: 'POST',
      apiKey,
      body: { targetVersion, author, reason, dryRun }
    })
}
