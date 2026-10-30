package com.koudai.cpm;

/**
 * llama-server 子进程的进程内单例。
 *
 * 引擎进程是 App 进程 fork 出来的，Activity 退出时如果把它一起 destroy，
 * 下次进入就得重新把 1.7GB 模型从闪存读一遍（实测约 9 秒）。把进程引用放在
 * 静态字段里，再由前台服务保活，Activity 退出后引擎仍然在跑，重新进入时
 * 只要 /health 通就直接接上。
 */
final class EngineHolder {

    private static Process process;
    private static boolean gpu;
    private static long startedAt;
    private static String modelPath;
    private static String mmprojPath;

    private EngineHolder() {
    }

    static synchronized void put(Process p, boolean gpuFlag, String model, String mmproj) {
        process = p;
        gpu = gpuFlag;
        startedAt = System.currentTimeMillis();
        modelPath = model;
        mmprojPath = mmproj;
    }

    static synchronized Process get() {
        return process;
    }

    static synchronized boolean alive() {
        return process != null && process.isAlive();
    }

    static synchronized boolean gpu() {
        return gpu;
    }

    static synchronized long startedAt() {
        return startedAt;
    }

    static synchronized String model() {
        return modelPath;
    }

    static synchronized String mmproj() {
        return mmprojPath;
    }

    /** 结束引擎进程（用户主动释放内存、或切换模型档案时用）。 */
    static synchronized void stop() {
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignore) {
            }
        }
        process = null;
        gpu = false;
        startedAt = 0;
        modelPath = null;
        mmprojPath = null;
    }
}
