package com.dsh.gcodesender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GCode 播放引擎：按三种流控策略把程序逐行送给主板。
 *
 * OK_WAIT    每行等到 ok 再发下一行（GRBL / Marlin，最稳）
 * CHAR_COUNT GRBL 字符计数流控（利用控制器缓冲区，速度快）
 * TIMED      固定间隔发送，不等回包（EBB 绘图仪 / 不返回 ok 的固件）
 */
public class GcodePlayback implements Runnable {

    public enum Mode {OK_WAIT, CHAR_COUNT, TIMED}

    public interface Callback {
        void onLog(String text);

        void onProgress(int sent, int total, long elapsedMs, int stalls);

        void onFinished(String reason);
    }

    private final SerialLink link;
    private final Mode mode;
    private final int intervalMs;
    private final int rxWindow;
    private final long okTimeoutMs;
    private final String lineEnding;
    private final boolean stripComments;
    private final Callback cb;

    private final List<String> program = new ArrayList<String>();

    private volatile boolean stopRequested;
    private volatile boolean paused;
    private volatile long lastRxAt;

    private final ArrayDeque<Integer> inflight = new ArrayDeque<Integer>();
    private int inflightChars;
    private int okCount;
    private int errCount;
    private int stalls;
    private int sent;

    private Thread self;

    public GcodePlayback(List<String> rawLines, SerialLink link, Mode mode, int intervalMs, int rxWindow,
                         int okTimeoutSec, String lineEnding, boolean stripComments, Callback cb) {
        this.link = link;
        this.mode = mode;
        this.intervalMs = Math.max(1, intervalMs);
        this.rxWindow = Math.max(16, rxWindow);
        this.okTimeoutMs = Math.max(1000L, okTimeoutSec * 1000L);
        this.lineEnding = (lineEnding == null || lineEnding.length() == 0) ? "\n" : lineEnding;
        this.stripComments = stripComments;
        this.cb = cb;
        prepare(rawLines);
    }

    public static String stripParens(String s) {
        if (s.indexOf('(') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 过滤规则与播放一致，供 UI 预先统计有效行数 */
    public static boolean isEffective(String raw, boolean stripComments) {
        if (raw == null) {
            return false;
        }
        String l = raw.trim();
        if (l.length() == 0) {
            return false;
        }
        if (stripComments) {
            int semi = l.indexOf(';');
            if (semi >= 0) {
                l = l.substring(0, semi).trim();
            }
            l = stripParens(l).trim();
            if (l.length() == 0 || l.startsWith("%")) {
                return false;
            }
        }
        return true;
    }

    private void prepare(List<String> rawLines) {
        if (rawLines == null) {
            return;
        }
        for (int i = 0; i < rawLines.size(); i++) {
            String raw = rawLines.get(i);
            if (!isEffective(raw, stripComments)) {
                continue;
            }
            String l = raw.trim();
            if (stripComments) {
                int semi = l.indexOf(';');
                if (semi >= 0) {
                    l = l.substring(0, semi).trim();
                }
                l = stripParens(l).trim();
            }
            program.add(l);
        }
    }

    public int totalLines() {
        return program.size();
    }

    public boolean isActive() {
        Thread t = self;
        return t != null && t.isAlive();
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
        synchronized (this) {
            notifyAll();
        }
    }

    public void stop() {
        stopRequested = true;
        synchronized (this) {
            notifyAll();
        }
    }

    @Override
    public void run() {
        self = Thread.currentThread();
        long startAt = System.currentTimeMillis();
        lastRxAt = startAt;
        int total = program.size();
        cb.onLog("[播放] 共 " + total + " 行，模式 " + modeName());
        try {
            for (int i = 0; i < total; i++) {
                if (stopRequested) {
                    finish("已停止");
                    return;
                }
                waitWhilePaused();
                if (stopRequested) {
                    finish("已停止");
                    return;
                }

                String line = program.get(i);
                byte[] bytes = (line + lineEnding).getBytes(StandardCharsets.US_ASCII);

                if (mode == Mode.CHAR_COUNT) {
                    if (!acquireWindow(bytes.length)) {
                        finish("已停止");
                        return;
                    }
                }

                try {
                    link.write(bytes);
                } catch (IOException e) {
                    finish("写入失败: " + e.getMessage());
                    return;
                }
                sent = i + 1;

                if (mode == Mode.OK_WAIT) {
                    if (!waitForOk()) {
                        finish("已停止");
                        return;
                    }
                } else if (mode == Mode.TIMED) {
                    if (!sleepOrStop(intervalMs)) {
                        finish("已停止");
                        return;
                    }
                }

                if (sent == total || sent % 20 == 0) {
                    cb.onProgress(sent, total, System.currentTimeMillis() - startAt, stalls);
                }
            }
            cb.onProgress(sent, total, System.currentTimeMillis() - startAt, stalls);
            finish("发送完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish("已停止");
        }
    }

    private String modeName() {
        if (mode == Mode.OK_WAIT) {
            return "等待ok";
        }
        if (mode == Mode.CHAR_COUNT) {
            return "字符计数";
        }
        return "定时" + intervalMs + "ms";
    }

    private void finish(String reason) {
        cb.onLog("[播放] " + reason + " —— 已发 " + sent + "/" + program.size() + " 行，超时 " + stalls + " 次，error " + errCount + " 次");
        cb.onFinished(reason);
    }

    private void waitWhilePaused() throws InterruptedException {
        synchronized (this) {
            while (paused && !stopRequested) {
                wait(100);
            }
        }
    }

    private boolean sleepOrStop(int ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        synchronized (this) {
            while (!stopRequested) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return true;
                }
                wait(Math.min(50, left));
            }
        }
        return false;
    }

    private boolean waitForOk() throws InterruptedException {
        long deadline = System.currentTimeMillis() + okTimeoutMs;
        synchronized (this) {
            int target = okCount + 1;
            while (!stopRequested && okCount < target) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    stalls++;
                    inflight.clear();
                    inflightChars = 0;
                    cb.onLog("[超时] 第 " + sent + " 行没有等到 ok，继续发送（可调大超时，或改用定时模式）");
                    return true;
                }
                wait(Math.min(100, left));
            }
        }
        return !stopRequested;
    }

    private boolean acquireWindow(int need) throws InterruptedException {
        synchronized (this) {
            while (!stopRequested && inflightChars + need > rxWindow) {
                wait(20);
                if (System.currentTimeMillis() - lastRxAt > okTimeoutMs) {
                    stalls++;
                    inflight.clear();
                    inflightChars = 0;
                    cb.onLog("[超时] 流控窗口长时间没有回包，已清空窗口继续");
                }
            }
            if (stopRequested) {
                return false;
            }
            inflight.add(need);
            inflightChars += need;
            return true;
        }
    }

    /** 串口收到数据时调用（在串口线程） */
    public void onSerialData(byte[] data, int len) {
        if (data == null || len <= 0) {
            return;
        }
        lastRxAt = System.currentTimeMillis();
        String s = new String(data, 0, len, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int oks = count(s, "ok");
        int errs = count(s, "error");
        if (oks == 0 && errs == 0) {
            return;
        }
        synchronized (this) {
            int responses = oks + errs;
            for (int i = 0; i < responses; i++) {
                Integer n = inflight.poll();
                if (n != null) {
                    inflightChars -= n;
                }
                if (inflightChars < 0) {
                    inflightChars = 0;
                }
            }
            okCount += responses;
            errCount += errs;
            notifyAll();
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) {
                break;
            }
            n++;
            from = i + needle.length();
        }
        return n;
    }
}
