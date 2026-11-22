import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

async function register(page: Page, name: string, email: string) {
  await page.goto('/register')
  await page.getByLabel('Given name').fill(name)
  await page.getByLabel('Family name').fill('Fixture')
  await page.getByLabel('Email address').fill(email)
  await page.getByRole('button', { name: 'Register customer' }).click()
}

async function createAndSelect(page: Page, name: string) {
  const email = `${randomUUID()}@example.test`
  await register(page, name, email)
  await expect(page.getByLabel('Customer ID')).toHaveValue(/^[\da-f-]{36}$/)
  const id = await page.getByLabel('Customer ID').inputValue()
  await page.getByRole('button', { name: 'Select customer' }).click()
  await expect(page.getByText(email, { exact: true })).toBeVisible()
  return { id, email }
}

test('validates synthetic registration and reports actual duplicate email', async ({ page }) => {
  const email = `${randomUUID()}@example.test`
  await page.goto('/register')
  await page.getByRole('button', { name: 'Register customer' }).click()
  await expect(page.getByText('Enter a name.')).toHaveCount(2)
  await register(page, 'Asha', 'asha@example.com')
  await expect(page.getByText('Use a synthetic email ending in .test.')).toBeVisible()
  await register(page, 'Asha', email)
  await expect(page.getByText('Customer registered.', { exact: false })).toBeVisible()
  await register(page, 'Asha', email.toUpperCase())
  await expect(page.getByText(/already registered/)).toBeVisible()
})

test('uses the HttpOnly session after reload and edits profile and consent', async ({ page, context }) => {
  const { email, id } = await createAndSelect(page, 'Asha')
  const cookie = (await context.cookies()).find((item) => item.name === 'RETAIL_DEMO_SESSION')
  expect(cookie).toMatchObject({ httpOnly: true, sameSite: 'Strict', domain: '127.0.0.1' })
  expect(await page.evaluate(() => ({
    cookie: document.cookie, local: localStorage.length, session: sessionStorage.length,
  }))).toEqual({ cookie: '', local: 0, session: 0 })
  await page.goto(`/profile?customerId=${randomUUID()}`)
  await expect(page.getByText(email, { exact: true })).toBeVisible()
  await page.getByLabel('Given name').fill('Meera')
  await page.getByRole('button', { name: 'Save profile' }).click()
  await expect(page.getByText('Profile updated.')).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('Given name')).toHaveValue('Meera')

  await page.getByRole('link', { name: 'Preferences', exact: true }).click()
  const consent = page.getByRole('switch', { name: 'Personalization consent' })
  await expect(consent).not.toBeChecked()
  await expect(page.getByTestId('consent-state')).toHaveText('Disabled')
  await page.getByLabel('Language tag').fill('en-IN')
  await page.getByRole('switch', { name: 'Marketing opt-in' }).check()
  await consent.check()
  await page.getByRole('button', { name: 'Save preferences' }).click()
  await expect(page.getByText('Preferences saved.')).toBeVisible()
  await expect(page.getByTestId('consent-state')).toHaveText('Enabled')
  expect(await (await context.request.get('/api/v1/demo/session/context')).json()).toMatchObject({
    customerId: id, personalizationConsent: true,
  })
  await page.reload()
  await expect(page.getByLabel('Language tag')).toHaveValue('en-IN')
  await expect(page.getByRole('switch', { name: 'Marketing opt-in' })).toBeChecked()
  await consent.uncheck()
  await page.getByRole('button', { name: 'Save preferences' }).click()
  await expect(page.getByText('Preferences saved.')).toBeVisible()
  await expect(page.getByTestId('consent-state')).toHaveText('Disabled')
  expect(await (await context.request.get('/api/v1/demo/session/context')).json()).toMatchObject({
    customerId: id, personalizationConsent: false,
  })
})

