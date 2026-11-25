import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
const PASSWORD=process.env.MEMO_CI_BROWSER_PASSWORD;
if(!PASSWORD)throw new Error('Missing CI browser password');
const PUBLIC='/api/dashboard/snapshot';
const PREVIEW='/api/internal/dashboard/snapshot-preview';
async function get(page,path){
 return page.evaluate(async path=>{
  let r=await fetch(path,{credentials:'same-origin',cache:'no-store'});
  return {status:r.status,headers:Object.fromEntries(r.headers),body:await r.text()};
 },path);
}
async function login(page,username){
 return page.evaluate(async({username,password})=>{
  let csrf=await fetch('/api/auth/csrf',{credentials:'same-origin'});
  if(!csrf.ok)throw new Error('CSRF handshake '+csrf.status);
  let token=(await csrf.json()).token;
  let r=await fetch('/api/auth/login',{
   method:'POST',credentials:'same-origin',
   headers:{'Content-Type':'application/json','X-CSRF-TOKEN':token},
   body:JSON.stringify({username,password})
  });
  return {status:r.status,body:await r.text()};
 },{username,password:PASSWORD});
}
async function logout(page){
 return page.evaluate(async()=>{
  let csrf=await fetch('/api/auth/csrf',{credentials:'same-origin'});
  let token=(await csrf.json()).token;
  let r=await fetch('/api/auth/logout',{method:'POST',credentials:'same-origin',
   headers:{'X-CSRF-TOKEN':token}});
  return r.status;
 });
}
function role(code){
 if(!['ADMIN','VIEWER'].includes(code))throw new Error('Unrecognized role');
 let sql="update auth.users set role_id=(select id from auth.roles where code='"+code+
   "'),updated_at=now() where username='admin'";
 execFileSync('psql',['-X','-v','ON_ERROR_STOP=1','-c',sql],
  {env:process.env,stdio:'pipe'});
}
test.beforeEach(async({page})=>{
 await page.goto('/dashboard-browser-parity.html?view=executive');
});
test.afterEach(()=>role('ADMIN'));

test('anonymous browser cannot read DB-backed snapshot and public path is closed',async({page})=>{
 expect((await get(page,PREVIEW)).status).toBe(401);
 expect((await page.request.get('http://127.0.0.1:8080'+PUBLIC)).status()).toBe(403);
 expect(JSON.parse((await get(page,'/api/auth/me')).body).authenticated).toBe(false);
});
test('CSRF-protected BCrypt login creates secure session and reads actual PostgreSQL snapshot',async({page})=>{
 let missing=await page.evaluate(async password=>
   (await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({username:'admin',password})})).status,PASSWORD);
 expect(missing).toBe(403);
 let result=await login(page,'admin');
 expect(result.status).toBe(200);
 expect(JSON.parse(result.body).authenticated).toBe(true);
 let cookies=await page.context().cookies('http://127.0.0.1:5173');
 let session=cookies.find(c=>c.name==='JSESSIONID');
 expect(session).toBeTruthy();
 expect(session.httpOnly).toBe(true);
 let me=await get(page,'/api/auth/me');
 expect(JSON.parse(me.body).authenticated).toBe(true);
 let response=await get(page,PREVIEW+'?dateBasis=ArrivalDate&from=2026-11-01&to=2026-11-30');
 expect(response.status).toBe(200);
 expect(response.headers['cache-control']).toContain('no-store');
 let report=JSON.parse(response.body);
 expect(report.readyForPublicApi).toBe(false);
 expect(report.snapshot.isHealthy).toBe(true);
 expect(report.snapshot.summary.totalNotes).toBeGreaterThan(0);
 expect(report.snapshot.views.executiveSummary.availability.hasRows).toBe(true);
 expect(Array.isArray(report.parityGaps)).toBe(true);
 expect((await page.request.get('http://127.0.0.1:8080'+PUBLIC)).status()).toBe(403);
});
test('logout invalidates session, while viewer can never read ADMIN-only snapshot',async({page})=>{
 expect((await login(page,'admin')).status).toBe(200);
 expect((await get(page,PREVIEW)).status).toBe(200);
 expect(await logout(page)).toBe(200);
 expect((await get(page,PREVIEW)).status).toBe(401);
 expect(JSON.parse((await get(page,'/api/auth/me')).body).authenticated).toBe(false);
 expect((await login(page,'ci_viewer')).status).toBe(200);
 expect((await get(page,PREVIEW)).status).toBe(403);
});
test('DB role revocation invalidates an existing session immediately',async({page})=>{
 expect((await login(page,'admin')).status).toBe(200);
 expect((await get(page,PREVIEW)).status).toBe(200);
 try{
  role('VIEWER');
  expect((await get(page,PREVIEW)).status).toBe(401);
  expect(JSON.parse((await get(page,'/api/auth/me')).body).authenticated).toBe(false);
 }finally{role('ADMIN');}
});

test('original React DashboardLive renders real ADMIN-only PostgreSQL report via CI bridge',async({page})=>{
 expect((await login(page,'admin')).status).toBe(200);
 await page.reload();
 const dashboard=page.getByRole('heading',{name:'المدير العام',exact:true});
 await expect(dashboard).toBeVisible();
 await expect.poll(()=>page.locator('[data-chart-kind]').count()).toBeGreaterThan(0);
 const direct=await page.request.get('http://127.0.0.1:8080'+PUBLIC);
 expect(direct.status()).toBe(403); // no production public route was opened
 const report=await get(page,PREVIEW+'?dateBasis=ArrivalDate&from=2026-11-01&to=2026-11-30');
 expect(report.status).toBe(200);
 const body=JSON.parse(report.body);
 expect(body.snapshot.summary.totalNotes).toBeGreaterThan(0);
 expect(body.readyForPublicApi).toBe(false);
});
