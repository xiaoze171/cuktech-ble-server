package com.cuktech.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restores the charger runtime after a reboot when the user enabled background running. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent == null ? null : intent.getAction())
                || !KeepAlivePreferences.isEnabled(context)
                || !ChargerService.hasBluetoothPermissions(context)) return;
        Intent start = new Intent(context, ChargerService.class).setAction(ChargerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start);
        else context.startService(start);
    }
}
