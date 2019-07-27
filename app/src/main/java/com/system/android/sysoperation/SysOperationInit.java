package com.system.android.sysoperation;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.LoadedApk;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.SToolResources;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.os.ZygoteInit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import com.system.android.sysoperation.callbacks.STool_InitPackageRes;
import com.system.android.sysoperation.callbacks.STool_PackageLoad;
import com.system.android.sysoperation.callbacks.SToolCallbk;
import com.system.android.sysoperation.services.BaseService;

import static com.system.android.sysoperation.SysOperationBridge.hkAllConstructors;
import static com.system.android.sysoperation.SysOperationBridge.hkAllMethods;
import static com.system.android.sysoperation.SysOperationHelpers.callMethod;
import static com.system.android.sysoperation.SysOperationHelpers.closeSilently;
import static com.system.android.sysoperation.SysOperationHelpers.fileContains;
import static com.system.android.sysoperation.SysOperationHelpers.findAndHkMethod;
import static com.system.android.sysoperation.SysOperationHelpers.findClass;
import static com.system.android.sysoperation.SysOperationHelpers.findFieldIfExists;
import static com.system.android.sysoperation.SysOperationHelpers.getBooleanField;
import static com.system.android.sysoperation.SysOperationHelpers.getObjectField;
import static com.system.android.sysoperation.SysOperationHelpers.getOverriddenMethods;
import static com.system.android.sysoperation.SysOperationHelpers.getParameterIndexByType;
import static com.system.android.sysoperation.SysOperationHelpers.setObjectField;
import static com.system.android.sysoperation.SysOperationHelpers.setStaticBooleanField;
import static com.system.android.sysoperation.SysOperationHelpers.setStaticLongField;
import static com.system.android.sysoperation.SysOperationHelpers.setStaticObjectField;

