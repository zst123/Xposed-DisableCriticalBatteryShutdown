package com.zst.xposed.disablecriticalbatteryshutdown;

import static de.robv.android.xposed.XposedHelpers.findClass;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.XModuleResources;
import android.os.BatteryManager;
import android.os.Build;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XMod implements IXposedHookLoadPackage, IXposedHookZygoteInit {
	
	static final int ID_NOTIFICATION = 123456789;
	static final String PKG_SYSTEM = "android";
	static final String PKG_BATTERY_SERVICE = "com.android.server.BatteryService";
	static final String PKG_ACTIVITY_MANAGER_NATIVE = "android.app.ActivityManagerNative";
	
	static XModuleResources mModRes;
	
	static NotificationManager mNotifManager;
	static Context mContext;
	static int mNumber;	
	static boolean mHasNotifiedAtLeastOnce;
	
	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		mModRes = XModuleResources.createInstance(startupParam.modulePath, null);
	}
	
	@Override
	public void handleLoadPackage(LoadPackageParam lpp) throws Throwable {
		if (!lpp.packageName.equals(PKG_SYSTEM)) return;
		
		final Class<?> battService = findClass(PKG_BATTERY_SERVICE, lpp.classLoader);
		
		XposedBridge.hookAllConstructors(battService, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				mContext = (Context) param.args[0];
				mNotifManager = (NotificationManager) mContext
						.getSystemService(Context.NOTIFICATION_SERVICE);
			}
		});
		
		XC_MethodHook hook = new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				param.setResult(null);
				
				final Object thiz = param.thisObject;
				
				int battLevel = -2;
				try {
					// Android 4.4.1 and above
					// https://github.com/android/platform_frameworks_base/commit/26faecc85ec3e809135b287173997e97fcb8fc30
					Object battery_prop = XposedHelpers.getObjectField(thiz, "mBatteryProps");
					battLevel = XposedHelpers.getIntField(battery_prop, "batteryLevel");
				} catch (Exception e) {
					// could not find BatteryProperties object
					// Android 4.4.0 and below
					try {
						battLevel = (Integer) XposedHelpers.callMethod(thiz, "getBatteryLevel");
					} catch (Exception e1) {
					}
				}
				
				Boolean isPowered = null;
				try {
					// Android 4.2.1 and above
					//https://github.com/android/platform_frameworks_base/commit/a4d8204e3068b9d8d6908d4cf3440e81967867a3
					isPowered = (Boolean) XposedHelpers.callMethod(thiz, "isPowered", 
							(BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS));
				} catch (Exception e) {
					// Android 4.2.0 and below
					try {
						isPowered = (Boolean) XposedHelpers.callMethod(thiz, "isPowered");
					} catch (Exception e1) {
					}
				}
				
				// shut down gracefully if our battery is critically low and we are not powered.
				if (battLevel == -2 || isPowered == null) {
					// That means our detection failed.
					if (!mHasNotifiedAtLeastOnce) {
						notifyUser(false);
					}
				} else if (battLevel == 0 && !isPowered) {
					notifyUser(true);
				}
			}
		};
		XposedBridge.hookAllMethods(battService, "shutdownIfNoPower", hook);
		XposedBridge.hookAllMethods(battService, "shutdownIfNoPowerLocked", hook);
	}
	
	@SuppressLint("NewApi")
	@SuppressWarnings("deprecation")
	private void notifyUser(boolean detection_working) {
		mHasNotifiedAtLeastOnce = true;
		mNumber++;
		if (Build.VERSION.SDK_INT < 11) {
			final String title = String.format(mModRes.getString(R.string.notification_title), mNumber);
			final String msg = mModRes.getString(R.string.notification_summary);
			
			Notification notification = new Notification();
			notification.icon = android.R.drawable.ic_dialog_alert;
			notification.flags |= Notification.FLAG_ONGOING_EVENT;
		    PendingIntent i = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
			notification.setLatestEventInfo(mContext, title, msg, i);
			mNotifManager.notify(ID_NOTIFICATION, notification);
		} else {
			Notification.Builder build = new Notification.Builder(mContext);
			build.setOngoing(true);
			build.setSmallIcon(android.R.drawable.ic_dialog_alert);
			build.setContentTitle(detection_working ? 
					String.format(mModRes.getString(R.string.notification_title), mNumber)
					: mModRes.getString(R.string.notification_title_alt));
			build.setContentText(mModRes.getString(R.string.notification_summary));
			
			if (Build.VERSION.SDK_INT <= 15) {
				mNotifManager.notify(ID_NOTIFICATION, build.getNotification());
			} else {
				mNotifManager.notify(ID_NOTIFICATION, build.build());
			}
		}
	}
}
