package com.lifemoments.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Foreground-only LAN setting copy. Discovery carries no credentials; both people approve the same short code. */
final class LocalDeviceTransfer implements AutoCloseable {
    interface Provider {
        TransferPayload exportPayload() throws Exception;
        void importPayload(TransferPayload value) throws Exception;
        void onStatus(String status);
        default void onClosed() { }
    }
    private static final String TYPE = "_lifemoments._tcp.";
    private static final long LIFETIME = 180000;
    private final Activity activity;
    private final Provider provider;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final NsdManager nsd;
    private final Map<String, NsdServiceInfo> services = new LinkedHashMap<>();
    private final List<String> names = new ArrayList<>();
    private volatile boolean active;
    private volatile boolean disposed;
    private volatile boolean connecting;
    private volatile ServerSocket server;
    private volatile Socket connection;
    private volatile TransferCrypto.Session crypto;
    private volatile CompletableFuture<Boolean> approval;
    private long expiresAt;
    private Network lanNetwork;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private AlertDialog dialog, pairDialog, manualDialog;
    private TextView statusText;
    private ArrayAdapter<String> adapter;
    private ListView list;
    private final Runnable expire = () -> finish("局域网迁移已过期，请重新打开。");

    LocalDeviceTransfer(Activity activity, Provider provider) {
        this.activity = activity; this.provider = provider;
        nsd = (NsdManager) activity.getSystemService(Context.NSD_SERVICE);
    }
    boolean isActive() { return active; }
    void show() {
        if (active || disposed) return;
        active = true;
        LinearLayout content=NativeUi.content(activity);
        content.addView(NativeUi.paragraph(activity,"两台设备接入同一个 Wi-Fi，保持解锁。选择这台设备的角色，再比对双方屏幕上的验证码。"));
        Button sender=NativeUi.choice(activity,"旧设备 · 发送设置","复制网盘连接、目录、图库索引与界面设置");
        Button receiver=NativeUi.choice(activity,"新设备 · 扫描并接收","查找旧设备，确认后应用到这台设备");
        content.addView(sender);content.addView(receiver);
        dialog = NativeUi.dialog(activity).setTitle("局域网复制设置")
                .setView(NativeUi.scroll(activity,content))
                .setNegativeButton("取消", (d, w) -> close()).create();
        sender.setOnClickListener(view->{if(active){dialog.dismiss();begin(true);}});
        receiver.setOnClickListener(view->{if(active){dialog.dismiss();begin(false);}});
        dialog.setOnCancelListener(d -> close()); dialog.show();
    }
    private void begin(boolean sender) {
        lanNetwork = findLanNetwork();
        if (lanNetwork == null || nsd == null) { finish("请让两台设备接入同一个 Wi-Fi，再打开局域网复制设置。"); return; }
        expiresAt = SystemClock.elapsedRealtime() + LIFETIME;
        main.postDelayed(expire, LIFETIME);
        try {
            WifiManager wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("LifeMomentsTransfer");
                multicast.setReferenceCounted(false); multicast.acquire();
            }
            showProgress(sender);
            if (sender) worker.execute(this::send); else discover();
        } catch (Exception e) { fail(e); }
    }
    private Network findLanNetwork() {
        ConnectivityManager connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return network;
        }
        return null;
    }
    private void showProgress(boolean sender) {
        LinearLayout content=NativeUi.content(activity);
        content.addView(NativeUi.paragraph(activity,sender
                ? "等待新设备扫描。两边比对验证码后，才会发送网盘连接、目录、图库索引和界面设置。窗口有效 3 分钟。"
                : "在旧设备打开“发送设置”，随后选择下方设备。接收将替换当前设备的网盘连接、目录、图库索引和界面设置。窗口有效 3 分钟。"));
        statusText=NativeUi.status(activity,sender?"正在准备本机名称和局域网地址…":"正在扫描局域网设备…");
        content.addView(statusText);
        if (!sender) {
            TextView caption=NativeUi.text(activity,"发现的旧设备",12,NativeUi.MUTED);caption.setPadding(0,NativeUi.dp(activity,18),0,NativeUi.dp(activity,8));content.addView(caption);
            FrameLayout devices=new FrameLayout(activity);devices.setBackground(NativeUi.rounded(activity,NativeUi.SURFACE,14,true));devices.setClipToOutline(true);
            list=NativeUi.deviceList(activity);adapter=NativeUi.deviceAdapter(activity);list.setAdapter(adapter);list.setDivider(new ColorDrawable(NativeUi.LINE));list.setDividerHeight(NativeUi.dp(activity,1));list.setSelector(new ColorDrawable(NativeUi.SELECTED));
            TextView empty=NativeUi.text(activity,"还未发现设备\n请在旧设备选择“发送设置”",12,NativeUi.MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(NativeUi.dp(activity,18),0,NativeUi.dp(activity,18),0);
            devices.addView(list,new FrameLayout.LayoutParams(-1,-1));devices.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
            content.addView(devices,new LinearLayout.LayoutParams(-1,NativeUi.dp(activity,220)));
            list.setOnItemClickListener((parent, view, position, id) -> {
                if (!active || position >= names.size()) return;
                NsdServiceInfo selected = services.get(names.get(position));
                if (selected == null) return;
                connecting = true; list.setEnabled(false); stopDiscovery(); update("正在连接所选设备…");
                resolve(selected);
            });
        }
        AlertDialog.Builder builder = NativeUi.dialog(activity).setTitle(sender ? "发送设置" : "扫描旧设备")
                .setView(NativeUi.scroll(activity,content)).setNegativeButton("取消", (d, w) -> close());
        if (!sender) builder.setNeutralButton("手动输入地址", null);
        dialog = builder.create();
        dialog.setOnCancelListener(d -> close()); dialog.show();
        if (!sender) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> manualConnect());
    }
    private void manualConnect() {
        if (!active || connecting) return;
        LinearLayout content=NativeUi.content(activity);
        content.addView(NativeUi.paragraph(activity,"输入旧设备“发送设置”中显示的地址。随后仍需比对两台屏幕上的验证码。"));
        EditText field=NativeUi.field(content,"局域网 IP 地址与端口","",false);
        field.setHint("192.168.1.20:12345 或 [IPv6]:端口");
        AlertDialog manual = NativeUi.dialog(activity).setTitle("旧设备的局域网地址")
                .setView(NativeUi.scroll(activity,content)).setNegativeButton("取消", null).setPositiveButton("连接", null).create();
        manualDialog = manual; manual.show();
        manual.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!active || connecting) { manual.dismiss(); return; }
            try {
                String endpoint = field.getText().toString().trim();
                if (endpoint.length() > 180) throw new IOException("请输入完整的局域网地址与端口。");
                String host, portText;
                if (endpoint.startsWith("[")) {
                    int end = endpoint.indexOf("]:");
                    if (end < 2) throw new IOException("IPv6 地址请使用 [地址]:端口。");
                    host = endpoint.substring(1, end); portText = endpoint.substring(end + 2);
                    if (!host.contains(":") || !host.matches("[0-9a-fA-F:.]+(?:%[a-zA-Z0-9_.-]{1,24})?")) throw new IOException("IPv6 地址格式无效。");
                } else {
                    int end = endpoint.lastIndexOf(':');
                    if (end < 1) throw new IOException("请输入 地址:端口。");
                    host = endpoint.substring(0, end); portText = endpoint.substring(end + 1);
                    if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("请输入旧设备显示的局域网 IP 地址。");
                }
                if (!portText.matches("[0-9]{1,5}")) throw new IOException("端口格式无效。");
                int port = Integer.parseInt(portText);
                InetAddress address = InetAddress.getByName(host); // Numeric address only; never resolve a supplied hostname.
                if (port < 1 || port > 65535 || !isLanAddress(address, lanNetwork)) throw new IOException("请输入当前局域网内的设备地址。");
                connecting = true; list.setEnabled(false); stopDiscovery(); manual.dismiss(); update("正在连接输入的局域网设备…");
                worker.execute(() -> receive(Collections.singletonList(address), port, lanNetwork));
            } catch (Exception e) { field.setError("地址无效，请填写旧设备显示的 地址:端口。"); }
        });
    }
    private void discover() {
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { if(!active){try{nsd.stopServiceDiscovery(this);}catch(Exception ignored){}}else update("正在扫描局域网；请在旧设备打开“发送设置”。"); }
            @Override public void onServiceFound(NsdServiceInfo service) {
                main.post(() -> {
                    if (!active || discovery == null || services.size() >= 32 || !TYPE.replaceAll("\\.$", "").equals(service.getServiceType().replaceAll("\\.$", ""))) return;
                    String name = service.getServiceName();
                    if (name == null || name.length() > 120) return;
                    services.put(name, service); refreshList();
                });
            }
            @Override public void onServiceLost(NsdServiceInfo service) { main.post(() -> { services.remove(service.getServiceName()); refreshList(); }); }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int code) { finish("局域网扫描未能启动，请检查 Wi-Fi 后重试。"); }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
        };
        if (Build.VERSION.SDK_INT >= 33) nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, lanNetwork, main::post, discovery);
        else nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }
    private void refreshList() {
        if (adapter == null) return;
        names.clear(); names.addAll(services.keySet()); adapter.clear();
        for (String name : names) adapter.add(name.replaceAll("[\\p{Cntrl}]", " "));
        adapter.notifyDataSetChanged();
    }
    @SuppressWarnings("deprecation")
    private void resolve(NsdServiceInfo service) {
        nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int code) { finish("未能解析所选设备，请重新扫描。"); }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                if (!active) return;
                List<InetAddress> addresses = Build.VERSION.SDK_INT >= 34 ? info.getHostAddresses()
                        : info.getHost() == null ? Collections.emptyList() : Collections.singletonList(info.getHost());
                Network network = Build.VERSION.SDK_INT >= 33 && info.getNetwork() != null ? info.getNetwork() : lanNetwork;
                worker.execute(() -> receive(addresses, info.getPort(), network));
            }
        });
    }
    private void register(int port) {
        NsdServiceInfo service = new NsdServiceInfo();
        service.setServiceType(TYPE); service.setServiceName("生活点滴-" + UUID.randomUUID().toString().substring(0, 6));
        service.setPort(port); service.setAttribute("v", "1");
        if (Build.VERSION.SDK_INT >= 33) service.setNetwork(lanNetwork);
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo info) { if(!active){try{nsd.unregisterService(this);}catch(Exception ignored){}}else update("本机名称：" + info.getServiceName() + "\n在新设备选中此名称。若扫描不到，可手动输入：\n" + displayAddresses(port) + "\n双方比对验证码后才发送设置。有效 3 分钟。"); }
            @Override public void onRegistrationFailed(NsdServiceInfo info, int code) { finish("无法发布局域网设备，请检查 Wi-Fi 后重试。"); }
            @Override public void onServiceUnregistered(NsdServiceInfo info) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo info, int code) { }
        };
        nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
    }
    private void send() {
        byte[] payload = null;
        try {
            checkActive(); server = new ServerSocket(0, 1); server.setSoTimeout((int) LIFETIME); checkActive();
            final int port = server.getLocalPort(); main.post(() -> { if (active) register(port); });
            connection = server.accept();
            if (!isLanAddress(connection.getInetAddress(), lanNetwork)) throw new IOException("仅允许局域网内设备连接。");
            closeServer(); main.post(this::unregister);
            setupSocket(connection);
            DataInputStream in = new DataInputStream(connection.getInputStream());
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            crypto = handshake(in, out, true); approveBoth(in, out, true);
            checkActive(); update("正在加密发送设置与图库索引…");
            payload = provider.exportPayload().encode(); crypto.active();
            TransferWire.sendPayload(out, crypto, payload);
            byte[] acknowledgement = readFrame(in);
            if (acknowledgement.length != 1 || acknowledgement[0] != 3) throw new IOException("新设备未能完成导入，原设备设置已保留。");
            finish("设置已复制到新设备；原设备设置已保留。");
        } catch (Exception | OutOfMemoryError e) { fail(e); }
        finally { if (payload != null) Arrays.fill(payload, (byte) 0); closeTransport(); }
    }
    private void receive(List<InetAddress> addresses, int port, Network network) {
        byte[] payload = null;
        try {
            if (port < 1 || port > 65535 || addresses.size() > 32) throw new IOException("局域网设备地址无效。");
            for (InetAddress address : addresses) {
                if (!isLanAddress(address, network)) continue;
                checkActive(); Socket trial = network.getSocketFactory().createSocket(); connection = trial;
                try { trial.connect(new InetSocketAddress(address, port), 8000); break; }
                catch (IOException e) { trial.close(); connection = null; }
            }
            if (connection == null) throw new IOException("未能连接旧设备；请确认双方在同一 Wi-Fi，路由器没有隔离设备。");
            setupSocket(connection);
            DataInputStream in = new DataInputStream(connection.getInputStream());
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            crypto = handshake(in, out, false); approveBoth(in, out, false);
            update("正在接收加密设置与图库索引…");
            payload = TransferWire.receivePayload(in, crypto);
            TransferPayload checked = TransferPayload.decode(payload); checkActive();
            update("正在保存设置；网盘凭据使用本机安全存储重新加密…");
            provider.importPayload(checked); sendFrame(out, new byte[]{3});
            finish("设置已接收。可以直接查看图库；若网盘提示授权过期，请重新连接。");
        } catch (Exception | OutOfMemoryError e) { fail(e); }
        finally { if (payload != null) Arrays.fill(payload, (byte) 0); closeTransport(); }
    }
    private void setupSocket(Socket socket) throws Exception {
        socket.setSoTimeout(15000); socket.setTcpNoDelay(true); checkActive();
    }
    private TransferCrypto.Session handshake(DataInputStream in, DataOutputStream out, boolean sender) throws Exception {
        return TransferWire.handshake(in, out, sender, expiresAt, SystemClock::elapsedRealtime);
    }
    private void approveBoth(DataInputStream in, DataOutputStream out, boolean sender) throws Exception {
        CompletableFuture<Boolean> answer = new CompletableFuture<>(); approval = answer;
        final String verificationCode = crypto.code;
        main.post(() -> {
            if (!active) { answer.complete(false); return; }
            LinearLayout content=NativeUi.content(activity);
            content.addView(NativeUi.paragraph(activity,"只有两台屏幕的 6 位数字完全一致，才点击确认。"));
            content.addView(NativeUi.verification(activity,verificationCode));
            TextView scope=NativeUi.paragraph(activity,"确认后将复制网盘授权、目录、图库索引与界面设置。");scope.setPadding(0,NativeUi.dp(activity,16),0,0);content.addView(scope);
            pairDialog = NativeUi.dialog(activity).setTitle("比对两台设备上的验证码")
                    .setView(NativeUi.scroll(activity,content))
                    .setNegativeButton("不一致 / 取消", (d, w) -> { answer.complete(false); close(); })
                    .setPositiveButton(sender ? "一致，允许发送" : "一致，接收设置", (d, w) -> answer.complete(true)).create();
            pairDialog.setOnCancelListener(d -> { answer.complete(false); close(); }); pairDialog.show();
        });
        if (!answer.get(Math.min(60000, remaining()), TimeUnit.MILLISECONDS)) throw new IOException("配对已取消。");
        checkActive(); connection.setSoTimeout((int) Math.min(60000, remaining()));
        TransferWire.confirm(in, out, crypto);
        connection.setSoTimeout((int) Math.min(30000, remaining())); approval = null;
    }
    private void sendFrame(DataOutputStream out, byte[] bytes) throws Exception { TransferWire.sendFrame(out, crypto, bytes); }
    private byte[] readFrame(DataInputStream in) throws Exception { return TransferWire.readFrame(in, crypto); }
    static boolean lanAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) return false;
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) return true;
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }
    private boolean isLanAddress(InetAddress address, Network network) {
        if (lanAddress(address)) return true;
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) return false;
        ConnectivityManager connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties links = connectivity == null ? null : connectivity.getLinkProperties(network);
        // IPv6 LANs often use globally scoped addresses. Accept them only inside a directly connected local prefix.
        if (links != null) for (LinkAddress local : links.getLinkAddresses())
            if (TransferWire.samePrefix(local.getAddress().getAddress(), address.getAddress(), local.getPrefixLength())) return true;
        return false;
    }
    private String displayAddresses(int port) {
        ConnectivityManager connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties links = connectivity == null ? null : connectivity.getLinkProperties(lanNetwork);
        List<String> endpoints = new ArrayList<>();
        if (links != null) for (LinkAddress local : links.getLinkAddresses()) {
            InetAddress address = local.getAddress();
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) continue;
            String host = address.getHostAddress();
            String endpoint = (address instanceof Inet6Address ? "[" + host + "]" : host) + ":" + port;
            if (address instanceof Inet6Address) endpoints.add(endpoint); else endpoints.add(0, endpoint);
        }
        return endpoints.isEmpty() ? "暂无地址，请重新连接 Wi-Fi" : String.join("\n", endpoints.subList(0, Math.min(3, endpoints.size())));
    }
    private long remaining() throws IOException { checkActive(); return Math.max(1, expiresAt - SystemClock.elapsedRealtime()); }
    private void checkActive() throws IOException { if (!active || disposed || SystemClock.elapsedRealtime() >= expiresAt) throw new IOException("配对已过期或取消，请重新开始。"); }
    private void update(String message) { main.post(() -> { if (active) { if (statusText != null) statusText.setText(message); provider.onStatus(message); } }); }
    private void fail(Throwable error) {
        String message = error instanceof IOException && error.getMessage() != null ? error.getMessage()
                : "局域网迁移未完成，请保持双方解锁并在同一 Wi-Fi，重新打开后重试。";
        if (message.length() > 180) message = "局域网迁移未完成，请重新打开后重试。";
        finish(message);
    }
    private void finish(String message) {
        main.post(() -> { if (!active) return; provider.onStatus(message); close(); Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); });
    }
    private void stopDiscovery() {
        NsdManager.DiscoveryListener previous = discovery; discovery = null;
        if (previous != null) try { nsd.stopServiceDiscovery(previous); } catch (Exception ignored) { }
    }
    private void unregister() {
        NsdManager.RegistrationListener previous = registration; registration = null;
        if (previous != null) try { nsd.unregisterService(previous); } catch (Exception ignored) { }
    }
    private void closeServer() { ServerSocket previous = server; server = null; if (previous != null) try { previous.close(); } catch (IOException ignored) { } }
    private void closeTransport() {
        closeServer(); Socket previous = connection; connection = null;
        if (previous != null) try { previous.close(); } catch (IOException ignored) { }
        TransferCrypto.Session previousCrypto = crypto; crypto = null; if (previousCrypto != null) previousCrypto.close();
    }
    @Override public void close() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::close); return; }
        if (disposed) return;
        disposed = true; boolean notify = active; active = false; main.removeCallbacks(expire);
        if (approval != null) approval.complete(false);
        stopDiscovery(); unregister(); closeServer();
        closeTransport();
        if (multicast != null && multicast.isHeld()) multicast.release();
        multicast = null;
        if (pairDialog != null) pairDialog.dismiss(); if (dialog != null) dialog.dismiss(); if (manualDialog != null) manualDialog.dismiss();
        services.clear(); names.clear(); worker.shutdownNow();
        if (notify) provider.onClosed();
    }
}
