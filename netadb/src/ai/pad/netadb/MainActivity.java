package ai.pad.netadb;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class MainActivity extends Activity {
    private TextView out;
    private EditText command;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        Button open = new Button(this);
        open.setText("打开网络调试");
        open.setTextSize(22);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBackground(new Runnable() {
                    @Override
                    public void run() {
                        final String text = enableAdb();
                        show(text);
                    }
                });
            }
        });

        command = new EditText(this);
        command.setHint("要执行的命令");
        command.setTextSize(18);
        command.setSingleLine(true);

        Button run = new Button(this);
        run.setText("执行命令");
        run.setTextSize(22);
        run.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String cmd = command.getText().toString();
                runBackground(new Runnable() {
                    @Override
                    public void run() {
                        show(exec(new String[]{"sh", "-c", cmd}, 8000));
                    }
                });
            }
        });

        out = new TextView(this);
        out.setTextSize(16);
        out.setText("正在读取设备信息…");

        root.addView(open);
        root.addView(command);
        root.addView(run);
        root.addView(out);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        runBackground(new Runnable() {
            @Override
            public void run() {
                show(info());
            }
        });
    }

    private void runBackground(final Runnable task) {
        new Thread(task).start();
    }

    private void show(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                out.setText(text);
            }
        });
    }

    private String enableAdb() {
        StringBuilder log = new StringBuilder();
        log.append(info()).append("\n\n");
        log.append("设置端口: ").append(setProp("service.adb.tcp.port", "5555")).append('\n');
        log.append("停止 adbd: ").append(setProp("ctl.stop", "adbd")).append('\n');
        sleep(400);
        log.append("启动 adbd: ").append(setProp("ctl.start", "adbd")).append('\n');
        sleep(600);
        String port = getProp("service.adb.tcp.port");
        log.append("读回端口: ").append(port).append('\n');
        if (!"5555".equals(port)) {
            log.append("系统接口没有改成 5555，改试 su。\n");
            log.append(exec(new String[]{"su", "-c",
                    "setprop service.adb.tcp.port 5555; setprop ctl.stop adbd; setprop ctl.start adbd"}, 8000));
            sleep(600);
            port = getProp("service.adb.tcp.port");
            log.append("su 之后端口: ").append(port).append('\n');
        }
        log.append(writeLocalProp());
        log.append("\n当前地址: ").append(ipAddress()).append(":5555\n");
        if ("5555".equals(port)) {
            log.append("网络调试已打开。电脑上执行:\nadb connect ").append(ipAddress()).append(":5555\n");
        } else {
            log.append("还没有打开。把这整段文字发给电脑上的助手。\n");
        }
        return log.toString();
    }

    private String info() {
        return "型号: " + getProp("ro.product.model")
                + "\n指纹: " + getProp("ro.build.fingerprint")
                + "\n标签: " + getProp("ro.build.tags")
                + "\nsecure: " + getProp("ro.secure")
                + "  debuggable: " + getProp("ro.debuggable")
                + "\n地址: " + ipAddress()
                + "\n当前调试端口: " + getProp("service.adb.tcp.port");
    }

    private String writeLocalProp() {
        File file = new File("/data/local.prop");
        try {
            StringBuilder kept = new StringBuilder();
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("service.adb.tcp.port=")) {
                        continue;
                    }
                    kept.append(line).append('\n');
                }
                reader.close();
            }
            kept.append("service.adb.tcp.port=5555\n");
            FileWriter writer = new FileWriter(file);
            writer.write(kept.toString());
            writer.close();
            return "已写入 /data/local.prop，重启后仍会监听 5555。\n";
        } catch (Throwable t) {
            return "写 /data/local.prop 失败: " + t + "\n";
        }
    }

    private static String setProp(String key, String value) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getMethod("set", String.class, String.class);
            method.invoke(null, key, value);
            return "已提交";
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    private static String getProp(String key) {
        String text = exec(new String[]{"/system/bin/getprop", key}, 3000);
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline);
        }
        return text.trim();
    }

    private static String exec(String[] command, int timeoutMs) {
        try {
            final Process process = Runtime.getRuntime().exec(command);
            final StringBuilder sink = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readStream(process.getInputStream(), sink);
                    readStream(process.getErrorStream(), sink);
                }
            });
            reader.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    process.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    sleep(50);
                }
            }
            try {
                process.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                process.destroy();
                sink.append("\n(命令超时)");
            }
            reader.join(500);
            String text = sink.toString().trim();
            return text.length() == 0 ? "(无输出)" : text;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    private static void readStream(java.io.InputStream input, StringBuilder sink) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
            reader.close();
        } catch (Exception ignored) {
        }
    }

    private String ipAddress() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = manager.getConnectionInfo();
            int ip = info.getIpAddress();
            if (ip != 0) {
                return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
            }
        } catch (Throwable ignored) {
        }
        return "(没有 Wi-Fi 地址)";
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
