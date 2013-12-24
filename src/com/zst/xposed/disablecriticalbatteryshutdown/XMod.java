package com.zst.xposed.disablecriticalbatteryshutdown;

import static de.robv.android.xposed.XposedHelpers.findClass;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.XModuleResources;
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
	
	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		mModRes = XModuleResources.createInstance(startupParam.modulePath, null);
	}
	
	@Override
	public void handleLoadPackage(LoadPackageParam lpp) throws Throwable {
		if (!lpp.packageName.equals(PKG_SYSTEM)) return;
		
		final Class<?> battService = findClass(PKG_BATTERY_SERVICE, lpp.classLoader);
		final Class<?> amn = findClass(PKG_ACTIVITY_MANAGER_NATIVE, lpp.classLoader);
		
		XposedBridge.hookAllConstructors(battService, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				mContext = (Context) param.args[0];
				mNotifManager = (NotificationManager) mContext
						.getSystemService(Context.NOTIFICATION_SERVICE);
			}
		});
		
		XposedBridge.hookAllMethods(battService, "shutdownIfNoPower", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				final Object thiz = param.thisObject;
				final int battLevel = (Integer) XposedHelpers.callMethod(thiz, "getBatteryLevel");
				final boolean isPowered = (Boolean) XposedHelpers.callMethod(thiz, "isPowered");
				final boolean isSystemReady = (Boolean) XposedHelpers.callStaticMethod(amn,
						"isSystemReady");
				// shut down gracefully if our battery is critically low and we are not powered.
				// wait until the system has booted before attempting to display the shutdown dialog.
				if (battLevel == 0 && !isPowered && isSystemReady) {
					notifyUser();
					param.setResult(null);
				}
			}
		});
	}
	
	@SuppressWarnings("deprecation")
	private void notifyUser() {
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
			build.setContentTitle(String.format(mModRes.getString(R.string.notification_title),
					mNumber));
			build.setContentText(mModRes.getString(R.string.notification_summary));
			
			if (Build.VERSION.SDK_INT <= 15) {
				mNotifManager.notify(ID_NOTIFICATION, build.getNotification());
			} else {
				mNotifManager.notify(ID_NOTIFICATION, build.build());
			}
		}
	}
}
