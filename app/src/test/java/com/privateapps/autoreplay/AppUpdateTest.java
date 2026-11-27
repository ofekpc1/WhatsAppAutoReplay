package com.privateapps.autoreplay;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.widget.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
public class AppUpdateTest {
    private final byte[] bytes="synthetic apk transfer".getBytes(StandardCharsets.UTF_8);
    private android.content.Context context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();File d=new File(context.getFilesDir(),"updates");if(d.isDirectory())for(File f:d.listFiles())f.delete();}
    private AppUpdateClient.Release release()throws Exception {return new AppUpdateClient.Release(7,"0.4.2",AppUpdateClient.ROOT+"Whatsapp-Auto-replay-0.4.2.apk.bin",AppUpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length,"תיקונים");}
    private AppUpdateClient.Response response(byte[] data,long length){return new AppUpdateClient.Response(){public InputStream stream(){return new ByteArrayInputStream(data);}public long length(){return length;}public void close(){}};}
    @Test public void manifestRejectsOtherHostsHttpPathsPackagesAndInvalidBounds()throws Exception {
        var base=release().json();
        for(String url:List.of("http://ofekpc.co.il/downloads/ofekstock/x.apk","https://evil.test/x.apk",AppUpdateClient.ROOT+"../x.apk",AppUpdateClient.ROOT+"Whatsapp-Auto-replay-a.apk?redirect=x","https://ofekpc.co.il@evil.test/x.apk")){
            JSONObject bad=new JSONObject(base.toString()).put("url",url);assertThrows(Exception.class,()->AppUpdateClient.Release.parse(bad.toString()));
        }
        for(JSONObject bad:List.of(new JSONObject(base.toString()).put("packageName","evil"),new JSONObject(base.toString()).put("size",AppUpdateClient.MAX_APK+1),new JSONObject(base.toString()).put("size",0),new JSONObject(base.toString()).put("sha256","bad"),new JSONObject(base.toString()).put("versionCode",0)))assertThrows(Exception.class,()->AppUpdateClient.Release.parse(bad.toString()));
    }
    @Test public void checkCachesMetadataAndFailureKeepsOfflineOffer()throws Exception {
        var r=release();byte[] json=r.json().toString().getBytes(StandardCharsets.UTF_8);List<String> urls=new ArrayList<>();
        try(var client=new AppUpdateClient(context,url->{urls.add(url);return response(json,-1);})){
            assertEquals(r,client.check());assertTrue(urls.get(0).startsWith(AppUpdateClient.ROOT+(BuildConfig.AUTOMATION_AVAILABLE?"latest.json":"latest-diagnostic.json")+"?check="));
        }
        try(var offline=new AppUpdateClient(context,url->{throw new IOException("offline");})){assertEquals(r,offline.cached());assertThrows(IOException.class,offline::check);assertEquals(r,offline.cached());}
    }
    @Test public void metadataIsBoundedEvenWithoutContentLength(){try(var c=new AppUpdateClient(context,url->response(new byte[16385],-1))){assertThrows(IOException.class,c::check);assertNull(c.cached());}}
    @Test public void successfulDownloadSurvivesReopenAndPreservesSettingsAndHistory()throws Exception {
        var r=release();List<Integer> progress=new ArrayList<>();
        var prefs=context.getSharedPreferences("settings",0);
        prefs.edit().putString("message","saved custom reply").commit();
        try(var store=new Store(context)){
            long id=store.log("synthetic","test","preserve history");
            try(var c=new AppUpdateClient(context,url->response(bytes,bytes.length))){c.download(r,progress::add);assertTrue(c.downloaded(r));AppUpdateClient.verifyFile(c.apk(r),r);}
            try(var c=new AppUpdateClient(context,url->{throw new IOException("must use saved file");})){assertTrue(c.download(r,pct->{}).isFile());}
            assertEquals(Integer.valueOf(100),progress.get(progress.size()-1));assertEquals("saved custom reply",prefs.getString("message",null));assertNotNull(store.row(id));assertEquals("preserve history",store.row(id).getDetail());
        }
    }
    @Test public void interruptedOversizedTruncatedOrCorruptDownloadsNeverBecomeInstallable()throws Exception {
        var r=release();
        for(byte[] invalid:List.of(Arrays.copyOf(bytes,bytes.length-1),Arrays.copyOf(bytes,bytes.length+1),new byte[bytes.length])){
            try(var c=new AppUpdateClient(context,url->response(invalid,-1))){assertThrows(IOException.class,()->c.download(r,p->{}));assertFalse(c.downloaded(r));}
        }
        try(var c=new AppUpdateClient(context,url->response(bytes,bytes.length+1))){assertThrows(IOException.class,()->c.download(r,p->{}));assertFalse(c.downloaded(r));}
        assertEquals(0,new File(context.getFilesDir(),"updates").listFiles((d,n)->n.endsWith(".part")).length);
    }
    @Test public void olderReleaseAndNonApkNeverReachInstaller()throws Exception {
        var r=release();try(var c=new AppUpdateClient(context,url->response(bytes,bytes.length))){
            var old=new AppUpdateClient.Release(c.installedCode(),r.name(),r.url(),r.sha256(),r.size(),r.notes());assertThrows(IOException.class,()->c.download(old,p->{}));
            c.download(r,p->{});assertThrows(IOException.class,()->c.verifiedInstaller(r));assertFalse(c.downloaded(r));
        }
    }
    private PackageInfo info(long code,String name,String signer){PackageInfo p=new PackageInfo();p.packageName=BuildConfig.APPLICATION_ID;p.setLongVersionCode(code);p.versionName=name;p.signingInfo=org.robolectric.util.ReflectionHelpers.callConstructor(SigningInfo.class);Shadows.shadowOf(p.signingInfo).setSignatures(new Signature[]{new Signature(signer.getBytes(StandardCharsets.UTF_8))});return p;}
    @Test public void samePackageNewerVersionAndExactSigningIdentityAreAllRequired()throws Exception {
        var r=release();var installed=info(6,"0.4.1","trusted");var good=info(7,r.name(),"trusted");AppUpdateClient.validateIdentity(installed,good,r);
        assertThrows(IOException.class,()->AppUpdateClient.validateIdentity(installed,info(7,r.name(),"other"),r));
        assertThrows(IOException.class,()->AppUpdateClient.validateIdentity(installed,info(6,r.name(),"trusted"),r));
        assertThrows(IOException.class,()->AppUpdateClient.validateIdentity(installed,info(7,"wrong","trusted"),r));
        good.packageName="another.app";assertThrows(IOException.class,()->AppUpdateClient.validateIdentity(installed,good,r));
    }
    @Test public void installerUsesContentUriReadOnlyAndCannotShareScanDatabase()throws Exception {
        File directory=new File(context.getFilesDir(),"updates");directory.mkdirs();File apk=new File(directory,"valid.apk");Files.write(apk.toPath(),bytes);
        // AndroidX FileProvider compares Unix separators; Windows Robolectric cannot resolve file URIs.
        var provider=context.getPackageManager().resolveContentProvider(context.getPackageName()+".updates",PackageManager.GET_META_DATA);assertNotNull(provider);assertFalse(provider.exported);assertTrue(provider.grantUriPermissions);
        try(var xml=provider.loadXmlMetaData(context.getPackageManager(),"android.support.FILE_PROVIDER_PATHS")){int paths=0;while(xml.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(xml.getEventType()==org.xmlpull.v1.XmlPullParser.START_TAG&&!"paths".equals(xml.getName())){paths++;assertEquals("files-path",xml.getName());assertEquals("updates/",xml.getAttributeValue(null,"path"));}assertEquals(1,paths);}
        Intent intent=File.separatorChar=='/'?AppUpdates.Companion.installerIntent(context,apk):AppUpdates.Companion.installerIntent(android.net.Uri.parse("content://"+context.getPackageName()+".updates/updates/valid.apk"));assertEquals("content",intent.getData().getScheme());assertEquals(context.getPackageName()+".updates",intent.getData().getAuthority());assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.getFlags());assertEquals("application/vnd.android.package-archive",intent.getType());
        assertThrows(IllegalArgumentException.class,()->AppUpdates.Companion.installerIntent(context,context.getDatabasePath("autoreplay.db")));
        assertThrows(IllegalArgumentException.class,()->AppUpdates.Companion.installerIntent(context,new File(context.getFilesDir(),"secret")));
    }
}
