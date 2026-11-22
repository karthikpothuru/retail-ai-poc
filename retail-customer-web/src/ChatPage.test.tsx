import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { App } from './App'
import type { ChatReply } from './chat-contract'

const id = '11111111-1111-4111-8111-111111111111'
const marker = `[DOC:${id}:v1:${id}]`
const reply: ChatReply = {
  answer: `Use the registration form. ${marker}`,
  citations: [{ marker, channel: 'REGISTRATION' }], inventoryObservations: [], productSkus: [],
  status: 'OK', personalized: false, error: null,
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const failure = (code: string, status: number) => json({ status: 'ERROR', error: { code, message: 'private diagnostic' } }, status)
const observation: ChatReply['inventoryObservations'][number] = {
  sku: 'KB-201', warehouseId: null, actualInventory: 15, onHandQuantity: null,
  reservedQuantity: null, availableQuantity: null, status: 'REPORTED', toolStatus: 'SUCCESS',
  observedAt: '2026-11-20T00:00:00Z', source: 'local-stock/get_inventory', requestId: id,
}

function setup(consent = false) {
  const state = { customerId: id, selectionId: crypto.randomUUID(), personalizationConsent: consent, consentEpoch: crypto.randomUUID() }
  let current: typeof state | null = state
  const ask = vi.fn(async (): Promise<Response> => json(reply))
  const fetch = vi.fn(async (path: string, init: RequestInit) => {
    if (path.endsWith('/context')) return current ? json(current) : json({}, 403)
    if (path.endsWith('/csrf')) return current ? json({ csrfToken: id }) : failure('SESSION_REQUIRED', 403)
    if (path.endsWith('/ask')) return ask()
    throw new Error(`Unexpected request ${path} ${init.method}`)
  })
  vi.stubGlobal('fetch', fetch)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/assistant']}><App /></MemoryRouter></QueryClientProvider>)
  return { user: userEvent.setup(), ask, fetch,
    expire: () => { current = null },
    switchCustomer: () => { current = { ...state, customerId: crypto.randomUUID(), selectionId: crypto.randomUUID() } },
    withdraw: () => { current = { ...state, personalizationConsent: false, consentEpoch: crypto.randomUUID() } },
  }
}

async function send(user: ReturnType<typeof userEvent.setup>, message = 'How do I register?') {
  await user.type(await screen.findByLabelText('Your question'), message)
  await user.click(screen.getByRole('button', { name: 'Send' }))
}

test('welcomes customers, rejects blank input, and supports keyboard submission of an independent turn', async () => {
  const { user, fetch, ask } = setup()
  expect(await screen.findByText('Welcome. What can I help you with?')).toBeVisible()
  const box = screen.getByLabelText('Your question')
  await user.type(box, '   {Enter}')
  expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
  expect(ask).not.toHaveBeenCalled()
  const suggestion = screen.getByRole('button', { name: 'How do I register?' })
  suggestion.focus()
  await user.keyboard('{Enter}')
  expect(box).toHaveFocus()
  await user.keyboard('{Enter}')
  expect(await screen.findByText('Document citations')).toBeVisible()
  await send(user, 'Tell me about KB-201.')
  await waitFor(() => expect(screen.getAllByRole('region', { name: 'Assistant response' })).toHaveLength(2))
  const turns = fetch.mock.calls.filter(([path]) => path.endsWith('/ask')).map(([, init]) => init)
  expect(turns.map(({ body }) => JSON.parse(String(body)))).toEqual([
    { message: 'How do I register?' }, { message: 'Tell me about KB-201.' },
  ])
  for (const init of turns) {
    expect(init.credentials).toBe('include')
    expect(new Headers(init.headers).get('X-Retail-CSRF')).toBe(id)
  }
  expect(new Headers(fetch.mock.calls.find(([path]) => path.endsWith('/csrf'))?.[1].headers).get('X-Retail-Portal')).toBe('1')
  await user.click(screen.getByRole('button', { name: 'Clear conversation' }))
  expect(screen.queryByText('Document citations')).not.toBeInTheDocument()
  expect(box).toHaveFocus()
})

test('disables sending in flight and renders catalog, cited product details, and typed inventory provenance', async () => {
  const { user, ask } = setup(true)
  let resolve!: (value: Response) => void
  ask.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
  await send(user, 'Check KB-201')
  expect(screen.getByRole('status')).toHaveTextContent('Waiting')
  await user.type(screen.getByLabelText('Your question'), 'Next')
  expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
  await waitFor(() => expect(ask).toHaveBeenCalledOnce())
  resolve(json({ ...reply, answer: `Mechanical keyboard. ${marker}`, personalized: true,
    citations: [{ marker, channel: 'PRODUCT' }], productSkus: ['KB-201'], inventoryObservations: [observation] }))
  const result = await screen.findByRole('region', { name: 'Assistant response' })
  expect(within(result).getByText('Product catalog references')).toBeVisible()
  expect(within(result).getByText(/Product-level inventory count/)).toHaveTextContent('15')
  expect(within(result).getByText('local-stock/get_inventory')).toBeVisible()
  expect(within(result).getByText('Personalized')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled()
})

test('renders explicit warehouse limitation without a fabricated count', async () => {
  const { user, ask } = setup()
  ask.mockResolvedValueOnce(json({ ...reply, status: 'PARTIAL', productSkus: ['KB-201'], inventoryObservations: [{
    ...observation, status: 'WAREHOUSE_UNSUPPORTED', toolStatus: 'NOT_CALLED', warehouseId: 'WH-CENTRAL-01',
    actualInventory: null, observedAt: null, requestId: null, source: null,
  }] }))
  await send(user, 'Is KB-201 in the central warehouse?')
  expect(await screen.findByText(/Warehouse-specific availability is unsupported/)).toBeVisible()
  expect(screen.getByText('Partial answer')).toBeVisible()
  expect(screen.queryByText(/Product-level inventory count/)).not.toBeInTheDocument()
})

test.each([
  ['ADK_TIMEOUT', 504, 'did not finish in time'],
  ['ADK_UNAVAILABLE', 503, 'temporarily unavailable'],
  ['CSRF_REJECTED', 403, 'security check failed'],
] as const)('shows a safe %s message and retries with a fresh token', async (code, status, message) => {
  const { user, ask, fetch } = setup()
  ask.mockResolvedValueOnce(failure(code, status))
  await send(user)
  expect(await screen.findByRole('alert')).toHaveTextContent(message)
  expect(screen.queryByText('private diagnostic')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Retry question' }))
  expect(await screen.findByText('Document citations')).toBeVisible()
  expect(fetch.mock.calls.filter(([path]) => path.endsWith('/csrf'))).toHaveLength(2)
})

test('clears messages and disables chat when the session expires', async () => {
  const { user, expire } = setup()
  await send(user)
  expect(await screen.findByText('Document citations')).toBeVisible()
  expire()
  await send(user, 'Another question')
  expect(await screen.findByText(/Select a synthetic customer to start chatting/)).toBeVisible()
  expect(screen.queryByText('Document citations')).not.toBeInTheDocument()
})

test.each(['switchCustomer', 'withdraw'] as const)('drops a delayed personalized response after %s', async (change) => {
  const state = setup(true)
  let resolve!: (value: Response) => void
  state.ask.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
  await send(state.user, 'Find a product for me.')
  await waitFor(() => expect(state.ask).toHaveBeenCalledOnce())
  state[change]()
  await act(async () => { resolve(json({ ...reply, personalized: true, answer: 'Stale private reply.' })) })
  expect(await screen.findByText('Welcome. What can I help you with?')).toBeVisible()
  expect(screen.queryByText('Stale private reply.')).not.toBeInTheDocument()
  if (change === 'withdraw') expect(screen.getByText('Disabled')).toBeVisible()
})

test('ignores an outstanding response after clearing the conversation', async () => {
  const { user, ask } = setup()
  let resolve!: (value: Response) => void
  ask.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
  await send(user)
  await waitFor(() => expect(ask).toHaveBeenCalledOnce())
  await user.click(screen.getByRole('button', { name: 'Clear conversation' }))
  await act(async () => { resolve(json(reply)) })
  expect(screen.queryByText('Document citations')).not.toBeInTheDocument()
  expect(screen.getByText('Welcome. What can I help you with?')).toBeVisible()
})

