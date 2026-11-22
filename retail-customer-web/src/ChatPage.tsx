import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, ApiError, errorMessage, type Session } from './api'
import { chatMessages, messageLimit, type ChatReply } from './chat-contract'
import { Notice } from './pages'
import { useSession } from './session'

const suggestions = [
  'How do I register?',
  'Tell me about KB-201.',
  'Is WM-101 available in the central warehouse?',
]
const outcomes = { OK: 'Complete', PARTIAL: 'Partial answer', INSUFFICIENT: 'Insufficient evidence' }
const inventoryStates = {
  REPORTED: 'Reported',
  WAREHOUSE_UNSUPPORTED: 'Warehouse-specific availability is unsupported.',
  UNKNOWN_WAREHOUSE: 'The requested warehouse was not recognized.',
  NOT_FOUND: 'No inventory record was found.',
  TIMEOUT: 'Inventory lookup timed out.',
  AUTHORIZATION_FAILED: 'Inventory lookup could not be authorized.',
  UNAVAILABLE: 'Inventory lookup is unavailable.',
  MALFORMED_RESPONSE: 'Inventory lookup returned unexpected information.',
  SKU_MISMATCH: 'Inventory lookup returned a different product.',
  REMOTE_ERROR: 'Inventory lookup failed.',
}

function AssistantReply({ reply }: { reply: ChatReply }) {
  return <section className="assistant-reply" aria-label="Assistant response">
    <h3>Assistant</h3>
    <p className="chat-text">{reply.answer}</p>
    <p className="chat-metadata"><strong>{outcomes[reply.status]}</strong><span>{reply.personalized ? 'Personalized' : 'General answer'}</span></p>
    {reply.citations.length > 0 && <section>
      <h4>Document citations</h4>
      <ul>{reply.citations.map(({ channel, marker }) =>
        <li key={marker}>{channel === 'REGISTRATION' ? 'Registration' : 'Product'} <code>{marker}</code></li>)}</ul>
    </section>}
    {reply.productSkus.length > 0 && <section>
      <h4>Product catalog references</h4>
      <ul>{reply.productSkus.map((sku) => <li key={sku}>{sku}</li>)}</ul>
    </section>}
    {reply.inventoryObservations.length > 0 && <section>
      <h4>MCP inventory observations</h4>
      <ul>{reply.inventoryObservations.map((item) => <li key={item.sku}>
        <strong>{item.sku}</strong> · {inventoryStates[item.status]}
        {item.warehouseId && <p>Requested warehouse {item.warehouseId}</p>}
        {item.actualInventory !== null && <p>Product-level inventory count <strong>{item.actualInventory}</strong></p>}
        {item.source && <p>Source <code>{item.source}</code></p>}
        {item.observedAt && <p>Observed <time dateTime={item.observedAt}>{item.observedAt}</time></p>}
        <p>Tool status <code>{item.toolStatus}</code></p>
        {item.requestId && <p>Request <code>{item.requestId}</code></p>}
      </li>)}</ul>
    </section>}
  </section>
}

type Turn = { message: string; reply?: ChatReply; error?: string }

