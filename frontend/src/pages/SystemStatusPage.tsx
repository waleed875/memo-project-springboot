import { useCallback, useEffect, useState } from 'react'
import { api } from '../services/api'

type AdminStatus = {
  backend: 'UP'
  database: 'CONNECTED' | 'UNAVAILABLE'
  databaseVersion: string | null
  expectedDatabaseVersion: string
  migrations: 'CURRENT' | 'REQUIRES_REVIEW' | 'UNKNOWN'
}

export function SystemStatusPage({ onClose }: { onClose: () => void }) {
  const [backend, setBackend] = useState<'UP' | 'DOWN' | 'CHECKING'>('CHECKING')
  const [details, setDetails] = useState<AdminStatus | null>(null)
  const [detailState, setDetailState] = useState<'CHECKING' | 'LOGIN_REQUIRED' | 'FORBIDDEN' | 'UNAVAILABLE' | 'READY'>('CHECKING')

  const refresh = useCallback(async () => {
    setBackend('CHECKING')
    setDetails(null)
    setDetailState('CHECKING')
    try {
      const response = await fetch('/api/system/health', { credentials: 'same-origin', cache: 'no-store' })
      const data = response.ok ? await response.json() as { backend?: string } : null
      setBackend(data?.backend === 'UP' ? 'UP' : 'DOWN')
    } catch {
      setBackend('DOWN')
    }

    try {
      const result = await fetch('/api/admin/system-status', { credentials: 'same-origin', cache: 'no-store' })
      if (result.status === 401) {
        setDetailState('LOGIN_REQUIRED')
      } else if (result.status === 403) {
        setDetailState('FORBIDDEN')
      } else if (!result.ok) {
        setDetailState('UNAVAILABLE')
      } else {
        const resultBody = await result.json() as AdminStatus
        if (resultBody.backend !== 'UP' || !['CONNECTED', 'UNAVAILABLE'].includes(resultBody.database)) {
          setDetailState('UNAVAILABLE')
        } else {
          setDetails(resultBody)
          setDetailState('READY')
        }
      }
    } catch {
      setDetailState('UNAVAILABLE')
    }
  }, [])

  useEffect(() => { void refresh() }, [refresh])

  const field = (title: string, value: string) => (
    <div className="rounded-xl border app-border app-surface-muted p-4">
      <dt className="text-xs app-text-muted">{title}</dt>
      <dd className="mt-2 text-base font-semibold app-text">{value}</dd>
    </div>
  )

  return (
    <main className="app-bg min-h-screen px-4 py-10" dir="rtl">
      <section className="app-surface mx-auto max-w-3xl rounded-2xl border app-border p-6 shadow-lg">
        <h1 className="text-2xl font-bold app-text">حالة النظام</h1>
        <p className="mt-2 text-sm app-text-muted">يُدار اتصال PostgreSQL من خادم Spring Boot وDocker، وليس من المتصفح. لا تُعرض بيانات الاتصال السرية هنا.</p>
        <dl className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-2">
          {field('الخادم الخلفي', backend === 'UP' ? 'متصل' : backend === 'DOWN' ? 'غير متاح' : 'جارٍ الفحص')}
          {field('قاعدة البيانات', detailState === 'READY' ? (details?.database === 'CONNECTED' ? 'متصلة' : 'غير متاحة') : 'يتطلب صلاحية المدير')}
          {field('إصدار قاعدة البيانات', detailState === 'READY' ? (details?.databaseVersion ?? 'غير معروف') : '—')}
          {field('إصدار المخطط المتوقع', detailState === 'READY' ? (details?.expectedDatabaseVersion ?? '—') : '—')}
          {field('الترحيلات', detailState === 'READY' ? (details?.migrations === 'CURRENT' ? 'مكتملة' : details?.migrations === 'REQUIRES_REVIEW' ? 'تحتاج مراجعة' : 'غير معروفة') : '—')}
        </dl>
        {detailState === 'LOGIN_REQUIRED' && <p className="mt-5 text-sm app-text-muted">سجّل الدخول بصلاحية مدير النظام لعرض حالة قاعدة البيانات والترحيلات.</p>}
        {detailState === 'FORBIDDEN' && <p className="mt-5 text-sm app-text-muted">هذه المعلومات متاحة لمدير النظام فقط.</p>}
        {detailState === 'UNAVAILABLE' && <p className="mt-5 text-sm app-text-muted">تعذر قراءة حالة قاعدة البيانات حاليًا. تحقق من تشغيل Spring Boot وPostgreSQL.</p>}
        <div className="mt-7 flex flex-wrap gap-3">
          <button type="button" onClick={() => void refresh()} className="btn-primary rounded-lg px-4 py-2 text-sm font-semibold">تحديث الحالة</button>
          <button type="button" onClick={onClose} className="rounded-lg border app-border px-4 py-2 text-sm font-semibold app-text">العودة</button>
        </div>
      </section>
    </main>
  )
}
