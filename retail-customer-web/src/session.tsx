import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { api, ApiError, type Session } from './api'

const sessionKey = ['demo-session'] as const
const privatePrefix = ['customer'] as const
export const customerKey = (session: Session, section: 'profile' | 'preferences') =>
  [...privatePrefix, session.selectionId, section] as const

type SessionState = {
  session: Session | null
  loading: boolean
  busy: boolean
  error: unknown
  candidate: string
  setCandidate: (value: string) => void
  select: (customerId: string) => Promise<Session>
  end: () => Promise<void>
  refresh: () => Promise<void>
  handleExpired: (error: unknown, selectionId: string) => void
  isCurrent: (selectionId: string, consentEpoch?: string) => boolean
}
const SessionContext = createContext<SessionState | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [candidate, setCandidate] = useState('')
  const changing = useRef(false)
  const channel = useRef<BroadcastChannel | null>(null)
  const sessionQuery = useQuery({
    queryKey: sessionKey, queryFn: ({ signal }) => api.session(signal),
    enabled: !busy, retry: false, refetchOnWindowFocus: 'always', refetchInterval: 15_000,
  })

  const selectionId = sessionQuery.isError ? undefined : sessionQuery.data?.selectionId
  useEffect(() => {
    queryClient.removeQueries({
      predicate: (query) => query.queryKey[0] === privatePrefix[0] && query.queryKey[1] !== selectionId,
    })
  }, [queryClient, selectionId])

  useEffect(() => {
    if (typeof BroadcastChannel !== 'function') return
    const updates = new BroadcastChannel('retail-demo-session')
    updates.onmessage = () => {
      setCandidate('')
      if (changing.current) return
      void queryClient.cancelQueries({ queryKey: sessionKey })
      queryClient.setQueryData(sessionKey, null)
      queryClient.removeQueries({ queryKey: privatePrefix })
      void queryClient.invalidateQueries({ queryKey: sessionKey })
    }
    channel.current = updates
    return () => { updates.close(); channel.current = null }
  }, [queryClient])

  async function transition(write: () => Promise<unknown>) {
    if (changing.current) throw new ApiError(409, 'A session change is already in progress.')
    changing.current = true
    setBusy(true)
    setCandidate('')
    try {
      await queryClient.cancelQueries({ queryKey: sessionKey })
      queryClient.setQueryData(sessionKey, null)
      queryClient.removeQueries({ queryKey: privatePrefix })
      await write()
      return await queryClient.fetchQuery({ queryKey: sessionKey, queryFn: ({ signal }) => api.session(signal), staleTime: 0 })
    } catch (error) {
      // The request may have reached Spring. Reconcile exclusively against server context.
      await queryClient.fetchQuery({ queryKey: sessionKey, queryFn: ({ signal }) => api.session(signal), staleTime: 0 })
        .catch(() => undefined)
      throw error
    } finally {
      changing.current = false
      setBusy(false)
      channel.current?.postMessage('session-changed')
    }
  }

  function isCurrent(id: string, epoch?: string) {
    const state = queryClient.getQueryState<Session | null>(sessionKey)
    return !changing.current && state?.status === 'success' && state.data?.selectionId === id &&
      (epoch === undefined || state.data.consentEpoch === epoch)
  }

  const value: SessionState = {
    session: busy || sessionQuery.isError ? null : (sessionQuery.data ?? null),
    loading: sessionQuery.isPending,
    busy,
    error: sessionQuery.error,
    candidate,
    setCandidate,
    async select(id) {
      const session = await transition(() => api.select(id))
      if (!session || session.customerId !== id.toLowerCase()) throw new ApiError(403, 'The selected session changed. Try again.')
      return session
    },
    async end() {
      const session = await transition(api.end)
      if (session) throw new ApiError(409, 'Another tab selected a customer. End that session to continue.')
    },
    async refresh() { await queryClient.invalidateQueries({ queryKey: sessionKey }) },
    handleExpired(error, id) {
      if (error instanceof ApiError && error.status === 403 && isCurrent(id)) {
        queryClient.setQueryData(sessionKey, null)
        queryClient.removeQueries({ queryKey: privatePrefix })
        void queryClient.invalidateQueries({ queryKey: sessionKey })
      }
    },
    isCurrent,
  }

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession() {
  const session = useContext(SessionContext)
  if (!session) throw new Error('SessionProvider is required')
  return session
}
