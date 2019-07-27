package com.system.android.sysoperation.callbacks;

import android.content.res.SToolResources;

import com.system.android.sysoperation.ISysOperationHkInitPackageResources;
import com.system.android.sysoperation.SysOperationBridge.CopyOnWriteSortedSet;

/**
 * This class is only used for internal purposes, except for the {@link InitPackageResourcesParam}
 * subclass.
 */
public abstract class STool_InitPackageResources extends SToolCallback implements ISysOperationHkInitPackageResources {
	/**
	 * Creates a new callback with default priority.
	 * @hide
	 */
	@SuppressWarnings("deprecation")
	public STool_InitPackageResources() {
		super();
	}

	/**
	 * Creates a new callback with a specific priority.
	 *
	 * @param priority See {@link SToolCallback#priority}.
	 * @hide
	 */
	public STool_InitPackageResources(int priority) {
		super(priority);
	}

	/**
	 * Wraps information about the resources being initialized.
	 */
	public static final class InitPackageResourcesParam extends SToolCallback.Param {
		/** @hide */
		public InitPackageResourcesParam(CopyOnWriteSortedSet<STool_InitPackageResources> callbacks) {
			super(callbacks);
		}

		/** The name of the package for which resources are being loaded. */
		public String packageName;

		/**
		 * Reference to the resources that can be used for calls to
		 * {@link SToolResources#setReplacement(String, String, String, Object)}.
		 */
		public SToolResources res;
	}

	/** @hide */
	@Override
	protected void call(Param param) throws Throwable {
		if (param instanceof InitPackageResourcesParam)
			handleInitPackageResources((InitPackageResourcesParam) param);
	}
}
