// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    public interface ServiceStateListener {
        void onServiceStateChanged(@Nullable XposedService service);
    }

    private static final Set<ServiceStateListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    public static void addServiceStateListener(
            @NonNull ServiceStateListener listener,
            boolean notifyImmediately) {
        LISTENERS.add(listener);
        if (notifyImmediately) {
            listener.onServiceStateChanged(service);
        }
    }

    public static void removeServiceStateListener(@NonNull ServiceStateListener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onServiceBind(@NonNull XposedService connectedService) {
        service = connectedService;
        notifyListeners(connectedService);
    }

    @Override
    public void onServiceDied(@NonNull XposedService deadService) {
        if (service == deadService) {
            service = null;
            notifyListeners(null);
        }
    }

    private static void notifyListeners(@Nullable XposedService currentService) {
        for (ServiceStateListener listener : LISTENERS) {
            listener.onServiceStateChanged(currentService);
        }
    }
}
