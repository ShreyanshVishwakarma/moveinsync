# JWT — manual curl checks

Copy-paste these in Windows PowerShell 5.1 or PowerShell 7. Start the server first:

```powershell
pwsh ./scripts/run-server.ps1
```

JSON bodies are piped as here-strings into `curl --data-binary "@-"` because PowerShell 5.1
mangles quoted JSON passed directly as an argument. `python -m json.tool` is only there to
pretty-print; drop the pipe if Python is not installed.

Demo users (seeded on startup):

| Username | Password | Role | Tenant |
| --- | --- | --- | --- |
| `admin` | `password123` | ADMIN | all |
| `operator` | `password123` | OPERATOR | acme-corp |
| `employee` | `password123` | EMPLOYEE | acme-corp |
| `globex` | `password123` | OPERATOR | globex-corp |

---

## 1. Log in and keep the token

```powershell
$login = @'
{"username":"operator","password":"password123"}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" `
  --data-binary "@-"

$env:TOKEN = ($login | ConvertFrom-Json).accessToken
$login | python -m json.tool
```

Expected: `200`, with `accessToken`, `tokenType: Bearer`, `expiresInSeconds: 604800`
(7 days), the role and the tenant. Nothing is stored server-side — the token is the
credential.

## 2. What the token says about me

```powershell
curl.exe -s http://localhost:8080/api/v1/auth/me `
  -H "Authorization: Bearer $env:TOKEN" | python -m json.tool
```

Expected: `username`, `role`, `clientId`, `tokenId`, `issuedAt`, `expiresAt`,
`secondsRemaining`.

## 3. No token → 401

```powershell
curl.exe -s -o NUL -w "HTTP %{http_code}\n" http://localhost:8080/api/v1/config/version
curl.exe -s http://localhost:8080/api/v1/config/version | python -m json.tool
```

Expected: `HTTP 401` and code `UNAUTHENTICATED`.

## 4. A read with the token (tenant-scoped)

```powershell
curl.exe -s -H "Authorization: Bearer $env:TOKEN" `
  "http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&officeId=blr-office-1" `
  | python -m json.tool
```

Expected: value `30`, `sourceLevel: OFFICE`.

## 5. Tenant isolation — ask for another client

```powershell
curl.exe -s -H "Authorization: Bearer $env:TOKEN" `
  "http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&clientId=globex-corp" `
  | python -m json.tool
```

Expected: `403` `TENANT_FORBIDDEN`. The operator token belongs to acme-corp.

## 6. Role checks on a write

Employee cannot publish:

```powershell
$employee = (@'
{"username":"employee","password":"password123"}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" --data-binary "@-" | ConvertFrom-Json).accessToken

@'
{"author":"employee","reason":"nope","changes":[
  {"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-1","value":"25"}]}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/change-sets `
  -H "Authorization: Bearer $employee" `
  -H "Content-Type: application/json" --data-binary "@-" | python -m json.tool
```

Expected: `403` `ROLE_FORBIDDEN`.

Operator can:

```powershell
@'
{"author":"operator","reason":"manual check","changes":[
  {"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-1","value":"25"}]}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/change-sets `
  -H "Authorization: Bearer $env:TOKEN" `
  -H "Content-Type: application/json" --data-binary "@-" | python -m json.tool
```

Expected: `201` with a new `version` and the entry.

## 7. Admin-only endpoints

```powershell
# Audit history is admin-only
curl.exe -s -o NUL -w "operator -> HTTP %{http_code}\n" `
  -H "Authorization: Bearer $env:TOKEN" http://localhost:8080/api/v1/change-sets

$admin = (@'
{"username":"admin","password":"password123"}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" --data-binary "@-" | ConvertFrom-Json).accessToken

curl.exe -s -o NUL -w "admin    -> HTTP %{http_code}\n" `
  -H "Authorization: Bearer $admin" http://localhost:8080/api/v1/change-sets

# Schema changes are admin-only too
@'
{"key":"manual_check_setting","type":"DURATION","defaultValue":"15","minValue":0,"maxValue":120}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/definitions `
  -H "Authorization: Bearer $env:TOKEN" `
  -H "Content-Type: application/json" --data-binary "@-" | python -m json.tool
```

Expected: operator `403`, admin `200`, and the definition POST is `403` for the operator.

## 8. A tampered token

```powershell
$bad = $env:TOKEN.Substring(0, $env:TOKEN.Length - 2) + "AA"
curl.exe -s -H "Authorization: Bearer $bad" `
  http://localhost:8080/api/v1/config/version | python -m json.tool
```

Expected: `401` with `code: TOKEN_INVALID`.

## 9. Logout revokes immediately

```powershell
curl.exe -s -o NUL -w "logout  -> HTTP %{http_code}\n" -X POST `
  -H "Authorization: Bearer $env:TOKEN" http://localhost:8080/api/v1/auth/logout

curl.exe -s -H "Authorization: Bearer $env:TOKEN" `
  http://localhost:8080/api/v1/config/version | python -m json.tool
```

Expected: logout `204`, then `401` with `code: TOKEN_REVOKED`. The token is still
cryptographically valid — the `jti` is on the revocation list — which is what makes
"log out everywhere" possible with stateless tokens.

## 10. Requesting a shorter lifetime

The server default is 7 days and never exceeds it. Ask for 15 minutes:

```powershell
@'
{"username":"operator","password":"password123","ttlSeconds":900}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" --data-binary "@-" | python -m json.tool
```

Expected: `expiresInSeconds: 900`. Ask for `31536000` (a year) and the response still says
`604800`.

## 11. Bad credentials

```powershell
@'
{"username":"operator","password":"wrong"}
'@ | curl.exe -s -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" --data-binary "@-" | python -m json.tool
```

Expected: `401` `INVALID_CREDENTIALS`. An unknown username returns the identical body, so
the endpoint does not reveal which half was wrong.

## 12. Service accounts still use API keys

Machine-to-machine callers that cannot log in keep using `X-API-Key`; it maps to the same
principal, so every role rule applies identically.

```powershell
curl.exe -s -H "X-API-Key: demo-admin-key" `
  http://localhost:8080/api/v1/config/version | python -m json.tool
```

Expected: `200`. An employee API key still gets `403` on writes.
