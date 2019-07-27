package com.system.android.sysoperation;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Log;

import com.android.internal.os.RuntimeInit;
import com.android.internal.os.ZygoteInit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dalvik.system.PathClassLoader;
import com.system.android.sysoperation.STool_MethodHk.MethodHkParam;
import com.system.android.sysoperation.callbacks.STool_InitPackageRes;
import com.system.android.sysoperation.callbacks.STool_PackageLoad;

import static com.system.android.sysoperation.SysOperationHelpers.getIntField;
import static com.system.android.sysoperation.SysOperationHelpers.setObjectField;

/**
 * This class contains most of SysOperation's central logic, such as initialization and callbacks used by
 * the native side. It also includes methods to add new hooks.
 */
@SuppressWarnings("JniMissingFunction")
public final class SysOperationBridge {
	/**
	 * The system class loader which can be used to locate Android framework classes.
	 * Application classes cannot be retrieved from it.
	 *
	 * @see ClassLoader#getSystemClassLoader
	 */
	public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();

	/** @hide */
	public static final String TAG = "SysOperation";

	/** @deprecated Use {@link #getSysOperationVersion()} instead. */
	@Deprecated
	public static int XPOSED_BRIDGE_VERSION;

	/*package*/ static boolean isZygote = true;

	private static int runtime = 0;
	private static final int RUNTIME_DALVIK = 1;
	private static final int RUNTIME_ART = 2;

	/*package*/ static boolean disableHooks = false;

	// This field is set "magically" on MIUI.
	/*package*/ static long BOOT_START_TIME;

	private static final Object[] EMPTY_ARRAY = new Object[0];

	// built-in handlers
	private static final Map<Member, CopyOnWriteSortedSet<STool_MethodHk>> sHookedMethodCallbacks = new HashMap<>();
	/*package*/ static final CopyOnWriteSortedSet<STool_PackageLoad> sLoadedPackageCallbacks = new CopyOnWriteSortedSet<>();
	/*package*/ static final CopyOnWriteSortedSet<STool_InitPackageRes> sInitPackageResourcesCallbacks = new CopyOnWriteSortedSet<>();

	private SysOperationBridge() {}

	/**
	 * Called when native methods and other things are initialized, but before preloading classes etc.
	 * @hide
	 */
	@SuppressWarnings("deprecation")
	protected static void main(String[] args) {
		// Initialize the SysOperation framework and modules
		try {
			if (!hadInitErrors()) {
				initXSToolResources();

				SELinuxHelper.initOnce();
				SELinuxHelper.initForProcess(null);

				runtime = getRuntime();
				XPOSED_BRIDGE_VERSION = getSysOperationVersion();

				if (isZygote) {
					SysOperationInit.hkResources();
					SysOperationInit.initForZygote();
				}

				SysOperationInit.loadModules();
			} else {
				Log.e(TAG, "Not initializing SysOperation because of previous errors");
			}
		} catch (Throwable t) {
			Log.e(TAG, "Errors during SysOperation initialization", t);
			disableHooks = true;
		}

		// Call the original startup code
		if (isZygote) {
			ZygoteInit.main(args);
		} else {
			RuntimeInit.main(args);
		}
	}

	/** @hide */
	protected static final class ToolEntryPoint {
		protected static void main(String[] args) {
			isZygote = false;
			SysOperationBridge.main(args);
		}
	}

	private static void initXSToolResources() throws IOException {
		// Create SysOperationResSpClass.
		Resources res = Resources.getSystem();
		File resDexFile = ensureSuperDexFile("SToolResources", res.getClass(), Resources.class);

		// Create XTypedArraySuperClass.
		Class<?> taClass = TypedArray.class;
		try {
			TypedArray ta = res.obtainTypedArray(res.getIdentifier("preloaded_drawables", "array", "android"));
			taClass = ta.getClass();
			ta.recycle();
		} catch (Resources.NotFoundException nfe) {
			SysOperationBridge.log(nfe);
		}
		Runtime.getRuntime().gc();
		File taDexFile = ensureSuperDexFile("SToolTypedArray", taClass, TypedArray.class);

		// Inject a ClassLoader for the created classes as parent of SysOperationBridge's ClassLoader.
		ClassLoader myCL =  SysOperationBridge.class.getClassLoader();
		String paths = resDexFile.getAbsolutePath() + File.pathSeparator + taDexFile.getAbsolutePath();
		PathClassLoader dummyCL = new PathClassLoader(paths, myCL.getParent());
		setObjectField(myCL, "parent", dummyCL);
	}

