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
page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })
page.on('pageerror', (error) => consoleErrors.push(String(error)))

await page.goto(BASE, { waitUntil: 'networkidle' })

// 1. initial render
check('title renders', (await page.title()).includes('Config Console'), await page.title())
await page.waitForSelector('.live-connected', { timeout: 15000 }).catch(() => {})
check('websocket connects', await page.locator('.live-connected').count() > 0)
const serverVersion = await page.locator('.version strong').first().innerText()
check('server version shown', /v\d+/.test(serverVersion), serverVersion)

// 2. resolve flow (operator role, blr office)
await page.getByRole('button', { name: 'Resolve', exact: true }).click()
await page.waitForSelector('.result', { timeout: 10000 })
const value = await page.locator('.result-value strong').innerText()
check('resolve returns office value 30', value === '30', value)
check('result shows OFFICE provenance', (await page.locator('.level-office').first().innerText()).includes('OFFICE'))

// 3. explain flow
await page.getByRole('button', { name: 'Explain' }).click()
await page.waitForSelector('.trace', { timeout: 10000 })
const steps = await page.locator('.trace li').count()
const winner = await page.locator('.trace-win .level-badge').innerText()
check('explain shows 4 levels', steps === 4, String(steps))
check('explain marks OFFICE as winner', winner.includes('OFFICE'), winner)

// 4. publish a two-key change set
await page.getByRole('button', { name: 'Prefill two-key example' }).click()
await page.getByRole('button', { name: 'Publish change set' }).click()
await page.waitForSelector('.published', { timeout: 15000 })
const published = await page.locator('.published-head strong').innerText()
const entryCount = await page.locator('.published li').count()
check('change set published as v2', published.includes('v2'), published)
check('change set has two entries', entryCount === 2, String(entryCount))
check('success banner shown', await page.locator('.banner-success').count() > 0)

// 5. live propagation: activity feed should record a push
await page.waitForTimeout(1200)
const feedText = await page.locator('.activity').innerText()
check('activity feed logs the write', feedText.includes('WRITE'), feedText.slice(0, 120).replace(/\n/g, ' | '))

// 6. overrides panel reflects new values
const overridesText = await page.locator('.override-group').first().innerText()
check('overrides panel shows office rows', overridesText.includes('blr-office-1'), overridesText.slice(0, 120).replace(/\n/g, ' | '))

// 7. validation rejection
await page.getByRole('button', { name: 'Prefill two-key example' }).click()
await page.locator('.row-line input').nth(1).fill('')
await page.locator('.row-line').last().locator('input').nth(1).fill('-30')
await page.getByRole('button', { name: 'Publish change set' }).click()
await page.waitForSelector('.inline-error', { timeout: 10000 })
const errorText = await page.locator('.inline-error').innerText()
check('invalid value rejected with 422', errorText.includes('422'), errorText.slice(0, 140).replace(/\n/g, ' | '))

// 8. role switch => tenant isolation
await page.locator('select').first().selectOption('demo-admin-key')
await page.waitForTimeout(1800)
const adminOverrides = await page.locator('.side-card').first().innerText()
check('admin sees acme overrides', adminOverrides.includes('acme-corp') || adminOverrides.includes('blr-office-1'))
await page.locator('select').first().selectOption('demo-globex-key')
await page.waitForTimeout(1800)
const globexOverrides = await page.locator('.side-card').first().innerText()
check('globex cannot see acme offices', !globexOverrides.includes('blr-office-1'), globexOverrides.slice(0, 160).replace(/\n/g, ' | '))

// 9. rollback preview
await page.locator('select').first().selectOption('demo-operator-key')
await page.waitForTimeout(1200)
await page.getByRole('button', { name: 'Preview diff' }).click()
await page.waitForSelector('.diff', { timeout: 10000 })
const diffText = await page.locator('.diff').innerText()
check('rollback preview shows a diff', diffText.includes('Diff vs current'), diffText.slice(0, 120).replace(/\n/g, ' | '))

// 10. screenshot for the user
await page.screenshot({ path: 'C:/Users/ASUS/AppData/Local/Temp/opencode/ui-dashboard.png', fullPage: false })

const realErrors = consoleErrors.filter((text) => !text.includes('422') && !text.includes('status of 40'))
check('no uncaught console errors', realErrors.length === 0, realErrors.slice(0, 3).join(' | '))

await browser.close()
const failed = results.filter((r) => !r.ok)
console.log(`\nTOTAL ${results.length} · FAILED ${failed.length}`)
process.exit(failed.length === 0 ? 0 : 1)
