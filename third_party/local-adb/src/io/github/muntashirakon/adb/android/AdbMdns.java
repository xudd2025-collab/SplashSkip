// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringDef;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Automatic discovery of ADB daemons.
 */
// Copyright 2020 南宫雪珊
// Copyright 2022 Muntashir Al-Islam
// Based on https://android.googlesource.com/platform/packages/modules/adb/+/eddd2d3a386a83f5d1e14f87a318adef4c2f1a9d/adb_mdns.cpp
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
public class AdbMdns {
    public static final String SERVICE_TYPE_ADB = "adb";
    public static final String SERVICE_TYPE_TLS_PAIRING = "adb-tls-pairing";
    public static final String SERVICE_TYPE_TLS_CONNECT = "adb-tls-connect";

    @StringDef({
            SERVICE_TYPE_ADB,
            SERVICE_TYPE_TLS_PAIRING,
            SERVICE_TYPE_TLS_CONNECT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceType {
    }

    public interface OnAdbDaemonDiscoveredListener {
        void onPortChanged(@Nullable InetAddress hostAddress, int port);
    }

    @NonNull
    private final Context mContext;
    @NonNull
    private final String mServiceType;
    @NonNull
    private final OnAdbDaemonDiscoveredListener mAdbDaemonDiscoveredListener;
    private final NsdManager.DiscoveryListener mDiscoveryListener;
    private final NsdManager mNsdManager;

    private boolean mRegistered;
    private boolean mRunning;
    @Nullable
    private String mServiceName;
    private final Map<String, Long> mLiveServices = new HashMap<>();
    private final Map<String, PendingResolve> mPendingResolves = new LinkedHashMap<>();
    private PendingResolve mResolving;
    private long mResolveVersion;
    private final Handler mResolveHandler = new Handler(Looper.getMainLooper());

    public AdbMdns(@NonNull Context context, @ServiceType @NonNull String serviceType,
                   @NonNull OnAdbDaemonDiscoveredListener portChangeListener) {
        mContext = Objects.requireNonNull(context);
        mServiceType = String.format("_%s._tcp", Objects.requireNonNull(serviceType));
        mAdbDaemonDiscoveredListener = Objects.requireNonNull(portChangeListener);
        mNsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        mDiscoveryListener = new DiscoveryListener(this);
    }

    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        if (!mRegistered) {
            mNsdManager.discoverServices(mServiceType, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
        }
    }

    public synchronized void stop() {
        if (!mRunning) return;
        mRunning = false;
        mLiveServices.clear();
        mPendingResolves.clear();
        mServiceName = null;
        if (mRegistered) {
            mNsdManager.stopServiceDiscovery(mDiscoveryListener);
        }
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    private synchronized void onDiscoveryStart() {
        mRegistered = true;
        // stop() may run while Android is still registering this listener.
        if (!mRunning) mNsdManager.stopServiceDiscovery(mDiscoveryListener);
    }

    private synchronized void onDiscoverStop() {
        mRegistered = false;
        mLiveServices.clear();
        mPendingResolves.clear();
        mServiceName = null;
    }

    private synchronized void onServiceFound(NsdServiceInfo serviceInfo) {
        if (!mRunning) return;
        long version = ++mResolveVersion;
        mLiveServices.put(serviceInfo.getServiceName(), version);
        mPendingResolves.put(serviceInfo.getServiceName(), new PendingResolve(serviceInfo, version));
        resolveNext();
    }

    private synchronized void resolveNext() {
        if (!mRunning || mResolving != null || mPendingResolves.isEmpty()) return;
        String name = mPendingResolves.keySet().iterator().next();
        PendingResolve request = mPendingResolves.remove(name);
        mResolving = request;
        // Legacy NSD allows only one outstanding resolve. Queue the latest found
        // version instead of issuing a parallel resolve that can be rejected.
        try { mNsdManager.resolveService(request.info, new ResolveListener(this, request)); }
        catch (RuntimeException unavailable) { finishResolve(request); }
    }

    private synchronized void finishResolve(PendingResolve request) {
        if (mResolving != request) return;
        mResolving = null;
        resolveNext();
    }

    private synchronized void resolveFailed(PendingResolve request, int error) {
        if (error == NsdManager.FAILURE_ALREADY_ACTIVE && request.retries < 5 && mRunning
                && Objects.equals(mLiveServices.get(request.info.getServiceName()), request.version)) {
            long delay = 250L << request.retries++;
            mResolveHandler.postDelayed(() -> retryResolve(request), delay);
        }
        finishResolve(request);
    }

    private synchronized void retryResolve(PendingResolve request) {
        String name = request.info.getServiceName();
        if (!mRunning || !Objects.equals(mLiveServices.get(name), request.version)) return;
        mPendingResolves.put(name, request);
        resolveNext();
    }

    private synchronized void onServiceLost(NsdServiceInfo serviceInfo) {
        if (!mRunning) return;
        mLiveServices.remove(serviceInfo.getServiceName());
        mPendingResolves.remove(serviceInfo.getServiceName());
        if (mServiceName != null && mServiceName.equals(serviceInfo.getServiceName())) {
            mServiceName = null;
            mAdbDaemonDiscoveredListener.onPortChanged(serviceInfo.getHost(), -1);
        }
    }

    private synchronized void onServiceResolved(NsdServiceInfo serviceInfo, long version) {
        if (!mRunning || !Objects.equals(mLiveServices.get(serviceInfo.getServiceName()), version)) return;
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress inetAddress : Collections.list(networkInterface.getInetAddresses())) {
                    String inetHost = inetAddress.getHostAddress();
                    if (Objects.equals(inetHost, serviceInfo.getHost().getHostAddress())
                            && isPortAvailable(serviceInfo.getPort())) {
                        mServiceName = serviceInfo.getServiceName();
                        mAdbDaemonDiscoveredListener.onPortChanged(serviceInfo.getHost(), serviceInfo.getPort());
                        return;
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(AndroidUtils.getHostIpAddress(mContext), port), 1);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static class DiscoveryListener implements NsdManager.DiscoveryListener {
        @NonNull
        private final AdbMdns mAdbMdns;

        private DiscoveryListener(@NonNull AdbMdns adbMdns) {
            mAdbMdns = adbMdns;
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            mAdbMdns.onDiscoveryStart();
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            mAdbMdns.onDiscoverStop();
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            mAdbMdns.onServiceFound(serviceInfo);
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            mAdbMdns.onServiceLost(serviceInfo);
        }
    }

    private static class ResolveListener implements NsdManager.ResolveListener {
        @NonNull
        private final AdbMdns mAdbMdns;
        private final PendingResolve mRequest;

        private ResolveListener(@NonNull AdbMdns adbMdns, PendingResolve request) {
            mAdbMdns = adbMdns;
            mRequest = request;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            mAdbMdns.resolveFailed(mRequest, errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            try { mAdbMdns.onServiceResolved(serviceInfo, mRequest.version); }
            finally { mAdbMdns.finishResolve(mRequest); }
        }
    }

    private static class PendingResolve {
        private final NsdServiceInfo info;
        private final long version;
        private int retries;
        private PendingResolve(NsdServiceInfo value, long generation) { info = value; version = generation; }
    }
}