function Conversation({ session }: { session: Session }) {
  const { refresh, isCurrent, handleExpired } = useSession()
  const [draft, setDraft] = useState('')
  const [turns, setTurns] = useState<Turn[]>([])
  const [pending, setPending] = useState(false)
  const input = useRef<HTMLTextAreaElement>(null)
  const active = useRef<AbortController | null>(null)
  useEffect(() => () => active.current?.abort(), [])
  const currentSelection = () => isCurrent(session.selectionId, session.consentEpoch)

  async function ask(message: string, retry = false) {
    const question = message.trim()
    if (!question || question.length > messageLimit || active.current || !currentSelection()) return
    const controller = new AbortController()
    const { signal } = controller
    active.current = controller
    setPending(true)
    setDraft('')
    if (!retry) setTurns((list) => [...list, { message: question }])
    const finish = (result: Turn) => {
      if (!signal.aborted && currentSelection()) {
        setTurns((list) => [...list.slice(0, -1), result])
      }
    }
    async function confirmBinding() {
      if (signal.aborted || !currentSelection()) return false
      const current = await api.session(signal)
      if (current?.selectionId === session.selectionId && current.consentEpoch === session.consentEpoch &&
          current.personalizationConsent === session.personalizationConsent) return !signal.aborted && currentSelection()
      await refresh()
      return false
    }

    try {
      const { csrfToken } = await api.chatCsrf(signal)
      if (!await confirmBinding()) {
        finish({ message: question, error: chatMessages.SESSION_CHANGED })
        return
      }
      const reply = await api.ask(question, csrfToken, signal)
      if (await confirmBinding() && (!reply.personalized || session.personalizationConsent)) {
        finish({ message: question, reply })
      } else {
        finish({ message: question, error: chatMessages.SESSION_CHANGED })
      }
    } catch (error) {
      if (signal.aborted || !currentSelection()) return
      if (error instanceof ApiError && error.code === 'SESSION_REQUIRED') {
        handleExpired(error, session.selectionId)
        return
      }
      if (error instanceof ApiError && (error.code === 'SESSION_CHANGED' || error.code === 'CSRF_REJECTED')) await refresh()
      finish({ message: question, error: errorMessage(error) })
    } finally {
      if (active.current === controller) {
        active.current = null
        if (!signal.aborted) setPending(false)
      }
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    void ask(draft)
  }

  function clear() {
    active.current?.abort()
    active.current = null
    setPending(false)
    setTurns([])
    setDraft('')
    input.current?.focus()
  }

  return <div className="chat">
    <div className="chat-toolbar">
      <p>Personalization consent <strong>{session.personalizationConsent ? 'Enabled' : 'Disabled'}</strong>
        {' · '}<Link className="text-link" to="/preferences">Manage preferences</Link></p>
      <button type="button" className="button secondary" onClick={clear}
        disabled={!turns.length && !draft}>Clear conversation</button>
    </div>
    {turns.length === 0 && <div className="chat-welcome">
      <p>Welcome. What can I help you with?</p>
      <div className="chat-suggestions" aria-label="Suggested questions">
        {suggestions.map((message) => <button type="button" className="button secondary" key={message}
          onClick={() => { setDraft(message); input.current?.focus() }}>{message}</button>)}
      </div>
    </div>}
    <ol className="chat-turns" aria-label="Conversation" role="log" aria-live="polite" aria-relevant="additions text">
      {turns.map((turn, index) => <li key={index}>
        <div className="chat-question"><strong>You</strong><p className="chat-text">{turn.message}</p></div>
        {turn.reply && <AssistantReply reply={turn.reply} />}
        {turn.error && !(pending && index === turns.length - 1) && <Notice kind="error">{turn.error}</Notice>}
        {turn.error && index === turns.length - 1 && !pending &&
          <button type="button" className="button secondary" onClick={() => void ask(turn.message, true)}>Retry question</button>}
      </li>)}
    </ol>
    {pending && <p className="notice" role="status">Waiting for the assistant…</p>}
    <form className="field" onSubmit={submit}>
      <label htmlFor="chat-message">Your question</label>
      <textarea id="chat-message" ref={input} rows={3} value={draft} maxLength={messageLimit}
        aria-describedby="chat-help" onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault()
            void ask(draft)
          }
        }} />
      <p id="chat-help" className="field-help">Each question is independent. Include the details it needs. Shift+Enter adds a line.</p>
      <button className="button primary" type="submit" disabled={pending || !draft.trim()}>Send</button>
    </form>
  </div>
}

export function ChatPage() {
  const { session, busy, loading, error, refresh } = useSession()
  return <>
    <div className="panel-heading"><h2>Chat with the assistant</h2></div>
    {busy || loading ? <Notice>Checking your demo session…</Notice>
      : error ? <><Notice kind="error">{errorMessage(error)}</Notice>
        <button type="button" className="button secondary" onClick={() => void refresh()}>Try again</button></>
      : session ? <Conversation key={session.selectionId + session.consentEpoch} session={session} />
      : <Notice>Select a synthetic customer to start chatting. <Link className="text-link" to="/select">Select customer</Link></Notice>}
  </>
}

