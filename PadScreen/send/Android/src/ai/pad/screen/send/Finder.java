package ai.pad.screen.send;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** 只听 _padscreen._tcp.，不主动连 8090，避免占住平板唯一的连接。 */
final class Finder {
    static final class FoundPad {
        final String name;
        final String host;
        final int port;

        FoundPad(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        String key() {
            return host + ":" + port;
        }
    }

    private static final String TAG = "PadScreenSend";
    private static final String SERVICE = "_padscreen._tcp.";

    private final Hub hub;
    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicast;
    private final HashMap<String, FoundPad> records = new HashMap<String, FoundPad>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<NsdServiceInfo>();
    private final NsdManager.DiscoveryListener discoveryListener;

    private List<FoundPad> pads = new ArrayList<FoundPad>();
    private boolean scanning;
    private boolean discovering;
    private boolean restartPending;
    private boolean resolving;
    private int generation;
    private boolean started;

    Finder(Hub hub, Context context) {
        this.hub = hub;
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wifi != null) {
            lock = wifi.createMulticastLock("padscreen-send");
            lock.setReferenceCounted(false);
        }
        multicast = lock;
        discoveryListener = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discovering = false;
                hub.showError("扫描失败（" + errorCode + "）");
            }

            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "stop discovery failed " + errorCode);
            }

            public void onDiscoveryStarted(String serviceType) {
                discovering = true;
            }

            public void onDiscoveryStopped(String serviceType) {
                discovering = false;
                if (restartPending) {
                    restartPending = false;
                    beginDiscover();
                }
            }

            public void onServiceFound(NsdServiceInfo serviceInfo) {
                enqueue(serviceInfo);
            }

            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                if (name == null) {
                    return;
                }
                synchronized (records) {
                    records.remove(name);
                }
                publish();
            }
        };
    }

    List<FoundPad> pads() {
        return pads;
    }

    boolean scanning() {
        return scanning;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        acquireMulticast();
        rescan();
    }

    void rescan() {
        generation++;
        final int seen = generation;
        synchronized (records) {
            records.clear();
        }
        resolveQueue.clear();
        pads = new ArrayList<FoundPad>();
        scanning = true;
        restartDiscover();
        deliver(new Runnable() {
            public void run() {
                hub.onFinderChanged();
            }
        });
        hub.main.postDelayed(new Runnable() {
            public void run() {
                if (generation != seen) {
                    return;
                }
                scanning = false;
                hub.onFinderChanged();
            }
        }, 2500);
    }

    private void acquireMulticast() {
        if (multicast == null || multicast.isHeld()) {
            return;
        }
        try {
            multicast.acquire();
        } catch (RuntimeException e) {
            Log.w(TAG, "multicast lock", e);
        }
    }

    private void restartDiscover() {
        if (nsd == null) {
            hub.showError("这台手机没有局域网发现服务");
            return;
        }
        if (discovering) {
            restartPending = true;
            try {
                nsd.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException e) {
                discovering = false;
                restartPending = false;
                beginDiscover();
            }
            return;
        }
        beginDiscover();
    }

    private void beginDiscover() {
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "discover", e);
            hub.showError("扫描没有重新开始");
        }
    }

    private void enqueue(NsdServiceInfo info) {
        resolveQueue.add(info);
        pump();
    }

    private void pump() {
        if (resolving || resolveQueue.isEmpty() || nsd == null) {
            return;
        }
        resolving = true;
        final NsdServiceInfo info = resolveQueue.removeFirst();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    resolving = false;
                    Log.w(TAG, "resolve failed " + errorCode);
                    pump();
                }

                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    resolving = false;
                    accept(serviceInfo);
                    pump();
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
            Log.w(TAG, "resolve", e);
            pump();
        }
    }

    private void accept(NsdServiceInfo info) {
        String host = ipv4(info.getHost());
        int port = info.getPort();
        if (host == null || port <= 0) {
            return;
        }
        String serviceName = info.getServiceName();
        if (serviceName == null || serviceName.length() == 0) {
            serviceName = host;
        }
        String visible = serviceName;
        synchronized (records) {
            records.put(serviceName, new FoundPad(visible, host, port));
        }
        publish();
    }

    private void publish() {
        List<FoundPad> next = new ArrayList<FoundPad>();
        synchronized (records) {
            next.addAll(records.values());
        }
        ArrayList<FoundPad> unique = new ArrayList<FoundPad>();
        for (int i = 0; i < next.size(); i++) {
            FoundPad pad = next.get(i);
            boolean seen = false;
            for (int j = 0; j < unique.size(); j++) {
                FoundPad have = unique.get(j);
                if (have.host.equals(pad.host) && have.port == pad.port) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                unique.add(pad);
            }
        }
        Collections.sort(unique, new Comparator<FoundPad>() {
            public int compare(FoundPad left, FoundPad right) {
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        pads = unique;
        if (!unique.isEmpty()) {
            scanning = false;
        }
        deliver(new Runnable() {
            public void run() {
                hub.onFinderChanged();
            }
        });
    }

    private static String ipv4(InetAddress address) {
        if (address == null) {
            return null;
        }
        if (address instanceof Inet6Address) {
            String mapped = address.getHostAddress();
            if (mapped != null && mapped.startsWith("::ffff:")) {
                String v4 = mapped.substring("::ffff:".length());
                if (v4.indexOf(':') < 0 && !"0.0.0.0".equals(v4)) {
                    return v4;
                }
            }
            return null;
        }
        String host = address.getHostAddress();
        if (host == null || host.indexOf(':') >= 0 || "0.0.0.0".equals(host)) {
            return null;
        }
        return host;
    }

    private void deliver(Runnable runnable) {
        if (Looper.myLooper() == hub.main.getLooper()) {
            runnable.run();
        } else {
            hub.main.post(runnable);
        }
    }
}
