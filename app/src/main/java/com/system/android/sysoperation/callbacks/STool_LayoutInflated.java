package com.system.android.sysoperation.callbacks;

import android.content.res.SToolResources;
import android.content.res.SToolResources.ResourceNames;
import android.view.View;

import com.system.android.sysoperation.SysOperationBridge.CopyOnWriteSortedSet;

/**
 * Callback for hooking layouts. Such callbacks can be passed to {@link SToolResources#hookLayout}
 * and its variants.
 */
public abstract class STool_LayoutInflated extends SToolCallback {
	/**
	 * Creates a new callback with default priority.
	 */
	@SuppressWarnings("deprecation")
	public STool_LayoutInflated() {
		super();
	}

	/**
	 * Creates a new callback with a specific priority.
	 *
	 * @param priority See {@link SToolCallback#priority}.
	 */
	public STool_LayoutInflated(int priority) {
		super(priority);
	}

	/**
	 * Wraps information about the inflated layout.
	 */
	public static final class LayoutInflatedParam extends SToolCallback.Param {
		/** @hide */
		public LayoutInflatedParam(CopyOnWriteSortedSet<STool_LayoutInflated> callbacks) {
			super(callbacks);
		}

		/** The view that has been created from the layout. */
		public View view;

		/** Container with the ID and name of the underlying resource. */
		public ResourceNames resNames;

		/** Directory from which the layout was actually loaded (e.g. "layout-sw600dp"). */
		public String variant;

		/** Resources containing the layout. */
		public SToolResources res;
	}

	/** @hide */
	@Override
	protected void call(Param param) throws Throwable {
		if (param instanceof LayoutInflatedParam)
			handleLayoutInflated((LayoutInflatedParam) param);
	}

	/**
	 * This method is called when the hooked layout has been inflated.
	 *
	 * @param liparam Information about the layout and the inflated view.
	 * @throws Throwable Everything the callback throws is caught and logged.
	 */
	public abstract void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable;

	/**
	 * An object with which the callback can be removed.
	 */
	public class Unhook implements ISToolUnhk<STool_LayoutInflated> {
		private final String resDir;
		private final int id;

		/** @hide */
		public Unhook(String resDir, int id) {
			this.resDir = resDir;
			this.id = id;
		}

		/**
		 * Returns the resource ID of the hooked layout.
		 */
		public int getId() {
			return id;
		}

		@Override
		public STool_LayoutInflated getCallback() {
			return STool_LayoutInflated.this;
		}

		@Override
		public void unhook() {
			SToolResources.unhkLayout(resDir, id, STool_LayoutInflated.this);
		}

	}
}
