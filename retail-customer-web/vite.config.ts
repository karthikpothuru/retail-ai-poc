import { defineConfig } from 'vitest/config'
import type { Plugin, ProxyOptions, ServerOptions } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import type { IncomingMessage, ServerResponse } from 'node:http'

function port(value: string | undefined, fallback: number) {
  const selected = value === undefined ? fallback : Number(value)
  if (!Number.isInteger(selected) || selected < 1024 || selected > 65535) {
    throw new Error('Invalid loopback development port')
  }
  return selected
}

const host = '127.0.0.1'
const portalPort = port(process.env.RETAIL_PORTAL_PORT, 5173)
const apiPort = port(process.env.RETAIL_CUSTOMER_API_PORT, 8081)
const authority = `${host}:${portalPort}`
const origin = `http://${authority}`
const customerPath = /^\/api\/v1\/customers\/[0-9a-f-]{36}(\/preferences)?$/
const chatAsk = '/api/v1/chat/ask'
const chatCsrf = '/api/v1/chat/csrf'

function allowedMethod(path: string, method: string) {
  if (path === '/api/v1/customers') return method === 'POST'
  if (path === '/api/v1/demo/session') return method === 'POST' || method === 'DELETE'
  if (path === '/api/v1/demo/session/context') return method === 'GET'
  if (path === chatCsrf) return method === 'GET'
  if (path === chatAsk) return method === 'POST'
  if (customerPath.test(path)) return method === 'GET' || method === (path.endsWith('/preferences') ? 'PUT' : 'PATCH')
  return false
}

function guard(req: IncomingMessage, res: ServerResponse, next: () => void) {
  const path = req.url ?? ''
  if (!/^\/api(?:\/|$)/.test(path)) return next()
  const method = req.method ?? ''
  const requestOrigin = req.headers.origin
  const sameOrigin = req.headers.host === authority &&
    (!requestOrigin || requestOrigin === origin) &&
    (req.headers['sec-fetch-site'] === undefined || req.headers['sec-fetch-site'] === 'same-origin')
  const write = method !== 'GET'
  const protectedWrite = requestOrigin === origin && req.headers['x-retail-portal'] === '1' &&
    (method === 'DELETE' || /^application\/json(?:;|$)/i.test(req.headers['content-type'] ?? ''))
  const chatHeaders = (path !== chatCsrf || req.headers['x-retail-portal'] === '1') &&
    (path !== chatAsk || typeof req.headers['x-retail-csrf'] === 'string')
  if (!sameOrigin || !allowedMethod(path, method) || (write && !protectedWrite) || !chatHeaders) {
    res.writeHead(403, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    res.end(JSON.stringify({ message: 'Request blocked by the local portal proxy' }))
    return
  }
  next()
}

const localApi: Plugin = {
  name: 'retail-loopback-api',
  configureServer(server) { server.middlewares.use(guard) },
  configurePreviewServer(server) { server.middlewares.use(guard) },
}

function springProxy(timeout: number): ProxyOptions {
  return {
    target: `http://${host}:${apiPort}`,
    changeOrigin: false,
    timeout,
    proxyTimeout: timeout,
    configure(proxy) {
      proxy.on('proxyRes', (response) => { response.headers['cache-control'] = 'no-store' })
    },
  }
}

const server: ServerOptions = {
  host,
  port: portalPort,
  strictPort: true,
  cors: false,
  proxy: {
    [chatAsk]: springProxy(60_000),
    '/api/': springProxy(5000),
  },
}

export default defineConfig({
  plugins: [localApi, react(), tailwindcss()],
  server,
  preview: server,
  test: { environment: 'jsdom', include: ['src/**/*.test.{ts,tsx}'], setupFiles: ['src/test/setup.ts'] },
})
