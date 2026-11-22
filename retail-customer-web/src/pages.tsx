import { useQuery, useQueryClient } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useForm, type UseFormRegisterReturn } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import {
  api, ApiError, errorMessage, preferencesForm, profileForm, registrationForm, selectionForm,
  type Preferences, type Profile, type ProfileForm, type Registration, type Session,
} from './api'
import { customerKey, useSession } from './session'

export function Notice({ children, kind = 'info' }: { children: ReactNode; kind?: 'info' | 'error' | 'success' }) {
  return <div className={`notice notice--${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{children}</div>
}

function TextField({ label, type = 'text', placeholder, help, field, error }: {
  label: string; type?: string; placeholder?: string; help?: string; field: UseFormRegisterReturn; error?: string
}) {
  const id = field.name
  return <div className="field">
    <label htmlFor={id}>{label}</label>
    <input id={id} type={type} placeholder={placeholder} autoComplete="off"
      aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-error` : help ? `${id}-help` : undefined}
      {...field} />
    {error ? <span className="field-error" id={`${id}-error`} role="alert">{error}</span>
      : help && <span className="field-help" id={`${id}-help`}>{help}</span>}
  </div>
}

function PanelHeading({ title, description }: { title: string; description: string }) {
  return <div className="panel-heading"><h2>{title}</h2><p>{description}</p></div>
}

export function RegisterPage() {
  const { setCandidate } = useSession()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)
  const form = useForm<Registration>({
    resolver: zodResolver(registrationForm), defaultValues: { givenName: '', familyName: '', email: '' },
  })
  const { errors, isSubmitting } = form.formState
  return <>
    <PanelHeading title="Create a customer" description="Register a synthetic account to try the customer portal." />
    <form className="portal-form" noValidate onSubmit={form.handleSubmit(async (data) => {
      setError(null)
      try {
        const result = await api.register(data)
        setCandidate(result.customerId)
        navigate('/select')
      } catch (error) {
        if (error instanceof ApiError && error.status === 409) {
          form.setError('email', { message: error.message }, { shouldFocus: true })
        } else setError(error)
      }
    })}>
      <fieldset disabled={isSubmitting}>
        <div className="name-row">
          <TextField label="Given name" placeholder="Asha" field={form.register('givenName')} error={errors.givenName?.message} />
          <TextField label="Family name" placeholder="Rao" field={form.register('familyName')} error={errors.familyName?.message} />
        </div>
        <TextField label="Email address" type="email" placeholder="asha.rao@example.test"
          help="Use an address ending in .test." field={form.register('email')} error={errors.email?.message} />
      </fieldset>
      {error != null && <Notice kind="error">{errorMessage(error)}</Notice>}
      <div className="form-actions">
        <button className="button primary" disabled={isSubmitting}>{isSubmitting ? 'Registering…' : 'Register customer'}</button>
        <Link className="text-link" to="/select">Select an existing customer</Link>
      </div>
    </form>
  </>
}

export function SelectPage() {
  const { candidate, busy, session, select } = useSession()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)
  const form = useForm<{ customerId: string }>({ resolver: zodResolver(selectionForm), defaultValues: { customerId: candidate } })
  useEffect(() => { form.reset({ customerId: candidate }) }, [candidate, form])
  return <>
    <PanelHeading title={session ? 'Switch customer' : 'Select a customer'}
      description="Enter the customer ID from registration to start a local demo session." />
    {candidate && <Notice kind="success">Customer registered. Keep the ID below if you want to select this account again.</Notice>}
    <form className="portal-form" noValidate onSubmit={form.handleSubmit(async ({ customerId }) => {
      setError(null)
      try { await select(customerId); navigate('/profile') }
      catch (error) { setError(error) }
    })}>
      <fieldset disabled={busy || form.formState.isSubmitting}>
        <TextField label="Customer ID" placeholder="00000000-0000-0000-0000-000000000000"
          field={form.register('customerId')} error={form.formState.errors.customerId?.message} />
      </fieldset>
      {error != null && <Notice kind="error">{errorMessage(error)}</Notice>}
      <div className="form-actions">
        <button className="button primary" disabled={busy || form.formState.isSubmitting}>{busy ? 'Selecting…' : 'Select customer'}</button>
        <Link className="text-link" to="/register">Register a customer</Link>
      </div>
    </form>
  </>
}

