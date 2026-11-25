import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { useDashboardData } from '../../../src/MemoDashboard.Web/Frontend/src/hooks/useDashboardData'
import { DashboardLive } from '../../../src/MemoDashboard.Web/Frontend/src/dashboard/DashboardLive'
import { LanguageProvider } from '../../../src/MemoDashboard.Web/Frontend/src/i18n/LanguageProvider'
import { ThemeProvider } from '../../../src/MemoDashboard.Web/Frontend/src/theme/ThemeProvider'
import { formatDateTime } from '../../../src/MemoDashboard.Web/Frontend/src/services/format'
import type { DashboardFilters, DashboardSnapshot } from '../../../src/MemoDashboard.Web/Frontend/src/types/dashboard'

const fixtureDir=process.env.MEMO_DASHBOARD_EXPORT_DIR
if (!fixtureDir) throw new Error('Missing Java HTTP exported JSON fixture directory')
const load=(name: string): DashboardSnapshot =>
  JSON.parse(readFileSync(join(fixtureDir,name+'.json'),'utf8')) as DashboardSnapshot
const globalSnapshot=load('global')
const scopedSnapshot=load('scoped')
const emptySnapshot=load('empty')
const metricsSnapshot=load('metrics')
const COMPANY='e1000000-0000-4000-8000-000000000001'
const filters: DashboardFilters = {
  from:'',to:'',dateBasis:'ArrivalDate',companyId:'',factoryId:'',
  productionLineId:'',noteTypeId:'',status:'',entryCutoffDate:''
}
const blankLookups={companies:[],factories:[],productionLines:[],noteTypes:[]}
let active: DashboardSnapshot=globalSnapshot
let responseStatus=200
const requests: string[]=[]
const fetchMock=vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url=String(input)
  requests.push(url)
  if(url.startsWith('/api/dashboard/snapshot')) {
    if(responseStatus!==200) return new Response(
      JSON.stringify({message:'Not allowed',code:'access_denied'}),
      {status:responseStatus,headers:{'Content-Type':'application/json','X-Correlation-ID':'phase-56'}})
    return new Response(JSON.stringify(active),{status:200,headers:{'Content-Type':'application/json'}})
  }
  if(url==='/api/dashboard/lookups')
    return new Response(JSON.stringify(blankLookups),{status:200})
  if(url==='/health')
    return new Response(JSON.stringify({status:'ok'}),{status:200})
  throw new Error('Unexpected UI request '+url+' '+String(init?.method))
})

beforeEach(() => {
  vi.stubGlobal('fetch',fetchMock)
  active=globalSnapshot
  responseStatus=200
  requests.length=0
  fetchMock.mockClear()
  localStorage.clear()
  window.history.replaceState({},'', '/?view=executive')
})
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('actual original React consumer against Spring HTTP snapshot JSON', () => {
  it('uses the unchanged public URL and accepts all 4 Spring preview scenarios', async () => {
    for (const fixture of [globalSnapshot,scopedSnapshot,emptySnapshot,metricsSnapshot]) {
      active=fixture
      const {result,unmount}=renderHook(() => useDashboardData(filters))
      await waitFor(() => expect(result.current.snapshotStatus).toBe('success'))
      expect(result.current.data?.isHealthy).toBe(true)
      expect(result.current.data?.views?.executiveSummary?.availability?.hasRows)
        .toBe(fixture.summary.totalNotes>0)
      expect(result.current.isStale).toBe(false)
      unmount()
    }
    expect(requests.filter(url=>url.startsWith('/api/dashboard/snapshot')).length).toBe(4)
    expect(requests.some(url=>url.startsWith('/api/internal/'))).toBe(false)
  })

  it('renders original dashboard headings and actual chart components from Java JSON', async () => {
    active=globalSnapshot
    render(<ThemeProvider><LanguageProvider>
      <DashboardLive config={null} />
    </LanguageProvider></ThemeProvider>)
    expect(await screen.findByRole('heading',{name:'المدير العام'})).toBeInTheDocument()
    await waitFor(() => expect(
      document.querySelector('[data-chart-kind]')
    ).not.toBeNull())
    expect(requests.some(url=>url.startsWith('/api/dashboard/snapshot'))).toBe(true)
  })

  it('preserves original no-data and HTTP 403 states instead of fabricating zero KPIs', async () => {
    active=emptySnapshot
    const {result,unmount}=renderHook(() => useDashboardData(filters))
    await waitFor(() => expect(result.current.snapshotStatus).toBe('success'))
    expect(result.current.data?.views.executiveSummary.availability.hasRows).toBe(false)
    unmount()

    responseStatus=403
    const next=renderHook(() => useDashboardData(filters))
    await waitFor(() => expect(next.result.current.snapshotStatus).toBe('error'))
    expect(next.result.current.snapshotError?.status).toBe(403)
    expect(next.result.current.snapshotError?.correlationId).toBe('phase-56')
    expect(next.result.current.data).toBeNull()
    next.unmount()
  })

  it('keeps the full arrival clock time and does not display midnight as an invented time', () => {
    const arrival=globalSnapshot.recentNotes?.[0]?.arrivalAt
    if (arrival) {
      const display=formatDateTime(arrival)
      expect(display).not.toBe('—')
      if (arrival.includes('T00:00:00')) expect(display).not.toMatch(/00:00/)
    }
    expect(formatDateTime('2026-11-25T09:45:00')).not.toBe(
      formatDateTime('2026-11-25T00:00:00'))
  })
})