	@SuppressLint("SetWorldReadable")
	private static File ensureSuperDexFile(String clz, Class<?> realSuperClz, Class<?> topClz) throws IOException {
		SysOperationBridge.removeFinalFlagNative(realSuperClz);
		File dexFile = DexCreator.ensure(clz, realSuperClz, topClz);
		dexFile.setReadable(true, false);
		return dexFile;
	}

	private native static boolean hadInitErrors();
	private static native int getRuntime();
	/*package*/ static native boolean startsSystemServer();
	/*package*/ static native String getStartClassName();
	/*package*/ native static boolean initXSToolResourcesNative();

	/**
	 * Returns the currently installed version of the SysOperation framework.
	 */
	public static native int getSysOperationVersion();

	/**
	 * Writes a message to the SysOperation error log.
	 *
	 * <p class="warning"><b>DON'T FLOOD THE LOG!!!</b> This is only meant for error logging.
	 * If you want to write information/debug messages, use logcat.
	 *
	 * @param text The log message.
	 */
	public synchronized static void log(String text) {
		Log.i(TAG, text);
	}

	/**
	 * Logs a stack trace to the SysOperation error log.
	 *
	 * <p class="warning"><b>DON'T FLOOD THE LOG!!!</b> This is only meant for error logging.
	 * If you want to write information/debug messages, use logcat.
	 *
	 * @param t The Throwable object for the stack trace.
	 */
	public synchronized static void log(Throwable t) {
		Log.e(TAG, Log.getStackTraceString(t));
	}