function Protected({ children }: { children: (session: Session) => ReactNode }) {
  const { session, busy, loading, error, refresh } = useSession()
  if (loading || busy) return <Notice>Checking demo session…</Notice>
  if (error != null) return <>
    <Notice kind="error">{errorMessage(error)}</Notice>
    <button className="button secondary" onClick={() => void refresh()}>Retry session check</button>
  </>
  if (!session) return <>
    <PanelHeading title="Select a customer first" description="Open a demo session to manage a profile and its preferences." />
    <Link className="button primary" to="/select">Select customer</Link>
  </>
  return children(session)
}

function usePrivateSave<T>(session: Session, write: (value: T, signal: AbortSignal) => Promise<void>) {
  const { isCurrent, handleExpired } = useSession()
  const controller = useRef<AbortController | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [saved, setSaved] = useState(false)
  useEffect(() => () => { controller.current?.abort() }, [])

  async function save(value: T) {
    if (!isCurrent(session.selectionId)) return
    const request = new AbortController()
    controller.current?.abort()
    controller.current = request
    setSaved(false)
    setError(null)
    try {
      await write(value, request.signal)
      if (!request.signal.aborted && isCurrent(session.selectionId)) setSaved(true)
    } catch (e) {
      if (!request.signal.aborted) {
        handleExpired(e, session.selectionId)
        setError(e)
      }
    }
  }
  return { save, error, saved }
}

function PrivateFeedback({ error, saved, label }: { error: unknown; saved: boolean; label: string }) {
  if (error != null) return <Notice kind="error">{errorMessage(error)}</Notice>
  return saved ? <Notice kind="success">{label}</Notice> : null
}

export function ProfilePage() {
  return <Protected>{(session) => <ProfileQuery session={session} key={session.selectionId} />}</Protected>
}

function ProfileQuery({ session }: { session: Session }) {
  const { handleExpired } = useSession()
  const query = useQuery({
    queryKey: customerKey(session, 'profile'), queryFn: ({ signal }) => api.profile(session, signal), retry: false,
  })
  useEffect(() => { handleExpired(query.error, session.selectionId) }, [query.error, session.selectionId, handleExpired])
  if (query.isPending) return <Notice>Loading customer profile…</Notice>
  if (query.isError) return <Retry error={query.error} retry={() => void query.refetch()} />
  return <ProfileEditor session={session} profile={query.data} />
}

function ProfileEditor({ session, profile }: { session: Session; profile: Profile }) {
  const client = useQueryClient()
  const { isCurrent } = useSession()
  const form = useForm<ProfileForm>({
    resolver: zodResolver(profileForm), defaultValues: { givenName: profile.givenName, familyName: profile.familyName },
  })
  const { save, error, saved } = usePrivateSave(session, async (value: ProfileForm, signal) => {
    const updated = await api.updateProfile(session, value, signal)
    if (signal.aborted || !isCurrent(session.selectionId)) return
    client.setQueryData(customerKey(session, 'profile'), updated)
    form.reset({ givenName: updated.givenName, familyName: updated.familyName })
  })
  const { errors, isSubmitting, isDirty } = form.formState
  return <>
    <PanelHeading title="Customer profile" description="Review the details for the customer in this demo session." />
    <div className="readout"><span>Email address</span><strong>{profile.email}</strong></div>
    <form className="portal-form" noValidate onSubmit={form.handleSubmit(save)}>
      <fieldset disabled={isSubmitting}>
        <div className="name-row">
          <TextField label="Given name" field={form.register('givenName')} error={errors.givenName?.message} />
          <TextField label="Family name" field={form.register('familyName')} error={errors.familyName?.message} />
        </div>
      </fieldset>
      <PrivateFeedback error={error} saved={saved} label="Profile updated." />
      <div className="form-actions"><button className="button primary" disabled={!isDirty || isSubmitting}>
        {isSubmitting ? 'Saving…' : 'Save profile'}</button><Link className="text-link" to="/preferences">Manage preferences</Link></div>
    </form>
  </>
}

