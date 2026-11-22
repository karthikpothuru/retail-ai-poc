import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { App } from './App'
import type { Profile } from './api'

const asha = '11111111-1111-4111-8111-111111111111'
const dev = '22222222-2222-4222-8222-222222222222'
const selection = '33333333-3333-4333-8333-333333333333'
const epoch = '44444444-4444-4444-8444-444444444444'
const defaults = { locale: 'en', marketingOptIn: false, personalizationConsent: false }
const profiles: Record<string, Profile> = {
  [asha]: { customerId: asha, givenName: 'Asha', familyName: 'Rao', email: 'asha@example.test' },
  [dev]: { customerId: dev, givenName: 'Dev', familyName: 'Shah', email: 'dev@example.test' },
}
const json = (payload: unknown, status = 200) => new Response(JSON.stringify(payload), {
  status, headers: { 'Content-Type': 'application/json' },
})

function fixture(initial: keyof typeof profiles | null = null) {
  let selected = initial
  let selectionId = selection
  let preferences = { ...defaults }
  let failProfile = false
  let duplicate = false
  const fetch = vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/demo/session/context')) {
      return selected ? json({ customerId: selected, selectionId, consentEpoch: epoch,
        personalizationConsent: preferences.personalizationConsent }) : json({}, 403)
    }
    if (url.endsWith('/demo/session') && init.method === 'DELETE') {
      selected = null
      return new Response(null, { status: 204 })
    }
    if (url.endsWith('/demo/session') && init.method === 'POST') {
      selected = JSON.parse(init.body as string).customerId
      selectionId = crypto.randomUUID()
      return new Response(null, { status: 204 })
    }
    if (url.endsWith('/customers') && init.method === 'POST') {
      return duplicate ? json({}, 409) : json(profiles[asha], 201)
    }
    if (selected && url.endsWith(`/${selected}/preferences`)) {
      if (init.method === 'PUT') preferences = JSON.parse(init.body as string)
      return json(preferences)
    }
    if (selected && url.endsWith(`/${selected}`)) {
      if (failProfile) return json({}, 503)
      if (init.method === 'PATCH') return json({ ...profiles[selected], ...JSON.parse(init.body as string) })
      return json(profiles[selected])
    }
    return json({}, 403)
  })
  vi.stubGlobal('fetch', fetch)
  return { fetch, failProfile: (value: boolean) => { failProfile = value }, duplicate: () => { duplicate = true } }
}

function mount(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const user = userEvent.setup()
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></QueryClientProvider>)
  return { user, client }
}

async function registerFields(user: ReturnType<typeof userEvent.setup>, email: string) {
  await user.type(screen.getByLabelText('Given name'), 'Asha')
  await user.type(screen.getByLabelText('Family name'), 'Rao')
  await user.type(screen.getByLabelText('Email address'), email)
  await user.click(screen.getByRole('button', { name: 'Register customer' }))
}

test('blocks invalid and non-synthetic registration before POST', async () => {
  const { fetch } = fixture()
  const { user } = mount('/register')
  await user.click(screen.getByRole('button', { name: 'Register customer' }))
  expect(await screen.findAllByText('Enter a name.')).toHaveLength(2)
  await registerFields(user, 'asha@example.com')
  expect(await screen.findByText('Use a synthetic email ending in .test.')).toBeVisible()
  expect(fetch.mock.calls.some(([, options]) => options.method === 'POST')).toBe(false)
})

test('shows a field error on duplicate email and preserves the form', async () => {
  const backend = fixture()
  backend.duplicate()
  const { user } = mount('/register')
  await registerFields(user, 'asha@example.test')
  expect(await screen.findByText(/already registered/)).toBeVisible()
  expect(screen.getByLabelText('Email address')).toHaveValue('asha@example.test')
})

