package sysoperation.res;

import android.content.res.Resources;

/**
 * This class is used as super class of XSToolResources.
 *
 * This implementation isn't included in the .dex file. Instead, it's created on the device.
 * Usually, it will extend Resources, but some ROMs use their own Resources subclass.
 * In that case, XSToolResSpClass will extend the ROM's subclass in an attempt to increase
 * compatibility.
 */
public class SToolResourcesSuperClass extends Resources {
	/** Dummy, will never be called (objects are transferred to this class only). */
	protected SToolResourcesSuperClass() {
		super(null, null, null);
		throw new UnsupportedOperationException();
	}
}
