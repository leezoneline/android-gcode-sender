package com.dsh.gcodesender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Test {

    static GcodePlayback P;
    static volatile boolean ackOn = true;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 过滤逻辑 ===");
        check("A", GcodePlayback.stripParens("G1 (c) X1 (d) Y2").equals("G1  X1  Y2"));
        check("B", GcodePlayback.isEffective("G1 X1 ; hi", true));
        check("C", !GcodePlayback.isEffective("(only comment)", true));
        check("D", !GcodePlayback.isEffective("%", true));
        check("E", !GcodePlayback.isEffective("   ", true));
        check("F", GcodePlayback.isEffective("; x", false));

        System.out.println("=== OK_WAIT 播放 ===");
        runOnce(GcodePlayback.Mode.OK_WAIT, 128, 3);

        System.out.println("=== CHAR_COUNT 播放 ===");
        runOnce(GcodePlayback.Mode.CHAR_COUNT, 120, 3);

        System.out.println("=== TIMED 播放 ===");
        ackOn = false;
        runOnce(GcodePlayback.Mode.TIMED, 120, 3);
        System.out.println("ALL DONE");
    }

    static void runOnce(GcodePlayback.Mode mode, int window, int timeoutSec) throws Exception {
        SerialLink.WRITTEN.clear();
        List<String> lines = Arrays.asList(
                "(header comment)", "G21", "G90", "", "G1 X10 Y10 ; move",
                "G1 X20 (inline)", "M5", "G0 X0 Y0");

        final long[] progress = new long[]{0, 0};
        P = new GcodePlayback(lines, new SerialLink(), mode, 2, window, timeoutSec, "\n", true,
                new GcodePlayback.Callback() {
                    public void onLog(String s) {
                        System.out.println("  log: " + s);
                    }

                    public void onProgress(int sent, int total, long elapsed, int stalls) {
                        progress[0] = sent;
                        progress[1] = total;
                    }

                    public void onFinished(String reason) {
                        System.out.println("  finished: " + reason);
                    }
                });

        Thread ack = new Thread(new Runnable() {
            public void run() {
                int last = 0;
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline) {
                    if (ackOn) {
                        int size;
                        synchronized (SerialLink.WRITTEN) {
                            size = SerialLink.WRITTEN.size();
                        }
                        while (last < size) {
                            last++;
                            byte[] ok = "ok\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                            P.onSerialData(ok, ok.length);
                        }
                    }
                    try {
                        Thread.sleep(3);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        ack.setDaemon(true);
        ack.start();

        Thread t = new Thread(P);
        t.start();
        t.join(6000);

        System.out.println("  written lines: " + SerialLink.WRITTEN.size() + " / expected 6");
        System.out.println("  progress: " + progress[0] + "/" + progress[1]);
        for (int i = 0; i < SerialLink.WRITTEN.size(); i++) {
            System.out.println("   > " + SerialLink.WRITTEN.get(i).trim());
        }
        check(mode + " 全部发送", SerialLink.WRITTEN.size() == 6);
    }

    static void check(String label, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label);
        if (!ok) {
            failures++;
        }
    }

    static int failures = 0;
}
