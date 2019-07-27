package android.content.res;

import android.app.AndroidAppHelper;
import android.util.DisplayMetrics;

import com.system.android.sysoperation.ISysOperationHkInitPackageResources;
import com.system.android.sysoperation.ISysOperationHkZygoteInit;
import com.system.android.sysoperation.ISysOperationHkZygoteInit.StartupParam;
import com.system.android.sysoperation.callbacks.STool_InitPackageResources.InitPackageResourcesParam;

/**
 * Provides access to resources from a certain path (usually the module's own path).
 */
public class SToolModuleResources extends Resources {
	private SToolModuleResources(AssetManager assets, DisplayMetrics metrics, Configuration config) {
		super(assets, metrics, config);
	}

	/**
	 * Creates a new instance.
	 *
	 * <p>This is usually called with {@link StartupParam#modulePath} from
	 * {@link ISysOperationHkZygoteInit#initZygote} and {@link InitPackageResourcesParam#res} from
	 * {@link ISysOperationHkInitPackageResources#handleInitPackageResources} (or {@code null} for
	 * system-wide replacements).
	 *
	 * @param path The path to the APK from which the resources should be loaded.
	 * @param origRes The resources object from which settings like the display metrics and the
	 *                configuration should be copied. May be {@code null}.
	 */
	public static SToolModuleResources createInstance(String path, SToolResources origRes) {
		if (path == null)
			throw new IllegalArgumentException("path must not be null");

		AssetManager assets = new AssetManager();
		assets.addAssetPath(path);

		SToolModuleResources res;
		if (origRes != null)
			res = new SToolModuleResources(assets, origRes.getDisplayMetrics(),	origRes.getConfiguration());
		else
			res = new SToolModuleResources(assets, null, null);

		AndroidAppHelper.addActiveResource(path, res);
		return res;
	}

	/**
	 * Creates an {@link SToolResForwarder} instance that forwards requests to {@code id} in this resource.
	 */
	public SToolResForwarder fwd(int id) {
		return new SToolResForwarder(this, id);
	}
}
