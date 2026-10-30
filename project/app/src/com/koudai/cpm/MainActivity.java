package com.koudai.cpm;

import android.app.Activity;
import android.app.ActivityManager;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 口袋CPM 测试版外壳。
 *
 * 模型随 APK 一起打包在 assets/models/ 下，首次启动释放到应用内部存储，
 * 之后直接复用（不会重复解包）。llama-server（arm64，打包为 jniLibs 里的
 * libllamaserver.so）在本地 127.0.0.1:8080 起服务，WebView 加载
 * assets/index.html 通过 HTTP 访问它。
 */
public class MainActivity extends Activity {

    private static final String TAG = "KoudaiCPM";
    private static final int PORT = 8080;

    /** assets 里的模型（打包进去的） */
    private static final String ASSET_MODEL = "models/MiniCPM-V-4_7-1B-Q4_K_M.gguf";
    private static final String ASSET_MMPROJ = "models/mmproj-4.7-patched.gguf";

    /** 释放到内部存储后的文件名 */
    private static final String MODEL_NAME = "MiniCPM-V-4_7-1B-Q4_K_M.gguf";
    private static final String MMPROJ_NAME = "mmproj-4.7-patched.gguf";

    /** 内置语音模型（SenseVoice 识别 + VITS 朗读） */
    private static final String ASSET_ASR_MODEL = "speech/asr/model.int8.onnx";
    private static final String ASSET_ASR_TOKENS = "speech/asr/tokens.txt";
    private static final String ASSET_TTS_MODEL = "speech/tts/model.onnx";
    private static final String ASSET_TTS_TOKENS = "speech/tts/tokens.txt";
    private static final String ASSET_TTS_LEXICON = "speech/tts/lexicon.txt";
    /** 朗读用的文本规整规则（随包内置，体积很小） */
    private static final String[] TTS_RULES = {"date.fst", "number.fst", "phone.fst", "new_heteronym.fst"};

