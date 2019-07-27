package com.system.android.sysoperation;


/**
 * Hook the initialization of Java-based command-line tools (like pm).
 *
 * @hide SysOperation no longer hooks command-line tools, therefore this interface shouldn't be
 * implemented anymore.
 */
public interface ISysOperationHkCmdInit extends ISysOperationMod {
	/**
	 * Called very early during startup of a command-line tool.
	 * @param startupParam Details about the module itself and the started process.
	 * @throws Throwable Everything is caught, but it will prevent further initialization of the module.
	 */
	void initCmdApp(StartupParam startupParam) throws Throwable;

	/** Data holder for {@link #initCmdApp}. */
	final class StartupParam {
		/*package*/ StartupParam() {}

		/** The path to the module's APK. */
		public String modulePath;

		/** The class name of the tools that the hook was invoked for. */
		public String startClassName;
	}
}
