package ro.cobrabm.fexdroid;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Log 32 launch profile. Experimental A/B switches are NOT user preferences. */
final class PortSettings {
    static final String[] EFFECTS={"ssao","motion_blur","depth_of_field","bloom","sunlight_shadows","subsurface","omni_light_shadows","spot_light_shadows"};
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("tlou",Context.MODE_PRIVATE);
    }

    // Existing player-selected game, driver, patch and resolution preferences survive upgrades.
    // Old experiment preferences cannot silently reactivate discarded modes.
    static void retireTestPreferences(Context context) {
        SharedPreferences.Editor edit=prefs(context).edit();
        for(String key:new String[]{"memory_walk_once","ring_binding_cursor","draw_counter_trim",
            "compute_perm_memo","host_copy_batch","srt_hot_readbacks","fetch_no_block",
            "land_on_guest","static_min_512","stack_mapped_readback","inline_single_copy",
            "split_cs64","mapped_fault_readbacks","mapped_readbacks","gpu_profile",
            "prep_pool","cpu_trims","prefetch_pipelines","parallel_pipelines",
            "prep_workers","frames_ahead","descriptor_bulk","readback","sotc_precise_narrow",
            "hud_gpu","hud_cpu",
            "compute_wait_fast","relaxed_skinning_fp"}) edit.remove(key);
        String limit=prefs(context).getString("fps_limit","60");
        if(prefs(context).getBoolean("fps_unlock",false)) edit.putString("fps_limit","120");
        else if(!"30".equals(limit) && !"40".equals(limit) && !"60".equals(limit) && !"120".equals(limit))
            edit.putString("fps_limit","60");
        // Fork: FEX-only switches have no effect with the native ARM64 CPU.
        for(String retired:new String[]{"fps_unlock","shader_scan_scalar",
                "aot_detailed_diagnostics","fast_render_cores","aot_gpu_prime_split_v2",
                "cpu_backend","adreno750_compact_present","pause_wfe","fex_no_tso",
                "fex_disk_cache","park_copy_waits","job_wake"}) edit.remove(retired);
        // Preserve an explicit old OFF preference while making Auto the default
        // for users who had the original always-visible default enabled.
        if(prefs(context).contains("touch_visible") &&
           !prefs(context).contains("touch_mode"))
            edit.putString("touch_mode",prefs(context).getBoolean("touch_visible",true)?"auto":"off");
        edit.remove("touch_visible");
        // Obsolete AOT profile preference must not affect the single ARM64 backend.
        edit.remove("aot_tso_profile");
        // The proxy/compatibility renderer was retired. Ignore old saved choices.
        edit.remove("render_path");
        edit.apply();
    }

    static File patchOptions(Context context) throws Exception {
        SharedPreferences p=prefs(context);
        JSONObject options=new JSONObject();
        // GPU-heavy proxy scaling kept the game native at 1080p and resolved
        // intermediate targets repeatedly. Direct 01.00 executable scaling
        // reduces ALL scene/post allocations; the existing Vulkan UI composer
        // expands the completed LDR background before drawing the HUD at 1080p.
        // "proxy" is the earlier fallback with separately allocated Vulkan
        // targets. The user has tested the direct-performance method at 720p
        // and original 1080p, NOT the proxy fallback in this build.
        // Never silently change the saved resolution.
        final String resolution=p.getString("resolution","original");
        options.put("resolution",resolution);
        // Generic 1.11 job-worker wait patch: not a hardware-specific workaround.
        // Preserve the existing CPU policy and FEX/AOT native wake behavior.
        options.put("job_sleep",true);
        // job_wake patched code into a cave the native ARM64 (AOT) library never compiled:
        // with the AOT CPU it stays off (upstream's v0.2.0 runs without it).
        options.put("job_wake",false);
        for(String key:EFFECTS) options.put(key,p.getBoolean(key,false));
        File file=new File(context.getFilesDir(),"patch-options.json");
        Files.write(file.toPath(),options.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    static void environment(Context context,Map<String,String> env) {
        environment(context,env,null);
    }
    // AOT geometry and movie paths must match the already-compiled guest
    // resolution. New visual preferences remain pending until native rebuild.
    static void environment(Context context,Map<String,String> env,String compiledAotResolution) {
        SharedPreferences p=prefs(context);
        String resolution=compiledAotResolution!=null?compiledAotResolution:
                p.getString("resolution","original");
        // The proven unified path is mandatory: no legacy proxy/composite mode.
        // Keep the original movie decoder, scale scene/HUD/aim as one domain.
        final String actualSize=sceneSize(resolution);
        env.put("TLOU_EXPECT_SCENE_SIZE","1920x1080");
        env.put("GOW3_UPSCALER","off");
        env.put("GOW3_OUTPUT_RES","1920x1080");
        env.remove("GOW3_RENDER_RES");
        env.remove("GOW3_UPSCALE_PRESET");
        env.remove("TLOU_SPATIAL_SCENE");
        env.remove("TLOU_HOST_SCENE_MODE");
        if(actualSize!=null) {
            env.put("TLOU_UNIFIED_GUEST_UI","1");
            env.put("TLOU_EXPECT_SCENE_SIZE",actualSize);
            env.put("TLOU_EXPECT_HOST_SCENE",actualSize);
            env.put("GOW3_MOVIE_SIZE",actualSize);
        } else {
            env.remove("TLOU_UNIFIED_GUEST_UI");
            env.remove("GOW3_MOVIE_SIZE");
            env.remove("TLOU_EXPECT_HOST_SCENE");
        }

        // AMD FSR 1.0 (spatial EASU + RCAS) in the presenter, opt-in. Below the
        // screen size EASU upscales the game's image; at the same size only
        // RCAS sharpens. Temporal upscalers stay off (see GameActivity).
        // Upscaler for the same switch: FSR 1 (EASU+RCAS) or Snapdragon GSR 1 (one pass).
        if(p.getBoolean("fsr",false)&&"sgsr".equals(p.getString("upscaler","fsr")))
            env.put("GOW3_SPATIAL_UPSCALER","sgsr");
        else env.remove("GOW3_SPATIAL_UPSCALER");
        if(p.getBoolean("fsr",false)) {
            env.put("GOW3_ANDROID_FSR","1");
            env.put("GOW3_FSR1","1");
            env.put("GOW3_RCAS","1");
            env.put("GOW3_RCAS_STRENGTH",fsrStrength(p.getString("fsr_sharpness","75")));
        } else {
            env.remove("GOW3_ANDROID_FSR");
            env.put("GOW3_FSR1","0");
            env.put("GOW3_RCAS","0");
            env.remove("GOW3_RCAS_STRENGTH");
        }

        // Exact flags observed in launch.log of TLOU-Android-Logs (32).zip.
        // Its preceding launch-previous.log used different v0.31 trial flags:
        // the more recent actual run had the three below OFF.
        env.put("TLOU_MEMORY_WALK_ONCE","1");
        env.put("TLOU_RING_BINDING_CURSOR","1");
        // Disable per-packet ARM64 clock barriers and shared stats atomics in normal gameplay.
        // CPU thread-load diagnostics use pthread clocks; full pipe stats remain opt-in.
        env.put("TLOU_DRAW_COUNTER_TRIM","1");
        // TLOU ZIP 30/31: exact memo saw 0 hits across >200k compute lookups/5s.
        // Retain the old stable default: avoid descriptor comparisons and memo bookkeeping.
        env.put("GOW3_TLOU_COMPUTE_MEMO","0");
        // Log 23: 1.09-1.12 copies/group, no host-copy wait improvement and
        // lower FPS than the previous build. Stop forcing the experimental
        // recording representation on in normal gameplay. The renderer still
        // implements GOW3_TLOU_HOST_COPY_BATCH=1 for isolated A/B experiments.
        env.put("GOW3_TLOU_HOST_COPY_BATCH","0");
        env.put("TLOU_SRT_HOT_READBACKS","1");
        env.put("TLOU_SRT_DEP_BATCH","0");
        env.put("GOW3_FETCH_NO_BLOCK","1");
        env.put("GOW3_LAND_ON_GUEST","1");
        env.put("GOW3_STATIC_MIN_BYTES","512");
        env.put("TLOU_STACK_MAPPED_READBACK","1");
        env.put("TLOU_INLINE_SINGLE_COPY","1");
        env.put("TLOU_SPLIT_CS64","1");
        env.put("TLOU_MAPPED_FAULT_READBACKS","1");
        env.put("TLOU_MAPPED_READBACKS","1");
        // 2026-11-13 CPU-bound gameplay: 850k post-draw calls and ~750k shader
        // scans every 5 s. Profiling these hot paths in a production session
        // distorts the saturated GPU-command thread even with sparse samples.
        // Keep the native scanner/NEON implementation unchanged and make its
        // diagnostics strictly opt-in via existing AOT detailed diagnostics.
        final boolean detailedAotDiagnostics = false;
        // Native phase sampler defaults ON when env is absent; force 0 here.
        env.put("TLOU_CPU_PHASE_SAMPLES",detailedAotDiagnostics?"1":"0");
        // The shader scanner profile checks env presence, not merely "1".
        if(detailedAotDiagnostics) env.put("TLOU_SHADER_SCAN_PROFILE","1");
        else env.remove("TLOU_SHADER_SCAN_PROFILE");
        env.put("TLOU_SHADER_SCAN_SCALAR","0");
        // Leave profiling truly disabled. Some native switches check presence,
        // so setting "0" instead of removing is incorrect.
        for(String name:new String[]{"GOW3_GPU_PROFILE","GOW3_FRAME_STATS","GOW3_PERF_DIAG"})
            env.remove(name);

        env.put("GOW3_READBACKS","1"); // Precise=2 caused artifacts; never resurrect.
        env.put("GOW3_EOP_READBACK","1"); // Selective readbacks, not Precise/Readback 2.
        // Readback 1 stays on at all levels. Every exact physics EOP result
        // already calls DownloadRangesAsync(..., protect=true, yield_to_cpu=true),
        // which read-protects its own pages without a global switch.
        // Protecting ALL other pending readbacks caused many unrelated page
        // faults and a sharp performance regression on Adreno 830.
        // Only shader-independent Full geometry synchronization is retained:
        // both user-provided 720p and native 1080p logs used Full, without the
        // previously suspected performance regression. No more Fast/Balanced
        // profiles which can return character deformation.
        env.put("TLOU_AUTO_SMALL_CS_READBACK","2");
        // Full geometry coverage remains enabled. Only the explicit EOP/physics
        // results are page-protected via DownloadRangesAsync(..., protect=true).
        // Global async protection also faults unrelated image and soft readbacks,
        // unlike the other Android ports, and needlessly serializes guest threads.
        // Important: do NOT disable GOW3_EOP_READBACK or downgrade Full coverage.
        env.remove("GOW3_ASYNC_READBACK");
        // Coverage audit was completed in earlier device logs. Do not run
        // its atomic identity tracking on the hot compute-binding path during
        // normal gameplay; an explicit native diagnostic can still enable it.
        env.remove("TLOU_PHYSICS_COVERAGE");
        env.put("GOW3_ASYNC_SHADERS","1");
        env.put("GOW3_FRAMES_AHEAD","0");
        env.put("TLOU_DESCRIPTOR_BULK","0");
        env.put("GOW3_PREP_POOL","1");
        env.put("GOW3_CPU_TRIMS","1");
        env.put("GOW3_PREFETCH_PIPELINES","1");
        env.put("GOW3_PARALLEL_PIPELINES","1");
        // TLOU: previously tested draw-preparation workers increased CPU
        // contention without raising FPS. Logs 19/20 still showed ~39 FPS
        // despite 99.9% hits with two workers. Restore the proven zero-worker
        // policy; do not keep the speculative normal-priority scanner or
        // per-5-second hit instrumentation running in gameplay.
        env.put("GOW3_PREP_WORKERS","0");
        // Release 0.2.0: do not constrain render threads to a user-chosen
        // fast-core subset. Oryon is detected from the SoC identifier; the
        // native AOT split policy still verifies the allowed CPU topology.
        env.put("TLOU_FAST_RENDER_CORES","0");
        String soc="";
        if(android.os.Build.VERSION.SDK_INT>=31) soc=android.os.Build.SOC_MODEL;
        final boolean oryon=TlouDevicePolicy.isOryonSoc(
                (soc==null?"":soc)+" "+android.os.Build.HARDWARE+" "+android.os.Build.BOARD);
        env.put("TLOU_AOT_SPLIT_PRIME",oryon?"1":"0");
        env.put("GOW3_ADPF", p.getBoolean("adpf",false) ? "1" : "0");
        // Stream buffer placement (A/B on Adreno): cached (default), device or host.
        String stream=p.getString("stream_memory","cached");
        env.put("GOW3_STREAM_MEMORY","device".equals(stream)||"host".equals(stream)?stream:"cached");
        // Shaders that read guest memory through raw 64-bit addresses (MUBUF ADDR64): Naughty Dog's
        // hair, beard and transparent shaders load their sun-shadow cascade matrices that way.
        // Without direct memory access those loads read the flat constant buffer instead, the
        // shadow lookups land in lit texels and hair glows golden in the dark.
        // GOW3_DMA_ADDR64 (emulator_settings.h) gives guest-memory reads to those loads only.
        // v0.1.7 used GOW3_DIRECT_MEMORY_ACCESS=1 (all dynamic reads, as the PC port): it
        // synchronized ~740 MB in 5 s at a cutscene start and the job system deadlocked.
        // DMA shaders sync 256 KiB windows around the guest addresses in their user data.
        env.remove("GOW3_DIRECT_MEMORY_ACCESS");
        if(p.getBoolean("hair_dma",false)) {
            env.put("GOW3_DMA_ADDR64","1");
            env.put("GOW3_DMA_SYNC","pointers 256");
        } else {
            env.remove("GOW3_DMA_ADDR64");
            env.remove("GOW3_DMA_SYNC");
        }
        // gow3_movie.cpp gow3_movie_scale_i420: one-pass NEON downscale of 1080p movies (v0.1.3).
        // Off = the original two swscale passes, to tell a movie problem from anything else.
        env.put("GOW3_MOVIE_AREA",p.getBoolean("movie_area",true)?"1":"0");
        // GPU command, draw recording and Vulkan recording threads at nice -4 (gow3_threads.h),
        // as the PC port's "above" priority: not queued behind the six busy job workers.
        if(p.getBoolean("gpu_thread_priority",true))env.put("GOW3_GPU_THREAD_PRIORITY","above");
        else env.remove("GOW3_GPU_THREAD_PRIORITY");
        // runtime_pad_bionic.c prints every pad-file change unless this is set (~14 lines/s).
        env.put("GOW3_PAD_QUIET","1");
        env.remove("TLOU_PREP_NORMAL_PRIORITY");
        env.remove("TLOU_PREP_HIT_STATS");
        env.put("GOW3_DEFERRED_READBACK","0");
        env.put("GOW3_STALE_READBACK","0");

        // 120 FPS is now a video setting, not a patch. Cinematics
        // retain their original cadence in the native VideoOut path.
        final boolean unlocked="120".equals(fpsLimit(context));
        env.put("GOW3_VBLANK_HZ",unlocked?"0":"60");
        env.put("GOW3_VSYNC",unlocked?"0":p.getBoolean("vsync",true)?"1":"0");
        env.put("GOW3_FPS_LIMIT",fpsLimit(context));
        // Render profile (Performance > Diagnostics): per-pass GPU timestamps and the CPU-side
        // frame stats in runtime.log, to find what to optimize next.
        if(p.getBoolean("render_profile",false)) {
            env.put("GOW3_GPU_PROFILE","1");
            env.put("GOW3_FRAME_STATS","1");
        } else {
            env.remove("GOW3_GPU_PROFILE");
            env.remove("GOW3_FRAME_STATS");
        }
    }

    static String experimentReport(Context context,Map<String,String> env) {
        StringBuilder result=new StringBuilder("TLOU launch settings: resolution=")
            .append(prefs(context).getString("resolution","original"));
        result.append("; renderer=Vulkan; CPU=native AOT ARM64");
        result.append("; shader-scan-diagnostic=").append(env.containsKey("TLOU_SHADER_SCAN_PROFILE")?"on":"off");
        result.append("; shader-scan-method=NEON");
        result.append("; game-raster=").append(env.getOrDefault("TLOU_EXPECT_SCENE_SIZE","1920x1080"));
        result.append("; game-ui=")
              .append(env.containsKey("TLOU_UNIFIED_GUEST_UI")
                      ?env.getOrDefault("TLOU_EXPECT_SCENE_SIZE","1920x1080")
                      :"1920x1080");
        result.append("; video-decoding=original; movie-plane-layout=")
              .append(env.getOrDefault("GOW3_MOVIE_SIZE","native"));
        result.append("; host-scene=")
              .append(env.getOrDefault("TLOU_EXPECT_HOST_SCENE","native"));
        result.append("; scene-composite=")
              .append(env.containsKey("TLOU_UNIFIED_GUEST_UI")?"unified-direct-no-ui-blit":
                      env.containsKey("TLOU_SPATIAL_SCENE")?"proxy-spatial-compat":"native");
        result.append("; spatial-fsr=")
              .append(env.containsKey("GOW3_ANDROID_FSR")
                      ?(env.containsKey("GOW3_SPATIAL_UPSCALER")?"sgsr1":"fsr1")
                       +" rcas="+env.getOrDefault("GOW3_RCAS_STRENGTH","75"):"off");
        result.append("; temporal-fsr=off");
        result.append("; frames-in-flight=").append(env.getOrDefault("GOW3_FRAMES_IN_FLIGHT","0"));
        result.append("; geometry-sync=full");
        result.append("; async-global-protect=").append(env.containsKey("GOW3_ASYNC_READBACK")?"on":"off");
        result.append("; geometry-sync policy=2 full only");
        result.append("; fps-limit=").append(fpsLimit(context));
        result.append("; effective-vsync=").append(env.getOrDefault("GOW3_VSYNC","?"));
        result.append("; virtual-vblank-hz=").append(env.getOrDefault("GOW3_VBLANK_HZ","?"));
        result.append("; adpf-native=").append(env.getOrDefault("GOW3_ADPF","0"));
        result.append("; fast-render-cores=").append(env.getOrDefault("TLOU_FAST_RENDER_CORES","0"));
        result.append("; aot-split-prime=").append(env.getOrDefault("TLOU_AOT_SPLIT_PRIME","0"));
        result.append("; compute-exact-memo=").append(env.getOrDefault("GOW3_TLOU_COMPUTE_MEMO","0"));
        result.append("; host-copy-batching=").append(env.getOrDefault("GOW3_TLOU_HOST_COPY_BATCH","0"));
        result.append("; guest-job-worker-wake=patched-sleep");
        result.append("; movie-area=").append(prefs(context).getBoolean("movie_area",true)?"on":"off");
        result.append("; hair-dma=").append(prefs(context).getBoolean("hair_dma",false)?"on":"off");
        result.append("; gpu-thread-priority=").append(prefs(context).getBoolean("gpu_thread_priority",true)?"above":"normal");
        result.append("; stream-memory=").append(prefs(context).getString("stream_memory","cached"));
        result.append("; render-profile=").append(prefs(context).getBoolean("render_profile",false)?"on":"off");
        return result.toString();
    }

    /** RCAS strength in percent; anything unexpected falls back to the default 75. */
    static String fsrStrength(String value) {
        return "25".equals(value)||"50".equals(value)||"90".equals(value)||"100".equals(value)?value:"75";
    }

    static String fpsLimit(Context context) {
        final String requested=prefs(context).getString("fps_limit","60");
        return "30".equals(requested)||"40".equals(requested)||"120".equals(requested)?requested:"60";
    }
    /** The game's image for a reduced resolution, null for Original (1080p). 900p and 828p
     *  (in place of 810p, whose halves are not whole) exist only for 01.11. */
    static String sceneSize(String resolution) {
        switch(resolution) {
            case "900": return "1600x900";
            case "828": return "1472x828";
            case "720": return "1280x720";
            case "540": return "960x540";
            case "360": return "640x360";
            default: return null;
        }
    }
    static String summary(Context context) {
        SharedPreferences p=prefs(context);
        String r=p.getString("resolution","original"), limit=fpsLimit(context);
        String res=r.equals("original")?"Original":r+"p";
        if(p.getBoolean("fsr",false))res+="sgsr".equals(p.getString("upscaler","fsr"))?" + SGSR":" + FSR";
        return res+"  ·  "+(limit+" FPS")+"  ·  "+
            ("120".equals(limit)?TlouLanguage.t(context,"VSync OFF (desbloqueio)"):
             p.getBoolean("vsync",true)?"VSync ON":"VSync OFF");
    }
}
