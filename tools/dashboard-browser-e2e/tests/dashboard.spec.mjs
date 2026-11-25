import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';

const dir = process.env.MEMO_DASHBOARD_EXPORT_DIR;
if (!dir) throw new Error('Phase 57 requires the four original Spring MockMvc JSON fixture files');
const snapshot = Object.fromEntries(
  ['global','scoped','empty','metrics'].map(name =>
    [name, JSON.parse(readFileSync(join(dir,name+'.json'),'utf8'))]));
for (const [name, value] of Object.entries(snapshot)) {
  if (!value.isHealthy || !value.views?.executiveSummary || !value.effectiveFilters)
    throw new Error('Invalid exported Spring JSON: '+name);
}
const company = snapshot.metrics.effectiveFilters.companyId;
if (!company) throw new Error('The company browser scenario must be company-scoped');
const companyNames = [...new Map(
  [...snapshot.global.companies, ...snapshot.metrics.companies]
    .map(row => [row.id,{id:row.id,name:row.name}])
).values()];
if (!companyNames.some(row => row.id===company))
  throw new Error('No company lookup for scoped dashboard');

const lookups = {companies:companyNames,factories:[],productionLines:[],noteTypes:[]};
const PAGE='/dashboard-browser-parity.html';

async function harness(page, {deny=null, empty=false} = {}) {
  const calls=[];
  let denial=deny;
  await page.route('**/*', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname==='/api/dashboard/snapshot') {
      calls.push({method:request.method(),url:url.toString(),search:url.search});
      if (denial !== null) {
        return route.fulfill({
          status:denial,contentType:'application/json',
          headers:{'X-Correlation-ID':'playwright-57'},
          body:JSON.stringify({message:'Denied by browser fixture',code:'access_denied'})
        });
      }
      const params=url.searchParams;
      const data=empty || params.get('from')==='2027-01-01'
        ? snapshot.empty
        : params.get('companyId')===company ? snapshot.metrics : snapshot.global;
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(data)});
    }
    if (url.pathname==='/api/dashboard/lookups')
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(lookups)});
    if (url.pathname==='/health')
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({status:'ok'})});
    if (url.pathname.startsWith('/api/'))
      throw new Error('Unexpected API dependency in browser parity test: '+url.pathname);
    return route.continue();
  });
  return {calls,setDenial(value){denial=value;}};
}

test('real Chromium: executive/operations/navigation and chart render from Spring JSON', async ({page}) => {
  const {calls}=await harness(page);
  await page.goto(PAGE+'?view=executive');
  await expect(page.getByRole('heading',{name:'المدير العام',exact:true})).toBeVisible();
  await expect.poll(()=>page.locator('[data-chart-kind]').count()).toBeGreaterThan(0);
  const nav=page.getByRole('navigation',{name:'تنقل لوحة المؤشرات'});
  await nav.getByRole('button',{name:'النظام والعمليات'}).click();
  await expect(page.getByRole('heading',{name:'النظام والعمليات',exact:true})).toBeVisible();
  await expect(page).toHaveURL(/view=operations/);
  await nav.getByRole('button',{name:'المدير العام'}).click();
  await expect(page.getByRole('heading',{name:'المدير العام',exact:true})).toBeVisible();
  expect(calls.length).toBeGreaterThan(0);
  expect(calls.every(c=>new URL(c.url).pathname==='/api/dashboard/snapshot')).toBe(true);
});

test('real Chromium: filter changes are persisted and re-requested with exact URL',async ({page}) => {
  const {calls}=await harness(page);
  await page.goto(PAGE+'?view=executive');
  await expect(page.getByRole('heading',{name:'المدير العام',exact:true})).toBeVisible();
  const form=page.locator('.dashboard-filter-bar');
  await expect(form.locator('select').first()).toBeVisible();
  await form.locator('select').first().selectOption('ArrivalDate');
  await form.locator('input[type="date"]').first().fill('2026-11-01');
  await expect.poll(()=>calls.some(c=>{
    let q=new URL(c.url).searchParams;
    return q.get('dateBasis')==='ArrivalDate' && q.get('from')==='2026-11-01';
  })).toBe(true);
  await expect(page).toHaveURL(/dateBasis=ArrivalDate/);
  expect(await page.evaluate(()=>JSON.parse(localStorage.getItem('memo-dashboard-filters')||'{}').from))
    .toBe('2026-11-01');
  await page.reload();
  await expect(form.locator('select').first()).toHaveValue('ArrivalDate');
  await expect(form.locator('input[type="date"]').first()).toHaveValue('2026-11-01');
});

test('real Chromium: directory company selection navigates to scoped report',async ({page}) => {
  const {calls}=await harness(page);
  await page.goto(PAGE+'?view=directory');
  await expect(page.getByRole('heading',{name:'اختيار شركة',exact:true})).toBeVisible();
  const search=page.getByRole('textbox',{name:'بحث عن شركة'});
  await expect(search).toBeVisible();
  await search.fill('لا توجد شركة');
  await expect(page.getByText('لا توجد شركة مطابقة للبحث.')).toBeVisible();
  await search.fill('');
  const target=page.locator('.live-directory').getByRole('button').first();
  await expect(target).toBeVisible();
  await target.click();
  await expect(page.getByRole('heading',{name:'تقرير الشركة',exact:true})).toBeVisible();
  await expect(page).toHaveURL(/view=company/);
  await expect.poll(()=>calls.some(c=>new URL(c.url).searchParams.get('companyId')===company))
    .toBe(true);
});

test('real Chromium: no-data is not presented as a successful populated chart',async ({page}) => {
  await harness(page,{empty:true});
  await page.goto(PAGE+'?view=executive');
  await expect(page.getByRole('heading',{name:'المدير العام',exact:true})).toBeVisible();
  await expect(page.getByText('لا توجد بيانات ضمن النطاق المحدد')).toBeVisible();
});

test('real Chromium: HTTP 403 shows failure and retry recovers without making a fake success',async ({page}) => {
  const state=await harness(page,{deny:403});
  await page.goto(PAGE+'?view=executive');
  const error=page.getByRole('alert');
  await expect(error).toBeVisible();
  await expect(error).toContainText('صلاحية');
  expect(state.calls.length).toBeGreaterThan(0);
  state.setDenial(null);
  await error.getByRole('button').click();
  await expect(error).toHaveCount(0);
  await expect(page.getByRole('heading',{name:'المدير العام',exact:true})).toBeVisible();
  await expect.poll(()=>page.locator('[data-chart-kind]').count()).toBeGreaterThan(0);
  expect(state.calls.length).toBeGreaterThanOrEqual(2);
});

test('real Chromium: no browser request escapes to a real snapshot API',async ({page}) => {
  const requests=[];
  page.on('request',req=>{
    const u=new URL(req.url());
    if(u.pathname.startsWith('/api/')) requests.push(u.pathname);
  });
  await harness(page);
  await page.goto(PAGE+'?view=executive');
  await expect(page.getByRole('heading',{name:'المدير العام',exact:true})).toBeVisible();
  expect(requests.length).toBeGreaterThan(0);
  expect(requests.every(path=>['/api/dashboard/lookups','/api/dashboard/snapshot'].includes(path)))
    .toBe(true);
});
