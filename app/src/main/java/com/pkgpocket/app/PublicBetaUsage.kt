package com.pkgpocket.app
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object PublicBetaUsage {
 private const val P="pkg_pocket_public_beta";private const val G="completed_game_uses";private const val D="completed_dlc_uses";private const val U="completed_update_uses"
 enum class Bucket{GAME,DLC,UPDATE};private data class C(val g:Int,val d:Int,val u:Int)
 @Volatile private var mem:C?=null;@Volatile private var at=0L
 private fun b(k:PkgKind)=when(k){PkgKind.GAME->Bucket.GAME;PkgKind.DLC->Bucket.DLC;PkgKind.UPDATE->Bucket.UPDATE;PkgKind.OTHER->Bucket.GAME}
 private fun mx(x:Bucket)=when(x){Bucket.GAME->BuildConfig.BETA_MAX_GAMES;Bucket.DLC->BuildConfig.BETA_MAX_DLCS;Bucket.UPDATE->BuildConfig.BETA_MAX_UPDATES}
 private fun clamp(x:C)=C(x.g.coerceIn(0,5),x.d.coerceIn(0,5),x.u.coerceIn(0,5))
 private fun local(c:Context):C{val p=c.getSharedPreferences(P,0);return clamp(C(p.getInt(G,0),p.getInt(D,0),p.getInt(U,0)))}
 private fun save(c:Context,x:C){val v=clamp(x);c.getSharedPreferences(P,0).edit().putInt(G,v.g).putInt(D,v.d).putInt(U,v.u).commit();mem=v;at=System.currentTimeMillis()}
 private fun dev(c:Context):String{val raw=Settings.Secure.getString(c.contentResolver,Settings.Secure.ANDROID_ID).orEmpty()+"|pkg-pocket-beta-device-v1";return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}}
 private fun parse(s:String):C{val j=JSONObject(s);return clamp(C(j.optInt("games"),j.optInt("dlcs"),j.optInt("updates")))}
 private fun req(m:String,p:String,body:String?=null):String{val base=BuildConfig.BETA_API_URL.trim().trimEnd('/');check(base.startsWith("https://"))
  val h=(URL(base+p).openConnection() as HttpURLConnection).apply{requestMethod=m;connectTimeout=6000;readTimeout=6000;setRequestProperty("Accept","application/json");if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json");outputStream.use{it.write(body.toByteArray())}}}
  return try{val n=h.responseCode;val st=if(n in 200..299)h.inputStream else h.errorStream;val t=st?.bufferedReader()?.use{it.readText()}.orEmpty();check(n in 200..299);t}finally{h.disconnect()}}
 private fun remote(c:Context):C{val now=System.currentTimeMillis();mem?.let{if(now-at<5000)return it};val k=URLEncoder.encode(dev(c),"UTF-8");val v=runBlocking{withContext(Dispatchers.IO){parse(req("GET","/v1/usage?device=$k"))}};save(c,v);return v}
 fun used(c:Context,x:Bucket):Int{if(!BuildConfig.PUBLIC_BETA)return 0;val v=try{remote(c)}catch(_:Throwable){local(c)};return when(x){Bucket.GAME->v.g;Bucket.DLC->v.d;Bucket.UPDATE->v.u}}
 fun remaining(c:Context,x:Bucket)=if(!BuildConfig.PUBLIC_BETA)Int.MAX_VALUE else (mx(x)-used(c,x)).coerceAtLeast(0)
 fun usedGames(c:Context)=used(c,Bucket.GAME);fun usedDlcs(c:Context)=used(c,Bucket.DLC);fun usedUpdates(c:Context)=used(c,Bucket.UPDATE)
 fun remainingGames(c:Context)=remaining(c,Bucket.GAME);fun remainingDlcs(c:Context)=remaining(c,Bucket.DLC);fun remainingUpdates(c:Context)=remaining(c,Bucket.UPDATE)
 fun fullyExhausted(c:Context)=BuildConfig.PUBLIC_BETA&&remainingGames(c)<=0&&remainingDlcs(c)<=0&&remainingUpdates(c)<=0
 fun requestedGames(x:List<PkgItem>)=x.count{b(it.kind)==Bucket.GAME};fun requestedDlcs(x:List<PkgItem>)=x.count{b(it.kind)==Bucket.DLC};fun requestedUpdates(x:List<PkgItem>)=x.count{b(it.kind)==Bucket.UPDATE}
 fun canFit(c:Context,x:List<PkgItem>)=!BuildConfig.PUBLIC_BETA||(requestedGames(x)<=remainingGames(c)&&requestedDlcs(x)<=remainingDlcs(c)&&requestedUpdates(x)<=remainingUpdates(c))
 @Synchronized fun recordCompleted(c:Context,k:PkgKind){if(!BuildConfig.PUBLIC_BETA)return;val q=when(b(k)){Bucket.GAME->"game";Bucket.DLC->"dlc";Bucket.UPDATE->"update"};val body=JSONObject().put("device",dev(c)).put("bucket",q).toString();val v=runBlocking{withContext(Dispatchers.IO){parse(req("POST","/v1/usage",body))}};save(c,v)}
}
