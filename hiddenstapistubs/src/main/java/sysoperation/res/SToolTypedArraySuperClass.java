package sysoperation.res;

import android.content.res.Resources;
import android.content.res.TypedArray;

/**
 * This class is used as super class of XSToolResources.XTypedArray.
 *
 * This implementation isn't included in the .dex file. Instead, it's created on the device.
 * Usually, it will extend TypedArray, but some ROMs use their own TypedArray subclass.
 * In that case, XSToolTypedArraySpClass will extend the ROM's subclass in an attempt to increase
 * compatibility.
 */
public class SToolTypedArraySuperClass extends TypedArray {
	/** Dummy, will never be called (objects are transferred to this class only). */
	protected SToolTypedArraySuperClass(Resources resources, int[] data, int[] indices, int len) {
		super(null, null, null, 0);
		throw new UnsupportedOperationException();
	}
}
