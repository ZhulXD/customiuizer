package name.monwf.customiuizer.mods.utils;

import org.apache.commons.lang3.RandomStringUtils;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * [duckfix] Lapisan kompatibilitas API-102: API-100 memakai BeforeHookCallback/AfterHookCallback
 * dengan before/after terpisah; API-102 hanya punya Hooker.intercept(Chain).
 * Kelas ini mengemulasi model sebelumnya supaya ratusan hook mod tidak perlu ditulis ulang.
 */
public class HookerClassHelper {

    /** Pengganti HookerClassHelper.BeforeHookCallback (API-100). */
    public static class BeforeHookCallback {
        final XposedInterface.Chain chain;
        Object[] argsOverride = null;
        boolean skipped = false;
        Object skipResult = null;
        Object pendingResult = null;
        boolean resultSet = false;

        BeforeHookCallback(XposedInterface.Chain chain) {
            this.chain = chain;
        }

        public Member getMember() { return chain.getExecutable(); }
        public Object getThisObject() { return chain.getThisObject(); }
        public java.util.List<Object> getArgs() { return chain.getArgs(); }
        public Object getResult() { return null; }
        public void setResult(Object result) { pendingResult = result; resultSet = true; }
        public Throwable getThrowable() { return null; }
        public void returnAndSkip(Object result) {
            skipped = true;
            skipResult = result;
        }
    }

    /** Pengganti HookerClassHelper.AfterHookCallback (API-100). */
    public static class AfterHookCallback {
        final XposedInterface.Chain chain;
        Object result;
        Throwable throwable;
        boolean resultSet = false;

        AfterHookCallback(XposedInterface.Chain chain, Object result, Throwable throwable) {
            this.chain = chain;
            this.result = result;
            this.throwable = throwable;
        }

