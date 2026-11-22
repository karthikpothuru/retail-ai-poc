import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'

beforeEach(() => { vi.stubGlobal('BroadcastChannel', undefined) })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
