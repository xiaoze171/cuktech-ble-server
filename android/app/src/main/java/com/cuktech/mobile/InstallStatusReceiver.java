package com.cuktech.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** PackageInstaller 会话结果回调：需用户确认时转发系统安装界面；失败时提示。 */
public class InstallStatusReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.cuktech.mobile.INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 系统不会自行弹窗：需取出确认 Intent 并显式启动安装界面
            // （Intent.EXTRA_INTENT 携带包安装器 Activity，URI 授权已由系统处理）
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Exception error) {
                    Toast.makeText(context,
                        context.getString(R.string.install_update_failed) + ": " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            }
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