        public Member getMember() { return chain.getExecutable(); }
        public Object getThisObject() { return chain.getThisObject(); }
        public java.util.List<Object> getArgs() { return chain.getArgs(); }
        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; resultSet = true; }
        public Throwable getThrowable() { return throwable; }
        public void returnAndSkip(Object r) { result = r; resultSet = true; }
    }

    interface BeforeMethodCallback {
        void beforeHook(BeforeHookCallback callback);
    }

    interface AfterMethodCallback {
        void afterHook(AfterHookCallback callback);
    }

    public static class MethodHook implements BeforeMethodCallback, AfterMethodCallback {
        public int mPriority;

        public MethodHook() {
            this(XposedInterface.PRIORITY_DEFAULT);
        }
        public MethodHook(int priority) {
            mPriority = priority;
        }
        public final void beforeHook(BeforeHookCallback callback) {
            try {
                this.before(callback);
            } catch (Throwable t) {
                XposedHelpers.log(t);
            }
        }
        public final void afterHook(AfterHookCallback callback) {
            try {
                this.after(callback);
            } catch (Throwable t) {
                XposedHelpers.log(t);
            }
        }
        protected void before(BeforeHookCallback callback) throws Throwable {

        }
        protected void after(AfterHookCallback callback) throws Throwable {

        }
    }

    static class BeforeHookerInfo {
        public String mHookerId;
        public BeforeMethodCallback mCallback;
        BeforeHookerInfo(String hkId, BeforeMethodCallback callback) {
            mHookerId = hkId;
            mCallback = callback;
        }
    }

    static class AfterHookerInfo {
        public String mHookerId;
        public AfterMethodCallback mCallback;
        AfterHookerInfo(String hkId, AfterMethodCallback callback) {
            mHookerId = hkId;
            mCallback = callback;
        }
    }

    public interface CustomMethodUnhooker {
        void unhook();
    }

    /** Hooker API-102: satu intercept(Chain) mengemulasi before/after semua callback terdaftar. */
    public static class CustomHooker implements XposedInterface.Hooker {
        static final ConcurrentHashMap<Member, ArrayList<BeforeHookerInfo>> beforeCallbacks = new ConcurrentHashMap<>();
        static final ConcurrentHashMap<Member, ArrayList<AfterHookerInfo>> afterCallbacks = new ConcurrentHashMap<>();
        static final ConcurrentHashMap<Member, Boolean> registered = new ConcurrentHashMap<>();

        public static CustomMethodUnhooker addCallback(Member m, MethodHook hook) {
            String hookerId = RandomStringUtils.randomAlphanumeric(12);
            for (Method method : hook.getClass().getDeclaredMethods()) {
                if (method.getName().equals("before")) {
                    ArrayList<BeforeHookerInfo> hookers = beforeCallbacks.get(m);
                    boolean firstHook = hookers == null;
                    if (firstHook) hookers = new ArrayList<>();
                    hookers.add(new BeforeHookerInfo(hookerId, hook));
                    if (firstHook) beforeCallbacks.put(m, hookers);
                }
                else if (method.getName().equals("after")) {
                    ArrayList<AfterHookerInfo> hookers = afterCallbacks.get(m);
                    boolean firstHook = hookers == null;
                    if (firstHook) hookers = new ArrayList<>();
                    hookers.add(new AfterHookerInfo(hookerId, hook));
                    if (firstHook) afterCallbacks.put(m, hookers);
                }
            }
            return new CustomMethodUnhooker() {
                public void unhook() {
                    ArrayList<BeforeHookerInfo> beforeHookers = beforeCallbacks.get(m);
                    if (beforeHookers != null) {
                        for (BeforeHookerInfo hookerInfo : beforeHookers) {
                            if (hookerInfo.mHookerId.equals(hookerId)) {
                                beforeHookers.remove(hookerInfo);
                                break;
                            }
                        }
                    }
                    ArrayList<AfterHookerInfo> afterHookers = afterCallbacks.get(m);
                    if (afterHookers != null) {
                        for (AfterHookerInfo hookerInfo : afterHookers) {
                            if (hookerInfo.mHookerId.equals(hookerId)) {
                                afterHookers.remove(hookerInfo);
                                break;
                            }
                        }
                    }
                }
            };
        }

        public static boolean memberIsRegistered(Member m) {
            return registered.get(m) != null;
        }

        public static void markRegistered(Member m) {
            registered.put(m, Boolean.TRUE);
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Member m = chain.getExecutable();
            ArrayList<BeforeHookerInfo> befores = beforeCallbacks.get(m);
            BeforeHookCallback bcb = null;
            if (befores != null && !befores.isEmpty()) {
                bcb = new BeforeHookCallback(chain);
                for (BeforeHookerInfo hookerInfo : new ArrayList<>(befores)) {
                    hookerInfo.mCallback.beforeHook(bcb);
                    if (bcb.skipped) return bcb.skipResult;
                }
            }
            Object result;
            Throwable t = null;
            try {
                if (bcb != null && bcb.argsOverride != null)
                    result = chain.proceed(bcb.argsOverride);
                else
                    result = chain.proceed();
            } catch (Throwable e) {
                t = e;
                result = null;
            }
            if (bcb != null && bcb.resultSet) {
                // setResult() dari fase before: ganti hasil akhir
                result = bcb.pendingResult;
                t = null;
            }
            ArrayList<AfterHookerInfo> afters = afterCallbacks.get(m);
            if (afters != null && !afters.isEmpty()) {
                AfterHookCallback acb = new AfterHookCallback(chain, result, t);
                for (AfterHookerInfo hookerInfo : new ArrayList<>(afters)) {
                    hookerInfo.mCallback.afterHook(acb);
                }
                if (acb.resultSet) return acb.result;
            }
            if (t != null) throw t;
            return result;
        }
    }

    /**
     * Predefined callback that skips the method without replacements.
     */
    public static final MethodHook DO_NOTHING = new MethodHook(XposedInterface.PRIORITY_DEFAULT + 2) {
        @Override
        protected void before(BeforeHookCallback param) throws Throwable {
            param.returnAndSkip(null);
        }
    };

    /**
     * Creates a callback which always returns a specific value.
     */
    public static MethodHook returnConstant(final Object result) {
        return returnConstant(XposedInterface.PRIORITY_DEFAULT, result);
    }

    public static MethodHook returnConstant(int priority, final Object result) {
        return new MethodHook(priority) {
            @Override
            protected void before(BeforeHookCallback param) throws Throwable {
                param.returnAndSkip(result);
            }
        };
    }
}
