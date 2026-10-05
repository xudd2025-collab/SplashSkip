package com.codex.splashskip;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import io.github.muntashirakon.adb.android.AdbMdns;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/** Runs against a fake NSD callback queue and occupied real loopback ports. */
public final class MdnsLifecycleCheck {
    private static int checks;
    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("NSD lifecycle check " + checks);
    }
    public static void main(String[] args) throws Exception {
        NsdManager manager = new NsdManager();
        List<Integer> events = new ArrayList<>();
        AdbMdns discovery = new AdbMdns(new Context(manager), AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                (address, port) -> events.add(port));
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket oldSocket = new ServerSocket(0, 1, loopback);
             ServerSocket newSocket = new ServerSocket(0, 1, loopback)) {
            NsdServiceInfo old = new NsdServiceInfo("old", loopback, oldSocket.getLocalPort());
            NsdServiceInfo current = new NsdServiceInfo("current", loopback, newSocket.getLocalPort());
            discovery.start(); manager.discovery.onDiscoveryStarted("test");
            manager.discovery.onServiceFound(old);
            NsdManager.ResolveListener delayed = manager.resolves.get(0);
            manager.discovery.onServiceLost(old);
            manager.discovery.onServiceFound(current);
            check(manager.resolves.size() == 1);
            delayed.onServiceResolved(old);
            check(events.isEmpty() && manager.resolves.size() == 2);
            manager.resolves.get(1).onServiceResolved(current);
            check(events.equals(List.of(current.getPort())));
            delayed.onServiceResolved(old);
            check(events.equals(List.of(current.getPort())));
            manager.discovery.onServiceLost(old);
            check(events.equals(List.of(current.getPort())));
            manager.discovery.onServiceLost(current);
            check(events.equals(List.of(current.getPort(), -1)));
            manager.resolves.get(1).onServiceResolved(current);
            check(events.equals(List.of(current.getPort(), -1)));

            // Reused service name: a superseded resolution cannot replace the new port.
            NsdServiceInfo sameOld = new NsdServiceInfo("same", loopback, old.getPort());
            NsdServiceInfo sameNew = new NsdServiceInfo("same", loopback, current.getPort());
            manager.discovery.onServiceFound(sameOld);
            manager.discovery.onServiceFound(sameNew);
            check(manager.resolves.size() == 3);
            manager.resolves.get(2).onServiceResolved(sameOld);
            check(manager.resolves.size() == 4 && events.size() == 2);
            manager.resolves.get(3).onServiceResolved(sameNew);
            check(events.get(events.size() - 1) == sameNew.getPort());
            int before = events.size();
            manager.resolves.get(2).onServiceResolved(sameOld);
            check(events.size() == before);
            manager.discovery.onServiceFound(sameOld);
            manager.discovery.onServiceFound(sameNew);
            check(manager.resolves.size() == 5);
            manager.resolves.get(4).onResolveFailed(sameOld, 3);
            check(manager.resolves.size() == 6);
            manager.resolves.get(5).onServiceResolved(sameNew);
            check(events.size() == before + 1 && events.get(events.size() - 1) == sameNew.getPort());
            before = events.size();
            // A resolve from a just-stopped instance can still occupy legacy NSD.
            manager.discovery.onServiceFound(current);
            manager.resolves.get(6).onResolveFailed(current, NsdManager.FAILURE_ALREADY_ACTIVE);
            check(!Handler.pending.isEmpty() && manager.resolves.size() == 7);
            Handler.runNext();
            check(manager.resolves.size() == 8);
            manager.resolves.get(7).onServiceResolved(current);
            check(events.size() == before + 1 && events.get(events.size() - 1) == current.getPort());
            before = events.size();
            manager.discovery.onServiceFound(current);
            manager.resolves.get(8).onResolveFailed(current, NsdManager.FAILURE_ALREADY_ACTIVE);
            manager.discovery.onServiceLost(current);
            before = events.size();
            Handler.runNext();
            check(manager.resolves.size() == 9);
            discovery.stop();
            check(manager.stopCalls == 1 && !discovery.isRunning());
            manager.resolves.get(3).onServiceResolved(sameNew);
            manager.discovery.onServiceLost(sameNew);
            manager.discovery.onServiceFound(current);
            check(events.size() == before && manager.resolves.size() == 9);
        }
        NsdManager pendingManager = new NsdManager();
        AdbMdns pending = new AdbMdns(new Context(pendingManager), AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                (address, port) -> { throw new AssertionError("Stopped discovery emitted endpoint"); });
        pending.start(); pending.stop();
        check(pendingManager.stopCalls == 0);
        pendingManager.discovery.onDiscoveryStarted("test");
        check(pendingManager.stopCalls == 1 && !pending.isRunning());
        System.out.println("NSD lifecycle checks passed: " + checks);
    }
}
