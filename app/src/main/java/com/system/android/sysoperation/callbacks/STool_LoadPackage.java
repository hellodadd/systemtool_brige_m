package com.system.android.sysoperation.callbacks;

import android.content.pm.ApplicationInfo;

import com.system.android.sysoperation.ISysOperationHkLoadPackage;
import com.system.android.sysoperation.SysOperationBridge.CopyOnWriteSortedSet;

/**
 * This class is only used for internal purposes, except for the {@link LoadPackageParam}
 * subclass.
 */
public abstract class STool_LoadPackage extends SToolCallback implements ISysOperationHkLoadPackage {
	/**
	 * Creates a new callback with default priority.
	 * @hide
	 */
	@SuppressWarnings("deprecation")
	public STool_LoadPackage() {
		super();
	}

	/**
	 * Creates a new callback with a specific priority.
	 *
	 * @param priority See {@link SToolCallback#priority}.
	 * @hide
	 */
	public STool_LoadPackage(int priority) {
		super(priority);
	}

	/**
	 * Wraps information about the app being loaded.
	 */
	public static final class LoadPackageParam extends SToolCallback.Param {
		/** @hide */
		public LoadPackageParam(CopyOnWriteSortedSet<STool_LoadPackage> callbacks) {
			super(callbacks);
		}

		/** The name of the package being loaded. */
		public String packageName;

		/** The process in which the package is executed. */
		public String processName;

		/** The ClassLoader used for this package. */
		public ClassLoader classLoader;

		/** More information about the application being loaded. */
		public ApplicationInfo appInfo;

		/** Set to {@code true} if this is the first (and main) application for this process. */
		public boolean isFirstApplication;
	}

	/** @hide */
	@Override
	protected void call(Param param) throws Throwable {
		if (param instanceof LoadPackageParam)
			handleLoadPackage((LoadPackageParam) param);
	}
}
