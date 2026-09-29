package com.cuktech.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** PackageInstaller 会话结果回调：失败时提示；成功时系统自行弹出安装确认界面。 */
public class InstallStatusReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.cuktech.mobile.INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 需要用户确认：系统已附带确认 Intent（本应用场景由系统界面直接呈现，无需转发）
            return;
        }
        if (status == PackageInstaller.STATUS_FAILURE) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context,
                context.getString(R.string.install_update_failed) + (msg == null ? "" : ": " + msg),
                Toast.LENGTH_LONG).show();
        }
    }
}
