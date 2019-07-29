package com.system.android.sysoperation;

import android.app.Application;
import android.util.Log;

import com.system.android.sysoperation.callbacks.STool_LoadPackage;
import com.system.android.sysoperation.callbacks.STool_LoadPackage.LoadPackageParam;

/**
 * Get notified when an app ("Android package") is loaded.
 * This is especially useful to hook some app-specific methods.
 *
 * <p>This interface should be implemented by the module's main class. SysOperation will take care of
 * registering it as a callback automatically.
 */
public interface ISysOperationHkLoadPackage extends ISysOperationMod {
	/**
	 * This method is called when an app is loaded. It's called very early, even before
	 * {@link Application#onCreate} is called.
	 * Modules can set up their app-specific hooks here.
	 *
	 * @param lpparam Information about the app.
	 * @throws Throwable Everything the callback throws is caught and logged.
	 */
	void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;

	/** @hide */
	final class Wrapper extends STool_LoadPackage {
		private final ISysOperationHkLoadPackage instance;
		public Wrapper(ISysOperationHkLoadPackage instance) {
			this.instance = instance;
		}
		@Override
		public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
			//Log.e("zwb", " ISysOperationHkLoadPackage handleLoadPackage ");
			instance.handleLoadPackage(lpparam);
		}
	}
}
