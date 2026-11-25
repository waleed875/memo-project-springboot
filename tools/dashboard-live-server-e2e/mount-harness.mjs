import {readFileSync,writeFileSync,copyFileSync} from 'node:fs';
import {join} from 'node:path';
const original=process.env.MEMO_ORIGINAL_FRONTEND;
if(!original)throw new Error('Original checkout path missing');
const vite=join(original,'vite.config.ts');
const old=readFileSync(vite,'utf8');
const anchor='  server: {';
if(!old.includes(anchor)||old.includes('CI-only live backend snapshot bridge'))
 throw new Error('Refusing to patch unexpected Vite config');
const patch=`  server: {
    // CI-only live backend snapshot bridge. Production public endpoint stays denied.
    proxy: {
      '/api/dashboard/snapshot': {
        target:'http://127.0.0.1:8080',
        changeOrigin:true,
        rewrite:(path)=>path.replace(/^\\/api\\/dashboard\\/snapshot(?=\\?|$)/,
          '/api/internal/dashboard/snapshot-preview'),
        selfHandleResponse:true,
        configure:(proxy)=>{
          proxy.on('proxyRes',(upstream,_req,downstream)=>{
            const chunks=[];
            upstream.on('data',chunk=>chunks.push(chunk));
            upstream.on('end',()=>{
              let body=Buffer.concat(chunks);
              const headers={...upstream.headers};
              delete headers['content-length'];
              delete headers['content-encoding'];
              if(upstream.statusCode===200){
                try {
                  const envelope=JSON.parse(body.toString('utf8'));
                  if(envelope.readyForPublicApi!==false || !envelope.snapshot)
                    throw new Error('Unexpected preview contract');
                  body=Buffer.from(JSON.stringify(envelope.snapshot));
                  headers['content-type']='application/json; charset=utf-8';
                }catch(err){
                  downstream.writeHead(502);
                  downstream.end('CI-only snapshot contract mismatch');
                  return;
                }
              }
              downstream.writeHead(upstream.statusCode||502,headers);
              downstream.end(body);
            });
          });
        }
      },
      '/api':{target:'http://127.0.0.1:8080',changeOrigin:true},
      '/actuator':{target:'http://127.0.0.1:8080',changeOrigin:true}
    },`;
writeFileSync(vite,old.replace(anchor,patch),'utf8');
copyFileSync('tools/dashboard-browser-e2e/harness/dashboard-browser-parity.html',
 join(original,'dashboard-browser-parity.html'));
copyFileSync('tools/dashboard-browser-e2e/harness/dashboard-browser-parity.tsx',
 join(original,'dashboard-browser-parity.tsx'));
console.log('Mounted isolated Vite proxy and original React dashboard');
