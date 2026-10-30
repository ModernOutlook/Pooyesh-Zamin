const CACHE='pooyesh-assets-v3';
const BASE=new URL('./',self.registration.scope);
const CORE=['','index.html','manifest.webmanifest','logo.png','logo.png?v=20261031','icon.svg'].map(x=>new URL(x,BASE).href);
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{
 const r=e.request,u=new URL(r.url);
 if(r.method!=='GET'||u.origin!==location.origin||u.pathname.includes('/api/'))return;
 if(r.mode==='navigate'){e.respondWith(fetch(r).then(res=>{caches.open(CACHE).then(c=>c.put(new URL('./index.html',BASE),res.clone()));return res}).catch(()=>caches.match(new URL('./index.html',BASE))));return;}
 e.respondWith(caches.match(r).then(hit=>hit||fetch(r).then(res=>{if(res.ok)caches.open(CACHE).then(c=>c.put(r,res.clone()));return res})));
});