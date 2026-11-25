package com.dsh.gcodesender;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * G 代码发送器：把 PC 端导出的 GCode 文件通过 OTG 串口送给 GRBL / Marlin / EBB 主板。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_FILE = 1001;

    private static final String[] BAUD_LIST = {"9600", "19200", "38400", "57600", "115200", "230400", "250000", "460800", "921600"};
    private static final String[] ENDING_LIST = {"LF  (\\n)", "CRLF  (\\r\\n)"};
    private static final String[] MODE_LIST = {
            "等待 ok（GRBL / Marlin）",
            "字符计数（GRBL 高速流控）",
            "定时发送（EBB / 无回包固件）"
    };

    private Spinner spDevice;
    private Spinner spBaud;
    private Spinner spLineEnding;
    private Spinner spMode;
    private CheckBox cbDtr;
    private CheckBox cbRts;
    private CheckBox cbStrip;
    private Button btnRefresh;
    private Button btnConnect;
    private Button btnPick;
    private Button btnStart;
    private Button btnPause;
    private Button btnStop;
    private Button btnSendCmd;
    private Button btnClearLog;
    private TextView tvConnState;
    private TextView tvFile;
    private TextView tvProgress;
    private TextView tvLog;
    private EditText etInterval;
    private EditText etWindow;
    private EditText etTimeout;
    private EditText etStartCode;
    private EditText etEndCode;
    private EditText etCmd;
    private ProgressBar pbProgress;
    private ScrollView scrollLog;
    private LinearLayout llQuick;

    private SerialLink link;

    private final List<UsbSerialDriver> driverList = new ArrayList<UsbSerialDriver>();
    private final List<String> deviceLabels = new ArrayList<String>();

    private UsbSerialDriver pendingDriver;
    private int pendingBaud;

    private GcodePlayback playback;
    private Thread playbackThread;
    private boolean pausedFlag;

    private List<String> rawLines = new ArrayList<String>();
    private String fileName = "";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ArrayDeque<String> logQueue = new ArrayDeque<String>();
    private boolean logScheduled;
    private final StringBuilder rxBuf = new StringBuilder();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spDevice = (Spinner) findViewById(R.id.spDevice);
        spBaud = (Spinner) findViewById(R.id.spBaud);
        spLineEnding = (Spinner) findViewById(R.id.spLineEnding);
        spMode = (Spinner) findViewById(R.id.spMode);
        cbDtr = (CheckBox) findViewById(R.id.cbDtr);
        cbRts = (CheckBox) findViewById(R.id.cbRts);
        cbStrip = (CheckBox) findViewById(R.id.cbStrip);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnConnect = (Button) findViewById(R.id.btnConnect);
        btnPick = (Button) findViewById(R.id.btnPick);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnPause = (Button) findViewById(R.id.btnPause);
        btnStop = (Button) findViewById(R.id.btnStop);
        btnSendCmd = (Button) findViewById(R.id.btnSendCmd);
        btnClearLog = (Button) findViewById(R.id.btnClearLog);
        tvConnState = (TextView) findViewById(R.id.tvConnState);
        tvFile = (TextView) findViewById(R.id.tvFile);
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvLog = (TextView) findViewById(R.id.tvLog);
        etInterval = (EditText) findViewById(R.id.etInterval);
        etWindow = (EditText) findViewById(R.id.etWindow);
        etTimeout = (EditText) findViewById(R.id.etTimeout);
        etStartCode = (EditText) findViewById(R.id.etStartCode);
        etEndCode = (EditText) findViewById(R.id.etEndCode);
        etCmd = (EditText) findViewById(R.id.etCmd);
        pbProgress = (ProgressBar) findViewById(R.id.pbProgress);
        scrollLog = (ScrollView) findViewById(R.id.scrollLog);
        llQuick = (LinearLayout) findViewById(R.id.llQuick);

        link = new SerialLink(this);

        ArrayAdapter<String> a1 = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, BAUD_LIST);
        a1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBaud.setAdapter(a1);
        spBaud.setSelection(4);

        ArrayAdapter<String> a2 = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, ENDING_LIST);
        a2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLineEnding.setAdapter(a2);

        ArrayAdapter<String> a3 = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, MODE_LIST);
        a3.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(a3);

        buildQuickButtons();
        loadPrefs();

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refreshDevices();
            }
        });
        btnConnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onConnectClick();
            }
        });
        btnPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickFile();
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startPlayback();
            }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                togglePause();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopPlayback();
            }
        });
        btnSendCmd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String t = etCmd.getText().toString().trim();
                if (t.length() == 0) {
                    toast("请输入命令");
                    return;
                }
                sendCommands(t);
            }
        });
        btnClearLog.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tvLog.setText("");
            }
        });
        cbStrip.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                updateFileSummary();
                savePrefs();
            }
        });

        registerUsbReceiver();
        refreshDevices();
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);
        appendLog("[就绪] 把机器用 OTG 线接上手机，刷新设备后点「连接」，再选择 GCode 文件点「开始」");
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Throwable ignored) {
        }
        if (playback != null) {
            playback.stop();
        }
        if (link != null) {
            link.close();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- USB

    private void registerUsbReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(SerialLink.ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (SerialLink.ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    appendLog("[USB] 权限已授予");
                    if (pendingDriver != null) {
                        UsbSerialDriver d = pendingDriver;
                        pendingDriver = null;
                        openPort(d, pendingBaud);
                    }
                } else {
                    appendLog("[错误] USB 权限被拒绝，无法打开串口");
                    toast("USB 权限被拒绝");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                appendLog("[USB] 检测到设备插入");
                refreshDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                appendLog("[USB] 设备已拔出");
                if (link != null && link.isOpen()) {
                    markDisconnected("设备已拔出");
                }
                refreshDevices();
            }
        }
    };

    private void refreshDevices() {
        List<UsbSerialDriver> ds = link.listDrivers();
        List<UsbDevice> others = link.listUnsupported(ds);

        driverList.clear();
        deviceLabels.clear();
        for (int i = 0; i < ds.size(); i++) {
            driverList.add(ds.get(i));
            deviceLabels.add(describe(ds.get(i)));
        }
        for (int i = 0; i < others.size(); i++) {
            driverList.add(null);
            UsbDevice d = others.get(i);
            deviceLabels.add("不支持 " + hex(d.getVendorId()) + ":" + hex(d.getProductId()));
        }
        if (deviceLabels.isEmpty()) {
            deviceLabels.add("未检测到 USB 串口设备（插 OTG 线后点刷新）");
        }

        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, deviceLabels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDevice.setAdapter(a);

        appendLog("[USB] 扫描完成：识别 " + ds.size() + " 个串口设备，其他 " + others.size() + " 个");
    }

    private void onConnectClick() {
        if (link.isOpen()) {
            markDisconnected("手动断开");
            return;
        }
        int idx = spDevice.getSelectedItemPosition();
        if (idx < 0 || idx >= driverList.size()) {
            toast("没有可用的串口设备");
            return;
        }
        UsbSerialDriver d = driverList.get(idx);
        if (d == null) {
            toast("该设备不是受支持的 USB 转串口芯片");
            return;
        }
        int baud = baudValue();
        if (!link.hasPermission(d.getDevice())) {
            pendingDriver = d;
            pendingBaud = baud;
            appendLog("[USB] 正在请求 USB 权限…（弹窗请点允许）");
            link.requestPermission(d.getDevice());
            return;
        }
        openPort(d, baud);
    }

    private void openPort(UsbSerialDriver d, int baud) {
        if (d.getPorts() == null || d.getPorts().isEmpty()) {
            toast("该设备没有可用的串口端点");
            return;
        }
        try {
            link.setListener(serialListener);
            link.open(d, baud, cbDtr.isChecked(), cbRts.isChecked());
            tvConnState.setText("已连接 " + describe(d) + " @ " + baud + " 8N1");
            tvConnState.setTextColor(Color.parseColor("#2E7D32"));
            btnConnect.setText("断开");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            appendLog("[USB] 串口已打开：" + describe(d) + " @" + baud);
            savePrefs();
        } catch (Exception e) {
            appendLog("[错误] 打开串口失败：" + e.getMessage());
            toast("打开失败：" + e.getMessage());
        }
    }

    private void markDisconnected(String reason) {
        if (playback != null && playback.isActive()) {
            playback.stop();
        }
        try {
            link.close();
        } catch (Throwable ignored) {
        }
        tvConnState.setText("未连接（" + reason + "）");
        tvConnState.setTextColor(Color.parseColor("#C62828"));
        btnConnect.setText("连接");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appendLog("[USB] 已断开：" + reason);
    }

    private final SerialLink.Listener serialListener = new SerialLink.Listener() {
        @Override
        public void onSerialData(byte[] data, int len) {
            GcodePlayback p = playback;
            if (p != null) {
                p.onSerialData(data, len);
            }
            onSerialBytes(data, len);
        }

        @Override
        public void onSerialClosed(final String reason) {
            ui.post(new Runnable() {
                public void run() {
                    markDisconnected(reason);
                }
            });
        }
    };

    private void onSerialBytes(byte[] data, int len) {
        String s = new String(data, 0, len, StandardCharsets.ISO_8859_1);
        synchronized (rxBuf) {
            rxBuf.append(s);
            int idx;
            while ((idx = rxBuf.indexOf("\n")) >= 0) {
                String line = rxBuf.substring(0, idx).replace("\r", "");
                rxBuf.delete(0, idx + 1);
                if (line.length() > 0) {
                    appendLog("<< " + line);
                }
            }
            if (rxBuf.length() > 4096) {
                rxBuf.setLength(0);
            }
        }
    }

    // ---------------------------------------------------------------- 文件

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_FILE);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadFile(data.getData());
        }
    }

    private void loadFile(Uri uri) {
        InputStream in = null;
        BufferedReader br = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("无法打开文件");
            }
            br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<String>();
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(l);
            }
            if (!lines.isEmpty() && lines.get(0).length() > 0 && lines.get(0).charAt(0) == '\uFEFF') {
                lines.set(0, lines.get(0).substring(1));
            }
            rawLines = lines;
            fileName = displayName(uri);
            updateFileSummary();
            appendLog("[文件] 已载入 " + fileName + "，共 " + lines.size() + " 行");
        } catch (Exception e) {
            appendLog("[错误] 读取文件失败：" + e.getMessage());
            toast("读取失败：" + e.getMessage());
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void updateFileSummary() {
        if (rawLines.isEmpty()) {
            tvFile.setText("未选择");
            return;
        }
        int valid = 0;
        boolean strip = cbStrip.isChecked();
        for (int i = 0; i < rawLines.size(); i++) {
            if (GcodePlayback.isEffective(rawLines.get(i), strip)) {
                valid++;
            }
        }
        tvFile.setText(fileName + "\n共 " + rawLines.size() + " 行，实际发送 " + valid + " 行");
    }

    private static String displayName(Uri uri) {
        String seg = uri.getLastPathSegment();
        if (seg == null) {
            return uri.toString();
        }
        int slash = seg.lastIndexOf('/');
        return slash >= 0 ? seg.substring(slash + 1) : seg;
    }

    // ---------------------------------------------------------------- 播放

    private void startPlayback() {
        if (!link.isOpen()) {
            toast("请先连接串口");
            return;
        }
        if (rawLines.isEmpty()) {
            toast("请先选择 GCode 文件");
            return;
        }
        if (playback != null && playback.isActive()) {
            toast("正在发送中");
            return;
        }

        List<String> merged = new ArrayList<String>();
        merged.addAll(splitCommands(etStartCode.getText().toString()));
        merged.addAll(rawLines);
        merged.addAll(splitCommands(etEndCode.getText().toString()));

        GcodePlayback.Mode mode;
        int modePos = spMode.getSelectedItemPosition();
        if (modePos == 1) {
            mode = GcodePlayback.Mode.CHAR_COUNT;
        } else if (modePos == 2) {
            mode = GcodePlayback.Mode.TIMED;
        } else {
            mode = GcodePlayback.Mode.OK_WAIT;
        }

        String ending = spLineEnding.getSelectedItemPosition() == 1 ? "\r\n" : "\n";
        playback = new GcodePlayback(merged, link, mode, parseInt(etInterval, 30), parseInt(etWindow, 128),
                parseInt(etTimeout, 15), ending, cbStrip.isChecked(), playbackCallback);
        pausedFlag = false;
        btnPause.setText("暂停");
        playbackThread = new Thread(playback, "gcode-playback");
        playbackThread.setDaemon(true);
        playbackThread.start();

        pbProgress.setProgress(0);
        btnStart.setEnabled(false);
        btnPause.setEnabled(true);
        btnStop.setEnabled(true);
        savePrefs();
    }

    private void togglePause() {
        GcodePlayback p = playback;
        if (p == null || !p.isActive()) {
            return;
        }
        if (pausedFlag) {
            p.resume();
            pausedFlag = false;
            btnPause.setText("暂停");
            appendLog("[播放] 继续");
        } else {
            p.pause();
            pausedFlag = true;
            btnPause.setText("继续");
            appendLog("[播放] 已暂停（当前这一行会发完）");
        }
    }

    private void stopPlayback() {
        GcodePlayback p = playback;
        if (p != null) {
            p.stop();
            appendLog("[播放] 收到停止请求");
        }
    }

    private final GcodePlayback.Callback playbackCallback = new GcodePlayback.Callback() {
        @Override
        public void onLog(String text) {
            appendLog(text);
        }

        @Override
        public void onProgress(final int sent, final int total, final long elapsedMs, final int stalls) {
            ui.post(new Runnable() {
                public void run() {
                    updateProgress(sent, total, elapsedMs, stalls);
                }
            });
        }

        @Override
        public void onFinished(final String reason) {
            ui.post(new Runnable() {
                public void run() {
                    btnStart.setEnabled(true);
                    btnPause.setEnabled(false);
                    btnStop.setEnabled(false);
                    btnPause.setText("暂停");
                    pausedFlag = false;
                    toast(reason);
                }
            });
        }
    };

    private void updateProgress(int sent, int total, long elapsedMs, int stalls) {
        if (total <= 0) {
            return;
        }
        int permille = (int) (sent * 1000L / total);
        pbProgress.setProgress(permille);
        String eta = "--:--";
        if (sent > 0 && sent < total) {
            eta = fmtDuration(elapsedMs * (total - sent) / sent);
        } else if (sent >= total) {
            eta = "00:00";
        }
        String extra = stalls > 0 ? ("  超时 " + stalls) : "";
        tvProgress.setText("行 " + sent + "/" + total + "   " + (permille / 10) + "%   已用 " + fmtDuration(elapsedMs)
                + "   剩余 " + eta + extra);
    }

    // ---------------------------------------------------------------- 手动命令

    private void buildQuickButtons() {
        String[][] list = {
                {"$$", "状态"},
                {"$X", "解锁"},
                {"$H", "回零"},
                {"G28", "回原点"},
                {"M5", "抬笔"},
                {"M3 S1000", "落笔"},
                {"G92 X0 Y0", "置零"},
                {"G0 X0 Y0", "去零点"},
                {"G21", "毫米"},
                {"G90", "绝对"}
        };
        llQuick.removeAllViews();
        for (int i = 0; i < list.length; i++) {
            final String cmd = list[i][0];
            Button b = new Button(this);
            b.setText(list[i][1]);
            b.setTextSize(12);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(12), dp(4), dp(12), dp(4));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sendCommands(cmd);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            llQuick.addView(b, lp);
        }
    }

    private void sendCommands(String text) {
        if (!link.isOpen()) {
            toast("请先连接串口");
            return;
        }
        List<String> cmds = splitCommands(text);
        String ending = spLineEnding.getSelectedItemPosition() == 1 ? "\r\n" : "\n";
        for (int i = 0; i < cmds.size(); i++) {
            String c = cmds.get(i);
            try {
                link.write((c + ending).getBytes(StandardCharsets.US_ASCII));
                appendLog(">> " + c);
            } catch (IOException e) {
                appendLog("[错误] 发送失败：" + e.getMessage());
            }
        }
    }

    private static List<String> splitCommands(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        String[] parts = text.replace('\n', ';').split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() > 0) {
                out.add(p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 日志

    private void appendLog(String s) {
        String line = ts() + "  " + s;
        synchronized (logQueue) {
            logQueue.add(line);
            while (logQueue.size() > 600) {
                logQueue.poll();
            }
        }
        if (!logScheduled) {
            logScheduled = true;
            ui.postDelayed(logFlush, 150);
        }
    }

    private final Runnable logFlush = new Runnable() {
        public void run() {
            StringBuilder sb = new StringBuilder();
            synchronized (logQueue) {
                logScheduled = false;
                while (!logQueue.isEmpty()) {
                    sb.append(logQueue.poll()).append('\n');
                }
            }
            if (sb.length() == 0) {
                return;
            }
            tvLog.append(sb.toString());
            CharSequence cs = tvLog.getText();
            if (cs != null && cs.length() > 30000) {
                tvLog.setText(cs.subSequence(cs.length() - 25000, cs.length()));
            }
            scrollLog.post(new Runnable() {
                public void run() {
                    scrollLog.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    };

    private static String ts() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String fmtDuration(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    // ---------------------------------------------------------------- 其它

    private int baudValue() {
        try {
            return Integer.parseInt(BAUD_LIST[spBaud.getSelectedItemPosition()]);
        } catch (Exception e) {
            return 115200;
        }
    }

    private static int parseInt(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String hex(int v) {
        return String.format(Locale.US, "0x%04X", v);
    }

    private static String describe(UsbSerialDriver d) {
        if (d == null) {
            return "?";
        }
        UsbDevice dev = d.getDevice();
        String name = d.getClass().getSimpleName().replace("SerialDriver", "");
        String product = "";
        try {
            product = dev.getProductName();
        } catch (Throwable ignored) {
        }
        String p = (product == null || product.length() == 0) ? "" : (" " + product);
        return name + p + " [" + hex(dev.getVendorId()) + ":" + hex(dev.getProductId()) + "]";
    }

    private void savePrefs() {
        try {
            SharedPreferences sp = getSharedPreferences("gcode", MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            e.putInt("baud", spBaud.getSelectedItemPosition());
            e.putInt("ending", spLineEnding.getSelectedItemPosition());
            e.putInt("mode", spMode.getSelectedItemPosition());
            e.putString("interval", etInterval.getText().toString());
            e.putString("window", etWindow.getText().toString());
            e.putString("timeout", etTimeout.getText().toString());
            e.putString("start", etStartCode.getText().toString());
            e.putString("end", etEndCode.getText().toString());
            e.putBoolean("strip", cbStrip.isChecked());
            e.putBoolean("dtr", cbDtr.isChecked());
            e.putBoolean("rts", cbRts.isChecked());
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences("gcode", MODE_PRIVATE);
        spBaud.setSelection(sp.getInt("baud", 4));
        spLineEnding.setSelection(sp.getInt("ending", 0));
        spMode.setSelection(sp.getInt("mode", 0));
        etInterval.setText(sp.getString("interval", "30"));
        etWindow.setText(sp.getString("window", "120"));
        etTimeout.setText(sp.getString("timeout", "15"));
        etStartCode.setText(sp.getString("start", ""));
        etEndCode.setText(sp.getString("end", ""));
        cbStrip.setChecked(sp.getBoolean("strip", true));
        cbDtr.setChecked(sp.getBoolean("dtr", true));
        cbRts.setChecked(sp.getBoolean("rts", true));
    }
}
