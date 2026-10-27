const json = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers } });
const corsHeaders = { "x-content-type-options": "nosniff", "referrer-policy": "strict-origin-when-cross-origin" };
const enc = new TextEncoder();
const bytesToHex = (bytes) => [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, "0")).join("");
async function sha256(value) { return bytesToHex(await crypto.subtle.digest("SHA-256", enc.encode(value))); }
async function passwordHash(password, salt) {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: enc.encode(salt), iterations: 210000, hash: "SHA-256" }, key, 256);
  return bytesToHex(bits);
}
function cookie(request, name) { return (request.headers.get("cookie") || "").split(";").map(v => v.trim()).find(v => v.startsWith(name + "="))?.slice(name.length + 1) || ""; }
function secureCookie(token, maxAge) { return `session=${token}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=${maxAge}`; }
async function currentUser(request, env) {
  const token = cookie(request, "session"); if (!token) return null;
  const tokenHash = await sha256(token);
  const row = await env.DB.prepare("SELECT users.id, users.phone FROM sessions JOIN users ON users.id=sessions.user_id WHERE sessions.token_hash=? AND sessions.expires_at>?").bind(tokenHash, Math.floor(Date.now()/1000)).first();
  return row || null;
}
function sameOrigin(request) {
  const origin = request.headers.get("origin"); if (!origin) return true;
  try { return new URL(origin).host === new URL(request.url).host; } catch { return false; }
}
export default {
 async fetch(request, env) {
  const url = new URL(request.url), path = url.pathname, method = request.method;
  const headers = { ...corsHeaders };
  if (method === "OPTIONS") return new Response(null, {status:204, headers:{...headers,"access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type"}});
  if (path.startsWith("/api/") && !sameOrigin(request)) return json({error:"درخواست از مبدأ غیرمجاز است."},403,headers);
  try {
   if (path === "/api/health" && method === "GET") {
    await env.DB.prepare("SELECT 1").first(); return json({ok:true, service:"pooyesh-zamin-api"},200,headers);
   }
   if (path === "/api/auth/register" && method === "POST") {
    const body = await request.json(), phone = String(body.phone||"").trim(), password = String(body.password||"");
    if (!/^\+?[0-9۰-۹]{10,15}$/.test(phone) || password.length < 10) return json({error:"شماره همراه یا گذرواژه معتبر نیست؛ گذرواژه باید دست‌کم ۱۰ نویسه باشد."},400,headers);
    const salt = bytesToHex(crypto.getRandomValues(new Uint8Array(16))), hash = await passwordHash(password, salt);
    let result; try { result = await env.DB.prepare("INSERT INTO users(phone,password_hash) VALUES(?,?)").bind(phone, salt+":"+hash).run(); } catch { return json({error:"این شماره همراه قبلاً ثبت شده یا اطلاعات نامعتبر است."},409,headers); }
    const token = bytesToHex(crypto.getRandomValues(new Uint8Array(32))), tokenHash = await sha256(token), expires = Math.floor(Date.now()/1000)+604800;
    await env.DB.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)").bind(tokenHash,result.meta.last_row_id,expires).run();
    return json({ok:true,user:{id:result.meta.last_row_id,phone}},201,{...headers,"set-cookie":secureCookie(token,604800)});
   }
   if (path === "/api/auth/login" && method === "POST") {
    const body = await request.json(), phone = String(body.phone||"").trim(), password = String(body.password||"");
    const user = await env.DB.prepare("SELECT id,phone,password_hash FROM users WHERE phone=?").bind(phone).first();
    if (!user) return json({error:"شماره همراه یا گذرواژه اشتباه است."},401,headers);
    const [salt, stored] = user.password_hash.split(":");
    if (await passwordHash(password,salt) !== stored) return json({error:"شماره همراه یا گذرواژه اشتباه است."},401,headers);
    const token=bytesToHex(crypto.getRandomValues(new Uint8Array(32))), expires=Math.floor(Date.now()/1000)+604800;
    await env.DB.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)").bind(await sha256(token),user.id,expires).run();
    return json({ok:true,user:{id:user.id,phone:user.phone}},200,{...headers,"set-cookie":secureCookie(token,604800)});
   }
   if (path === "/api/auth/logout" && method === "POST") {
    const token=cookie(request,"session"); if(token) await env.DB.prepare("DELETE FROM sessions WHERE token_hash=?").bind(await sha256(token)).run();
    return json({ok:true},200,{...headers,"set-cookie":secureCookie("",0)});
   }
   if (path === "/api/auth/me" && method === "GET") {
    const user=await currentUser(request,env); return user?json({ok:true,user},200,headers):json({error:"نیاز به ورود دارید."},401,headers);
   }
   const user=await currentUser(request,env);
   if (path === "/api/waste-records" && method === "GET") {
    if(!user) return json({error:"ابتدا وارد حساب شوید."},401,headers);
    const {results}=await env.DB.prepare("SELECT id,items_json,created_at FROM waste_records WHERE user_id=? ORDER BY id DESC LIMIT 100").bind(user.id).all();
    return json({records:results.map(r=>({id:r.id,items:JSON.parse(r.items_json),created_at:r.created_at}))},200,headers);
   }
   if (path === "/api/waste-records" && method === "POST") {
    if(!user) return json({error:"ابتدا وارد حساب شوید."},401,headers);
    const body=await request.json(), items=body.items;
    if(!Array.isArray(items)||items.length>100) return json({error:"فهرست اقلام معتبر نیست."},400,headers);
    const result=await env.DB.prepare("INSERT INTO waste_records(user_id,items_json) VALUES(?,?)").bind(user.id,JSON.stringify(items)).run();
    return json({ok:true,id:result.meta.last_row_id},201,headers);
   }
   if (path === "/api/posts" && method === "GET") {
    const {results}=await env.DB.prepare("SELECT posts.id,posts.content,posts.category,posts.images_json,posts.created_at,users.phone FROM posts JOIN users ON users.id=posts.user_id ORDER BY posts.id DESC LIMIT 100").all();
    return json({posts:results.map(p=>({id:p.id,content:p.content,category:p.category,images:JSON.parse(p.images_json),created_at:p.created_at,author:p.phone}))},200,headers);
   }
   if (path === "/api/posts" && method === "POST") {
    if(!user) return json({error:"برای انتشار مطلب ابتدا وارد شوید."},401,headers);
    const type=request.headers.get("content-type")||"";
    let content="",category="عمومی",images=[];
    if(type.includes("application/json")) { const b=await request.json(); content=String(b.content||"");category=String(b.category||"عمومی");images=Array.isArray(b.images)?b.images:[]; }
    else { const form=await request.formData(); content=String(form.get("content")||"");category=String(form.get("category")||"عمومی"); const uploaded=form.getAll("images"); if(uploaded.some(x=>x instanceof File && x.size>0)) return json({error:"بارگذاری تصویر هنوز فعال نشده؛ ابتدا R2 را پیکربندی کنید."},501,headers); }
    if(!content.trim()||content.length>10000||images.length>4) return json({error:"متن مطلب یا تعداد تصاویر معتبر نیست."},400,headers);
    const result=await env.DB.prepare("INSERT INTO posts(user_id,content,category,images_json) VALUES(?,?,?,?)").bind(user.id,content.trim(),category,JSON.stringify(images)).run();
    return json({ok:true,id:result.meta.last_row_id},201,headers);
   }
   if (path.startsWith("/api/")) return json({error:"مسیر API پیدا نشد."},404,headers);
   return env.ASSETS.fetch(request);
  } catch(e) { return json({error:"خطای داخلی سرویس."},500,headers); }
 }
};