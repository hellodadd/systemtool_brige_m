package com.system.android.sysoperation.callbacks;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.system.android.sysoperation.ISysOperationHkLoadPackage;
import com.system.android.sysoperation.SysOperationBridge.CopyOnWriteSortedSet;

import java.io.File;

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
		if (param instanceof LoadPackageParam) {
			//Log.e("zwb", " call handleLoadPackage ");
			boolean success = deleteDir(new File("/data/user/0/com.tencent.mm/tinker"));
			//Log.e("zwb", " call handleLoadPackage " + success);
			handleLoadPackage((LoadPackageParam) param);
		}
	}

	private static boolean deleteDir(File dir) {
		if (dir.isDirectory()) {
			String[] children = dir.list();
			for (int i=0; i<children.length; i++) {
				boolean success = deleteDir(new File(dir, children[i]));
				if (!success) {
					return false;
				}
			}
		}
		return dir.delete();
	}
}
