// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.app.NotificationChannel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "HyperOS4NotificationImportance";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String IMPORTANCE_KEY = "importance";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!SETTINGS_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        hookPreferenceVisibility(loadPackageParam.classLoader);
        hookChannelPreferenceSetup(loadPackageParam.classLoader);
    }

    private static void hookPreferenceVisibility(ClassLoader classLoader) {
        try {
            Class<?> baseSettings = XposedHelpers.findClass(
                    "com.android.settings.notification.BaseNotificationSettings",
                    classLoader);

            XposedBridge.hookAllMethods(baseSettings, "setPrefVisible", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || param.args[0] == null) {
                        return;
                    }

                    Object key = XposedHelpers.callMethod(param.args[0], "getKey");
                    if (IMPORTANCE_KEY.equals(key)) {
                        param.args[1] = true;
                    }
                }
            });
            log("已接管通知重要性选项的可见性");
        } catch (Throwable throwable) {
            log("未找到 BaseNotificationSettings#setPrefVisible", throwable);
            hookAndroidXVisibilityFallback(classLoader);
        }
    }

    private static void hookAndroidXVisibilityFallback(ClassLoader classLoader) {
        try {
            Class<?> preference = XposedHelpers.findClass("androidx.preference.Preference", classLoader);
            XposedHelpers.findAndHookMethod(preference, "setVisible", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!Boolean.FALSE.equals(param.args[0])) {
                                return;
                            }
                            Object key = XposedHelpers.callMethod(param.thisObject, "getKey");
                            if (IMPORTANCE_KEY.equals(key) && calledFromNotificationSettings()) {
                                param.args[0] = true;
                            }
                        }
                    });
            log("已启用 AndroidX 可见性兼容方案");
        } catch (Throwable throwable) {
            log("AndroidX 可见性兼容方案不可用", throwable);
        }
    }

    private static boolean calledFromNotificationSettings() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (className.startsWith("com.android.settings.notification.")) {
                return true;
            }
        }
        return false;
    }

    private static void hookChannelPreferenceSetup(ClassLoader classLoader) {
        try {
            Class<?> channelSettings = XposedHelpers.findClass(
                    "com.android.settings.notification.ChannelNotificationSettings",
                    classLoader);

            XposedBridge.hookAllMethods(channelSettings, "setupChannelDefaultPrefs",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            restoreImportancePreference(param.thisObject, classLoader);
                        }
                    });
            log("已接管通知渠道重要性设置");
        } catch (Throwable throwable) {
            log("未找到 ChannelNotificationSettings#setupChannelDefaultPrefs", throwable);
        }
    }

    private static void restoreImportancePreference(Object fragment, ClassLoader classLoader) {
        try {
            Object preference = XposedHelpers.callMethod(fragment, "findPreference", IMPORTANCE_KEY);
            if (preference == null) {
                log("当前通知渠道页面不包含 importance 偏好");
                return;
            }

            XposedHelpers.callMethod(preference, "setVisible", true);
            setFieldIfPresent(fragment, "mImportance", preference);

            int backupImportance = XposedHelpers.getIntField(fragment, "mBackupImportance");
            if (backupImportance > 0) {
                selectCurrentImportance(preference, backupImportance);
            }

            Object listener = createImportanceListener(fragment, classLoader);
            XposedHelpers.callMethod(preference, "setOnPreferenceChangeListener", listener);
        } catch (Throwable throwable) {
            log("恢复通知重要性偏好失败", throwable);
        }
    }

    private static void selectCurrentImportance(Object preference, int importance) {
        try {
            Object result = XposedHelpers.callMethod(
                    preference,
                    "findSpinnerIndexOfValue",
                    String.valueOf(importance));
            int index = (Integer) result;
            if (index >= 0) {
                XposedHelpers.callMethod(preference, "setValueIndex", index);
            }
        } catch (Throwable throwable) {
            log("同步当前通知重要性失败", throwable);
        }
    }

    private static Object createImportanceListener(Object fragment, ClassLoader classLoader)
            throws ClassNotFoundException {
        Class<?> listenerClass = XposedHelpers.findClass(
                "androidx.preference.Preference$OnPreferenceChangeListener",
                classLoader);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (!"onPreferenceChange".equals(method.getName()) || args == null || args.length < 2) {
                    return true;
                }

                try {
                    int importance = Integer.parseInt(String.valueOf(args[1]));
                    setFieldIfPresent(fragment, "mBackupImportance", importance);

                    NotificationChannel channel = (NotificationChannel)
                            XposedHelpers.getObjectField(fragment, "mChannel");
                    channel.setImportance(importance);
                    XposedHelpers.callMethod(channel, "lockFields", 4);

                    Object backend = XposedHelpers.getObjectField(fragment, "mBackend");
                    String packageName = (String) XposedHelpers.getObjectField(fragment, "mPkg");
                    int uid = XposedHelpers.getIntField(fragment, "mUid");
                    XposedHelpers.callMethod(backend, "updateChannel", packageName, uid, channel);
                    XposedHelpers.callMethod(fragment, "updateDependents", false);
                    return true;
                } catch (Throwable throwable) {
                    log("保存通知重要性失败", throwable);
                    return false;
                }
            }
        };

        return Proxy.newProxyInstance(classLoader, new Class<?>[]{listenerClass}, handler);
    }

    private static void setFieldIfPresent(Object object, String fieldName, Object value) {
        try {
            XposedHelpers.setObjectField(object, fieldName, value);
        } catch (Throwable throwable) {
            log("字段不存在: " + fieldName, throwable);
        }
    }

    private static void setFieldIfPresent(Object object, String fieldName, int value) {
        try {
            XposedHelpers.setIntField(object, fieldName, value);
        } catch (Throwable throwable) {
            log("字段不存在: " + fieldName, throwable);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable throwable) {
        XposedBridge.log(TAG + ": " + message);
        XposedBridge.log(throwable);
    }
}
