package io.github.yunhyok.usagering.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UsageBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (UsageWorkScheduler.bootRestoreEnabled(context)) {
                    UsageWorkScheduler.schedule(context, UsageWorkScheduler.savedInterval(context))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