export function PreferencesPage() {
  return <Protected>{(session) => <PreferencesQuery session={session} key={session.selectionId} />}</Protected>
}

function PreferencesQuery({ session }: { session: Session }) {
  const { handleExpired } = useSession()
  const query = useQuery({
    queryKey: customerKey(session, 'preferences'), queryFn: ({ signal }) => api.preferences(session, signal), retry: false,
  })
  useEffect(() => { handleExpired(query.error, session.selectionId) }, [query.error, session.selectionId, handleExpired])
  if (query.isPending) return <Notice>Loading customer preferences…</Notice>
  if (query.isError) return <Retry error={query.error} retry={() => void query.refetch()} />
  return <PreferencesEditor session={session} preferences={query.data} />
}

function PreferencesEditor({ session, preferences }: { session: Session; preferences: Preferences }) {
  const client = useQueryClient()
  const { isCurrent, refresh } = useSession()
  const form = useForm<Preferences>({ resolver: zodResolver(preferencesForm), defaultValues: preferences })
  const { save, error, saved } = usePrivateSave(session, async (value: Preferences, signal) => {
    const updated = await api.updatePreferences(session, value, signal)
    if (signal.aborted || !isCurrent(session.selectionId)) return
    client.setQueryData(customerKey(session, 'preferences'), updated)
    form.reset(updated)
    await refresh()
  })
  const { errors, isSubmitting, isDirty } = form.formState
  // Show authoritative changes from another tab without overwriting edits in progress.
  useEffect(() => { if (!isDirty) form.reset(preferences) }, [form, isDirty, preferences])
  return <>
    <PanelHeading title="Customer preferences" description="Set your language, marketing choices, and personalization consent." />
    <form className="portal-form" noValidate onSubmit={form.handleSubmit(save)}>
      <fieldset disabled={isSubmitting}>
        <TextField label="Language tag" placeholder="en" help="Examples include en and en-IN."
          field={form.register('locale')} error={errors.locale?.message} />
        <Toggle title="Marketing opt-in" description="Allow promotional messages in the demo."
          field={form.register('marketingOptIn')} />
        <Toggle title="Personalization consent" description="Allow the demo to use saved preferences. Turn it off and save to withdraw consent."
          field={form.register('personalizationConsent')} state={preferences.personalizationConsent ? 'Enabled' : 'Disabled'} />
      </fieldset>
      <PrivateFeedback error={error} saved={saved} label="Preferences saved." />
      <button className="button primary" disabled={!isDirty || isSubmitting}>{isSubmitting ? 'Saving…' : 'Save preferences'}</button>
    </form>
  </>
}

function Toggle({ title, description, field, state }: {
  title: string; description: string; field: UseFormRegisterReturn; state?: string
}) {
  return <div className="toggle">
    <div><label htmlFor={field.name}>{title}</label>
      {state && <span className={`consent-state ${state === 'Enabled' ? 'is-on' : ''}`} data-testid="consent-state">{state}</span>}
      <p id={`${field.name}-hint`}>{description}</p></div>
    <input className="switch" type="checkbox" role="switch" id={field.name} aria-describedby={`${field.name}-hint`} {...field} />
  </div>
}

function Retry({ error, retry }: { error: unknown; retry: () => void }) {
  return <><Notice kind="error">{errorMessage(error)}</Notice><button className="button secondary" onClick={retry}>Try again</button></>
}
