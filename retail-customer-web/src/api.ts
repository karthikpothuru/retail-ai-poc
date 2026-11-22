import { z } from 'zod'
import { chatCsrfPath, chatErrorSchema, chatMessages, chatPath, chatReplySchema, csrfSchema } from './chat-contract'

const uuid = z.uuid()
const name = z.string().trim().min(1, 'Enter a name.').max(80, 'Use 80 characters or fewer.')
const email = z.string().trim().toLowerCase().pipe(z.email('Enter a valid email address.').max(254))
export const profileForm = z.object({ givenName: name, familyName: name })
export const registrationForm = profileForm.extend({
  email: email.refine((value) => /@[^@]+\.test$/.test(value), 'Use a synthetic email ending in .test.'),
})
export const selectionForm = z.object({ customerId: z.string().trim().pipe(z.uuid('Enter a valid customer ID.')) })
export const preferencesForm = z.object({
  locale: z.string().trim().max(35).regex(/^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*$/, 'Enter a language tag such as en or en-IN.'),
  marketingOptIn: z.boolean(),
  personalizationConsent: z.boolean(),
})

const profileSchema = z.object({ customerId: uuid, givenName: z.string(), familyName: z.string(), email: z.email() })
const contextSchema = z.object({
  customerId: uuid, selectionId: uuid, personalizationConsent: z.boolean(), consentEpoch: uuid,
})
export type Session = z.infer<typeof contextSchema>
export type Registration = z.input<typeof registrationForm>
export type ProfileForm = z.input<typeof profileForm>
export type Profile = z.infer<typeof profileSchema>
export type Preferences = z.infer<typeof preferencesForm>

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string, readonly code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(
  path: string, schema: z.ZodType<T> | null,
  { method = 'GET', body, signal, csrf }: { method?: string; body?: object; signal?: AbortSignal; csrf?: string } = {},
): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (method !== 'GET' || path === chatCsrfPath) headers.set('X-Retail-Portal', '1')
  if (csrf) headers.set('X-Retail-CSRF', csrf)
  if (body) headers.set('Content-Type', 'application/json')
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      method, headers, credentials: 'include', cache: 'no-store', redirect: 'error',
      body: body ? JSON.stringify(body) : undefined, signal,
    })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new ApiError(0, 'The customer service could not be reached. Try again.')
  }
  if (!response.ok) {
    if (path === chatPath || path === chatCsrfPath) {
      const payload = chatErrorSchema.safeParse(await response.json().catch(() => null))
      const code = payload.success ? payload.data.error.code : undefined
      const message = code && Object.hasOwn(chatMessages, code) ? chatMessages[code] : undefined
      throw new ApiError(response.status, message ?? 'The retail assistant is temporarily unavailable. Try again.', code)
    }
    const messages: Record<number, string> = {
      400: 'Check the information you entered and try again.',
      403: 'The demo session is no longer available. Select a customer again.',
      404: 'The synthetic customer or demo endpoint was not found.',
      409: 'That email is already registered. Use another synthetic address.',
    }
    throw new ApiError(response.status, messages[response.status] ?? 'The customer service is temporarily unavailable. Try again.')
  }
  if (!schema) return undefined as T
  try {
    return schema.parse(await response.json())
  } catch {
    throw new ApiError(502, 'The customer service returned unexpected information. Try again.')
  }
}

function activeCustomer(session: Session) {
  return `/customers/${session.customerId}`
}

export const api = {
  chatCsrf: (signal: AbortSignal) => request(chatCsrfPath, csrfSchema, { signal }),
  ask: (message: string, csrf: string, signal: AbortSignal) =>
    request(chatPath, chatReplySchema, { method: 'POST', body: { message }, csrf, signal }),
  register: (input: Registration) => request('/customers', profileSchema, { method: 'POST', body: input }),
  select: (customerId: string) => request('/demo/session', null, { method: 'POST', body: { customerId } }),
  end: () => request('/demo/session', null, { method: 'DELETE' }),
  async session(signal?: AbortSignal) {
    try { return await request('/demo/session/context', contextSchema, { signal }) }
    catch (error) {
      if (error instanceof ApiError && error.status === 403) return null
      throw error
    }
  },
  profile: (session: Session, signal?: AbortSignal) => request(activeCustomer(session), profileSchema, { signal }),
  updateProfile: (session: Session, input: ProfileForm, signal?: AbortSignal) =>
    request(activeCustomer(session), profileSchema, { method: 'PATCH', body: input, signal }),
  preferences: (session: Session, signal?: AbortSignal) =>
    request(`${activeCustomer(session)}/preferences`, preferencesForm, { signal }),
  updatePreferences: (session: Session, input: Preferences, signal?: AbortSignal) =>
    request(`${activeCustomer(session)}/preferences`, preferencesForm, { method: 'PUT', body: input, signal }),
}

export function errorMessage(error: unknown) {
  return error instanceof ApiError ? error.message : 'Something went wrong. Try again.'
}
