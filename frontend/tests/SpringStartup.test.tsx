import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import App from '../src/App'

describe('Spring Boot startup and system status instead of browser database setup', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_MEMO_BACKEND', 'spring')
    window.history.replaceState(null, '', '/')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    window.history.replaceState(null, '', '/')
  })

  it('starts at login without calling legacy setup endpoints', async () => {
    const fetchMock = vi.fn(async (input: string) => {
      if (input === '/api/admin/session') return new Response(JSON.stringify({ authenticated: false }), { status: 200 })
      throw new Error('Unexpected endpoint: ' + input)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)

    expect(await screen.findByText('نظام تدقيق المذكرات والرقابة الصناعية')).toBeInTheDocument()
    expect(screen.queryByText('ضبط إعدادات الاتصال بقاعدة البيانات')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/session', expect.anything())
    expect(fetchMock.mock.calls.some(([url]) => url.startsWith('/api/setup/'))).toBe(false)
  })

  it('offers a safe status view before login without exposing credentials', async () => {
    const fetchMock = vi.fn(async (input: string) => {
      if (input === '/api/admin/session') return new Response(JSON.stringify({ authenticated: false }), { status: 200 })
      if (input === '/api/system/health') return new Response(JSON.stringify({ backend: 'UP' }), { status: 200 })
      if (input === '/api/admin/system-status') return new Response(null, { status: 401 })
      throw new Error('Unexpected endpoint: ' + input)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /حالة النظام/ }))
    expect(await screen.findByText('سجّل الدخول بصلاحية مدير النظام لعرض حالة قاعدة البيانات والترحيلات.')).toBeInTheDocument()
    expect(screen.getByText('متصل')).toBeInTheDocument()
    expect(screen.queryByText('ضبط الاتصال بقاعدة البيانات')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => url.startsWith('/api/setup/'))).toBe(false)
  })

  it('renders a status view directly when opened by an administrator link', async () => {
    window.history.replaceState(null, '', '/?systemStatus=1')
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      if (input === '/api/system/health') return new Response(JSON.stringify({ backend: 'UP' }), { status: 200 })
      if (input === '/api/admin/system-status') return new Response(JSON.stringify({
        backend: 'UP', database: 'CONNECTED', databaseVersion: '070',
        expectedDatabaseVersion: '070', migrations: 'CURRENT',
      }), { status: 200 })
      throw new Error('Unexpected endpoint: ' + input)
    }))

    render(<App />)
    expect(await screen.findByText('مكتملة')).toBeInTheDocument()
    expect(screen.getAllByText('070')).toHaveLength(2)
    expect(screen.getByText('متصلة')).toBeInTheDocument()
  })
})
