package ro.cobrabm.fexdroid;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.nio.file.Files;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Durable launch breadcrumbs exist before JNI, AHB or the FEX child is started. */
final class RunLog {
    static File launch(Context c) { return new File(c.getFilesDir(),"launch.log"); }
    static synchronized void begin(Context c) {
        File current=launch(c), previous=new File(c.getFilesDir(),"launch-previous.log");
        if(current.exists()) { previous.delete(); current.renameTo(previous); }
        write(c,"JOGAR • "+new Date()+" • Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+" • "+Build.MANUFACTURER+" "+Build.MODEL);
    }
    static synchronized void write(Context c,String message) {
        try(FileOutputStream out=new FileOutputStream(launch(c),true)) {
            out.write((new Date()+" ["+Thread.currentThread().getName()+"] "+message+"\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch(IOException e) { android.util.Log.e("TLOU","Cannot write launch log",e); }
    }
    static void failure(Context c,String label,Throwable error) {
        StringWriter trace=new StringWriter(); error.printStackTrace(new PrintWriter(trace));
        write(c,label+"\n"+trace);
    }
    static File runtime(Context c) { return new File(c.getFilesDir(),"tlou/runtime.log"); }
    static String tail(File file,int bytes) {
        if(!file.isFile())return "Ainda não criado.";
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            in.seek(Math.max(0,in.length()-bytes)); byte[] data=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(data);return new String(data,StandardCharsets.UTF_8);
        }catch(IOException e){return e.toString();}
    }
    static String summary(Context c) {
        return "INICIALIZAÇÃO\n"+tail(launch(c),10000)+"\n\nRUNTIME\n"+tail(runtime(c),10000)+
            "\n\nINSTALAÇÃO / PREPARAÇÃO\n"+tail(new File(c.getFilesDir(),"installation.log"),5000);
    }
    /** Crashed runs, kept with their date (runtime.log keeps only this run and the one before). */
    static File crashes(Context c) { return new File(c.getFilesDir(),"tlou/crashes"); }
    static final int KEPT_CRASHES=6;
    /** Copies this run's launch and runtime logs to tlou/crashes/crash-<date>-*.log, keeping the
     *  newest KEPT_CRASHES runs. */
    static synchronized void archiveCrash(Context c,String reason) {
        File dir=crashes(c);
        if(!dir.isDirectory()&&!dir.mkdirs())return;
        String stamp=new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",java.util.Locale.US).format(new Date());
        try {
            copyTail(runtime(c),new File(dir,"crash-"+stamp+"-runtime.log"),null);
            copyTail(launch(c),new File(dir,"crash-"+stamp+"-launch.log"),"Motivo: "+reason+"\n\n");
        } catch(IOException e) { android.util.Log.e("TLOU","Cannot archive crash log",e); }
        File[] files=dir.listFiles((d,name)->name.startsWith("crash-"));
        if(files==null)return;
        java.util.Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));
        for(int i=KEPT_CRASHES*2;i<files.length;i++)files[i].delete();
        write(c,"Log da falha salvo: crash-"+stamp);
    }
    private static void copyTail(File from,File to,String header) throws IOException {
        if(!from.isFile())return;
        try(RandomAccessFile in=new RandomAccessFile(from,"r");FileOutputStream out=new FileOutputStream(to)) {
            if(header!=null)out.write(header.getBytes(StandardCharsets.UTF_8));
            in.seek(Math.max(0,in.length()-4L*1024*1024));
            byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
        }
    }
    static void export(Context c,OutputStream output) throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            for(String path:new String[]{"launch.log","launch-previous.log","tlou/runtime.log","tlou/runtime-previous.log","installation.log","tlou-prepared/device-ready.json","tlou/perf-cs-22e6e19e.bin","tlou/perf-cs-61d67259.bin"}) {
                File file=new File(c.getFilesDir(),path);if(!file.isFile())continue;
                zip.putNextEntry(new ZipEntry(path));
                try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
                    in.seek(Math.max(0,in.length()-4L*1024*1024));byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)zip.write(bytes,0,n);
                }
                zip.closeEntry();
            }
            File[] crashed=crashes(c).listFiles((d,name)->name.startsWith("crash-"));
            if(crashed!=null) {
                java.util.Arrays.sort(crashed);
                for(File file:crashed) {
                    zip.putNextEntry(new ZipEntry("tlou/crashes/"+file.getName()));
                    Files.copy(file.toPath(),zip);
                    zip.closeEntry();
                }
            }
            zip.putNextEntry(new ZipEntry("android-process-exits.txt"));
            try {
                ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
                if(manager!=null)for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(c.getPackageName(),0,5)) {
                    String line=new Date(exit.getTimestamp())+" process="+exit.getProcessName()+" reason="+exit.getReason()+" status="+exit.getStatus()+" description="+exit.getDescription()+"\n";
                    zip.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch(RuntimeException e) { zip.write(e.toString().getBytes(StandardCharsets.UTF_8)); }
            zip.closeEntry();
        }
    }
}
