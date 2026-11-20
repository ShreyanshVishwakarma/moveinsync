import { chromium } from 'playwright'

const BASE = 'http://localhost:5173'
const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' :: ' + detail : ''}`)
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const consoleErrors = []
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => consoleErrors.push(String(e)))

await page.goto(BASE, { waitUntil: 'networkidle' })

// 1. login screen blocks the app
check('login screen shown', await page.locator('.login-card').count() === 1)
check('app hidden before login', await page.locator('.topbar').count() === 0)

// 2. wrong password
await page.locator('input[type="password"]').fill('wrong')
await page.getByRole('button', { name: 'Sign in' }).click()
await page.waitForSelector('.inline-error', { timeout: 10000 })
check('bad password rejected', (await page.locator('.inline-error').innerText()).includes('Invalid username or password'))

// 3. login as operator via demo chip
await page.getByRole('button', { name: /Operator/ }).first().click()
await page.getByRole('button', { name: 'Sign in' }).click()
await page.waitForSelector('.topbar', { timeout: 15000 })
await page.waitForSelector('.live-connected', { timeout: 15000 }).catch(() => {})

const chips = await page.locator('.topbar .chip').allInnerTexts()
check('session shows role and tenant', chips.join(' ').includes('OPERATOR') && chips.join(' ').includes('acme-corp'), chips.join(' | '))
check('websocket connected with token', await page.locator('.live-connected').count() > 0)
check('token persisted to storage', await page.evaluate(() => Boolean(localStorage.getItem('moveinsync.session'))))

// 4. read path works with the token
await page.getByRole('button', { name: 'Resolve', exact: true }).click()
await page.waitForSelector('.result', { timeout: 10000 })
check('resolve as operator -> 30', (await page.locator('.result-value strong').innerText()) === '30')

// 5. publish works for operator
await page.getByRole('button', { name: 'Prefill two-key example' }).click()
await page.getByRole('button', { name: 'Publish change set' }).click()
await page.waitForSelector('.published', { timeout: 15000 })
check('operator can publish', (await page.locator('.published-head strong').innerText()).includes('v2'))

// 6. role change: sign out, sign in as employee, write is refused
await page.getByRole('button', { name: 'Sign out' }).click()
await page.waitForSelector('.login-card', { timeout: 10000 })
check('sign out returns to login', await page.locator('.login-card').count() === 1)
check('session cleared from storage', await page.evaluate(() => localStorage.getItem('moveinsync.session') === null))

await page.getByRole('button', { name: /Employee/ }).first().click()
await page.getByRole('button', { name: 'Sign in' }).click()
await page.waitForSelector('.topbar', { timeout: 15000 })

await page.getByRole('button', { name: 'Prefill two-key example' }).click()
await page.getByRole('button', { name: 'Publish change set' }).click()
await page.waitForSelector('.banner-error', { timeout: 10000 })
const banner = await page.locator('.banner-error').innerText()
check('employee publish refused with 403', banner.includes('403') || banner.includes('ROLE_FORBIDDEN'), banner)

// 7. admin sees history-only panels
await page.getByRole('button', { name: 'Sign out' }).click()
await page.waitForSelector('.login-card')
await page.getByRole('button', { name: /Admin/ }).first().click()
await page.getByRole('button', { name: 'Sign in' }).click()
await page.waitForSelector('.topbar', { timeout: 15000 })
check('admin session shows all tenants', (await page.locator('.topbar .chip').allInnerTexts()).join(' ').includes('all tenants'))

await page.screenshot({ path: 'C:/Users/ASUS/AppData/Local/Temp/opencode/jwt-dashboard.png', fullPage: false })

const real = consoleErrors.filter((t) => !t.includes('401') && !t.includes('403') && !t.includes('422'))
check('no uncaught console errors', real.length === 0, real.slice(0, 3).join(' | '))

await browser.close()
const failed = results.filter((r) => !r.ok)
console.log(`\nTOTAL ${results.length} · FAILED ${failed.length}`)
process.exit(failed.length === 0 ? 0 : 1)