    /**
     * 音色表：key -> speech/ 下的目录名。
     * 前两个随包内置（首次启动从 assets 解出），后四个来自「音色包」zip，
     * 导入后自动出现在音色列表里，不需要重新打包 APK。
     */
    private static final String[][] VOICES = {
            {"female", "tts"},
            {"male", "tts_m"},
            {"bronya", "tts_bronya"},
            {"theresa", "tts_theresa"},
            {"zzl", "tts_zzl"},
            {"doom", "tts_doom"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView status;
    private Process server;
    private volatile boolean destroyed = false;

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean gpuActive = false;
    private volatile boolean serviceStarted = false;
    /** 引擎是否已经就绪（退出时据此决定要不要保活）。 */
    private volatile boolean engineReady = false;

    private volatile int engineCtx = 8192;
    /** 是否给 KV cache 开 q8 量化（同等内存下装两倍上下文）。 */
    private volatile boolean engineQuantKv = true;
    private volatile int engineThreads = 4;

    /** 当前 TTS 音色目录，避免每次进 App 都把 116MB 朗读模型重载一遍。 */
    private static String voiceDirPath;
    /** 存档导出的临时缓冲（分片从 JS 送进来，避免一次跨进程传几 MB）。 */
    private final StringBuilder archiveBuf = new StringBuilder();
    /** 选中的存档内容（JS 也分片取）。 */
    private String archivePickText = null;

    /** WebView 里 <input type=file> 的回调 */
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private static final int REQ_PICK = 9001;
    private static final int REQ_ASR = 9002;
    private static final int REQ_MIC = 9003;
    private static final int REQ_ZIP = 9005;
    private static final int REQ_ARCHIVE = 9006;
    private volatile boolean pendingAsr = false;
    private volatile boolean recording = false;
    private volatile boolean importing = false;

    /** 引擎输出尾部（出错时显示） */
    private final java.util.List<String> logLines =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        status = new TextView(this);
        status.setTextSize(15f);
        status.setTextColor(0xFF333333);
        status.setGravity(Gravity.CENTER);
        status.setPadding(56, 56, 56, 56);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        initTts();
        askNotificationPermission();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boot();
                } catch (Throwable t) {
                    Log.e(TAG, "启动失败", t);
                    final String msg = String.valueOf(t);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("启动失败：\n" + msg);
                        }
                    });
                }
            }
        }, "boot").start();
    }

    /** 后台线程：释放模型 → 起服务 → 等就绪 → 显示界面 */
    private void boot() throws Exception {
        cleanTempFiles();
        File dir = new File(getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建模型目录：" + dir);
        }

        // 选模型档案：1b（默认）/ 35b。选中的不存在就退回另一个，都没有再引导导入。
        String want = modelPref();
        if (ID_35B.equals(want) && !canRun35b()) {
            Log.w(TAG, "当前机型内存不够跑 35B，回退 1B");
            want = ID_1B;
        }
        String id = want;
        if (!ready(new File(modelDir(want), modelFile(want)))) {
            String other = "35b".equals(want) ? "1b" : "35b";
            id = ready(new File(modelDir(other), modelFile(other))) ? other : want;
        }
        final File mdir = modelDir(id);
        if (!mdir.exists() && !mdir.mkdirs()) {
            throw new IOException("无法创建模型目录：" + mdir);
        }
        final File model = new File(mdir, modelFile(id));
        final File mmproj = new File(mdir, mmprojFile(id));
        Log.i(TAG, "使用的模型：" + id + " -> " + model.getAbsolutePath());

        // 0) 热启动：引擎进程还活着（前台服务保活），直接接上，不再重载 1.7GB 模型
        if (EngineHolder.alive() && healthy()
                && model.getAbsolutePath().equals(EngineHolder.model())) {
            long up = (System.currentTimeMillis() - EngineHolder.startedAt()) / 1000;
            Log.i(TAG, "引擎已在运行（" + up + " 秒），跳过模型加载");
            server = EngineHolder.get();
            gpuActive = EngineHolder.gpu();
            engineReady = true;
            ensureSpeech();
            startKeepAlive();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showWeb();
                }
            });
            return;
        }
        if (EngineHolder.alive()) {
            Log.i(TAG, "模型档案与运行中的引擎不一致，重启引擎");
            EngineHolder.stop();
        }

        // 1) 主模型（必需；只有 1B 有内置副本，35B 必须导入）
        if (!ready(model) && "1b".equals(id)) {
            setStatusAsync("首次启动，正在解出文本模型…\n（约 641MB）");
            if (!extractParts(ASSET_MODEL, model, "文本模型")) {
                showSetup(dir, model, mmproj);
                return;
            }
        }
        if (!ready(model)) {
            showSetup(mdir, model, mmproj);
            return;
        }
        // 2) 视觉投影器（可选，没有就走纯文本）
        if (!ready(mmproj) && "1b".equals(id)) {
            setStatusAsync("正在解出视觉模块…\n（约 1.06GB，第一次会慢一点）");
            extractParts(ASSET_MMPROJ, mmproj, "视觉模块");
        }
        boolean hasMmproj = ready(mmproj);

        // 3) 语音模型：识别 + 男女两套音色（已在本进程初始化过就跳过）
        ensureSpeech();

        boolean big = "35b".equals(id);
        // 1B：8192 上下文 + q8 量化的 KV cache，内存占用和原来 4096 差不多
        engineCtx = big ? 2048 : 8192;
        engineQuantKv = !big;
        engineThreads = big ? 6 : 4;
        setStatusAsync(big
                ? "正在加载 35B 模型…\n（首次要几十秒到几分钟，内存吃紧时会慢）"
                : "正在启动本地引擎…\n（视觉加速：GPU）");
        boolean ok = tryStart(model, hasMmproj ? mmproj : null, !big, big ? 300_000 : 75_000);

        if (!ok && !big && engineCtx > 4096) {
            // 上下文放大后内存不够（或 KV 量化不被支持）时，退回 4096 保证能用
            Log.w(TAG, "8192 上下文启动失败，退回 4096 重试");
            setStatusAsync("上下文占用偏高，正在改回默认再试…");
            engineCtx = 4096;
            engineQuantKv = false;   // 连量化一起关掉，最大限度保证能起来
            ok = tryStart(model, hasMmproj ? mmproj : null, true, 75_000);
        }

        if (!ok && hasMmproj && !big) {
            // GPU（OpenCL）在某些机型/系统策略下不可用，自动退回纯 CPU，功能不变只是慢
            setStatusAsync("GPU 加速不可用，正在改用 CPU 启动…\n（识别一张图约 7 分钟）");
            ok = tryStart(model, mmproj, false, 180_000);
        }

        if (ok) {
            engineReady = true;
            getSharedPreferences("koudai", MODE_PRIVATE).edit()
                    .putString("model", id).apply();
            startKeepAlive();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showWeb();
                }
            });
        } else {
            setStatusAsync("引擎启动失败。\n\n最近的引擎日志：\n" + serverTail());
        }
    }

    /** 起一次引擎，成功返回 true；进程提前退出视为失败。 */
    private boolean tryStart(File model, File mmproj, boolean gpu, long timeoutMs) {
        try {
            startServer(model, mmproj, gpu);
        } catch (Throwable t) {
            Log.e(TAG, "无法启动引擎进程", t);
            return false;
        }
        // 就绪前如果进程已经退出，就不必空等到超时
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!destroyed && System.currentTimeMillis() < deadline) {
            if (server == null || !server.isAlive()) {
                Log.w(TAG, "引擎进程已退出，判定启动失败");
                return false;
            }
            if (healthy()) {
                gpuActive = gpu;
                return true;
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private boolean ready(File f) {
        return f.exists() && f.length() > 1024 * 1024;
    }

    private boolean has(File f) {
        return f.exists() && f.length() > 0;
    }

    /** 清掉上次导入中断留下的 .tmp* 文件，避免占着空间。 */
    private void cleanTempFiles() {
        File[] roots = {new File(getFilesDir(), "models"), new File(getFilesDir(), "speech")};
        for (File root : roots) {
            cleanTempIn(root, 0);
        }
    }

    private void cleanTempIn(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 3) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                cleanTempIn(k, depth + 1);
            } else if (k.getName().contains(".tmp")) {
                if (k.delete()) Log.i(TAG, "清理残留临时文件 " + k.getName());
            }
        }
    }

    /** 释放语音模型并按需初始化（本进程里已经就绪的就不再重复加载）。 */
    private void ensureSpeech() {
        File sdir = new File(getFilesDir(), "speech");
        File asrDir = new File(sdir, "asr");
        if (!asrDir.exists()) asrDir.mkdirs();
        File asrModel = new File(asrDir, "model.int8.onnx");
        File asrTokens = new File(asrDir, "tokens.txt");

        if (!ready(asrModel)) {
            setStatusAsync("正在解出语音识别模型…\n（约 228 MB）");
            extractParts(ASSET_ASR_MODEL, asrModel, "语音识别模型");
        }
        if (!has(asrTokens)) extractParts(ASSET_ASR_TOKENS, asrTokens, "识别词表");

        File femaleDir = new File(sdir, "tts");
        File maleDir = new File(sdir, "tts_m");
        boolean femaleOk = prepareVoice(femaleDir, "speech/tts", "女声");
        boolean maleOk = prepareVoice(maleDir, "speech/tts_m", "男声");

        // 初始化语音引擎（失败只是没有语音，不影响对话）
        if (ready(asrModel) && has(asrTokens)) {
            if (!SpeechEngine.asrReady()) {
                setStatusAsync("正在准备离线语音…");
                SpeechEngine.initAsr(asrModel, asrTokens);
            }
            String want = voicePref();
            File voiceDir = voiceDirOf(want);
            if (voiceDir == null || !voiceReady(want)) {
                // 这个音色还没导入（音色包没装），退回内置女声
                voiceDir = femaleDir;
                if (!"female".equals(want)) {
                    Log.w(TAG, "音色 " + want + " 不可用，退回内置女声");
                    getSharedPreferences("koudai", MODE_PRIVATE).edit()
                            .putString("voice", "female").apply();
                }
            }
            if (ready(new File(voiceDir, "model.onnx"))) {
                String abs = voiceDir.getAbsolutePath();
                if (!SpeechEngine.ttsReady() || !abs.equals(voiceDirPath)) initVoice(voiceDir);
            }
        }
    }

    /** 释放一个音色的模型文件到内部存储（女声 tts / 男声 tts_m）。 */
    private boolean prepareVoice(File dir, String assetBase, String label) {
        if (!dir.exists() && !dir.mkdirs()) return false;
        File model = new File(dir, "model.onnx");
        File tokens = new File(dir, "tokens.txt");
        File lexicon = new File(dir, "lexicon.txt");
        if (!ready(model)) {
            setStatusAsync("正在解出语音朗读模型（" + label + "）…\n（约 116 MB）");
            extractParts(assetBase + "/model.onnx", model, label + "朗读模型");
        }
        if (!has(tokens)) extractParts(assetBase + "/tokens.txt", tokens, label + "词表");
        if (!has(lexicon)) extractParts(assetBase + "/lexicon.txt", lexicon, label + "词典");
        for (String n : TTS_RULES) {
            File f = new File(dir, n);
            if (!has(f)) extractParts(assetBase + "/" + n, f, label + "规则");
        }
        return ready(model) && has(tokens);
    }

    /** 某个音色的目录；未知 key 返回 null。 */
    private File voiceDirOf(String key) {
        if (key == null) return null;
        for (String[] v : VOICES) {
            if (v[0].equals(key)) return new File(new File(getFilesDir(), "speech"), v[1]);
        }
        return null;
    }

    /** 这个音色的模型齐不齐（内置的没导入时靠 assets 补齐）。 */
    private boolean voiceReady(String key) {
        File dir = voiceDirOf(key);
        if (dir == null) return false;
        return ready(new File(dir, "model.onnx")) && has(new File(dir, "tokens.txt"));
    }

    /** 用指定音色目录（重新）初始化朗读引擎。 */
    private boolean initVoice(File dir) {
        File model = new File(dir, "model.onnx");
        File tokens = new File(dir, "tokens.txt");
        File lexicon = new File(dir, "lexicon.txt");
        if (!ready(model) || !has(tokens)) return false;
        StringBuilder rules = new StringBuilder();
        for (String n : TTS_RULES) {
            File f = new File(dir, n);
            if (has(f)) {
                if (rules.length() > 0) rules.append(',');
                rules.append(f.getAbsolutePath());
            }
        }
        SpeechEngine.releaseTts();
        boolean ok = SpeechEngine.initTts(model, tokens, lexicon, rules.toString());
        if (ok) voiceDirPath = dir.getAbsolutePath();
        return ok;
    }

    private String voicePref() {
        return getSharedPreferences("koudai", MODE_PRIVATE).getString("voice", "female");
    }

    /** 退出界面时是否保留引擎（默认保留，秒开；可在设置里关掉换取内存）。 */
    private boolean keepWarm() {
        return getSharedPreferences("koudai", MODE_PRIVATE).getBoolean("warm", true);
    }

    /* ---------- 模型档案：1b / 35b ---------- */

    private static final String ID_1B = "1b";
    private static final String ID_35B = "35b";

    private String modelPref() {
        return getSharedPreferences("koudai", MODE_PRIVATE).getString("model", ID_1B);
    }

    private File modelDir(String id) {
        return new File(getFilesDir(), ID_35B.equals(id) ? "models35" : "models");
    }

    private String modelFile(String id) {
        return ID_35B.equals(id)
                ? "MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf"
                : MODEL_NAME;
    }

    private String mmprojFile(String id) {
        return ID_35B.equals(id) ? "mmproj-model-f16.gguf" : MMPROJ_NAME;
    }

    /** 某个档案的模型是否齐（主模型必须有）。 */
    private boolean modelExists(String id) {
        return ready(new File(modelDir(id), modelFile(id)));
    }

    /** 物理内存（字节）。ActivityManager 的 totalMem 在本机型会把 zram 算进去，改用 /proc/meminfo。 */
    private long totalRamBytes() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return 0;
    }

    /** 35B 需要 ~15.5GB 常驻，实测 12GB 机型加载会拖到系统重启，这里直接挡掉。 */
    private boolean canRun35b() {
        long ram = totalRamBytes();
        boolean ok = ram >= 15L * 1024 * 1024 * 1024;
        if (!ok) Log.w(TAG, "内存不足，禁用 35B：MemTotal=" + (ram / 1073741824.0) + " GB");
        return ok;
    }

    /**
     * 把 assets 里按 400MB 分片的模型（name.000 / name.001 …）顺序拼接还原成 dst。
     * 分片是为了避开单 asset 过大时 AssetManager 的读取问题。
     */
    private boolean extractParts(String baseAsset, File dst, String label) {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        FileOutputStream out = null;
        long written = 0;
        int parts = 0;
        try {
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[1024 * 1024];
            for (int i = 0; i < 1000; i++) {
                String asset = baseAsset + "." + String.format("%03d", i);
                InputStream in;
                try {
                    in = getAssets().open(asset);
                } catch (IOException e) {
                    break; // 没有后续分片了
                }
                parts++;
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        written += n;
                        if (written % (64L * 1024 * 1024) < 1024 * 1024) {
                            setStatusAsync("正在解出" + label + "…\n已写入 "
                                    + (written / 1048576) + " MB");
                        }
                    }
                } finally {
                    in.close();
                }
            }
            out.flush();
            out.close();
            out = null;

            if (parts == 0) {
                Log.w(TAG, "assets 里没有 " + baseAsset);
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(dst)) {
                Log.w(TAG, "重命名失败，保留 .part 文件");
                return tmp.length() > 0;
            }
            Log.i(TAG, label + " 解出完成：" + dst + " " + parts + " 片 " + written + " 字节");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "解包失败 " + baseAsset, t);
            return false;
        } finally {
            try {
                if (out != null) out.close();
            } catch (IOException ignore) {
            }
        }
    }

    /** assets 和内部存储都没有模型时的兜底引导页。 */
    private void showSetup(final File dir, File model, File mmproj) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                ScrollView sv = new ScrollView(MainActivity.this);
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(56, 96, 56, 56);

                TextView title = new TextView(MainActivity.this);
                title.setText("还没有模型文件");
                title.setTextSize(22f);
                title.setTextColor(0xFF111111);
                box.addView(title);

                TextView body = new TextView(MainActivity.this);
                body.setTextSize(14f);
                body.setTextColor(0xFF444444);
                body.setPadding(0, 24, 0, 0);
                body.setText("这个安装包不含模型，需要导入模型包（.zip）。\n\n"
                        + "模型包里有文本模型、视觉模块和语音模型，"
                        + "导入后会自动解压到应用内部存储，之后不再需要它。\n\n"
                        + "也可以手动把文件放进：\n" + dir.getAbsolutePath());
                box.addView(body);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = 48;

                Button importBtn = new Button(MainActivity.this);
                importBtn.setText("导入模型包（.zip）");
                importBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickModelZip();
                    }
                });
                box.addView(importBtn, lp);

                Button retry = new Button(MainActivity.this);
                retry.setText("重新检测");
                retry.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        recreate();
                    }
                });
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp2.topMargin = 16;
                box.addView(retry, lp2);

                sv.addView(box);
                root.removeAllViews();
                root.addView(sv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        });
    }

    /* ==================== 模型包导入（zip） ==================== */

    /** 让用户挑一个模型 zip。 */
    /** 把 JSON 存档写到公共下载目录下的「口袋CPM」文件夹；返回 ok:路径 或 err:原因。 */
    private String writeArchive(String json, String name) {
        try {
            String base = name == null ? "" : name.trim();
            if (base.isEmpty()) {
                base = "koudai-存档-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
            }
            if (!base.toLowerCase(Locale.US).endsWith(".json")) base += ".json";
            byte[] bytes = json.getBytes("UTF-8");

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, base);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/口袋CPM");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "err:系统没给出可写位置";
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return "err:打不开写入通道";
                try {
                    os.write(bytes);
                    os.flush();
                } finally {
                    os.close();
                }
                Log.i(TAG, "存档已写入 " + uri + "（" + bytes.length + " 字节）");
                return "ok:" + base;
            }

            // Android 9 及以下：直接写公共 Download（需要外部存储权限，已在清单里）
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "口袋CPM");
            if (!dir.exists() && !dir.mkdirs()) return "err:无法创建存档目录";
            File f = new File(dir, base);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(bytes);
                fos.flush();
            } finally {
                fos.close();
            }
            return "ok:" + f.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "存档失败", t);
            return "err:" + t.getMessage();
        }
    }

    private void pickArchive() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_ARCHIVE);
        } catch (Throwable t) {
            Log.e(TAG, "打不开文件选择器", t);
            jsArchiveReady("打不开文件选择器");
        }
    }

    /** 通知页面：存档读好了（err 为 null 表示成功，“cancel” 表示用户取消）。 */
    private void jsArchiveReady(final String err) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                String arg = err == null ? "null" : JSONObject.quote(err);
                web.evaluateJavascript("window.onArchiveReady && window.onArchiveReady(" + arg + ")", null);
            }
        });
    }

    private void pickModelZip() {
        if (importing) {
            Toast.makeText(this, "正在导入中，等这次结束再选", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_ZIP);
        } catch (Throwable t) {
            Log.e(TAG, "打不开文件选择器", t);
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 导入期间把界面切成一个居中的进度页（只在还没进 WebView 时用）。 */
    private void showProgressScreen() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                box.setBackgroundColor(Color.WHITE);
                android.view.ViewParent p = status.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(status);
                status.setGravity(Gravity.CENTER);
                status.setTextSize(16f);
                status.setPadding(56, 56, 56, 56);
                box.addView(status, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                root.removeAllViews();
                root.addView(box, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        });
    }

    private void importZip(final Uri uri) {
        if (importing) {
            Toast.makeText(this, "已经在导入了，请等这次结束", Toast.LENGTH_SHORT).show();
            return;
        }
        importing = true;
        if (web == null) showProgressScreen();   // 还没进 WebView 时，进度要占满屏才看得见
        setStatusAsync("正在导入模型包…\n（解压约 2GB，别退出、别锁屏）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err;
                try {
                    err = doImport(uri);
                } catch (Throwable t) {
                    Log.e(TAG, "导入出错", t);
                    err = String.valueOf(t.getMessage());
                }
                final String e = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (e == null) {
                            Toast.makeText(MainActivity.this, "模型包导入完成",
                                    Toast.LENGTH_LONG).show();
                            recreate();
                        } else {
                            importing = false;
                            setStatus("导入失败：" + e);
                            Toast.makeText(MainActivity.this, "导入失败：" + e,
                                    Toast.LENGTH_LONG).show();
                            if (web != null) {
                                web.evaluateJavascript("document.getElementById('importMask')"
                                        + " && document.getElementById('importMask')"
                                        + ".classList.remove('show')", null);
                            }
                        }
                    }
                });
            }
        }, "import").start();
    }

    /** 流式解压模型包；成功返回 null，失败返回原因。 */
    private String doImport(Uri uri) throws Exception {
        long totalZip = 0;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) totalZip = c.getLong(0);
        } catch (Throwable ignore) {
        }

        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) return "读不到这个文件";
        CountingStream cin = new CountingStream(raw);
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(cin, 1 << 16));
        byte[] buf = new byte[1 << 20];
        org.json.JSONObject manifest = null;
        final java.util.Map<String, Long> expected = new java.util.HashMap<String, Long>();
        int lastPct = -1;
        int extracted = 0;
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals("manifest.json")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    int n;
                    while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                    manifest = new org.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
                    String space = checkSpace(manifest);
                    if (space != null) return space;
                    org.json.JSONArray mf = manifest.optJSONArray("files");
                    for (int i = 0; mf != null && i < mf.length(); i++) {
                        org.json.JSONObject f = mf.getJSONObject(i);
                        expected.put(f.optString("path"), f.optLong("size"));
                    }
                    continue;
                }
                if (!name.startsWith("models/") && !name.startsWith("models35/")
                        && !name.startsWith("speech/")) continue;
                if (name.contains("..")) continue;

                File out = new File(getFilesDir(), name);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return "无法创建目录：" + parent;
                }

                // 已经存在且大小正确就跳过写入（重导入时只补缺的部分，省时间也少写盘）
                boolean skip = false;
                if (manifest != null) {
                    Long want = expected.get(name);
                    if (want != null && out.exists() && out.length() == want) skip = true;
                }

                if (skip) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        // 丢弃：只为把流推进到下一个条目
                    }
                } else {
                    File tmp = new File(out.getPath() + ".tmp" + System.nanoTime());
                    FileOutputStream fos = new FileOutputStream(tmp);
                    try {
                        int n;
                        while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                        fos.flush();
                    } catch (Throwable t) {
                        try {
                            fos.close();
                        } catch (Throwable ignore) {
                        }
                        tmp.delete();
                        throw t;
                    }
                    fos.close();
                    if (out.exists() && !out.delete()) {
                        tmp.delete();
                        return "无法覆盖：" + name;
                    }
                    if (!tmp.renameTo(out)) {
                        tmp.delete();
                        return "写入失败：" + name;
                    }
                    extracted++;
                }

                if (totalZip > 0) {
                    int pct = (int) (cin.count() * 100 / totalZip);
                    if (pct / 2 != lastPct / 2) {
                        lastPct = pct;
                        setStatusAsync("正在导入模型包… " + pct + "%\n（解压约 2GB，别退出、别锁屏）");
                    }
                }
            }
        } finally {
            try {
                zin.close();
            } catch (Throwable ignore) {
            }
        }

        if (manifest == null) return "包里没有 manifest.json，不是有效的模型包";
        org.json.JSONArray files = manifest.optJSONArray("files");
        for (int i = 0; files != null && i < files.length(); i++) {
            org.json.JSONObject f = files.getJSONObject(i);
            String path = f.optString("path");
            long size = f.optLong("size");
            File out = new File(getFilesDir(), path);
            if (!out.exists() || out.length() != size) {
                return "校验失败：" + path + "（" + (out.exists() ? out.length() : 0)
                        + " / " + size + " 字节）";
            }
        }
        Log.i(TAG, "模型包导入完成，解出 " + extracted + " 个文件，清单 "
                + (files == null ? 0 : files.length()) + " 个");
        return null;
    }

    /** 按 manifest 预估需要多少空间。 */
    private String checkSpace(org.json.JSONObject manifest) {
        try {
            org.json.JSONArray files = manifest.optJSONArray("files");
            long need = 0;
            for (int i = 0; files != null && i < files.length(); i++) {
                need += files.getJSONObject(i).optLong("size");
            }
            StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
            long free = fs.getAvailableBytes();
            if (need > 0 && free < need + 200L * 1024 * 1024) {
                return "空间不足：需要 " + (need / 1048576) + " MB，可用 " + (free / 1048576) + " MB";
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 统计已读取字节数，用来算导入进度。 */
    private static class CountingStream extends java.io.FilterInputStream {
        private long count = 0;

        CountingStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    private void startServer(final File model, final File mmproj, boolean gpu) throws IOException {
        String libDir = getApplicationInfo().nativeLibraryDir;
        java.util.List<String> cmd = new java.util.ArrayList<String>();
        cmd.add(libDir + "/libllamaserver.so");
        cmd.add("-m");
        cmd.add(model.getAbsolutePath());
        if (mmproj != null) {
            cmd.add("-mm");
            cmd.add(mmproj.getAbsolutePath());
        }
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(PORT));
        cmd.add("-c");
        cmd.add(String.valueOf(engineCtx));
        if (engineQuantKv) {
            // 把 KV cache 从 f16 压到 q8：上下文翻倍，内存几乎不变
            cmd.add("--cache-type-k");
            cmd.add("q8_0");
            cmd.add("--cache-type-v");
            cmd.add("q8_0");
        }
        cmd.add("-t");
        cmd.add(String.valueOf(engineThreads));
        // 混合加速：LLM 留在 CPU（生成最快），仅视觉编码器 offload 到 Adreno GPU（OpenCL）
        cmd.add("-ngl");
        cmd.add("0");
        if (mmproj != null && gpu) {
            cmd.add("--mmproj-offload");
        }
        Log.i(TAG, "启动参数: " + cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(getFilesDir());
        pb.redirectErrorStream(true);
        // 子进程的自然命名空间看不到厂商库，必须显式把 /vendor/lib64 加进搜索路径，
        // 否则 libggml-opencl.so 会因找不到 libOpenCL.so 而链接失败（App 自身的
        // uses-native-library 声明只作用于进程内，不传递给 exec 出来的子进程）。
        pb.environment().put("LD_LIBRARY_PATH", libDir + ":/vendor/lib64:/system/lib64");
        synchronized (logLines) {
            logLines.clear();
        }
        server = pb.start();
        EngineHolder.put(server, gpu, model.getAbsolutePath(),
                mmproj == null ? null : mmproj.getAbsolutePath());
        drain(server.getInputStream());
    }

    /** 单次健康检查。 */
    private boolean healthy() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + PORT + "/health").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 引擎日志尾部，出错时显示出来便于定位。 */
    private String serverTail() {
        StringBuilder sb = new StringBuilder();
        synchronized (logLines) {
            int from = Math.max(0, logLines.size() - 12);
            for (int i = from; i < logLines.size(); i++) {
                sb.append(logLines.get(i)).append('\n');
            }
        }
        return sb.length() == 0 ? "(没有输出)" : sb.toString();
    }

    /** 把引擎输出打到 logcat（并留最后若干行给界面显示），避免管道写满阻塞。 */
    private void drain(final InputStream in) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i(TAG + "/srv", line);
                        synchronized (logLines) {
                            logLines.add(line);
                            while (logLines.size() > 200) {
                                logLines.remove(0);
                            }
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
        }, "drain").start();
    }

    private void showWeb() {
        if (destroyed) return;
        WebView v = new WebView(this);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        v.setBackgroundColor(Color.WHITE);
        // 只放行自家页面和本地引擎，其它地址一律不跳。
        // 否则一旦页面里能点到外部链接，远端页面会拿到 Koudai 这个 JS 接口
        // （可以直接调用分享、导入、朗读等原生能力）。
        v.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return blockUrl(req == null ? null : req.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockUrl(url);
            }

            private boolean blockUrl(String url) {
                if (url == null) return true;
                if (url.startsWith("file:///android_asset/")) return false;
                if (url.startsWith("http://127.0.0.1:8080") || url.startsWith("http://localhost:8080")) return false;
                if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return false;
                Log.w(TAG, "拦截外部跳转：" + url);
                return true;
            }
        });
        v.addJavascriptInterface(new Bridge(), "Koudai");
        // 接手页面里的 <input type=file>，否则点「+」加图片毫无反应
        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                String[] accept = params != null ? params.getAcceptTypes() : null;
                boolean imageOnly = true;
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && !a.trim().isEmpty() && !a.contains("image")) {
                            imageOnly = false;
                        }
                    }
                }
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(imageOnly ? "image/*" : "*/*");
                Intent chooser = Intent.createChooser(i, "选择图片");
                if (imageOnly) {
                    // 加一个「拍照」入口：输出走自己的 content provider，避免 file:// 被系统拒绝
                    try {
                        File shots = new File(getCacheDir(), "shots");
                        if (!shots.exists()) shots.mkdirs();
                        File out = new File(shots, "shot_" + System.currentTimeMillis() + ".jpg");
                        cameraUri = ShotProvider.uriFor(out);
                        Intent cap = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        cap.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        cap.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cap});
                    } catch (Throwable t) {
                        Log.w(TAG, "拍照入口不可用", t);
                    }
                }
                try {
                    startActivityForResult(chooser, REQ_PICK);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "打开选图器失败", e);
                    filePathCallback = null;
                    return false;
                }
            }
        });
        web = v;
        v.loadUrl("file:///android_asset/index.html");

        root.removeAllViews();
        root.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void setStatusAsync(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                status.setText(text);
                if (importing && web != null) {
                    web.evaluateJavascript("window.onImportStatus && onImportStatus("
                            + JSONObject.quote(text) + ")", null);
                }
            }
        });
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                Uri picked = data != null ? data.getData() : null;
                if (picked == null && cameraUri != null) {
                    picked = cameraUri;   // 拍照回来时 data 是 null，结果在 EXTRA_OUTPUT
                }
                if (picked != null) {
                    result = new Uri[]{picked};
                    Log.i(TAG, "用户选了图片：" + picked);
                }
            }
            cameraUri = null;
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        if (requestCode == REQ_ZIP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri zip = data.getData();
                Log.i(TAG, "选了模型包：" + zip);
                try {
                    // 长期读权限，避免导入过程中被回收
                    getContentResolver().takePersistableUriPermission(zip,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignore) {
                }
                importZip(zip);
            }
            return;
        }
        if (requestCode == REQ_ARCHIVE) {
            archivePickText = null;
            String err = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    Uri u = data.getData();
                    try {
                        getContentResolver().takePersistableUriPermission(u,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignore) {
                    }
                    InputStream in = getContentResolver().openInputStream(u);
                    if (in == null) {
                        err = "读不到这个文件";
                    } else {
                        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        char[] buf = new char[8192];
                        int n;
                        int total = 0;
                        while ((n = br.read(buf)) > 0) {
                            sb.append(buf, 0, n);
                            total += n;
                            if (total > 40 * 1024 * 1024) break;   // 存档不该超过 40MB
                        }
                        br.close();
                        archivePickText = sb.toString();
                        Log.i(TAG, "读到存档，长度 " + total);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "读取存档失败", t);
                    err = "读取失败：" + t.getMessage();
                }
            } else {
                err = "cancel";
            }
            jsArchiveReady(err);
            return;
        }
        if (requestCode == REQ_ASR) {
            String text = null;
            String err = null;
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (list != null && !list.isEmpty()) {
                    text = list.get(0);
                }
            }
            if (text == null || text.trim().isEmpty()) {
                err = "没有听清，再试一次";
            }
            jsAsr(text, err);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ==================== 原生能力（供 WebView 调用） ==================== */

    /** JS 桥：页面里通过 window.Koudai.xxx() 调用。 */
    private class Bridge {

        @JavascriptInterface
        public String deviceInfo() {
            return deviceInfoJson();
        }

        @JavascriptInterface
        public boolean ttsReady() {
            return SpeechEngine.ttsReady() || ttsAvailable();
        }

        /** 退出后是否保留引擎（省内存 1.7GB vs 下次秒开）。 */
        @JavascriptInterface
        public boolean getWarm() {
            return keepWarm();
        }

        @JavascriptInterface
        public void setWarm(boolean on) {
            // 只改下次退出时的行为；本次退出由 onDestroy 统一处理，避免聊天中途引擎被掐
            getSharedPreferences("koudai", MODE_PRIVATE).edit()
                    .putBoolean("warm", on).apply();
            Log.i(TAG, "退出后保留引擎=" + on);
        }

        @JavascriptInterface
        public void speak(final String text) {
            if (text == null || text.trim().isEmpty()) return;
            // 优先用内置 VITS（完全离线、音色统一）；没准备好再退回系统朗读
            if (SpeechEngine.ttsReady()) {
                SpeechEngine.speak(text, 1.0f);
                return;
            }
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (tts == null || !ttsReady) {
                        Toast.makeText(MainActivity.this,
                                "语音朗读模型还没准备好", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "koudai");
                }
            });
        }

        @JavascriptInterface
        public void stopSpeak() {
            SpeechEngine.stop();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (tts != null) tts.stop();
                }
            });
        }

        /** 切换朗读音色："female" / "male"，返回是否切换成功。 */
        @JavascriptInterface
        public boolean setVoice(String v) {
            File dir = voiceDirOf(v);
            if (dir == null) {
                Log.w(TAG, "未知音色：" + v);
                return false;
            }
            if (SpeechEngine.ttsReady() && dir.getAbsolutePath().equals(voiceDirPath)) {
                // 已经是这个音色了，别白等一次 1 秒的模型加载
                getSharedPreferences("koudai", MODE_PRIVATE).edit()
                        .putString("voice", v).apply();
                return true;
            }
            boolean ok = initVoice(dir);
            if (ok) {
                getSharedPreferences("koudai", MODE_PRIVATE).edit()
                        .putString("voice", v).apply();
            }
            Log.i(TAG, "切换音色 " + v + " 成功=" + ok);
            return ok;
        }

        @JavascriptInterface
        public void startAsr() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    startAsrInternal();
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            if (text == null || text.isEmpty()) return;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(i, "分享"));
                    } catch (Throwable t) {
                        Log.w(TAG, "分享失败", t);
                    }
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            if (text == null) return;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardManager cm =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("口袋CPM", text));
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "复制失败", t);
                    }
                }
            });
        }

        @JavascriptInterface
        public void restartEngine() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    restartEngineInternal();
                }
            });
        }

        /** 从设置页导入模型包。 */
        @JavascriptInterface
        public void importModels() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    pickModelZip();
                }
            });
        }

        /** 切换对话模型："1b" / "35b"，返回是否已导入。 */
        @JavascriptInterface
        public boolean setModel(String id) {
            String v = ID_35B.equals(id) ? ID_35B : ID_1B;
            if (ID_35B.equals(v) && !canRun35b()) {
                Log.w(TAG, "拒绝切到 35B：内存不足");
                return false;
            }
            if (!modelExists(v)) {
                Log.w(TAG, "切换模型失败，未导入：" + v);
                return false;
            }
            getSharedPreferences("koudai", MODE_PRIVATE).edit()
                    .putString("model", v).apply();
            Log.i(TAG, "切换模型到 " + v);
            restartEngineInternal();
            return true;
        }

        /** 存档：archiveStart → 多次 archiveAppend → archiveFinish 落盘。 */
        @JavascriptInterface
        public void archiveStart() {
            archiveBuf.setLength(0);
        }

        @JavascriptInterface
        public void archiveAppend(String part) {
            if (part != null) archiveBuf.append(part);
        }

        @JavascriptInterface
        public String archiveFinish(String name) {
            String json = archiveBuf.toString();
            archiveBuf.setLength(0);
            return writeArchive(json, name);
        }

        /** 让用户挑一个存档文件，读完通过 window.onArchiveReady 通知页面。 */
        @JavascriptInterface
        public void importArchive() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    pickArchive();
                }
            });
        }

        @JavascriptInterface
        public int archiveLength() {
            return archivePickText == null ? 0 : archivePickText.length();
        }

        @JavascriptInterface
        public String archiveChunk(int off, int len) {
            if (archivePickText == null) return "";
            int from = Math.max(0, off);
            int to = Math.min(archivePickText.length(), from + Math.max(0, len));
            if (from >= to) return "";
            return archivePickText.substring(from, to);
        }

        @JavascriptInterface
        public String engineLog() {
            return serverTail();
        }
    }

    /** 内置离线识别：录一段话 → SenseVoice 转文字。 */
    private void startAsrInternal() {
        if (recording) {
            // 再来一次就是"说完了"，提前结束录音
            SpeechEngine.cancelRecording();
            return;
        }
        if (!SpeechEngine.asrReady()) {
            jsAsr(null, "语音识别模型还没准备好");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAsr = true;
            try {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } catch (Throwable t) {
                pendingAsr = false;
                jsAsr(null, "申请麦克风权限失败");
            }
            return;
        }
        startRecordTask();
    }

    private void startRecordTask() {
        recording = true;
        jsAsrState(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File wav = new File(getCacheDir(), "last_asr.wav");
                    float[] pcm = SpeechEngine.record(20_000, 1_000, wav);
                    recording = false;
                    jsAsrState(false);
                    if (pcm == null) {
                        jsAsr(null, "没听到声音，再说一次");
                        return;
                    }
                    if (SpeechEngine.lastPeak < 0.012) {
                        jsAsr(null, "声音太小了，靠近麦克风再说一次");
                        return;
                    }
                    String text = SpeechEngine.recognize(pcm);
                    if (text == null || text.trim().isEmpty()) {
                        jsAsr(null, "没听清，再说一次");
                    } else {
                        jsAsr(text.trim(), null);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "语音识别流程失败", t);
                    recording = false;
                    jsAsrState(false);
                    jsAsr(null, "识别失败：" + t.getMessage());
                }
            }
        }, "asr").start();
    }

    /** 通知页面录音状态：window.onAsrState(listening)。 */
    private void jsAsrState(final boolean listening) {
        final WebView v = web;
        if (v == null) return;
        final String js = "window.onAsrState && onAsrState(" + listening + ")";
        ui.post(new Runnable() {
            @Override
            public void run() {
                v.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "麦克风权限 granted=" + granted);
            if (pendingAsr) {
                pendingAsr = false;
                if (granted) startRecordTask();
                else jsAsr(null, "没有麦克风权限，语音输入用不了");
            }
        }
        super.onRequestPermissionsResult(code, perms, results);
    }

    /** 把识别结果回给页面：window.onAsrResult(text, error)。 */
    private void jsAsr(final String text, final String err) {
        final WebView v = web;
        if (v == null) return;
        String js = "window.onAsrResult && onAsrResult("
                + JSONObject.quote(text == null ? "" : text) + ","
                + (err == null ? "null" : JSONObject.quote(err)) + ")";
        ui.post(new Runnable() {
            @Override
            public void run() {
                v.evaluateJavascript(js, null);
            }
        });
    }

    private void initTts() {
        try {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        ttsReady = false;
                        Log.w(TAG, "TTS 初始化失败 status=" + status);
                        return;
                    }
                    try {
                        int r = tts.setLanguage(Locale.CHINA);
                        ttsReady = r != TextToSpeech.LANG_MISSING_DATA
                                && r != TextToSpeech.LANG_NOT_SUPPORTED;
                    } catch (Throwable t) {
                        ttsReady = false;
                    }
                    Log.i(TAG, "TTS 可用=" + ttsReady);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "TTS 创建失败", t);
        }
    }

    private boolean ttsAvailable() {
        return tts != null && ttsReady;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9003);
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private void startKeepAlive() {
        if (serviceStarted) return;
        try {
            startForegroundService(new Intent(this, EngineService.class));
            serviceStarted = true;
        } catch (Throwable t) {
            Log.w(TAG, "前台服务启动失败", t);
        }
    }

    private void restartEngineInternal() {
        EngineHolder.stop();
        server = null;
        engineReady = false;
        gpuActive = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boot();
                } catch (Throwable t) {
                    Log.e(TAG, "重启引擎失败", t);
                }
            }
        }, "reboot").start();
    }

    private String deviceInfoJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("brand", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            o.put("model", Build.MODEL == null ? "" : Build.MODEL);
            o.put("android", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            o.put("api", Build.VERSION.SDK_INT);
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                o.put("ramTotal", round1(mi.totalMem / 1073741824.0));
                o.put("ramAvail", round1(mi.availMem / 1073741824.0));
            }
            o.put("cores", Runtime.getRuntime().availableProcessors());
            StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
            o.put("storageFree", round1(fs.getAvailableBytes() / 1073741824.0));
            o.put("storageTotal", round1(fs.getTotalBytes() / 1073741824.0));
            o.put("modelsSize", round1(dirSize(new File(getFilesDir(), "models")) / 1073741824.0));
            o.put("hasModels", ready(new File(new File(getFilesDir(), "models"), MODEL_NAME)));
            o.put("model", modelPref());
            o.put("has1b", modelExists(ID_1B));
            o.put("has35", modelExists(ID_35B));
            o.put("can35", canRun35b());
            o.put("ramTotalPhys", round1(totalRamBytes() / 1073741824.0));
            o.put("size35", round1(dirSize(new File(getFilesDir(), "models35")) / 1073741824.0));
            o.put("gpu", gpuActive);
            o.put("warm", EngineHolder.alive());
            o.put("warmSec", EngineHolder.alive()
                    ? (System.currentTimeMillis() - EngineHolder.startedAt()) / 1000 : 0);
            o.put("keepWarm", keepWarm());
            o.put("tts", SpeechEngine.ttsReady() || ttsAvailable());
            o.put("asr", SpeechEngine.asrReady());
            o.put("voice", voicePref());
            try {
                JSONObject vv = new JSONObject();
                for (String[] v : VOICES) vv.put(v[0], voiceReady(v[0]));
                o.put("voices", vv);
            } catch (Throwable ignore) {
            }
            o.put("version", getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Throwable t) {
            Log.w(TAG, "读取设备信息失败", t);
        }
        return o.toString();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        long sum = 0;
        for (File k : kids) sum += dirSize(k);
        return sum;
    }

    /** 系统返回键：聊天页先回列表，列表页才退出。 */
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到前台：取消后台闲置计时
        EngineService.disarmIdle(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 退到后台才开始计时，闲置超时由服务自动释放内存
        if (engineReady) EngineService.armIdle(this);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // 引擎留着：前台服务保活，下次进入直接接上（约 1 秒）。
        // 要立刻释放内存，用通知栏的「结束引擎」，或等后台闲置超时。
        if (engineReady && EngineHolder.alive() && keepWarm()) {
            Log.i(TAG, "退出界面，引擎保持运行");
        } else {
            EngineHolder.stop();
            server = null;
            engineReady = false;
            if (serviceStarted) {
                try {
                    stopService(new Intent(this, EngineService.class));
                } catch (Throwable ignore) {
                }
                serviceStarted = false;
            }
        }
        if (web != null) {
            // 进程要留着（引擎还在跑），把 WebView 彻底销毁，别把已销毁的 Activity 挂住
            try {
                root.removeView(web);
                web.destroy();
            } catch (Throwable ignore) {
            }
            web = null;
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignore) {
            }
            tts = null;
        }
        super.onDestroy();
    }
}
