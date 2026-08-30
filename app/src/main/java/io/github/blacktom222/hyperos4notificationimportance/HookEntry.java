// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.blacktom222.hyperos4notificationimportance;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class HookEntry extends XposedModule {
    private static final String TAG = "HyperOS4NotificationImportance";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String IMPORTANCE_KEY = "importance";

    private boolean systemUiFilterLogged;
    private boolean systemUiMediumRestoreLogged;
    private boolean systemUiMediumPromotionLogged;
    private boolean systemUiSilentPolicyLogged;
    private Object notificationCollection;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        logInfo("API " + getApiVersion() + " 入口已加载，进程=" + param.getProcessName()
                + "，框架=" + getFrameworkName() + "/" + getFrameworkVersionCode());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }

        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getClassLoader();
        if (SETTINGS_PACKAGE.equals(packageName)) {
            hookPreferenceVisibility(classLoader);
            hookChannelPreferenceSetup(classLoader);
        } else if (SYSTEM_UI_PACKAGE.equals(packageName)) {
            hookSystemUiLowPriorityIcons(classLoader);
        }
    }

    private void hookSystemUiLowPriorityIcons(ClassLoader classLoader) {
        boolean rankingHooked = hookStatusIconRanking(classLoader);
        boolean silentPolicyHooked = hookSilentIconPolicy(classLoader);
        boolean iconPredicateHooked = hookNotificationIconPredicate(classLoader);
        boolean pipelineHooked = hookStackCoordinator(classLoader);
        boolean legacyHooked = false;

        if (!iconPredicateHooked && !pipelineHooked) {
            legacyHooked = hookLegacyNotificationIconController(classLoader);
        }

        if (iconPredicateHooked) {
            logInfo("已接管 System UI 新版通知图标过滤管线");
        }
        if (rankingHooked) {
            logInfo("已接管 System UI 状态栏图标重要性判定");
        }
        if (silentPolicyHooked) {
            logInfo("已接管 System UI 静默通知图标策略");
        }
        if (pipelineHooked) {
            logInfo("已接管 System UI 的 StackCoordinator 兼容管线");
        }
        if (!iconPredicateHooked && !pipelineHooked && legacyHooked) {
            logInfo("已接管 System UI 旧版通知图标管线");
        } else if (!iconPredicateHooked && !pipelineHooked && !legacyHooked) {
            logInfo("未找到可用的 System UI 通知图标过滤入口");
        }
    }

    /**
     * HyperOS 4 treats IMPORTANCE_DEFAULT as silent in its final status-icon pipeline. Promote
     * DEFAULT to HIGH only while SystemUI calculates status-bar icons. The stored channel and
     * ranking remain unchanged, so sound, heads-up and notification-shade behavior still use the
     * real importance.
     */
    private boolean hookStatusIconRanking(ClassLoader classLoader) {
        try {
            Class<?> rankingClass = Class.forName(
                    "android.service.notification.NotificationListenerService$Ranking",
                    false,
                    classLoader);
            return hookAllMethods(rankingClass, "getImportance", chain -> {
                Object result = chain.proceed();
                if (result instanceof Integer
                        && ((Integer) result) == NotificationManager.IMPORTANCE_DEFAULT
                        && calledFromStatusIconPipeline()) {
                    logSystemUiMediumPromotionOnce();
                    return NotificationManager.IMPORTANCE_HIGH;
                }
                return result;
            });
        } catch (Throwable throwable) {
            logError("接管状态栏图标重要性判定失败", throwable);
            return false;
        }
    }

    private boolean calledFromStatusIconPipeline() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (className.startsWith("com.android.systemui.statusbar.notification.icon.")
                    || className.contains(
                    "statusbar.notification.collection.coordinator.StackCoordinator")
                    || className.endsWith("NotificationIconAreaController")) {
                return true;
            }
        }
        return false;
    }

    private boolean hookSilentIconPolicy(ClassLoader classLoader) {
        XposedInterface.Hooker forceShowSilentIcons = chain -> {
            if (chain.getArgs().isEmpty() || !(chain.getArg(0) instanceof Boolean)) {
                return chain.proceed();
            }

            Object[] args = chain.getArgs().toArray();
            args[0] = false;
            logSilentPolicyOnce();
            return chain.proceed(args);
        };

        boolean hooked = false;
        hooked |= hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.domain.interactor."
                        + "SilentNotificationStatusIconsVisibilityInteractor",
                "setHideSilentStatusIcons",
                classLoader,
                forceShowSilentIcons);
        hooked |= hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.notification.MiuiNotificationListener",
                "onSilentStatusBarIconsVisibilityChanged",
                classLoader,
                forceShowSilentIcons);
        hooked |= hookAllMethodsIfPresent(
                "com.android.systemui.statusbar.NotificationListener",
                "onSilentStatusBarIconsVisibilityChanged",
                classLoader,
                forceShowSilentIcons);
        return hooked;
    }

    private boolean hookNotificationIconPredicate(ClassLoader classLoader) {
        String className = "com.android.systemui.statusbar.notification.icon.domain.interactor."
                + "NotificationIconsInteractor$filteredNotifSet$1$1";
        try {
            Class<?> predicateClass = findClassIfExists(className, classLoader);
            if (predicateClass == null) {
                return false;
            }

            return hookAllMethods(predicateClass, "invoke", chain -> {
                Object result = chain.proceed();
                if (!(result instanceof Boolean) || chain.getArgs().isEmpty()) {
                    return result;
                }

                Integer importance = resolveImportance(chain.getArg(0), classLoader);
                if (importance != null && importance <= NotificationManager.IMPORTANCE_LOW) {
                    logSystemUiFilterOnce();
                    return false;
                }
                if (importance != null
                        && importance >= NotificationManager.IMPORTANCE_DEFAULT
                        && !Boolean.TRUE.equals(result)
                        && Boolean.FALSE.equals(readBooleanField(
                                chain.getThisObject(), "$showLowPriority"))) {
                    logSystemUiMediumRestoreOnce(importance);
                    return true;
                }
                return result;
            });
        } catch (Throwable throwable) {
            logError("接管 NotificationIconsInteractor 失败", throwable);
            return false;
        }
    }

    private boolean hookStackCoordinator(ClassLoader classLoader) {
        XposedInterface.Hooker filterRenderedList = chain -> {
            if (chain.getArgs().isEmpty() || !(chain.getArg(0) instanceof List<?>)) {
                return chain.proceed();
            }

            List<?> entries = (List<?>) chain.getArg(0);
            ArrayList<Object> filtered = new ArrayList<>(entries.size());
            for (Object listEntry : entries) {
                Integer importance = resolveListEntryImportance(listEntry);
                if (importance == null || importance > NotificationManager.IMPORTANCE_LOW) {
                    filtered.add(listEntry);
                }
            }
            if (filtered.size() == entries.size()) {
                return chain.proceed();
            }

            Object[] args = chain.getArgs().toArray();
            args[0] = filtered;
            logSystemUiFilterOnce();
            return chain.proceed(args);
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

    private boolean hookLegacyNotificationIconController(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = findClassIfExists(
                    "com.android.systemui.statusbar.phone.NotificationIconAreaController",
                    classLoader);
            if (controllerClass == null) {
                return false;
            }

            return hookAllMethods(controllerClass, "updateStatusBarIcons", chain -> {
                Object entriesObject;
                try {
                    entriesObject = getFieldValue(chain.getThisObject(), "mNotificationEntries");
                } catch (Throwable ignored) {
                    return chain.proceed();
                }
                if (!(entriesObject instanceof List<?>)) {
                    return chain.proceed();
                }

                List<?> entries = (List<?>) entriesObject;
                ArrayList<Object> filtered = new ArrayList<>(entries.size());
                for (Object listEntry : entries) {
                    Integer importance = resolveListEntryImportance(listEntry);
                    if (importance == null || importance > NotificationManager.IMPORTANCE_LOW) {
                        filtered.add(listEntry);
                    }
                }
                if (filtered.size() != entries.size()) {
                    setFieldValue(chain.getThisObject(), "mNotificationEntries", filtered);
                    logSystemUiFilterOnce();
                }
                return chain.proceed();
            });
        } catch (Throwable throwable) {
            logError("接管旧版通知图标管线失败", throwable);
            return false;
        }
    }

    private Integer resolveImportance(Object notificationModel, ClassLoader classLoader) {
        Integer directImportance = readImportance(notificationModel);
        if (directImportance != null) {
            return directImportance;
        }

        try {
            Object keyValue;
            try {
                keyValue = getFieldValue(notificationModel, "key");
            } catch (Throwable ignored) {
                keyValue = callMethod(notificationModel, "getKey");
            }
            String key = String.valueOf(keyValue);
            if ("null".equals(key)) {
                return null;
            }

            Object collection = getNotificationCollection(classLoader);
            Object entry = collection == null ? null : callMethod(collection, "getEntry", key);
            return readImportance(entry);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getNotificationCollection(ClassLoader classLoader) {
        if (notificationCollection != null) {
            return notificationCollection;
        }
        try {
            Class<?> dependencyClass = Class.forName(
                    "com.android.systemui.Dependency", false, classLoader);
            Class<?> helperClass = Class.forName(
                    "com.android.systemui.statusbar.policy.DismissNotificationHelper",
                    false,
                    classLoader);
            Object helper = callStaticMethod(dependencyClass, "get", helperClass);
            notificationCollection = getFieldValue(helper, "notifCollection");
        } catch (Throwable ignored) {
            return null;
        }
        return notificationCollection;
    }

    private Integer resolveListEntryImportance(Object listEntry) {
        if (listEntry == null) {
            return null;
        }
        try {
            return readImportance(callMethod(listEntry, "getRepresentativeEntry"));
        } catch (Throwable ignored) {
            return readImportance(listEntry);
        }
    }

    private Integer readImportance(Object object) {
        if (object == null) {
            return null;
        }
        try {
            Object ranking;
            try {
                ranking = getFieldValue(object, "mRanking");
            } catch (Throwable ignored) {
                ranking = callMethod(object, "getRanking");
            }
            return (Integer) callMethod(ranking, "getImportance");
        } catch (Throwable ignored) {
            try {
                return (Integer) callMethod(object, "getImportance");
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private void hookPreferenceVisibility(ClassLoader classLoader) {
        try {
            Class<?> baseSettings = Class.forName(
                    "com.android.settings.notification.BaseNotificationSettings",
                    false,
                    classLoader);
            if (!hookAllMethods(baseSettings, "setPrefVisible", chain -> {
                if (chain.getArgs().size() < 2 || chain.getArg(0) == null) {
                    return chain.proceed();
                }

                Object key = callMethod(chain.getArg(0), "getKey");
                if (!IMPORTANCE_KEY.equals(key)) {
                    return chain.proceed();
                }
                Object[] args = chain.getArgs().toArray();
                args[1] = true;
                return chain.proceed(args);
            })) {
                throw new NoSuchMethodException("setPrefVisible");
            }
            logInfo("已接管通知重要性选项的可见性");
        } catch (Throwable throwable) {
            logError("未找到 BaseNotificationSettings#setPrefVisible", throwable);
            hookAndroidXVisibilityFallback(classLoader);
        }
    }

    private void hookAndroidXVisibilityFallback(ClassLoader classLoader) {
        try {
            Class<?> preference = Class.forName(
                    "androidx.preference.Preference", false, classLoader);
            Method setVisible = preference.getDeclaredMethod("setVisible", boolean.class);
            hook(setVisible).intercept(chain -> {
                if (!Boolean.FALSE.equals(chain.getArg(0))) {
                    return chain.proceed();
                }
                Object key = callMethod(chain.getThisObject(), "getKey");
                if (!IMPORTANCE_KEY.equals(key) || !calledFromNotificationSettings()) {
                    return chain.proceed();
                }
                return chain.proceed(new Object[]{true});
            });
            logInfo("已启用 AndroidX 可见性兼容方案");
        } catch (Throwable throwable) {
            logError("AndroidX 可见性兼容方案不可用", throwable);
        }
    }

    private boolean calledFromNotificationSettings() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getClassName().startsWith("com.android.settings.notification.")) {
                return true;
            }
        }
        return false;
    }

    private void hookChannelPreferenceSetup(ClassLoader classLoader) {
        try {
            Class<?> channelSettings = Class.forName(
                    "com.android.settings.notification.ChannelNotificationSettings",
                    false,
                    classLoader);
            if (!hookAllMethods(channelSettings, "setupChannelDefaultPrefs", chain -> {
                Object result = chain.proceed();
                restoreImportancePreference(chain.getThisObject(), classLoader);
                return result;
            })) {
                throw new NoSuchMethodException("setupChannelDefaultPrefs");
            }
            logInfo("已接管通知渠道重要性设置");
        } catch (Throwable throwable) {
            logError("未找到 ChannelNotificationSettings#setupChannelDefaultPrefs", throwable);
        }
    }

    private void restoreImportancePreference(Object fragment, ClassLoader classLoader) {
        try {
            Object preference = callMethod(fragment, "findPreference", IMPORTANCE_KEY);
            if (preference == null) {
                logInfo("当前通知渠道页面不包含 importance 偏好");
                return;
            }

            callMethod(preference, "setVisible", true);
            setFieldIfPresent(fragment, "mImportance", preference);

            int backupImportance = ((Number) getFieldValue(
                    fragment, "mBackupImportance")).intValue();
            if (backupImportance > 0) {
                selectCurrentImportance(preference, backupImportance);
            }

            Object listener = createImportanceListener(fragment, classLoader);
            callMethod(preference, "setOnPreferenceChangeListener", listener);
        } catch (Throwable throwable) {
            logError("恢复通知重要性偏好失败", throwable);
        }
    }

    private void selectCurrentImportance(Object preference, int importance) {
        try {
            int index = ((Number) callMethod(
                    preference,
                    "findSpinnerIndexOfValue",
                    String.valueOf(importance))).intValue();
            if (index < 0 && importance == NotificationManager.IMPORTANCE_MIN) {
                index = ((Number) callMethod(
                        preference,
                        "findSpinnerIndexOfValue",
                        String.valueOf(NotificationManager.IMPORTANCE_LOW))).intValue();
            }
            if (index >= 0) {
                callMethod(preference, "setValueIndex", index);
            }
        } catch (Throwable throwable) {
            logError("同步当前通知重要性失败", throwable);
        }
    }

    private Object createImportanceListener(Object fragment, ClassLoader classLoader)
            throws ClassNotFoundException {
        Class<?> listenerClass = Class.forName(
                "androidx.preference.Preference$OnPreferenceChangeListener",
                false,
                classLoader);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (!"onPreferenceChange".equals(method.getName())
                        || args == null
                        || args.length < 2) {
                    return true;
                }

                try {
                    int importance = Integer.parseInt(String.valueOf(args[1]));
                    setFieldIfPresent(fragment, "mBackupImportance", importance);

                    NotificationChannel channel = (NotificationChannel) getFieldValue(
                            fragment, "mChannel");
                    channel.setImportance(importance);
                    callMethod(channel, "lockFields", 4);

                    Object backend = getFieldValue(fragment, "mBackend");
                    String packageName = (String) getFieldValue(fragment, "mPkg");
                    int uid = ((Number) getFieldValue(fragment, "mUid")).intValue();
                    callMethod(backend, "updateChannel", packageName, uid, channel);
                    callMethod(fragment, "updateDependents", false);
                    logInfo("已保存通知渠道重要性，importance=" + importance);
                    return true;
                } catch (Throwable throwable) {
                    logError("保存通知重要性失败", throwable);
                    return false;
                }
            }
        };

        return Proxy.newProxyInstance(classLoader, new Class<?>[]{listenerClass}, handler);
    }

    private boolean hookAllMethodsIfPresent(
            String className,
            String methodName,
            ClassLoader classLoader,
            XposedInterface.Hooker hooker) {
        Class<?> targetClass = findClassIfExists(className, classLoader);
        return targetClass != null && hookAllMethods(targetClass, methodName, hooker);
    }

    private boolean hookAllMethods(
            Class<?> targetClass,
            String methodName,
            XposedInterface.Hooker hooker) {
        boolean hooked = false;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                hook(method).intercept(hooker);
                hooked = true;
            } catch (Throwable throwable) {
                logError("Hook 方法失败: " + targetClass.getName() + "#" + methodName,
                        throwable);
            }
        }
        return hooked;
    }

    private static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getFieldValue(Object object, String fieldName)
            throws ReflectiveOperationException {
        Field field = findField(object.getClass(), fieldName);
        return field.get(object);
    }

    private static void setFieldValue(Object object, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = findField(object.getClass(), fieldName);
        field.set(object, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + fieldName);
    }

    private static Object callMethod(Object object, String methodName, Object... args)
            throws ReflectiveOperationException {
        Method method = findCompatibleMethod(object.getClass(), methodName, args);
        return invoke(method, object, args);
    }

    private static Object callStaticMethod(Class<?> type, String methodName, Object... args)
            throws ReflectiveOperationException {
        Method method = findCompatibleMethod(type, methodName, args);
        return invoke(method, null, args);
    }

    private static Object invoke(Method method, Object receiver, Object[] args)
            throws ReflectiveOperationException {
        try {
            method.setAccessible(true);
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException) {
                throw (ReflectiveOperationException) cause;
            }
            throw exception;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String methodName, Object[] args)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodName.equals(method.getName())
                        && parametersMatch(method.getParameterTypes(), args)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            if (methodName.equals(method.getName())
                    && parametersMatch(method.getParameterTypes(), args)) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + methodName);
    }

    private static boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return false;
        }
        for (int index = 0; index < parameterTypes.length; index++) {
            Object argument = args[index];
            if (argument == null) {
                if (parameterTypes[index].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!box(parameterTypes[index]).isAssignableFrom(argument.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    private void setFieldIfPresent(Object object, String fieldName, Object value) {
        try {
            setFieldValue(object, fieldName, value);
        } catch (Throwable throwable) {
            logError("字段不存在: " + fieldName, throwable);
        }
    }

    private void setFieldIfPresent(Object object, String fieldName, int value) {
        setFieldIfPresent(object, fieldName, Integer.valueOf(value));
    }

    private Boolean readBooleanField(Object object, String fieldName) {
        if (object == null) {
            return null;
        }
        try {
            Object value = getFieldValue(object, fieldName);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void logSystemUiFilterOnce() {
        if (!systemUiFilterLogged) {
            systemUiFilterLogged = true;
            logInfo("已隐藏 LOW/MIN 通知的状态栏图标");
        }
    }

    private void logSystemUiMediumRestoreOnce(int importance) {
        if (!systemUiMediumRestoreLogged) {
            systemUiMediumRestoreLogged = true;
            logInfo("已恢复 DEFAULT 及以上通知的状态栏图标，importance=" + importance);
        }
    }

    private void logSystemUiMediumPromotionOnce() {
        if (!systemUiMediumPromotionLogged) {
            systemUiMediumPromotionLogged = true;
            logInfo("已在状态栏图标管线中将 DEFAULT(3) 按 HIGH(4) 处理");
        }
    }

    private void logSilentPolicyOnce() {
        if (!systemUiSilentPolicyLogged) {
            systemUiSilentPolicyLogged = true;
            logInfo("已允许静默通知进入状态栏图标候选列表");
        }
    }

    private void logInfo(String message) {
        log(Log.INFO, TAG, message);
    }

    private void logError(String message, Throwable throwable) {
        log(Log.ERROR, TAG, message, throwable);
    }
}