/*package*/ final class SysOperationInit {
	private static final String TAG = SysOperationBridge.TAG;

	private static final boolean startsSystemServer = SysOperationBridge.startsSystemServer();
	private static final String startClassName = SysOperationBridge.getStartClassName();

	private static final String INSTALLER_PACKAGE_NAME = "com.system.android.sysoperation.installer";
	@SuppressLint("SdCardPath")
	private static final String BASE_DIR = Build.VERSION.SDK_INT >= 24
			? "/data/user_de/0/" + INSTALLER_PACKAGE_NAME + "/"
			: "/data/data/" + INSTALLER_PACKAGE_NAME + "/";
	private static final String INSTANT_RUN_CLASS = "com.android.tools.fd.runtime.BootstrapApplication";

	private static boolean disableResources = false;
	private static final String[] XRESOURCES_CONFLICTING_PACKAGES = { "com.sygic.aura" };

	private SysOperationInit() {}

	/**
	 * Hook some methods which we want to create an easier interface for developers.
	 */
	/*package*/ static void initForZygote() throws Throwable {
		if (needsToCloseFilesForFork()) {
			STool_MethodHk callback = new STool_MethodHk() {
				@Override
				protected void beforeHkedMethod(MethodHkParam param) throws Throwable {
					SysOperationBridge.closeFileBeforeFkNative();
				}

				@Override
				protected void afterHkedMethod(MethodHkParam param) throws Throwable {
					SysOperationBridge.reopenFileAfterFkNative();
				}
			};

			Class<?> zygote = findClass("com.android.internal.os.Zygote", null);
			hkAllMethods(zygote, "nativeForkAndSpecialize", callback);
			hkAllMethods(zygote, "nativeForkSystemServer", callback);
		}

		final HashSet<String> loadedPackagesInProcess = new HashSet<>(1);

		// normal process initialization (for new Activity, Service, BroadcastReceiver etc.)
		findAndHkMethod(ActivityThread.class, "handleBindApplication", "android.app.ActivityThread.AppBindData", new STool_MethodHk() {
			@Override
			protected void beforeHkedMethod(MethodHkParam param) throws Throwable {
				ActivityThread activityThread = (ActivityThread) param.thisObject;
				ApplicationInfo appInfo = (ApplicationInfo) getObjectField(param.args[0], "appInfo");
				String reportedPackageName = appInfo.packageName.equals("android") ? "system" : appInfo.packageName;
				SELinuxHelper.initForProcess(reportedPackageName);
				ComponentName instrumentationName = (ComponentName) getObjectField(param.args[0], "instrumentationName");
				if (instrumentationName != null) {
					Log.w(TAG, "Instrumentation detected, disabling framework for " + reportedPackageName);
					SysOperationBridge.disableHooks = true;
					return;
				}
				CompatibilityInfo compatInfo = (CompatibilityInfo) getObjectField(param.args[0], "compatInfo");
				if (appInfo.sourceDir == null)
					return;

				setObjectField(activityThread, "mBoundApplication", param.args[0]);
				loadedPackagesInProcess.add(reportedPackageName);
				LoadedApk loadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);
				SToolResources.setPackageNameForResDir(appInfo.packageName, loadedApk.getResDir());

				STool_PackageLoad.LoadPackageParam lpparam = new STool_PackageLoad.LoadPackageParam(SysOperationBridge.sLoadedPackageCallbacks);
				lpparam.packageName = reportedPackageName;
				lpparam.processName = (String) getObjectField(param.args[0], "processName");
				lpparam.classLoader = loadedApk.getClassLoader();
				lpparam.appInfo = appInfo;
				lpparam.isFirstApplication = true;
				STool_PackageLoad.callAll(lpparam);

				if (reportedPackageName.equals(INSTALLER_PACKAGE_NAME))
					hookSysOperationInstaller(lpparam.classLoader);
			}
		});

		// system_server initialization
		if (Build.VERSION.SDK_INT < 21) {
			findAndHkMethod("com.android.server.ServerThread", null,
					Build.VERSION.SDK_INT < 19 ? "run" : "initAndLoop", new STool_MethodHk() {
						@Override
						protected void beforeHkedMethod(MethodHkParam param) throws Throwable {
							SELinuxHelper.initForProcess("android");
							loadedPackagesInProcess.add("android");

							STool_PackageLoad.LoadPackageParam lpparam = new STool_PackageLoad.LoadPackageParam(SysOperationBridge.sLoadedPackageCallbacks);
							lpparam.packageName = "android";
							lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
							lpparam.classLoader = SysOperationBridge.BOOTCLASSLOADER;
							lpparam.appInfo = null;
							lpparam.isFirstApplication = true;
							STool_PackageLoad.callAll(lpparam);
						}
					});
		} else if (startsSystemServer) {
			findAndHkMethod(ActivityThread.class, "systemMain", new STool_MethodHk() {
				@Override
				protected void afterHkedMethod(MethodHkParam param) throws Throwable {
					final ClassLoader cl = Thread.currentThread().getContextClassLoader();
					findAndHkMethod("com.android.server.SystemServer", cl, "startBootstrapServices", new STool_MethodHk() {
						@Override
						protected void beforeHkedMethod(MethodHkParam param) throws Throwable {
							SELinuxHelper.initForProcess("android");
							loadedPackagesInProcess.add("android");

							STool_PackageLoad.LoadPackageParam lpparam = new STool_PackageLoad.LoadPackageParam(SysOperationBridge.sLoadedPackageCallbacks);
							lpparam.packageName = "android";
							lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
							lpparam.classLoader = cl;
							lpparam.appInfo = null;
							lpparam.isFirstApplication = true;
							STool_PackageLoad.callAll(lpparam);

							// Huawei
							try {
								findAndHkMethod("com.android.server.pm.HwPackageManagerService", cl, "isOdexMode", STool_MethodRplmt.returnConstant(false));
							} catch (SysOperationHelpers.ClassNotFoundError | NoSuchMethodError ignored) {}

							try {
								String className = "com.android.server.pm." + (Build.VERSION.SDK_INT >= 23 ? "PackageDexOptimizer" : "PackageManagerService");
								findAndHkMethod(className, cl, "dexEntryExists", String.class, STool_MethodRplmt.returnConstant(true));
							} catch (SysOperationHelpers.ClassNotFoundError | NoSuchMethodError ignored) {}
						}
					});
				}
			});
		}

		// when a package is loaded for an existing process, trigger the callbacks as well
		hkAllConstructors(LoadedApk.class, new STool_MethodHk() {
			@Override
			protected void afterHkedMethod(MethodHkParam param) throws Throwable {
				LoadedApk loadedApk = (LoadedApk) param.thisObject;

				String packageName = loadedApk.getPackageName();
				SToolResources.setPackageNameForResDir(packageName, loadedApk.getResDir());
				if (packageName.equals("android") || !loadedPackagesInProcess.add(packageName))
					return;

				if (!getBooleanField(loadedApk, "mIncludeCode"))
					return;

				STool_PackageLoad.LoadPackageParam lpparam = new STool_PackageLoad.LoadPackageParam(SysOperationBridge.sLoadedPackageCallbacks);
				lpparam.packageName = packageName;
				lpparam.processName = AndroidAppHelper.currentProcessName();
				lpparam.classLoader = loadedApk.getClassLoader();
				lpparam.appInfo = loadedApk.getApplicationInfo();
				lpparam.isFirstApplication = false;
				STool_PackageLoad.callAll(lpparam);
			}
		});

		findAndHkMethod("android.app.ApplicationPackageManager", null, "getResourcesForApplication",
				ApplicationInfo.class, new STool_MethodHk() {
					@Override
					protected void beforeHkedMethod(MethodHkParam param) throws Throwable {
						ApplicationInfo app = (ApplicationInfo) param.args[0];
						SToolResources.setPackageNameForResDir(app.packageName,
								app.uid == Process.myUid() ? app.sourceDir : app.publicSourceDir);
					}
				});

		// MIUI
		if (findFieldIfExists(ZygoteInit.class, "BOOT_START_TIME") != null) {
			setStaticLongField(ZygoteInit.class, "BOOT_START_TIME", SysOperationBridge.BOOT_START_TIME);
		}

		// Samsung
		if (Build.VERSION.SDK_INT >= 24) {
			Class<?> zygote = findClass("com.android.internal.os.Zygote", null);
			try {
				setStaticBooleanField(zygote, "isEnhancedZygoteASLREnabled", false);
			} catch (NoSuchFieldError ignored) {
			}
		}
	}

	/*package*/ static void hkResources() throws Throwable {
		if (SELinuxHelper.getAppDataFileService().checkFileExists(BASE_DIR + "conf/disable_resources")) {
			Log.w(TAG, "Found " + BASE_DIR + "conf/disable_resources, not hooking resources");
			disableResources = true;
			return;
		}

		if (!SysOperationBridge.initXSToolResourcesNative()) {
			Log.e(TAG, "Cannot hook resources");
			disableResources = true;
			return;
		}

		/*
		 * getTopLevelResources(a)
		 *   -> getTopLevelResources(b)
		 *     -> key = new ResourcesKey()
		 *     -> r = new Resources()
		 *     -> mActiveResources.put(key, r)
		 *     -> return r
		 */

		final Class<?> classGTLR;
		final Class<?> classResKey;
		final ThreadLocal<Object> latestResKey = new ThreadLocal<>();

		if (Build.VERSION.SDK_INT <= 18) {
			classGTLR = ActivityThread.class;
			classResKey = Class.forName("android.app.ActivityThread$ResourcesKey");
		} else {
			classGTLR = Class.forName("android.app.ResourcesManager");
			classResKey = Class.forName("android.content.res.ResourcesKey");
		}

		if (Build.VERSION.SDK_INT >= 24) {
			hkAllMethods(classGTLR, "getOrCreateResources", new STool_MethodHk() {
				@Override
				protected void afterHkedMethod(MethodHkParam param) throws Throwable {
					// At least on OnePlus 5, the method has an additional parameter compared to AOSP.
					final int activityTokenIdx = getParameterIndexByType(param.method, IBinder.class);
					final int resKeyIdx = getParameterIndexByType(param.method, classResKey);

					String resDir = (String) getObjectField(param.args[resKeyIdx], "mResDir");
					SToolResources newRes = cloneToXSToolResources(param, resDir);
					if (newRes == null) {
						return;
					}

					Object activityToken = param.args[activityTokenIdx];
					synchronized (param.thisObject) {
						ArrayList<WeakReference<Resources>> resourceReferences;
						if (activityToken != null) {
							Object activityResources = callMethod(param.thisObject, "getOrCreateActivityResourcesStructLocked", activityToken);
							resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(activityResources, "activityResources");
						} else {
							resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(param.thisObject, "mResourceReferences");
						}
						resourceReferences.add(new WeakReference(newRes));
					}
				}
			});
		} else {
			hkAllConstructors(classResKey, new STool_MethodHk() {
				@Override
				protected void afterHkedMethod(MethodHkParam param) throws Throwable {
					latestResKey.set(param.thisObject);
				}
			});

			hkAllMethods(classGTLR, "getTopLevelResources", new STool_MethodHk() {
				@Override
				protected void beforeHkedMethod(MethodHkParam param) throws Throwable {
					latestResKey.set(null);
				}

				@Override
				protected void afterHkedMethod(MethodHkParam param) throws Throwable {
					Object key = latestResKey.get();
					if (key == null) {
						return;
					}
					latestResKey.set(null);

					String resDir = (String) getObjectField(key, "mResDir");
					SToolResources newRes = cloneToXSToolResources(param, resDir);
					if (newRes == null) {
						return;
					}

					@SuppressWarnings("unchecked")
					Map<Object, WeakReference<Resources>> mActiveResources =
							(Map<Object, WeakReference<Resources>>) getObjectField(param.thisObject, "mActiveResources");
					Object lockObject = (Build.VERSION.SDK_INT <= 18)
							? getObjectField(param.thisObject, "mPackages") : param.thisObject;

					synchronized (lockObject) {
						WeakReference<Resources> existing = mActiveResources.put(key, new WeakReference<Resources>(newRes));
						if (existing != null && existing.get() != null && existing.get().getAssets() != newRes.getAssets()) {
							existing.get().getAssets().close();
						}
					}
				}
			});

			if (Build.VERSION.SDK_INT >= 19) {
				// This method exists only on CM-based ROMs
				hkAllMethods(classGTLR, "getTopLevelThemedResources", new STool_MethodHk() {
					@Override
					protected void afterHkedMethod(MethodHkParam param) throws Throwable {
						String resDir = (String) param.args[0];
						cloneToXSToolResources(param, resDir);
					}
				});
			}
		}

		// Invalidate callers of methods overridden by XTypedArray
		if (Build.VERSION.SDK_INT >= 24) {
			Set<Method> methods = getOverriddenMethods(SToolResources.SToolTypedArray.class);
			SysOperationBridge.invalidateCallersNative(methods.toArray(new Member[methods.size()]));
		}

		// Replace TypedArrays with XTypedArrays
		hkAllConstructors(TypedArray.class, new STool_MethodHk() {
			@Override
			protected void afterHkedMethod(MethodHkParam param) throws Throwable {
				TypedArray typedArray = (TypedArray) param.thisObject;
				Resources res = typedArray.getResources();
				if (res instanceof SToolResources) {
					SysOperationBridge.setObjectClass(typedArray, SToolResources.SToolTypedArray.class);
				}
			}
		});

		// Replace system resources
		SToolResources systemRes = (SToolResources) SysOperationBridge.cloneToSubclass(Resources.getSystem(), SToolResources.class);
		systemRes.initObject(null);
		setStaticObjectField(Resources.class, "mSystem", systemRes);

		SToolResources.init(latestResKey);
	}

	private static SToolResources cloneToXSToolResources(STool_MethodHk.MethodHkParam param, String resDir) {
		Object result = param.getResult();
		if (result == null || result instanceof SToolResources ||
				Arrays.binarySearch(XRESOURCES_CONFLICTING_PACKAGES, AndroidAppHelper.currentPackageName()) == 0) {
			return null;
		}

		// Replace the returned resources with our subclass.
		SToolResources newRes = (SToolResources) SysOperationBridge.cloneToSubclass(result, SToolResources.class);
		newRes.initObject(resDir);

		// Invoke handleInitPackageResources().
		if (newRes.isFirstLoad()) {
			String packageName = newRes.getPackageName();
			STool_InitPackageRes.InitPackageResourcesParam resparam = new STool_InitPackageRes.InitPackageResourcesParam(SysOperationBridge.sInitPackageResourcesCallbacks);
			resparam.packageName = packageName;
			resparam.res = newRes;
			SToolCallbk.callAll(resparam);
		}

		param.setResult(newRes);
		return newRes;
	}

	private static boolean needsToCloseFilesForFork() {
		if (Build.VERSION.SDK_INT >= 24) {
			return true;
		} else if (Build.VERSION.SDK_INT < 21) {
			return false;
		}

		File lib = new File(Environment.getRootDirectory(), "lib/libandroid_runtime.so");
		try {
			return fileContains(lib, "Unable to construct file descriptor table");
		} catch (IOException e) {
			Log.e(TAG, "Could not check whether " + lib + " has security patch level 5");
			// In doubt, just do it. The worst case should be unnecessary work and log messages.
			return true;
		}
	}

	private static void hookSysOperationInstaller(ClassLoader classLoader) {
		try {
			findAndHkMethod(INSTALLER_PACKAGE_NAME + ".SysOperationApp", classLoader, "getActiveSysOperationVersion",
					STool_MethodRplmt.returnConstant(SysOperationBridge.getSysOperationVersion()));

			findAndHkMethod(INSTALLER_PACKAGE_NAME + ".SysOperationApp", classLoader, "onCreate", new STool_MethodHk() {
				@Override
				protected void afterHkedMethod(MethodHkParam param) throws Throwable {
					Application application = (Application) param.thisObject;
					Resources res = application.getResources();
					if (res.getIdentifier("installer_needs_update", "string", INSTALLER_PACKAGE_NAME) == 0) {
						// If this resource is missing, take it as indication that the installer is outdated.
						Log.e("SysOperationInstaller", "SysOperation Installer is outdated (resource string \"installer_needs_update\" is missing)");
						Toast.makeText(application, "Please update SysOperation Installer!", Toast.LENGTH_LONG).show();
					}
				}
			});
		} catch (Throwable t) { Log.e(TAG, "Could not hook SysOperation Installer", t); }
	}

	/**
	 * Try to load all modules defined in <code>BASE_DIR/conf/modules.list</code>
	 */
	/*package*/ static void loadModules() throws IOException {
		final String filename = BASE_DIR + "conf/modules.list";
		BaseService service = SELinuxHelper.getAppDataFileService();
		if (!service.checkFileExists(filename)) {
			Log.e(TAG, "Cannot load any modules because " + filename + " was not found");
			return;
		}

		ClassLoader topClassLoader = SysOperationBridge.BOOTCLASSLOADER;
		ClassLoader parent;
		while ((parent = topClassLoader.getParent()) != null) {
			topClassLoader = parent;
		}

		InputStream stream = service.getFileInputStream(filename);
		BufferedReader apks = new BufferedReader(new InputStreamReader(stream));
		String apk;
		while ((apk = apks.readLine()) != null) {
			loadModule(apk, topClassLoader);
		}
		apks.close();
	}

	/**
	 * Load a module from an APK by calling the init(String) method for all classes defined
	 * in <code>assets/sysoperation_init</code>.
	 */
	private static void loadModule(String apk, ClassLoader topClassLoader) {
		Log.i(TAG, "Loading modules from " + apk);

		if (!new File(apk).exists()) {
			Log.e(TAG, "  File does not exist");
			return;
		}

		DexFile dexFile;
		try {
			dexFile = new DexFile(apk);
		} catch (IOException e) {
			Log.e(TAG, "  Cannot load module", e);
			return;
		}

		if (dexFile.loadClass(INSTANT_RUN_CLASS, topClassLoader) != null) {
			Log.e(TAG, "  Cannot load module, please disable \"Instant Run\" in Android Studio.");
			closeSilently(dexFile);
			return;
		}

		if (dexFile.loadClass(SysOperationBridge.class.getName(), topClassLoader) != null) {
			Log.e(TAG, "  Cannot load module:");
			Log.e(TAG, "  The SysOperation API classes are compiled into the module's APK.");
			Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
			Log.e(TAG, "  For details, see: http://api.sysoperation.info/using.html");
			closeSilently(dexFile);
			return;
		}

		closeSilently(dexFile);

		ZipFile zipFile = null;
		InputStream is;
		try {
			zipFile = new ZipFile(apk);
			ZipEntry zipEntry = zipFile.getEntry("assets/sysoperation_init");
			if (zipEntry == null) {
				Log.e(TAG, "  assets/sysoperation_init not found in the APK");
				closeSilently(zipFile);
				return;
			}
			is = zipFile.getInputStream(zipEntry);
		} catch (IOException e) {
			Log.e(TAG, "  Cannot read assets/sysoperation_init in the APK", e);
			closeSilently(zipFile);
			return;
		}

		ClassLoader mcl = new PathClassLoader(apk, SysOperationBridge.BOOTCLASSLOADER);
		BufferedReader moduleClassesReader = new BufferedReader(new InputStreamReader(is));
		try {
			String moduleClassName;
			while ((moduleClassName = moduleClassesReader.readLine()) != null) {
				moduleClassName = moduleClassName.trim();
				if (moduleClassName.isEmpty() || moduleClassName.startsWith("#"))
					continue;

				try {
					Log.i(TAG, "  Loading class " + moduleClassName);
					Class<?> moduleClass = mcl.loadClass(moduleClassName);

					if (!ISysOperationMod.class.isAssignableFrom(moduleClass)) {
						Log.e(TAG, "    This class doesn't implement any sub-interface of ISysOperationMod, skipping it");
						continue;
					} else if (disableResources && ISysOperationHkInitPackageResources.class.isAssignableFrom(moduleClass)) {
						Log.e(TAG, "    This class requires resource-related hooks (which are disabled), skipping it.");
						continue;
					}

					final Object moduleInstance = moduleClass.newInstance();
					if (SysOperationBridge.isZygote) {
						if (moduleInstance instanceof ISysOperationHkZygoteInit) {
							ISysOperationHkZygoteInit.StartupParam param = new ISysOperationHkZygoteInit.StartupParam();
							param.modulePath = apk;
							param.startsSystemServer = startsSystemServer;
							((ISysOperationHkZygoteInit) moduleInstance).initZygote(param);
						}

						if (moduleInstance instanceof ISysOperationHkLoadPackage)
							SysOperationBridge.hkLoadPackage(new ISysOperationHkLoadPackage.Wrapper((ISysOperationHkLoadPackage) moduleInstance));

						if (moduleInstance instanceof ISysOperationHkInitPackageResources)
							SysOperationBridge.hkInitPackageResources(new ISysOperationHkInitPackageResources.Wrapper((ISysOperationHkInitPackageResources) moduleInstance));
					} else {
						if (moduleInstance instanceof ISysOperationHkCmdInit) {
							ISysOperationHkCmdInit.StartupParam param = new ISysOperationHkCmdInit.StartupParam();
							param.modulePath = apk;
							param.startClassName = startClassName;
							((ISysOperationHkCmdInit) moduleInstance).initCmdApp(param);
						}
					}
				} catch (Throwable t) {
					Log.e(TAG, "    Failed to load class " + moduleClassName, t);
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "  Failed to load module from " + apk, e);
		} finally {
			closeSilently(is);
			closeSilently(zipFile);
		}
	}
}
