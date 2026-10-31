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
  const bearer = request.headers.get("authorization") || "";
  const token = bearer.startsWith("Bearer ") ? bearer.slice(7).trim() : cookie(request, "session");
  if (!token || token.length > 256) return null;
  const tokenHash = await sha256(token);
  return await env.DB.prepare("SELECT users.id, users.phone, users.name, users.account_type AS accountType, users.address, users.area FROM sessions JOIN users ON users.id=sessions.user_id WHERE sessions.token_hash=? AND sessions.expires_at>?").bind(tokenHash, Math.floor(Date.now()/1000)).first();
}
async function companyAuthorized(request, env) {
  const configured = env.COMPANY_ACCESS_TOKEN || "";
  const bearer = request.headers.get("authorization") || "";
  const supplied = bearer.startsWith("Bearer ") ? bearer.slice(7).trim() : "";
  if (!configured || !supplied || configured.length > 256 || supplied.length > 256) return false;
  return (await sha256(configured)) === (await sha256(supplied));
}
function sameOrigin(request) {
  const origin = request.headers.get("origin"); if (!origin) return true;
  try { return new URL(origin).host === new URL(request.url).host; } catch { return false; }
}
export default {
 async fetch(request, env) {
  const url = new URL(request.url), path = url.pathname, method = request.method;
  const headers = { ...corsHeaders };
  if (method === "OPTIONS") return new Response(null, {status:204, headers:{...headers,"access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type,authorization"}});
  if (path.startsWith("/api/") && !sameOrigin(request)) return json({error:"درخواست از مبدأ غیرمجاز است."},403,headers);
  try {
   if (path === "/api/health" && method === "GET") {
    await env.DB.prepare("SELECT 1").first(); return json({ok:true, service:"pooyesh-zamin-api"},200,headers);
   }
   if (path === "/api/auth/register" && method === "POST") {
    const body = await request.json(), phone = String(body.phone||"").trim(), password = String(body.password||""), name = String(body.name||"").trim(), accountType = String(body.accountType||"home"), address = String(body.address||"").trim(), area = String(body.area||"").trim();
    if (!/^\+?[0-9۰-۹]{10,15}$/.test(phone) || password.length < 10 || !name || name.length > 120 || !["collector","home"].includes(accountType) || address.length > 500 || area.length > 120) return json({error:"اطلاعات ثبت‌نام معتبر نیست؛ نام و شماره همراه لازم است و گذرواژه باید دست‌کم ۱۰ نویسه باشد."},400,headers);
    const salt = bytesToHex(crypto.getRandomValues(new Uint8Array(16))), hash = await passwordHash(password, salt);
    let result; try { result = await env.DB.prepare("INSERT INTO users(phone,password_hash,name,account_type,address,area) VALUES(?,?,?,?,?,?)").bind(phone, salt+":"+hash,name,accountType,address,area).run(); } catch { return json({error:"این شماره همراه قبلاً ثبت شده یا اطلاعات نامعتبر است."},409,headers); }
    const token = bytesToHex(crypto.getRandomValues(new Uint8Array(32))), tokenHash = await sha256(token), expires = Math.floor(Date.now()/1000)+604800;
    await env.DB.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)").bind(tokenHash,result.meta.last_row_id,expires).run();
    return json({ok:true,user:{id:result.meta.last_row_id,phone,name,accountType,address,area},access_token:token},201,{...headers,"set-cookie":secureCookie(token,604800)});
   }
   if (path === "/api/auth/login" && method === "POST") {
    const body = await request.json(), phone = String(body.phone||"").trim(), password = String(body.password||"");
    const user = await env.DB.prepare("SELECT id,phone,password_hash,name,account_type,address,area FROM users WHERE phone=?").bind(phone).first();
    if (!user) return json({error:"شماره همراه یا گذرواژه اشتباه است."},401,headers);
    const [salt, stored] = user.password_hash.split(":");
    if (await passwordHash(password,salt) !== stored) return json({error:"شماره همراه یا گذرواژه اشتباه است."},401,headers);
    const token=bytesToHex(crypto.getRandomValues(new Uint8Array(32))), expires=Math.floor(Date.now()/1000)+604800;
    await env.DB.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)").bind(await sha256(token),user.id,expires).run();
    return json({ok:true,user:{id:user.id,phone:user.phone,name:user.name,accountType:user.account_type,address:user.address,area:user.area},access_token:token},200,{...headers,"set-cookie":secureCookie(token,604800)});
   }
   if (path === "/api/imagery/search" && method === "GET") {
    const user = await currentUser(request, env);
    if (!user) return json({error:"ابتدا وارد حساب شوید."},401,headers);
    const raw = String(url.searchParams.get("bbox") || "").split(",").map(Number);
    if (raw.length !== 4 || raw.some(v => !Number.isFinite(v)) || raw[0] < -180 || raw[2] > 180 || raw[1] < -90 || raw[3] > 90 || raw[0] >= raw[2] || raw[1] >= raw[3] || (raw[2]-raw[0]) > 0.1 || (raw[3]-raw[1]) > 0.1) return json({error:"محدوده جغرافیایی معتبر نیست."},400,headers);
    const catalog = await fetch("https://api.imagery.hotosm.org/stac/search", {
      method:"POST", headers:{"content-type":"application/json","accept":"application/geo+json"},
      body:JSON.stringify({bbox:raw,limit:10,collections:["openaerialmap"]})
    });
    if (!catalog.ok) return json({error:"کاتالوگ تصاویر هوایی موقتاً در دسترس نیست."},502,headers);
    const data = await catalog.json();
    const features = (data.features || []).slice(0,10).map(feature => ({
      id:feature.id, type:"Feature", geometry:feature.geometry,
      properties:{
        title:feature.properties?.title || feature.properties?.["o:title"] || "تصویر هوایی",
        datetime:feature.properties?.datetime || feature.properties?.["view:off_nadir"],
        license:feature.properties?.license || feature.properties?.["license"] || "بررسی مجوز در صفحه منبع الزامی است",
        gsd:feature.properties?.gsd ?? feature.properties?.["gsd"],
        platform:feature.properties?.platform || feature.properties?.["platform"]
      },
      assets:Object.fromEntries(Object.entries(feature.assets || {}).filter(([key,value])=>{
        if (!["visual","data","thumbnail"].includes(key) || !value.href) return false;
        try {
          const assetUrl = new URL(value.href);
          return assetUrl.protocol === "https:" && (assetUrl.hostname.endsWith(".amazonaws.com") || assetUrl.hostname.endsWith(".hotosm.org") || assetUrl.hostname.endsWith(".openaerialmap.org"));
        } catch { return false; }
      }).map(([key,value])=>[key,{href:value.href,type:value.type,title:value.title,roles:value.roles}]))
    }));
    return json({type:"FeatureCollection",features,attribution:"OpenAerialMap / Humanitarian OpenStreetMap Team"},200,headers);
   }
   if (path === "/api/company/location-records" && method === "GET") {
    if (!env.COMPANY_ACCESS_TOKEN) return json({error:"دسترسی شرکت هنوز در سرور پیکربندی نشده است."},503,headers);
    if (!await companyAuthorized(request,env)) return json({error:"دسترسی غیرمجاز است."},401,headers);
    const limitRaw = Number(url.searchParams.get("limit") || 100), afterRaw = Number(url.searchParams.get("after_id") || 0);
    if (!Number.isInteger(limitRaw) || limitRaw < 1 || !Number.isSafeInteger(afterRaw) || afterRaw < 0) return json({error:"پارامتر صفحه‌بندی معتبر نیست."},400,headers);
    const limit = Math.min(200,limitRaw), afterId = afterRaw;
    const {results} = await env.DB.prepare("SELECT location_records.id,location_records.client_id,location_records.latitude,location_records.longitude,location_records.accuracy_m,location_records.altitude_m,location_records.capture_height_m,location_records.captured_at,location_records.image_kind,location_records.image_source,location_records.notes,location_records.created_at,users.phone,(location_records.image_key IS NOT NULL) AS has_image FROM location_records JOIN users ON users.id=location_records.user_id WHERE location_records.id>? ORDER BY location_records.id ASC LIMIT ?").bind(afterId,limit).all();
    const records = results.map(row=>({...row,image_url:row.has_image?"/api/company/location-records/"+row.id+"/image":null}));
    return json({records,next_cursor:records.length?records[records.length-1].id:null},200,headers);
   }
   if (path.startsWith("/api/company/location-records/") && path.endsWith("/image") && method === "GET") {
    if (!env.COMPANY_ACCESS_TOKEN) return json({error:"دسترسی شرکت هنوز در سرور پیکربندی نشده است."},503,headers);
    if (!await companyAuthorized(request,env)) return json({error:"دسترسی غیرمجاز است."},401,headers);
    const id = Number(path.split("/")[4]);
    if (!Number.isSafeInteger(id) || id < 1) return json({error:"شناسه نامعتبر است."},400,headers);
    const row = await env.DB.prepare("SELECT image_key,image_mime FROM location_records WHERE id=?").bind(id).first();
    if (!row || !row.image_key || !env.MEDIA) return json({error:"تصویری برای این ثبت پیدا نشد."},404,headers);
    const object = await env.MEDIA.get(row.image_key);
    if (!object) return json({error:"فایل تصویر پیدا نشد."},404,headers);
    return new Response(object.body,{headers:{...headers,"content-type":row.image_mime || "application/octet-stream","content-length":String(object.size),"cache-control":"private, no-store"}});
   }
   if (path.startsWith("/api/location-records/") && path.endsWith("/image") && method === "GET") {
    const user = await currentUser(request,env);
    if (!user) return json({error:"ابتدا وارد حساب شوید."},401,headers);
    const id = Number(path.split("/")[3]);
    if (!Number.isSafeInteger(id) || id < 1) return json({error:"شناسه نامعتبر است."},400,headers);
    const row = await env.DB.prepare("SELECT image_key,image_mime FROM location_records WHERE id=? AND user_id=?").bind(id,user.id).first();
    if (!row || !row.image_key || !env.MEDIA) return json({error:"تصویری برای این ثبت پیدا نشد."},404,headers);
    const object = await env.MEDIA.get(row.image_key);
    if (!object) return json({error:"فایل تصویر پیدا نشد."},404,headers);
    return new Response(object.body,{headers:{...headers,"content-type":row.image_mime || "application/octet-stream","content-length":String(object.size),"cache-control":"private, no-store"}});
   }
   if (path === "/api/location-records" && (method === "GET" || method === "POST")) {
    const user = await currentUser(request,env);
    if (!user) return json({error:"ابتدا وارد حساب شوید."},401,headers);
    if (method === "GET") {
      const {results} = await env.DB.prepare("SELECT id,client_id,latitude,longitude,accuracy_m,altitude_m,capture_height_m,captured_at,image_kind,image_source,notes,created_at,(image_key IS NOT NULL) AS has_image FROM location_records WHERE user_id=? ORDER BY id DESC LIMIT 200").bind(user.id).all();
      return json({records:results},200,headers);
    }
    const form = await request.formData();
    const clientId = String(form.get("client_id") || "");
    const latitude = Number(form.get("latitude")), longitude = Number(form.get("longitude"));
    const capturedAt = String(form.get("captured_at") || "");
    const accuracyRaw = String(form.get("accuracy_m") || ""), altitudeRaw = String(form.get("altitude_m") || "");
    const accuracy = accuracyRaw ? Number(accuracyRaw) : null, altitude = altitudeRaw ? Number(altitudeRaw) : null;
    const captureHeightRaw = String(form.get("capture_height_m") || ""), captureHeight = captureHeightRaw ? Number(captureHeightRaw) : null;
    if (!/^[a-f0-9-]{36}$/i.test(clientId) || !Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180 || !capturedAt || (accuracy !== null && (!Number.isFinite(accuracy) || accuracy < 0)) || (altitude !== null && !Number.isFinite(altitude)) || (captureHeight !== null && (!Number.isFinite(captureHeight) || captureHeight < 0 || captureHeight > 500))) return json({error:"اطلاعات موقعیت معتبر نیست."},400,headers);
    let imageKey = null, imageMime = null;
    const file = form.get("image");
    if (file instanceof File && file.size > 0) {
      const allowed = ["image/jpeg","image/png","image/webp","image/heic","image/tiff","application/geotiff"];
      if (file.size > 50*1024*1024 || !allowed.includes(file.type)) return json({error:"قالب تصویر پشتیبانی نمی‌شود یا حجم آن بیش از ۵۰ مگابایت است."},413,headers);
      if (!env.MEDIA) return json({error:"فضای ذخیره‌سازی تصاویر پیکربندی نشده است."},503,headers);
      imageKey = "location-images/" + user.id + "/" + clientId;
      imageMime = file.type;
      await env.MEDIA.put(imageKey,file.stream(),{httpMetadata:{contentType:file.type},customMetadata:{userId:String(user.id),clientId}});
    }
    const imageKind = String(form.get("image_kind") || "none");
    const imageSource = String(form.get("image_source") || "none");
    const notes = String(form.get("notes") || "").slice(0,2000);
    const result = await env.DB.prepare("INSERT INTO location_records(user_id,client_id,latitude,longitude,accuracy_m,altitude_m,capture_height_m,captured_at,image_key,image_mime,image_kind,image_source,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,client_id) DO UPDATE SET latitude=excluded.latitude,longitude=excluded.longitude,accuracy_m=excluded.accuracy_m,altitude_m=excluded.altitude_m,capture_height_m=excluded.capture_height_m,captured_at=excluded.captured_at,image_key=COALESCE(excluded.image_key,location_records.image_key),image_mime=COALESCE(excluded.image_mime,location_records.image_mime),image_kind=excluded.image_kind,image_source=excluded.image_source,notes=excluded.notes").bind(user.id,clientId,latitude,longitude,accuracy,altitude,captureHeight,capturedAt,imageKey,imageMime,imageKind,imageSource,notes).run();
    return json({ok:true,id:result.meta.last_row_id,client_id:clientId},201,headers);
   }
   if (path === "/api/auth/logout" && method === "POST") {
    const bearer=request.headers.get("authorization") || ""; const token=bearer.startsWith("Bearer ") ? bearer.slice(7).trim() : cookie(request,"session"); if(token) await env.DB.prepare("DELETE FROM sessions WHERE token_hash=?").bind(await sha256(token)).run();
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
    const {results}=await env.DB.prepare("SELECT posts.id,posts.content,posts.category,posts.images_json,posts.created_at,users.phone,users.name FROM posts JOIN users ON users.id=posts.user_id ORDER BY posts.id DESC LIMIT 100").all();
    return json({posts:results.map(p=>({id:p.id,content:p.content,category:p.category,images:JSON.parse(p.images_json),created_at:p.created_at,author:p.phone,authorName:p.name || p.phone,createdAt:p.created_at}))},200,headers);
   }
   if (path === "/api/posts" && method === "POST") {
    if(!user) return json({error:"برای انتشار مطلب ابتدا وارد شوید."},401,headers);
    const type=request.headers.get("content-type")||"";
    let content="",category="عمومی",images=[];
    if(type.includes("application/json")) { const b=await request.json(); content=String(b.content||"");category=String(b.category||"عمومی");images=Array.isArray(b.images)?b.images:[]; }
    else { const form=await request.formData(); content=String(form.get("content")||"");category=String(form.get("category")||"عمومی"); const uploaded=form.getAll("images"); if(uploaded.some(x=>x instanceof File && x.size>0)) return json({error:"بارگذاری تصویر هنوز فعال نشده؛ ابتدا R2 را پیکربندی کنید."},501,headers); }
    if(!content.trim()||content.length>10000||images.length>4) return json({error:"متن مطلب یا تعداد تصاویر معتبر نیست."},400,headers);
    const result=await env.DB.prepare("INSERT INTO posts(user_id,content,category,images_json) VALUES(?,?,?,?)").bind(user.id,content.trim(),category,JSON.stringify(images)).run();
    return json({ok:true,id:result.meta.last_row_id,post:{id:result.meta.last_row_id,content:content.trim(),category,images,createdAt:new Date().toISOString(),authorName:user.name || user.phone}},201,headers);
   }
   if (path.startsWith("/api/")) return json({error:"مسیر API پیدا نشد."},404,headers);
   return env.ASSETS.fetch(request);
  } catch(e) { return json({error:"خطای داخلی سرویس."},500,headers); }
 }
};