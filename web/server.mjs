import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(fileURLToPath(new URL('./public/', import.meta.url)));
const LIMIT = 32 * 1024 * 1024;
const types = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.json':'application/json','.webmanifest':'application/manifest+json'};

export function createApp({upstream = 'https://tyo.magnusk.top:9443', fetcher = fetch, secure = false} = {}) {
  const target = new URL(upstream);
  if (target.protocol !== 'https:' && !(target.protocol === 'http:' && ['localhost','127.0.0.1','[::1]'].includes(target.hostname))) throw Error('Upstream must use HTTPS, except on loopback');
  const sessions = new Map();
  const cookie = (sid, age = 2592000) => `gain_session=${sid}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${age}${secure ? '; Secure' : ''}`;
  const json = (res, status, data) => { res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(data)); };
  async function request(route, options = {}) {
    return fetcher(new URL(route, target), {...options, redirect:'error', signal:AbortSignal.timeout(30000)});
  }
  async function body(req, max = LIMIT) {
    let n = 0; const chunks = [];
    for await (const chunk of req) { n += chunk.length; if (n > max) throw Object.assign(Error('请求内容过大'), {status:413}); chunks.push(chunk); }
    return Buffer.concat(chunks).toString('utf8');
  }
  async function rotate(s) {
    if (!s.refreshing) s.refreshing = (async () => {
      const r = await request('/v1/auth/refresh', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({refreshToken:s.refreshToken})});
      if (!r.ok) throw Object.assign(Error('登录已过期，请重新登录'), {status:401});
      Object.assign(s, await r.json());
      s.expires = Date.now() + s.expiresIn * 1000;
    })().finally(() => { s.refreshing = null; });
    return s.refreshing;
  }
  async function authorized(s, route, options = {}) {
    if (s.expires < Date.now() + 10000) await rotate(s);
    const send = () => request(route, {...options, headers:{...options.headers, Authorization:`Bearer ${s.accessToken}`}});
    let r = await send();
    if (r.status === 401) { await rotate(s); r = await send(); }
    return r;
  }
  return http.createServer(async (req, res) => {
    res.setHeader('Cache-Control','no-store');
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname.startsWith('/api/')) {
        if (!['GET','HEAD'].includes(req.method)) {
          const expected = `${secure ? 'https' : 'http'}://${req.headers.host}`;
          if (req.headers.origin !== expected || req.headers['x-gain-request'] !== '1') return json(res,403,{error:{message:'请求来源不合法'}});
          if (!req.headers['content-type']?.startsWith('application/json')) return json(res,415,{error:{message:'需要 JSON 请求'}});
        }
        for (const [id,s] of sessions) if (s.until < Date.now()) sessions.delete(id);
        const sid = req.headers.cookie?.match(/(?:^|;\s*)gain_session=([a-f0-9]{64})(?:;|$)/)?.[1];
        const s = sessions.get(sid);
        if (url.pathname === '/api/login' && req.method === 'POST') {
          if (sessions.size >= 10000) return json(res,503,{error:{message:'会话已满，请稍后重试'}});
          const input = JSON.parse(await body(req,4096));
          const r = await request('/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:input.username,password:input.password})});
          const data = await r.json();
          if (!r.ok) return json(res,r.status,data);
          if (s) { await request('/v1/auth/logout',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:s.refreshToken})}); sessions.delete(sid); }
          const id = randomBytes(32).toString('hex');
          sessions.set(id,{...data, expires:Date.now()+data.expiresIn*1000, until:Date.now()+2592000000});
          res.setHeader('Set-Cookie',cookie(id));
          return json(res,200,{user:data.user});
        }
        if (!s) return json(res,401,{error:{message:'请先登录'}});
        if (url.pathname !== '/api/session' && req.headers['x-gain-user'] !== s.user.id) return json(res,401,{error:{message:'登录账号已变更，请重新登录后继续'}});
        if (url.pathname === '/api/session' && req.method === 'GET') {
          const r = await authorized(s,'/v1/me');
          if (!r.ok) return json(res,r.status,{error:{message:'登录已过期'}});
          return json(res,200,{user:s.user});
        }
        if (url.pathname === '/api/logout' && req.method === 'POST') {
          const r = await request('/v1/auth/logout',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:s.refreshToken})});
          if (!r.ok && r.status !== 401) return json(res,502,{error:{message:'退出失败，请重试'}});
          sessions.delete(sid); res.setHeader('Set-Cookie',cookie('',0)); return json(res,200,{ok:true});
        }
        if (url.pathname === '/api/password' && req.method === 'POST') {
          const payload = await body(req,4096);
          const r = await authorized(s,'/v1/auth/password',{method:'POST',headers:{'Content-Type':'application/json'},body:payload});
          const data = await r.json();
          if (!r.ok) return json(res,r.status,data);
          Object.assign(s,data,{expires:Date.now()+data.expiresIn*1000});
          sessions.delete(sid);
          const next = randomBytes(32).toString('hex'); sessions.set(next,s); res.setHeader('Set-Cookie',cookie(next));
          return json(res,200,{user:s.user});
        }
        if (!/^\/api\/sync(?:\/meta|\/revisions(?:\/\d+)?)?$/.test(url.pathname) || !(req.method === 'GET' || req.method === 'PUT' && url.pathname === '/api/sync')) return json(res,404,{error:{message:'接口不存在'}});
        const headers = {};
        for (const h of ['if-match','if-none-match','x-content-hash','content-type']) if (req.headers[h]) headers[h] = req.headers[h];
        const r = await authorized(s,url.pathname.replace('/api/','/v1/'),{method:req.method,headers,...(req.method==='PUT'?{body:await body(req)}:{})});
        for (const h of ['content-type','etag','x-revision','x-content-hash','x-format-version','x-schema-version','x-updated-at']) if (r.headers.has(h)) res.setHeader(h,r.headers.get(h));
        res.writeHead(r.status); res.end(Buffer.from(await r.arrayBuffer())); return;
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') return json(res,405,{error:{message:'方法不支持'}});
      const name = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
      const file = path.resolve(root, `.${name}`);
      if (!file.startsWith(root + path.sep) || !types[path.extname(file)]) return json(res,404,{error:{message:'页面不存在'}});
      const data = await readFile(file);
      res.setHeader('Content-Type',types[path.extname(file)]); res.end(req.method==='HEAD'?undefined:data);
    } catch (e) {
      json(res,e.status || (e.code==='ENOENT'?404:e instanceof SyntaxError?400:502),{error:{message:e.status?e.message:e.code==='ENOENT'?'页面不存在':e instanceof SyntaxError?'数据格式不正确':'无法连接同步服务，请稍后重试'}});
    }
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 4173);
  createApp({upstream:process.env.ASSETBOARD_UPSTREAM,secure:process.env.COOKIE_SECURE==='1'}).listen(port,process.env.HOST || '127.0.0.1',() => console.log(`日盈 Web: http://127.0.0.1:${port}`));
}
