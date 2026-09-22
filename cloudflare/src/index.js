const reply=(x,s=200)=>new Response(JSON.stringify(x),{status:s,headers:{"content-type":"application/json","cache-control":"no-store"}});
const valid=x=>typeof x==="string"&&/^[a-f0-9]{64}$/.test(x);
const clean=x=>({games:Math.max(0,Math.min(5,Number(x?.games)||0)),dlcs:Math.max(0,Math.min(5,Number(x?.dlcs)||0)),updates:Math.max(0,Math.min(5,Number(x?.updates)||0))});
export default{async fetch(r,e){const u=new URL(r.url);
if(u.pathname==="/health")return reply({ok:true});
if(u.pathname!=="/v1/usage")return reply({error:"not_found"},404);
if(r.method==="GET"){const d=u.searchParams.get("device");if(!valid(d))return reply({error:"invalid_device"},400);return reply(clean(await e.BETA_USAGE.get("device:"+d,"json")))}
if(r.method==="POST"){let b;try{b=await r.json()}catch{return reply({error:"invalid_json"},400)}
if(!valid(b?.device)||!["game","dlc","update"].includes(b?.bucket))return reply({error:"invalid_request"},400);
const k="device:"+b.device,c=clean(await e.BETA_USAGE.get(k,"json"));
if(b.bucket==="game")c.games=Math.min(5,c.games+1);if(b.bucket==="dlc")c.dlcs=Math.min(5,c.dlcs+1);if(b.bucket==="update")c.updates=Math.min(5,c.updates+1);
await e.BETA_USAGE.put(k,JSON.stringify(c));return reply(c)}
return reply({error:"method_not_allowed"},405)}};
