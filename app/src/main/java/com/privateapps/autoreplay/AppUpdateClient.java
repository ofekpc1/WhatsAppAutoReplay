package com.privateapps.autoreplay;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.IntConsumer;
import javax.net.ssl.HttpsURLConnection;

/** Public release files only. Never uses API tokens, browser cookies or reply storage. */
public final class AppUpdateClient implements AutoCloseable {
    public static final String ROOT="https://ofekpc.co.il/downloads/whatsapp-autoreplay/";
    static final long MAX_APK=80L*1024*1024;
    public record Release(long code,String name,String url,String sha256,long size,String notes) {
        JSONObject json()throws Exception{return new JSONObject().put("versionCode",code).put("versionName",name).put("url",url).put("sha256",sha256).put("size",size).put("notes",notes).put("packageName",BuildConfig.APPLICATION_ID);}
        static Release parse(String text)throws Exception {
            JSONObject j=new JSONObject(text);
            if(!BuildConfig.APPLICATION_ID.equals(j.getString("packageName")))throw new IOException("העדכון מיועד לאפליקציה אחרת");
            long code=j.getLong("versionCode"),size=j.getLong("size");String name=j.getString("versionName"),url=j.getString("url"),sha=j.getString("sha256"),notes=j.optString("notes","");
            if(code<1||code>Integer.MAX_VALUE||size<1||size>MAX_APK||!name.matches("[0-9][a-zA-Z0-9.\\-]{0,63}")||!sha.matches("[a-fA-F0-9]{64}")||notes.length()>4000)throw new IOException("פרטי העדכון אינם תקינים");
            if(!url.matches("https://ofekpc\\.co\\.il/downloads/whatsapp-autoreplay/Whatsapp-Auto-replay-[a-zA-Z0-9.\\-]+\\.apk(?:\\.bin)?"))throw new IOException("כתובת העדכון אינה מורשית");
            return new Release(code,name,url,sha.toLowerCase(Locale.ROOT),size,notes);
        }
    }
    interface Response extends AutoCloseable { InputStream stream()throws IOException; long length(); void close(); }
    interface Transport { Response open(String url)throws IOException; }
    private final Context context;
    private final File directory;
    private final Transport transport;
    private volatile HttpsURLConnection active;
    private volatile boolean closed;
    public AppUpdateClient(Context context){this(context,null);}
    AppUpdateClient(Context context,Transport transport){this.context=context.getApplicationContext();this.directory=new File(context.getFilesDir(),"updates");this.transport=transport==null?this::open:transport;}
    private Response open(String url)throws IOException {
        if(closed)throw new IOException("ההורדה נעצרה");
        HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();active=c;
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setUseCaches(false);
        c.setRequestProperty("Cache-Control","no-cache");c.setRequestProperty("Accept-Encoding","identity");
        try { if(c.getResponseCode()!=200)throw new IOException("שרת העדכונים אינו זמין כרגע ("+c.getResponseCode()+")"); }
        catch(IOException e){c.disconnect();throw e;}
        return new Response(){public InputStream stream()throws IOException{return c.getInputStream();}public long length(){return c.getContentLengthLong();}public void close(){c.disconnect();if(active==c)active=null;}};
    }
    public long installedCode()throws Exception {return code(context.getPackageManager().getPackageInfo(context.getPackageName(),0));}
    static long code(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    public Release check()throws Exception {
        try(Response r=transport.open(ROOT+(BuildConfig.AUTOMATION_AVAILABLE?"latest.json":"latest-diagnostic.json")+"?check="+System.currentTimeMillis());InputStream in=r.stream()){
            if(r.length()>16384)throw new IOException("תשובת שרת העדכונים גדולה מדי");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while(buffer.size()<=16384&&(n=in.read(chunk,0,Math.min(chunk.length,16385-buffer.size())))!=-1)buffer.write(chunk,0,n);byte[] bytes=buffer.toByteArray();if(bytes.length>16384)throw new IOException("תשובת שרת העדכונים גדולה מדי");
            Release release=Release.parse(new String(bytes,StandardCharsets.UTF_8).replaceFirst("^\\uFEFF",""));
            ensureDirectory();File temporary=new File(directory,"latest-"+UUID.randomUUID()+".tmp");
            try{Files.write(temporary.toPath(),release.json().toString().getBytes(StandardCharsets.UTF_8));Files.move(temporary.toPath(),new File(directory,"latest.json").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{temporary.delete();}
            return release;
        }
    }
    public Release cached(){try{File file=new File(directory,"latest.json");if(file.length()>16384)return null;return Release.parse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception e){return null;}}
    private void ensureDirectory()throws IOException {if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("לא ניתן לשמור את העדכון בטלפון");}
    File apk(Release r){return new File(directory,"update-"+r.code()+"-"+r.sha256()+".apk");}
    public boolean downloaded(Release r){return apk(r).isFile();}
    public File download(Release r,IntConsumer progress)throws Exception {
        if(r.code()<=installedCode())throw new IOException("הגרסה הזו כבר מותקנת");
        ensureDirectory();File target=apk(r);
        if(target.isFile()){try{verifyFile(target,r);return target;}catch(Exception e){if(!target.delete())throw new IOException("לא ניתן להחליף קובץ עדכון פגום",e);}}
        if(directory.getUsableSpace()<r.size()+8L*1024*1024)throw new IOException("אין מספיק מקום פנוי להורדת העדכון");
        File temporary=new File(directory,"download-"+UUID.randomUUID()+".part");
        try(Response response=transport.open(r.url());InputStream in=response.stream()){
            if(response.length()>=0&&response.length()!=r.size())throw new IOException("גודל קובץ העדכון אינו תואם");
            try(OutputStream out=new FileOutputStream(temporary)){
                byte[] buffer=new byte[65536];long total=0;int n,last=-1;
                while((n=in.read(buffer))!=-1){if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("ההורדה נעצרה");total+=n;if(total>r.size())throw new IOException("קובץ העדכון גדול מהצפוי");out.write(buffer,0,n);int percent=(int)(total*100/r.size());if(percent!=last){progress.accept(percent);last=percent;}}
                if(total!=r.size())throw new IOException("ההורדה לא הושלמה. אפשר לנסות שוב");
            }
            verifyFile(temporary,r);
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return target;
        }finally{temporary.delete();}
    }
    static void verifyFile(File file,Release r)throws Exception {
        if(file.length()!=r.size())throw new IOException("גודל קובץ העדכון אינו תואם");
        MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)sha.update(b,0,n);}
        if(!hex(sha.digest()).equals(r.sha256()))throw new IOException("בדיקת שלמות העדכון נכשלה. יש להוריד מחדש");
    }
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    public File verifiedInstaller(Release r)throws Exception {
        File file=apk(r);
        try {
            verifyFile(file,r);
            int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
            PackageManager pm=context.getPackageManager();PackageInfo current=pm.getPackageInfo(context.getPackageName(),flags);
            PackageInfo candidate=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags);
            validateIdentity(current,candidate,r);
            return file;
        } catch(Exception e) {
            // Do not leave the UI stuck offering installation of a corrupt cached file.
            file.delete();throw e;
        }
    }
    static Set<String> signers(PackageInfo p)throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28?(p.signingInfo==null?null:p.signingInfo.getApkContentsSigners()):p.signatures;
        Set<String> result=new HashSet<>();if(signatures!=null)for(Signature s:signatures)result.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));return result;
    }
    static void validateIdentity(PackageInfo installed,PackageInfo candidate,Release r)throws Exception {
        if(candidate==null||!BuildConfig.APPLICATION_ID.equals(candidate.packageName)||!installed.packageName.equals(candidate.packageName)||code(candidate)!=r.code()||!r.name().equals(candidate.versionName)||code(candidate)<=code(installed))throw new IOException("זהות או גרסת קובץ ההתקנה אינן תקינות");
        Set<String> trusted=signers(installed);if(trusted.isEmpty()||!trusted.equals(signers(candidate)))throw new IOException("חתימת העדכון אינה תואמת לאפליקציה המותקנת");
    }
    @Override public void close(){closed=true;HttpsURLConnection c=active;if(c!=null)c.disconnect();}
}
