package com.system.android.sysoperation;

import java.lang.reflect.Member;

import com.system.android.sysoperation.callbacks.ISToolUnhk;
import com.system.android.sysoperation.callbacks.SToolCallbk;

/**
 * Callback class for method hooks.
 *
 * <p>Usually, anonymous subclasses of this class are created which override
 * {@link #beforeHookedMethod} and/or {@link #afterHookedMethod}.
 */
public abstract class STool_MethodHk extends SToolCallbk {
	/**
	 * Creates a new callback with default priority.
	 */
	@SuppressWarnings("deprecation")
	public STool_MethodHk() {
		super();
	}

	/**
	 * Creates a new callback with a specific priority.
	 *
	 * <p class="note">Note that {@link #afterHookedMethod} will be called in reversed order, i.e.
	 * the callback with the highest priority will be called last. This way, the callback has the
	 * final control over the return value. {@link #beforeHookedMethod} is called as usual, i.e.
	 * highest priority first.
	 *
	 * @param priority See {@link SToolCallbk#priority}.
	 */
	public STool_MethodHk(int priority) {
		super(priority);
	}

	/**
	 * Called before the invocation of the method.
	 *
	 * <p>You can use {@link MethodHkParam#setResult} and {@link MethodHkParam#setThrowable}
	 * to prevent the original method from being called.
	 *
	 * <p>Note that implementations shouldn't call {@code super(param)}, it's not necessary.
	 *
	 * @param param Information about the method call.
	 * @throws Throwable Everything the callback throws is caught and logged.
	 */
	protected void beforeHkedMethod(MethodHkParam param) throws Throwable {}

	/**
	 * Called after the invocation of the method.
	 *
	 * <p>You can use {@link MethodHkParam#setResult} and {@link MethodHkParam#setThrowable}
	 * to modify the return value of the original method.
	 *
	 * <p>Note that implementations shouldn't call {@code super(param)}, it's not necessary.
	 *
	 * @param param Information about the method call.
	 * @throws Throwable Everything the callback throws is caught and logged.
	 */
	protected void afterHkedMethod(MethodHkParam param) throws Throwable {}

	/**
	 * Wraps information about the method call and allows to influence it.
	 */
	public static final class MethodHkParam extends SToolCallbk.Param {
		/** @hide */
		@SuppressWarnings("deprecation")
		public MethodHkParam() {
			super();
		}

		/** The hooked method/constructor. */
		public Member method;

		/** The {@code this} reference for an instance method, or {@code null} for static methods. */
		public Object thisObject;

		/** Arguments to the method call. */
		public Object[] args;

		private Object result = null;
		private Throwable throwable = null;
		/* package */ boolean returnEarly = false;

		/** Returns the result of the method call. */
		public Object getResult() {
			return result;
		}

		/**
		 * Modify the result of the method call.
		 *
		 * <p>If called from {@link #beforeHkedMethod}, it prevents the call to the original method.
		 */
		public void setResult(Object result) {
			this.result = result;
			this.throwable = null;
			this.returnEarly = true;
		}

		/** Returns the {@link Throwable} thrown by the method, or {@code null}. */
		public Throwable getThrowable() {
			return throwable;
		}

		/** Returns true if an exception was thrown by the method. */
		public boolean hasThrowable() {
			return throwable != null;
		}

		/**
		 * Modify the exception thrown of the method call.
		 *
		 * <p>If called from {@link #beforeHkedMethod}, it prevents the call to the original method.
		 */
		public void setThrowable(Throwable throwable) {
			this.throwable = throwable;
			this.result = null;
			this.returnEarly = true;
		}

		/** Returns the result of the method call, or throws the Throwable caused by it. */
		public Object getResultOrThrowable() throws Throwable {
			if (throwable != null)
				throw throwable;
			return result;
		}
	}

	/**
	 * An object with which the method/constructor can be unhooked.
	 */
	public class Unhk implements ISToolUnhk<STool_MethodHk> {
		private final Member hookMethod;

		/*package*/ Unhk(Member hookMethod) {
			this.hookMethod = hookMethod;
		}

		/**
		 * Returns the method/constructor that has been hooked.
		 */
		public Member getHkedMethod() {
			return hookMethod;
		}

		@Override
		public STool_MethodHk getCallback() {
			return STool_MethodHk.this;
		}

		@SuppressWarnings("deprecation")
		@Override
		public void unhk() {
			SysOperationBridge.unhkMethod(hookMethod, STool_MethodHk.this);
		}

	}
}