test('switches customers, clears another tab, and invalidates old cookies', async ({ page, context, playwright, baseURL }) => {
  const first = await createAndSelect(page, 'First')
  const old = (await context.cookies()).find((item) => item.name === 'RETAIL_DEMO_SESSION')!
  const secondTab = await context.newPage()
  await secondTab.goto('/profile')
  await expect(secondTab.getByLabel('Given name')).toHaveValue('First')
  await secondTab.getByLabel('Given name').fill('Abandoned edit')

  const second = await createAndSelect(page, 'Second')
  await expect(secondTab.getByLabel('Given name')).toHaveValue('Second')
  await expect(secondTab.getByText(first.email, { exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Preferences', exact: true }).click()
  await expect(page.getByRole('switch', { name: 'Personalization consent' })).not.toBeChecked()
  const current = (await context.cookies()).find((item) => item.name === old.name)!
  expect(current.value).not.toEqual(old.value)
  const replay = await playwright.request.newContext({ baseURL })
  try {
    const stale = await replay.get('/api/v1/demo/session/context', { headers: { Cookie: `${old.name}=${old.value}` } })
    expect(stale.status()).toBe(403)
    await page.getByRole('button', { name: 'End session' }).click()
    await expect(page.getByText('No customer selected')).toBeVisible()
    await expect(secondTab.getByRole('heading', { name: 'Select a customer first' })).toBeVisible()
    expect((await replay.get('/api/v1/demo/session/context', { headers: { Cookie: `${current.name}=${current.value}` } })).status()).toBe(403)
    expect((await context.request.get(`/api/v1/customers/${second.id}`)).status()).toBe(403)
  } finally {
    await replay.dispose()
  }
})

test('blocks foreign origins and unmarked writes while Spring stays closed to CORS', async ({ page, context, baseURL }) => {
  const { id } = await createAndSelect(page, 'Asha')
  const foreign = 'http://127.0.0.1:9191'
  const attempts = await Promise.all([
    context.request.post('/api/v1/demo/session', { data: { customerId: id },
      headers: { Origin: foreign, 'X-Retail-Portal': '1' } }),
    context.request.delete('/api/v1/demo/session', { headers: { Origin: baseURL! } }),
    context.request.get('/api/v1/demo/session/context', { headers: { Origin: foreign } }),
  ])
  expect(attempts.map((attempt) => attempt.status())).toEqual([403, 403, 403])
  expect(attempts.every((attempt) => !attempt.headers()['access-control-allow-origin'])).toBe(true)
  expect((await context.request.get('/api/v1/demo/session/context')).status()).toBe(200)

  const springPort = process.env.RETAIL_CUSTOMER_API_PORT ?? '8081'
  const cors = await context.request.fetch(`http://127.0.0.1:${springPort}/api/v1/demo/session`, {
    method: 'OPTIONS', headers: { Origin: foreign, 'Access-Control-Request-Method': 'DELETE' },
  })
  expect(cors.headers()['access-control-allow-origin']).toBeUndefined()
})

test('reports a failed profile request and recovers after retry', async ({ page }) => {
  const { email } = await createAndSelect(page, 'Retry')
  await page.route(/\/api\/v1\/customers\/[\da-f-]+$/, (route) => route.fulfill({ status: 503, body: '{}' }), { times: 1 })
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('temporarily unavailable')
  await page.getByRole('button', { name: 'Try again' }).click()
  await expect(page.getByText(email, { exact: true })).toBeVisible()
})

test('keeps all navigation and registration controls usable on a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  const preferences = page.getByRole('link', { name: 'Preferences', exact: true })
  await expect(preferences).toBeInViewport()
  await expect(page.getByRole('button', { name: 'Register customer' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
})

test('keeps the assistant usable at 320px with keyboard navigation', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 780 })
  await createAndSelect(page, 'Keyboard')
  const assistant = page.getByRole('link', { name: 'AI Assistant' })
  await assistant.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: 'Chat with the assistant' })).toBeVisible()
  const suggestion = page.getByRole('button', { name: 'How do I register?' })
  await suggestion.focus()
  await page.keyboard.press('Enter')
  const question = page.getByLabel('Your question')
  await expect(question).toBeFocused()
  await expect(question).toHaveValue('How do I register?')
  await page.keyboard.press('Shift+Enter')
  await expect(question).toHaveValue(/\n$/)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
})

test('restricts the exact chat paths, method, origin, and marker', async ({ page, baseURL }) => {
  const headers = { Origin: baseURL!, 'X-Retail-Portal': '1', 'X-Retail-CSRF': randomUUID() }
  const blocked = await Promise.all([
    page.request.get('/api/v1/chat/ask', { headers }),
    page.request.get('/api/v1/chat/csrf', { headers: { Origin: baseURL! } }),
    page.request.post('/api/v1/chat/ask?customerId=ignored', { headers, data: { message: 'Hello' } }),
    page.request.post('/api/v1/chat/ask', {
      headers: { ...headers, Origin: 'http://127.0.0.1:9191' }, data: { message: 'Hello' },
    }),
    page.request.post('/api/v1/chat/ask', {
      headers: { Origin: baseURL!, 'X-Retail-Portal': '1' }, data: { message: 'Hello' },
    }),
    page.request.post('/api/internal/v1/retail/ask', { headers, data: { message: 'Hello' } }),
  ])
  for (const response of blocked) {
    expect(response.status()).toBe(403)
    expect(await response.json()).toHaveProperty('message', 'Request blocked by the local portal proxy')
  }
})

test('renders a single-turn answer and evidence from real Spring and ADK', async ({ page, context, baseURL }) => {
  test.skip(process.env.RETAIL_CHAT_E2E !== 'true', 'Requires an external loopback ADK adapter and chat-enabled Spring')
  test.setTimeout(90_000)
  await createAndSelect(page, 'Assistant')
  const rejected = await context.request.post('/api/v1/chat/ask', {
    data: { message: 'How do I register?' },
    headers: { Origin: baseURL!, 'X-Retail-Portal': '1', 'X-Retail-CSRF': randomUUID() },
  })
  expect(rejected.status()).toBe(403)
  expect((await rejected.json()).error.code).toBe('CSRF_REJECTED')
  await page.getByRole('link', { name: 'AI Assistant' }).click()
  const input = page.getByLabel('Your question')
  const question = 'How do I register? Tell me about KB-201 and check its availability.'
  await input.fill(question)
  const post = page.waitForRequest((request) => request.method() === 'POST' && request.url().endsWith('/chat/ask'))
  await input.press('Enter')
  expect((await post).postDataJSON()).toEqual({ message: question })
  const answer = page.getByRole('region', { name: 'Assistant response' })
  await expect(answer.getByText(/HTTP 201/)).toBeVisible({ timeout: 60_000 })
  await expect(answer.getByText('Document citations')).toBeVisible()
  await expect(answer.getByText('Product catalog references')).toBeVisible()
  await expect(answer.getByText(/Product-level inventory count/)).toBeVisible()
  await expect(answer.getByText('local-stock/get_inventory')).toBeVisible()
  await expect(answer.getByText('General answer')).toBeVisible()
  await expect(page.getByText('Personalization consent')).toHaveText(/Disabled/)
  await page.getByRole('button', { name: 'End session' }).click()
  await page.getByRole('link', { name: 'AI Assistant' }).click()
  await expect(page.getByText(/Select a synthetic customer to start chatting/)).toBeVisible()
  await expect(answer).toHaveCount(0)
})
