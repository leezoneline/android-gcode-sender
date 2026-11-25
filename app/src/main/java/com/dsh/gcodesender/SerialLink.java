package com.dsh.gcodesender;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * USB 串口（CH340 / CP2102 / FTDI / CDC-ACM）封装。
 * 基于 usb-serial-for-android 3.8.1 源码内联编译。
 */
public class SerialLink {

    public static final String ACTION_USB_PERMISSION = "com.dsh.gcodesender.USB_PERMISSION";

    public interface Listener {
        void onSerialData(byte[] data, int len);

        void onSerialClosed(String reason);
    }

    private final Context ctx;
    private final UsbManager manager;

    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private SerialInputOutputManager ioManager;
    private volatile Listener listener;

    private final SerialInputOutputManager.Listener ioListener = new SerialInputOutputManager.Listener() {
        @Override
        public void onNewData(byte[] data) {
            Listener l = listener;
            if (l != null) {
                l.onSerialData(data, data.length);
            }
        }

        @Override
        public void onRunError(Exception e) {
            Listener l = listener;
            if (l != null) {
                l.onSerialClosed(e == null ? "读线程异常结束" : ("读线程结束: " + e.getMessage()));
            }
        }
    };

    public SerialLink(Context context) {
        this.ctx = context.getApplicationContext();
        this.manager = (UsbManager) this.ctx.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 已识别的串口芯片设备 */
    public List<UsbSerialDriver> listDrivers() {
        try {
            List<UsbSerialDriver> list = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            return list == null ? new ArrayList<UsbSerialDriver>() : list;
        } catch (Throwable t) {
            return new ArrayList<UsbSerialDriver>();
        }
    }

    /** 当前插入但未被 usb-serial 识别的 USB 设备 */
    public List<UsbDevice> listUnsupported(List<UsbSerialDriver> known) {
        List<UsbDevice> out = new ArrayList<UsbDevice>();
        for (UsbDevice d : manager.getDeviceList().values()) {
            boolean matched = false;
            for (int i = 0; i < known.size(); i++) {
                if (known.get(i).getDevice().getDeviceId() == d.getDeviceId()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.add(d);
            }
        }
        return out;
    }

    public boolean hasPermission(UsbDevice device) {
        return device != null && manager.hasPermission(device);
    }

    public void requestPermission(UsbDevice device) {
        if (device == null) {
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
        manager.requestPermission(device, pi);
    }

    public void open(UsbSerialDriver driver, int baudRate, boolean dtr, boolean rts) throws IOException {
        close();
        if (driver == null) {
            throw new IOException("未识别的串口设备");
        }
        UsbDevice device = driver.getDevice();
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) {
            throw new IOException("打开 USB 设备失败：权限未授予或被其他程序占用");
        }
        this.connection = conn;
        UsbSerialPort p = driver.getPorts().get(0);
        p.open(conn);
        p.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        try {
            p.setDTR(dtr);
        } catch (IOException ignored) {
        }
        try {
            p.setRTS(rts);
        } catch (IOException ignored) {
        }
        this.port = p;
        SerialInputOutputManager m = new SerialInputOutputManager(p, ioListener);
        m.setReadTimeout(200);
        m.start();
        this.ioManager = m;
    }

    public boolean isOpen() {
        return port != null;
    }

    public void write(byte[] data) throws IOException {
        UsbSerialPort p = port;
        if (p == null) {
            throw new IOException("串口未打开");
        }
        SerialInputOutputManager m = ioManager;
        if (m != null) {
            m.writeAsync(data);
        } else {
            p.write(data, 3000);
        }
    }

    public void close() {
        SerialInputOutputManager m = ioManager;
        ioManager = null;
        if (m != null) {
            try {
                m.stop();
            } catch (Throwable ignored) {
            }
        }
        UsbSerialPort p = port;
        port = null;
        if (p != null) {
            try {
                p.close();
            } catch (Throwable ignored) {
            }
        }
        UsbDeviceConnection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
