package com.koudai.cpm;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;

/**
 * 内置离线语音：SenseVoice（识别）+ VITS（朗读）。
 *
 * 全部在本机 CPU 上跑，不联网、不依赖厂商语音服务。两套模型和 llama 模型
 * 一样随 APK 打包，首次启动释放到内部存储。
 */
public class SpeechEngine {

    private static final String TAG = "KoudaiSpeech";
    public static final int SAMPLE_RATE = 16000;

    private static OfflineRecognizer asr;
    private static OfflineTts tts;

    private static AudioTrack track;
    private static volatile boolean playing = false;
    private static volatile boolean cancelRecord = false;
    /** 上一次录音的峰值音量（0~1），用来判断是不是离太远/没开口 */
    public static volatile double lastPeak = 0;

    public static boolean asrReady() {
        return asr != null;
    }

    public static boolean ttsReady() {
        return tts != null;
    }

    /** 初始化识别器（SenseVoice）。 */
    public static synchronized boolean initAsr(File model, File tokens) {
        if (asr != null) return true;
        try {
            OfflineRecognizerConfig cfg = OfflineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setFeatureDim(80)
                            .build())
                    .setOfflineModelConfig(OfflineModelConfig.builder()
                            .setSenseVoice(OfflineSenseVoiceModelConfig.builder()
                                    .setModel(model.getAbsolutePath())
                                    .setLanguage("zh")
                                    .setInverseTextNormalization(true)
                                    .build())
                            .setTokens(tokens.getAbsolutePath())
                            .setNumThreads(2)
                            .build())
                    .setDecodingMethod("greedy_search")
                    .build();
            long t0 = System.currentTimeMillis();
            asr = new OfflineRecognizer(cfg);
            Log.i(TAG, "识别器就绪，耗时 " + (System.currentTimeMillis() - t0) + "ms");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "识别器初始化失败", t);
            asr = null;
            return false;
        }
    }

    /** 初始化朗读引擎（VITS）。ruleFsts 为逗号分隔的文本规整规则（数字/日期/电话）。 */
    public static synchronized boolean initTts(File model, File tokens, File lexicon, String ruleFsts) {
        if (tts != null) return true;
        try {
            OfflineTtsConfig.Builder builder = OfflineTtsConfig.builder()
                    .setModel(OfflineTtsModelConfig.builder()
                            .setVits(OfflineTtsVitsModelConfig.builder()
                                    .setModel(model.getAbsolutePath())
                                    .setTokens(tokens.getAbsolutePath())
                                    .setLexicon(lexicon.getAbsolutePath())
                                    .setNoiseScale(0.667f)
                                    .setNoiseScaleW(0.8f)
                                    .setLengthScale(1.0f)
                                    .build())
                            .setNumThreads(4)
                            .build())
                    .setMaxNumSentences(1);
            if (ruleFsts != null && !ruleFsts.isEmpty()) {
                builder.setRuleFsts(ruleFsts);
            }
            long t0 = System.currentTimeMillis();
            tts = new OfflineTts(builder.build());
            Log.i(TAG, "朗读引擎就绪，耗时 " + (System.currentTimeMillis() - t0) + "ms，采样率 "
                    + tts.getSampleRate());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "朗读引擎初始化失败", t);
            tts = null;
            return false;
        }
    }

    /** 释放当前朗读引擎（切换音色时用）。 */
    public static synchronized void releaseTts() {
        stop();
        if (tts != null) {
            try {
                tts.release();
            } catch (Throwable ignore) {
            }
            tts = null;
        }
    }

    /**
     * 录音到静音为止。
     *
     * @param maxMs     最长录音时长
     * @param silenceMs 说完后静音多久算结束
     * @param wavOut    可选：把这次录音存成 wav，便于核对音量和音质
     * @return 16k 单声道归一化采样；失败返回 null
     */
    public static float[] record(int maxMs, int silenceMs, File wavOut) {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 4096;
        AudioRecord rec = null;
        try {
            // 用原始麦克风：VOICE_RECOGNITION 在部分机型上会被系统静音或过度降噪
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord 初始化失败");
                return null;
            }
            final int step = 1600;                       // 100ms
            short[] buf = new short[step];
            short[] all = new short[(maxMs / 100 + 4) * step];
            int total = 0;
            boolean started = false;
            int silent = 0;
            int frames = maxMs / 100;
            double peak = 0;
            double threshold = 0.006;                    // 初始很宽松，后面按底噪自适应
            double maxRms = 0;
            int minSpeechFrames = 4;                     // 连续 4 帧(400ms)才算开口

            rec.startRecording();
            cancelRecord = false;
            for (int i = 0; i < frames; i++) {
                if (cancelRecord) break;
                int n = rec.read(buf, 0, step);
                if (n <= 0) break;
                if (total + n > all.length) n = all.length - total;
                System.arraycopy(buf, 0, all, total, n);
                total += n;

                double sum = 0;
                for (int k = 0; k < n; k++) {
                    double v = buf[k] / 32768.0;
                    sum += v * v;
                    double a = Math.abs(v);
                    if (a > peak) peak = a;
                }
                double rms = Math.sqrt(sum / Math.max(1, n));
                if (rms > maxRms) maxRms = rms;

                if (!started) {
                    // 前 1.5 秒还没开口就把阈值压到底噪水平，避免一直等
                    if (i >= 15) threshold = Math.max(0.0015, maxRms * 0.35);
                    if (rms > threshold) {
                        silent++;                            // 用 silent 先计"像说话的帧"
                        if (silent >= minSpeechFrames) {
                            started = true;
                            silent = 0;
                        }
                    } else {
                        silent = 0;
                    }
                    if (!started && i >= 60) break;           // 6 秒没开口就放弃
                } else {
                    if (rms > threshold * 0.6) {
                        silent = 0;
                    } else {
                        silent += 100;
                    }
                    if (silent >= silenceMs) break;
                }
            }
            try {
                rec.stop();
            } catch (Throwable ignore) {
            }
            Log.i(TAG, "录音结束 " + (total / (float) SAMPLE_RATE) + "s started=" + started
                    + " peak=" + String.format("%.3f", peak)
                    + " maxRms=" + String.format("%.4f", maxRms)
                    + " thr=" + String.format("%.4f", threshold));
            if (total < SAMPLE_RATE / 3) return null;

            float[] out = new float[total];
            // 归一化到 -3dB 左右：手机离得远时音量常常偏小，识别会明显变差
            float gain = 1f;
            if (peak > 0.02 && peak < 0.8) gain = (float) (0.7 / peak);
            if (gain > 6f) gain = 6f;
            for (int i = 0; i < total; i++) {
                float v = (float) (all[i] / 32768.0) * gain;
                if (v > 1f) v = 1f;
                if (v < -1f) v = -1f;
                out[i] = v;
            }
            if (!started) Log.w(TAG, "没检测到明显说话声，仍尝试识别");
            lastPeak = peak;
            if (wavOut != null) writeWav(all, total, wavOut);
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "录音失败", t);
            return null;
        } finally {
            if (rec != null) {
                try {
                    rec.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 存一份 16bit PCM wav，便于在电脑上核对录音音量。 */
    private static void writeWav(short[] pcm, int samples, File out) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(out);
            int dataLen = samples * 2;
            byte[] header = new byte[44];
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            putInt(header, 4, 36 + dataLen);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            putInt(header, 16, 16);
            putShort(header, 20, 1);              // PCM
            putShort(header, 22, 1);              // mono
            putInt(header, 24, SAMPLE_RATE);
            putInt(header, 28, SAMPLE_RATE * 2);
            putShort(header, 32, 2);
            putShort(header, 34, 16);
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            putInt(header, 40, dataLen);
            fos.write(header);
            byte[] pcmBytes = new byte[dataLen];
            for (int i = 0; i < samples; i++) {
                pcmBytes[2 * i] = (byte) (pcm[i] & 0xff);
                pcmBytes[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            fos.write(pcmBytes);
            Log.i(TAG, "录音已存 " + out);
        } catch (Throwable t) {
            Log.w(TAG, "写 wav 失败", t);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    /** 识别一段音频，返回文本（可能为空串）。 */
    public static String recognize(float[] samples) {
        if (asr == null || samples == null || samples.length == 0) return null;
        OfflineStream s = null;
        try {
            long t0 = System.currentTimeMillis();
            s = asr.createStream();
            s.acceptWaveform(samples, SAMPLE_RATE);
            asr.decode(s);
            String text = asr.getResult(s).getText();
            Log.i(TAG, "识别完成 " + (System.currentTimeMillis() - t0) + "ms: " + text);
            return text;
        } catch (Throwable t) {
            Log.e(TAG, "识别失败", t);
            return null;
        } finally {
            if (s != null) {
                try {
                    s.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 朗读文本（后台线程合成并播放，可被 stop 打断）。 */
    public static void speak(final String text, final float speed) {
        if (tts == null || text == null || text.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    stop();
                    long t0 = System.currentTimeMillis();
                    GeneratedAudio audio = tts.generate(text, 0, speed);
                    float[] samples = audio.getSamples();
                    int sr = audio.getSampleRate();
                    if (samples == null || samples.length == 0) return;
                    short[] pcm = new short[samples.length];
                    for (int i = 0; i < samples.length; i++) {
                        float v = samples[i];
                        if (v > 1f) v = 1f;
                        if (v < -1f) v = -1f;
                        pcm[i] = (short) (v * 32767);
                    }
                    Log.i(TAG, "合成 " + samples.length / (float) sr + "s，耗时 "
                            + (System.currentTimeMillis() - t0) + "ms");
                    play(pcm, sr);
                } catch (Throwable t) {
                    Log.e(TAG, "朗读失败", t);
                }
            }
        }, "tts").start();
    }

    private static void play(short[] pcm, int sr) {
        int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 4096;
        AudioTrack tr = null;
        try {
            tr = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2, AudioTrack.MODE_STREAM);
            track = tr;
            playing = true;
            tr.play();
            int off = 0;
            int chunk = Math.max(minBuf / 2, 1600);
            while (playing && off < pcm.length) {
                int n = Math.min(chunk, pcm.length - off);
                tr.write(pcm, off, n);
                off += n;
            }
        } catch (Throwable t) {
            Log.e(TAG, "播放失败", t);
        } finally {
            playing = false;
            track = null;
            if (tr != null) {
                try {
                    tr.stop();
                } catch (Throwable ignore) {
                }
                tr.release();
            }
        }
    }

    /** 让正在进行的录音提前结束（再点一次麦克风）。 */
    public static void cancelRecording() {
        cancelRecord = true;
    }

    /** 停止当前朗读。 */
    public static void stop() {        playing = false;
        AudioTrack tr = track;
        if (tr != null) {
            try {
                tr.pause();
                tr.flush();
            } catch (Throwable ignore) {
            }
        }
    }
}
