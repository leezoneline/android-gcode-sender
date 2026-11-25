package com.dsh.gcodesender;

import java.io.IOException;

/** 测试桩：把写入内容记到静态列表，模拟串口。 */
public class SerialLink {

    public static final java.util.List<String> WRITTEN =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    public void write(byte[] data) throws IOException {
        WRITTEN.add(new String(data, java.nio.charset.StandardCharsets.US_ASCII));
    }
}