	/**
	 * Hook any method (or constructor) with the specified callback. See below for some wrappers
	 * that make it easier to find a method/constructor in one step.
	 *
	 * @param hookMethod The method to be hooked.
	 * @param callback The callback to be executed when the hooked method is called.
	 * @return An object that can be used to remove the hook.
	 *
	 * @see SysOperationHelpers#findAndHookMethod(String, ClassLoader, String, Object...)
	 * @see SysOperationHelpers#findAndHookMethod(Class, String, Object...)
	 * @see #hkAllMethods
	 * @see SysOperationHelpers#findAndHookConstructor(String, ClassLoader, Object...)
	 * @see SysOperationHelpers#findAndHookConstructor(Class, Object...)
	 * @see #hkAllConstructors
	 */
	public static STool_MethodHk.Unhk hkMethod(Member hookMethod, STool_MethodHk callback) {
		if (!(hookMethod instanceof Method) && !(hookMethod instanceof Constructor<?>)) {
			throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod.toString());
		} else if (hookMethod.getDeclaringClass().isInterface()) {
			throw new IllegalArgumentException("Cannot hook interfaces: " + hookMethod.toString());
		} else if (Modifier.isAbstract(hookMethod.getModifiers())) {
			throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod.toString());
		}

		boolean newMethod = false;
		CopyOnWriteSortedSet<STool_MethodHk> callbacks;
		synchronized (sHookedMethodCallbacks) {
			callbacks = sHookedMethodCallbacks.get(hookMethod);
			if (callbacks == null) {
				callbacks = new CopyOnWriteSortedSet<>();
				sHookedMethodCallbacks.put(hookMethod, callbacks);
				newMethod = true;
			}
		}
		callbacks.add(callback);

		if (newMethod) {
			Class<?> declaringClass = hookMethod.getDeclaringClass();
			int slot;
			Class<?>[] parameterTypes;
			Class<?> returnType;
			if (runtime == RUNTIME_ART) {
				slot = 0;
				parameterTypes = null;
				returnType = null;
			} else if (hookMethod instanceof Method) {
				slot = getIntField(hookMethod, "slot");
				parameterTypes = ((Method) hookMethod).getParameterTypes();
				returnType = ((Method) hookMethod).getReturnType();
			} else {
				slot = getIntField(hookMethod, "slot");
				parameterTypes = ((Constructor<?>) hookMethod).getParameterTypes();
				returnType = null;
			}

			AdditionalHookInfo additionalInfo = new AdditionalHookInfo(callbacks, parameterTypes, returnType);
			hkMethodNative(hookMethod, declaringClass, slot, additionalInfo);
		}

		return callback.new Unhk(hookMethod);
	}

	/**
	 * Removes the callback for a hooked method/constructor.
	 *
	 * @deprecated Use {@link STool_MethodHk.Unhk#unhk} instead. An instance of the {@code Unhook}
	 * class is returned when you hook the method.
	 *
	 * @param hookMethod The method for which the callback should be removed.
	 * @param callback The reference to the callback as specified in {@link #hkMethod}.
	 */
	@Deprecated
	public static void unhkMethod(Member hookMethod, STool_MethodHk callback) {
		CopyOnWriteSortedSet<STool_MethodHk> callbacks;
		synchronized (sHookedMethodCallbacks) {
			callbacks = sHookedMethodCallbacks.get(hookMethod);
			if (callbacks == null)
				return;
		}
		callbacks.remove(callback);
	}

	/**
	 * Hooks all methods with a certain name that were declared in the specified class. Inherited
	 * methods and constructors are not considered. For constructors, use
	 * {@link #hkAllConstructors} instead.
	 *
	 * @param hookClass The class to check for declared methods.
	 * @param methodName The name of the method(s) to hook.
	 * @param callback The callback to be executed when the hooked methods are called.
	 * @return A set containing one object for each found method which can be used to unhook it.
	 */
	@SuppressWarnings("UnusedReturnValue")
	public static Set<STool_MethodHk.Unhk> hkAllMethods(Class<?> hookClass, String methodName, STool_MethodHk callback) {
		Set<STool_MethodHk.Unhk> unhooks = new HashSet<>();
		for (Member method : hookClass.getDeclaredMethods())
			if (method.getName().equals(methodName))
				unhooks.add(hkMethod(method, callback));
		return unhooks;
	}

	/**
	 * Hook all constructors of the specified class.
	 *
	 * @param hookClass The class to check for constructors.
	 * @param callback The callback to be executed when the hooked constructors are called.
	 * @return A set containing one object for each found constructor which can be used to unhook it.
	 */
	@SuppressWarnings("UnusedReturnValue")
	public static Set<STool_MethodHk.Unhk> hkAllConstructors(Class<?> hookClass, STool_MethodHk callback) {
		Set<STool_MethodHk.Unhk> unhooks = new HashSet<>();
		for (Member constructor : hookClass.getDeclaredConstructors())
			unhooks.add(hkMethod(constructor, callback));
		return unhooks;
	}

	/**
	 * This method is called as a replacement for hooked methods.
	 */
	private static Object handleHkedMethod(Member method, int originalMethodId, Object additionalInfoObj,
			Object thisObject, Object[] args) throws Throwable {
		AdditionalHookInfo additionalInfo = (AdditionalHookInfo) additionalInfoObj;

		if (disableHooks) {
			try {
				return invOriMethodNative(method, originalMethodId, additionalInfo.parameterTypes,
						additionalInfo.returnType, thisObject, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		Object[] callbacksSnapshot = additionalInfo.callbacks.getSnapshot();
		final int callbacksLength = callbacksSnapshot.length;
		if (callbacksLength == 0) {
			try {
				return invOriMethodNative(method, originalMethodId, additionalInfo.parameterTypes,
						additionalInfo.returnType, thisObject, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		MethodHkParam param = new MethodHkParam();
		param.method = method;
		param.thisObject = thisObject;
		param.args = args;

		// call "before method" callbacks
		int beforeIdx = 0;
		do {
			try {
				((STool_MethodHk) callbacksSnapshot[beforeIdx]).beforeHkedMethod(param);
			} catch (Throwable t) {
				SysOperationBridge.log(t);

				// reset result (ignoring what the unexpectedly exiting callback did)
				param.setResult(null);
				param.returnEarly = false;
				continue;
			}

			if (param.returnEarly) {
				// skip remaining "before" callbacks and corresponding "after" callbacks
				beforeIdx++;
				break;
			}
		} while (++beforeIdx < callbacksLength);

		// call original method if not requested otherwise
		if (!param.returnEarly) {
			try {
				param.setResult(invOriMethodNative(method, originalMethodId,
						additionalInfo.parameterTypes, additionalInfo.returnType, param.thisObject, param.args));
			} catch (InvocationTargetException e) {
				param.setThrowable(e.getCause());
			}
		}

		// call "after method" callbacks
		int afterIdx = beforeIdx - 1;
		do {
			Object lastResult =  param.getResult();
			Throwable lastThrowable = param.getThrowable();

			try {
				((STool_MethodHk) callbacksSnapshot[afterIdx]).afterHkedMethod(param);
			} catch (Throwable t) {
				SysOperationBridge.log(t);

				// reset to last result (ignoring what the unexpectedly exiting callback did)
				if (lastThrowable == null)
					param.setResult(lastResult);
				else
					param.setThrowable(lastThrowable);
			}
		} while (--afterIdx >= 0);

		// return
		if (param.hasThrowable())
			throw param.getThrowable();
		else
			return param.getResult();
	}

	/**
	 * Adds a callback to be executed when an app ("Android package") is loaded.
	 *
	 * <p class="note">You probably don't need to call this. Simply implement {@link ISysOperationHookLoadPackage}
	 * in your module class and SysOperation will take care of registering it as a callback.
	 *
	 * @param callback The callback to be executed.
	 * @hide
	 */
	public static void hkLoadPackage(STool_PackageLoad callback) {
		synchronized (sLoadedPackageCallbacks) {
			sLoadedPackageCallbacks.add(callback);
		}
	}

	/**
	 * Adds a callback to be executed when the resources for an app are initialized.
	 *
	 * <p class="note">You probably don't need to call this. Simply implement {@link ISysOperationHookInitPackageResources}
	 * in your module class and SysOperation will take care of registering it as a callback.
	 *
	 * @param callback The callback to be executed.
	 * @hide
	 */
	public static void hkInitPackageResources(STool_InitPackageRes callback) {
		synchronized (sInitPackageResourcesCallbacks) {
			sInitPackageResourcesCallbacks.add(callback);
		}
	}

	/**
	 * Intercept every call to the specified method and call a handler function instead.
	 * @param method The method to intercept
	 */
	private native synchronized static void hkMethodNative(Member method, Class<?> declaringClass, int slot, Object additionalInfo);

	private native static Object invOriMethodNative(Member method, int methodId,
			Class<?>[] parameterTypes, Class<?> returnType, Object thisObject, Object[] args)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

	/**
	 * Basically the same as {@link Method#invoke}, but calls the original method
	 * as it was before the interception by SysOperation. Also, access permissions are not checked.
	 *
	 * <p class="caution">There are very few cases where this method is needed. A common mistake is
	 * to replace a method and then invoke the original one based on dynamic conditions. This
	 * creates overhead and skips further hooks by other modules. Instead, just hook (don't replace)
	 * the method and call {@code param.setResult(null)} in {@link STool_MethodHk#beforeHkedMethod}
	 * if the original method should be skipped.
	 *
	 * @param method The method to be called.
	 * @param thisObject For non-static calls, the "this" pointer, otherwise {@code null}.
	 * @param args Arguments for the method call as Object[] array.
	 * @return The result returned from the invoked method.
	 * @throws NullPointerException
	 *             if {@code receiver == null} for a non-static method
	 * @throws IllegalAccessException
	 *             if this method is not accessible (see {@link AccessibleObject})
	 * @throws IllegalArgumentException
	 *             if the number of arguments doesn't match the number of parameters, the receiver
	 *             is incompatible with the declaring class, or an argument could not be unboxed
	 *             or converted by a widening conversion to the corresponding parameter type
	 * @throws InvocationTargetException
	 *             if an exception was thrown by the invoked method
	 */
	public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
			throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (args == null) {
			args = EMPTY_ARRAY;
		}

		Class<?>[] parameterTypes;
		Class<?> returnType;
		if (runtime == RUNTIME_ART && (method instanceof Method || method instanceof Constructor)) {
			parameterTypes = null;
			returnType = null;
		} else if (method instanceof Method) {
			parameterTypes = ((Method) method).getParameterTypes();
			returnType = ((Method) method).getReturnType();
		} else if (method instanceof Constructor) {
			parameterTypes = ((Constructor<?>) method).getParameterTypes();
			returnType = null;
		} else {
			throw new IllegalArgumentException("method must be of type Method or Constructor");
		}

		return invOriMethodNative(method, 0, parameterTypes, returnType, thisObject, args);
	}

	/*package*/ static void setObjectClass(Object obj, Class<?> clazz) {
		if (clazz.isAssignableFrom(obj.getClass())) {
			throw new IllegalArgumentException("Cannot transfer object from " + obj.getClass() + " to " + clazz);
		}
		setObjectClassNative(obj, clazz);
	}

	private static native void setObjectClassNative(Object obj, Class<?> clazz);
	/*package*/ static native void dumpObjectNative(Object obj);

	/*package*/ static Object cloneToSubclass(Object obj, Class<?> targetClazz) {
		if (obj == null)
			return null;

		if (!obj.getClass().isAssignableFrom(targetClazz))
			throw new ClassCastException(targetClazz + " doesn't extend " + obj.getClass());

		return cloneToSubclassNative(obj, targetClazz);
	}

	private static native Object cloneToSubclassNative(Object obj, Class<?> targetClazz);

	private static native void removeFinalFlagNative(Class<?> clazz);

	/*package*/ static native void closeFileBeforeFkNative();
	/*package*/ static native void reopenFileAfterFkNative();

	/*package*/ static native void invalidateCallersNative(Member[] methods);

	/** @hide */
	public static final class CopyOnWriteSortedSet<E> {
		private transient volatile Object[] elements = EMPTY_ARRAY;

		@SuppressWarnings("UnusedReturnValue")
		public synchronized boolean add(E e) {
			int index = indexOf(e);
			if (index >= 0)
				return false;

			Object[] newElements = new Object[elements.length + 1];
			System.arraycopy(elements, 0, newElements, 0, elements.length);
			newElements[elements.length] = e;
			Arrays.sort(newElements);
			elements = newElements;
			return true;
		}

		@SuppressWarnings("UnusedReturnValue")
		public synchronized boolean remove(E e) {
			int index = indexOf(e);
			if (index == -1)
				return false;

			Object[] newElements = new Object[elements.length - 1];
			System.arraycopy(elements, 0, newElements, 0, index);
			System.arraycopy(elements, index + 1, newElements, index, elements.length - index - 1);
			elements = newElements;
			return true;
		}

		private int indexOf(Object o) {
			for (int i = 0; i < elements.length; i++) {
				if (o.equals(elements[i]))
					return i;
			}
			return -1;
		}

		public Object[] getSnapshot() {
			return elements;
		}
	}

	private static class AdditionalHookInfo {
		final CopyOnWriteSortedSet<STool_MethodHk> callbacks;
		final Class<?>[] parameterTypes;
		final Class<?> returnType;

		private AdditionalHookInfo(CopyOnWriteSortedSet<STool_MethodHk> callbacks, Class<?>[] parameterTypes, Class<?> returnType) {
			this.callbacks = callbacks;
			this.parameterTypes = parameterTypes;
			this.returnType = returnType;
		}
	}
}
