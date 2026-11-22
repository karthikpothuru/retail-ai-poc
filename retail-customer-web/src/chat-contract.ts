import { z } from 'zod'

export const chatPath = '/chat/ask'
export const chatCsrfPath = '/chat/csrf'
export const messageLimit = 1200
export const csrfSchema = z.object({ csrfToken: z.uuid() })
export const chatErrorSchema = z.object({
  status: z.literal('ERROR'),
  error: z.object({ code: z.string() }),
})

const sku = z.string().regex(/^[A-Z]{2,8}-[0-9]{2,8}$/)
const observation = z.object({
  sku,
  warehouseId: z.string().nullable(),
  actualInventory: z.number().int().nonnegative().nullable(),
  onHandQuantity: z.null(),
  reservedQuantity: z.null(),
  availableQuantity: z.null(),
  status: z.enum(['REPORTED', 'WAREHOUSE_UNSUPPORTED', 'UNKNOWN_WAREHOUSE', 'NOT_FOUND', 'TIMEOUT',
    'AUTHORIZATION_FAILED', 'UNAVAILABLE', 'MALFORMED_RESPONSE', 'SKU_MISMATCH', 'REMOTE_ERROR']),
  toolStatus: z.enum(['SUCCESS', 'REMOTE_ERROR', 'TIMEOUT_UNKNOWN', 'AUTHORIZATION_FAILED', 'CONNECTION_FAILURE',
    'GATEWAY_FAILURE', 'NOT_CALLED', 'DENIED', 'CANCELLED_UNKNOWN', 'MALFORMED_RESPONSE']),
  observedAt: z.iso.datetime().nullable(),
  source: z.literal('local-stock/get_inventory').nullable(),
  requestId: z.uuid().nullable(),
}).refine((item) => item.status === 'REPORTED'
  ? item.actualInventory !== null && item.warehouseId === null && item.toolStatus === 'SUCCESS'
  : item.actualInventory === null)

export const chatReplySchema = z.object({
  answer: z.string().min(1).max(16000),
  citations: z.array(z.object({
    marker: z.string().regex(/^\[DOC:[a-f0-9-]{36}:v[1-9][0-9]*:[a-f0-9-]{36}\]$/),
    channel: z.enum(['REGISTRATION', 'PRODUCT']),
  })).max(20),
  inventoryObservations: z.array(observation).max(3),
  productSkus: z.array(sku).max(3),
  personalized: z.boolean(),
  status: z.enum(['OK', 'PARTIAL', 'INSUFFICIENT']),
  error: z.null(),
})
export type ChatReply = z.infer<typeof chatReplySchema>

export const chatMessages: Record<string, string> = {
  INVALID_REQUEST: 'Enter a question of up to 1,200 characters.',
  SESSION_REQUIRED: 'Select a synthetic customer to ask a question.',
  SESSION_CHANGED: 'Your session or consent changed. Ask again with the current selection.',
  CSRF_REJECTED: 'The security check failed. Try again to get a fresh security token.',
  ADK_UNAVAILABLE: 'The retail assistant is temporarily unavailable. Try again.',
  ADK_TIMEOUT: 'The retail assistant did not finish in time. Try again.',
  INVALID_ADK_RESPONSE: 'The retail assistant returned unexpected information. Try again.',
}