test('treats a registration UUID as a selection candidate and opens the cookie-backed profile', async () => {
  const { fetch } = fixture()
  const { user } = mount('/register')
  await registerFields(user, 'asha@example.test')
  expect(await screen.findByLabelText('Customer ID')).toHaveValue(asha)
  expect(screen.getByText('No customer selected')).toBeVisible()
  await user.click(screen.getByRole('button', { name: 'Select customer' }))
  expect(await screen.findByText('asha@example.test')).toBeVisible()
  expect(fetch.mock.calls.every(([, init]) => init.credentials === 'include')).toBe(true)
  const request = fetch.mock.calls.find(([path, init]) => path.endsWith('/demo/session') && init.method === 'POST')
  expect(new Headers(request?.[1].headers).get('X-Retail-Portal')).toBe('1')
})

test('retrieves and edits the server-selected profile', async () => {
  const { fetch } = fixture(asha)
  const { user } = mount('/profile')
  const name = await screen.findByLabelText('Given name')
  expect(name).toHaveValue('Asha')
  await user.clear(name)
  await user.type(name, 'Meera')
  await user.click(screen.getByRole('button', { name: 'Save profile' }))
  expect(await screen.findByText('Profile updated.')).toBeVisible()
  const patch = fetch.mock.calls.find(([, init]) => init.method === 'PATCH')
  expect(patch?.[0]).toBe(`/api/v1/customers/${asha}`)
  expect(JSON.parse(patch?.[1].body as string)).toEqual({ givenName: 'Meera', familyName: 'Rao' })
})

test('shows default consent off and can explicitly grant and withdraw it', async () => {
  const { fetch } = fixture(asha)
  const { user } = mount('/preferences')
  const consent = await screen.findByRole('switch', { name: 'Personalization consent' })
  expect(consent).not.toBeChecked()
  expect(screen.getByTestId('consent-state')).toHaveTextContent('Disabled')
  const locale = screen.getByLabelText('Language tag')
  await user.clear(locale)
  await user.type(locale, 'en-IN')
  await user.click(screen.getByRole('switch', { name: 'Marketing opt-in' }))
  await user.click(consent)
  await user.click(screen.getByRole('button', { name: 'Save preferences' }))
  await waitFor(() => expect(screen.getByTestId('consent-state')).toHaveTextContent('Enabled'))
  await user.click(consent)
  await user.click(screen.getByRole('button', { name: 'Save preferences' }))
  await waitFor(() => expect(screen.getByTestId('consent-state')).toHaveTextContent('Disabled'))
  expect(fetch.mock.calls.filter(([, init]) => init.method === 'PUT').map(([, init]) => JSON.parse(init.body as string)))
    .toEqual([{ locale: 'en-IN', marketingOptIn: true, personalizationConsent: true },
      { locale: 'en-IN', marketingOptIn: true, personalizationConsent: false }])
})

test('clears the previous customer cache and form on switching or ending', async () => {
  fixture(asha)
  const { user, client } = mount('/profile')
  const name = await screen.findByLabelText('Given name')
  await user.type(name, ' Unsaved')
  await user.click(screen.getByRole('link', { name: 'Select customer' }))
  await user.type(screen.getByLabelText('Customer ID'), dev)
  await user.click(screen.getByRole('button', { name: 'Select customer' }))
  expect(await screen.findByText('dev@example.test')).toBeVisible()
  expect(screen.getByLabelText('Given name')).toHaveValue('Dev')
  expect(client.getQueryData(['customer', selection, 'profile'])).toBeUndefined()
  await user.click(screen.getByRole('button', { name: 'End session' }))
  expect(await screen.findByText('No customer selected')).toBeVisible()
  expect(client.getQueriesData({ queryKey: ['customer'] })).toHaveLength(0)
  await user.click(screen.getByRole('link', { name: 'Profile' }))
  expect(await screen.findByText('Select a customer first')).toBeVisible()
})

test('reports an API failure and allows retry', async () => {
  const backend = fixture(asha)
  backend.failProfile(true)
  const { user } = mount('/profile')
  expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')
  backend.failProfile(false)
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('asha@example.test')).toBeVisible()
})

test('a URL-supplied customer identifier cannot open a profile', async () => {
  const { fetch } = fixture()
  mount(`/profile?customerId=${asha}`)
  expect(await screen.findByText('Select a customer first')).toBeVisible()
  expect(fetch.mock.calls.some(([path]) => path.includes(asha))).toBe(false)
})
