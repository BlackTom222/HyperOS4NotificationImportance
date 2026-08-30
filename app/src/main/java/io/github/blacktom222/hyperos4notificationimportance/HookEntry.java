// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.app.NotificationChannel;
import android.app.NotificationManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "HyperOS4NotificationImportance";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String IMPORTANCE_KEY = "importance";
    private static boolean systemUiFilterLogged;
    private static boolean systemUiSilentPolicyLogged;
    private static boolean systemUiResolutionErrorLogged;
    private static Object notificationCollection;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (SETTINGS_PACKAGE.equals(loadPackageParam.packageName)) {
            hookPreferenceVisibility(loadPackageParam.classLoader);
            hookChannelPreferenceSetup(loadPackageParam.classLoader);
            return;
        }

        if (SYSTEM_UI_PACKAGE.equals(loadPackageParam.packageName)) {
            hookSystemUiLowPriorityIcons(loadPackageParam.classLoader);
        }
    }

    private static void hookSystemUiLowPriorityIcons(ClassLoader classLoader) {
        boolean silentPolicyHooked = hookSilentIconPolicy(classLoader);
        boolean iconPredicateHooked = hookNotificationIconPredicate(classLoader);
        boolean pipelineHooked = false;
        boolean legacyHooked = false;

        if (!silentPolicyHooked || !iconPredicateHooked) {
            pipelineHooked = hookStackCoordinator(classLoader);
        }
        if (!iconPredicateHooked && !pipelineHooked) {
            legacyHooked = hookLegacyNotificationIconController(classLoader);
        }

        if (iconPredicateHooked && silentPolicyHooked) {
            log("已接管 System UI 新版通知图标过滤管线");
        } else if (pipelineHooked) {
            log("已接管 System UI 的 StackCoordinator 兼容管线");
        } else if (legacyHooked) {
            log("已接管 System UI 旧版通知图标管线");
        } else {
            log("未找到可用的 System UI 通知图标过滤入口");
        }
    }

    private static boolean hookSilentIconPolicy(ClassLoader classLoader) {
        boolean hooked = false;
        XC_MethodHook forceHideSilentIcons = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                    param.args[0] = true;
                    logSilentPolicyOnce();
                }
            }
        };

        hooked |= hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.domain.interactor."
                        + "SilentNotificationStatusIconsVisibilityInteractor",
                "setHideSilentStatusIcons",
                classLoader,
                forceHideSilentIcons);
        hooked |= hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.notification.MiuiNotificationListener",
                "onSilentStatusBarIconsVisibilityChanged",
                classLoader,
                forceHideSilentIcons);
        hooked |= hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.NotificationListener",
                "onSilentStatusBarIconsVisibilityChanged",
                classLoader,
                forceHideSilentIcons);

        try {
            Class<?> repositoryClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.data.repository."
                            + "NotificationListenerSettingsRepository",
                    classLoader);
            if (repositoryClass != null) {
                XposedBridge.hookAllConstructors(repositoryClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        forceShowSilentStatusIconsOff(param.thisObject);
                    }
                });
                hooked = true;
            }
        } catch (Throwable throwable) {
            log("接管静默通知图标设置仓库失败", throwable);
        }

        try {
            Class<?> statusBarInteractor = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.notification.icon.domain.interactor."
                            + "StatusBarNotificationIconsInteractor",
                    classLoader);
            if (statusBarInteractor != null) {
                XposedBridge.hookAllConstructors(statusBarInteractor, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        for (Object argument : param.args) {
                            if (argument != null && argument.getClass().getName().contains(
                                    "NotificationListenerSettingsRepository")) {
                                forceShowSilentStatusIconsOff(argument);
                            }
                        }
                    }
                });
                hooked = true;
            }
        } catch (Throwable throwable) {
            log("接管状态栏静默通知策略失败", throwable);
        }

        return hooked;
    }

    private static boolean hookNotificationIconPredicate(ClassLoader classLoader) {
        try {
            Class<?> predicateClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.notification.icon.domain.interactor."
                            + "NotificationIconsInteractor$filteredNotifSet$1$1",
                    classLoader);
            if (predicateClass == null) {
                return false;
            }

            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    predicateClass,
                    "invoke",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.getResult() instanceof Boolean)
                                    || !Boolean.TRUE.equals(param.getResult())
                                    || param.args.length == 0
                                    || param.args[0] == null) {
                                return;
                            }

                            Boolean showLowPriority = readBooleanField(
                                    param.thisObject, "$showLowPriority");
                            if (!Boolean.FALSE.equals(showLowPriority)) {
                                return;
                            }

                            Object notificationModel = param.args[0];
                            Integer importance = resolveImportance(
                                    notificationModel, classLoader);
                            Boolean silent = resolveSilent(notificationModel);
                            if ((importance != null
                                    && importance <= NotificationManager.IMPORTANCE_LOW)
                                    || Boolean.TRUE.equals(silent)) {
                                param.setResult(false);
                                logSystemUiFilterOnce();
                            }
                        }
                    });
            return !hooks.isEmpty();
        } catch (Throwable throwable) {
            log("接管 NotificationIconsInteractor 失败", throwable);
            return false;
        }
    }

    private static boolean hookStackCoordinator(ClassLoader classLoader) {
        XC_MethodHook filterRenderedList = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof List<?>)) {
                    return;
                }

                List<?> entries = (List<?>) param.args[0];
                ArrayList<Object> filtered = new ArrayList<>(entries.size());
                for (Object listEntry : entries) {
                    Integer importance = resolveListEntryImportance(listEntry);
                    if (importance == null
                            || importance > NotificationManager.IMPORTANCE_LOW) {
                        filtered.add(listEntry);
                    }
                }
                if (filtered.size() != entries.size()) {
                    param.args[0] = filtered;
                    logSystemUiFilterOnce();
                }
            }
        };

        if (hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.notification.collection.coordinator."
                        + "StackCoordinator$attach$1",
                "onAfterRenderList",
                classLoader,
                filterRenderedList)) {
            return true;
        }
        return hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.notification.collection.coordinator."
                        + "StackCoordinator",
                "onAfterRenderList",
                classLoader,
                filterRenderedList);
    }

    private static boolean hookLegacyNotificationIconController(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.phone.NotificationIconAreaController",
                    classLoader);
            if (controllerClass == null) {
                return false;
            }

            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    controllerClass,
                    "updateStatusBarIcons",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object entriesObject;
                            try {
                                entriesObject = XposedHelpers.getObjectField(
                                        param.thisObject, "mNotificationEntries");
                            } catch (Throwable ignored) {
                                return;
                            }
                            if (!(entriesObject instanceof List<?>)) {
                                return;
                            }

                            List<?> entries = (List<?>) entriesObject;
                            ArrayList<Object> filtered = new ArrayList<>(entries.size());
                            for (Object listEntry : entries) {
                                Integer importance = resolveListEntryImportance(listEntry);
                                if (importance == null
                                        || importance > NotificationManager.IMPORTANCE_LOW) {
                                    filtered.add(listEntry);
                                }
                            }
                            if (filtered.size() != entries.size()) {
                                XposedHelpers.setObjectField(
                                        param.thisObject, "mNotificationEntries", filtered);
                                logSystemUiFilterOnce();
                            }
                        }
                    });
            return !hooks.isEmpty();
        } catch (Throwable throwable) {
            log("接管旧版通知图标管线失败", throwable);
            return false;
        }
    }

    private static boolean hookAllMethodsIfPresent(
            String className,
            String methodName,
            ClassLoader classLoader,
            XC_MethodHook hook) {
        try {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, classLoader);
            return targetClass != null
                    && !XposedBridge.hookAllMethods(targetClass, methodName, hook).isEmpty();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static void forceShowSilentStatusIconsOff(Object repository) {
        try {
            Object flow;
            try {
                flow = XposedHelpers.getObjectField(repository, "showSilentStatusIcons");
            } catch (Throwable ignored) {
                flow = XposedHelpers.callMethod(repository, "getShowSilentStatusIcons");
            }
            XposedHelpers.callMethod(flow, "setValue", false);
            logSilentPolicyOnce();
        } catch (Throwable throwable) {
            if (!systemUiResolutionErrorLogged) {
                systemUiResolutionErrorLogged = true;
                log("关闭静默通知状态栏图标失败", throwable);
            }
        }
    }

    private static Integer resolveImportance(Object notificationModel, ClassLoader classLoader) {
        Integer directImportance = readImportance(notificationModel);
        if (directImportance != null) {
            return directImportance;
        }

        try {
            String key;
            try {
                key = String.valueOf(XposedHelpers.getObjectField(notificationModel, "key"));
            } catch (Throwable ignored) {
                key = String.valueOf(XposedHelpers.callMethod(notificationModel, "getKey"));
            }
            if (key == null || "null".equals(key)) {
                return null;
            }

            Object collection = getNotificationCollection(classLoader);
            Object entry = collection == null
                    ? null
                    : XposedHelpers.callMethod(collection, "getEntry", key);
            return readImportance(entry);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Object getNotificationCollection(ClassLoader classLoader) {
        if (notificationCollection != null) {
            return notificationCollection;
        }
        try {
            Class<?> dependencyClass = XposedHelpers.findClass(
                    "com.android.systemui.Dependency", classLoader);
            Class<?> helperClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.policy.DismissNotificationHelper",
                    classLoader);
            Object helper = XposedHelpers.callStaticMethod(
                    dependencyClass, "get", helperClass);
            notificationCollection = XposedHelpers.getObjectField(helper, "notifCollection");
        } catch (Throwable throwable) {
            return null;
        }
        return notificationCollection;
    }

    private static Integer resolveListEntryImportance(Object listEntry) {
        if (listEntry == null) {
            return null;
        }
        try {
            Object entry = XposedHelpers.callMethod(listEntry, "getRepresentativeEntry");
            return readImportance(entry);
        } catch (Throwable ignored) {
            return readImportance(listEntry);
        }
    }

    private static Integer readImportance(Object object) {
        if (object == null) {
            return null;
        }
        try {
            Object ranking;
            try {
                ranking = XposedHelpers.getObjectField(object, "mRanking");
            } catch (Throwable ignored) {
                ranking = XposedHelpers.callMethod(object, "getRanking");
            }
            return (Integer) XposedHelpers.callMethod(ranking, "getImportance");
        } catch (Throwable ignored) {
            try {
                return (Integer) XposedHelpers.callMethod(object, "getImportance");
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static Boolean resolveSilent(Object notificationModel) {
        try {
            return (Boolean) XposedHelpers.callMethod(notificationModel, "isSilent");
        } catch (Throwable ignored) {
            return readBooleanField(notificationModel, "isSilent");
        }
    }

    private static Boolean readBooleanField(Object object, String fieldName) {
        try {
            return XposedHelpers.getBooleanField(object, fieldName);
        } catch (Throwable ignored) {
            try {
                Object value = XposedHelpers.getObjectField(object, fieldName);
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static void logSilentPolicyOnce() {
        if (!systemUiSilentPolicyLogged) {
            systemUiSilentPolicyLogged = true;
            log("已关闭静默通知的状态栏图标显示策略");
        }
    }

    private static void logSystemUiFilterOnce() {
        if (!systemUiFilterLogged) {
            systemUiFilterLogged = true;
            log("已隐藏低优先级通知的状态栏图标");
        }
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
            if (index < 0 && importance == NotificationManager.IMPORTANCE_MIN) {
                index = (Integer) XposedHelpers.callMethod(
                        preference,
                        "findSpinnerIndexOfValue",
                        String.valueOf(NotificationManager.IMPORTANCE_LOW));
            }
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
