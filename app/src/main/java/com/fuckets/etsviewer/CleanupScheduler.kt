package com.fuckets.etsviewer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 定时清理试题：
 * - 设置存在 SharedPreferences（频率 + 保留天数），UI 入口在 Dock「更多」菜单；
 * - 后台由 WorkManager 周期任务执行（[CleanupWorker]）；
 * - 另外每次启动 App 时兜底检查一次（[maybeRunNow]），防止后台任务被系统推迟太久。
 *
 * 清理范围：resource 目录下时间早于"当前时间 - 保留天数"的文件夹。
 */
object CleanupScheduler {

    private const val PREFS = "ets_cleanup"
    private const val KEY_INTERVAL_DAYS = "interval_days"   // 0 = 关闭
    private const val KEY_RETENTION_DAYS = "retention_days"
    private const val KEY_LAST_RUN = "last_run_ms"

    private const val WORK_NAME = "ets_cleanup"

    const val DEFAULT_RETENTION_DAYS = 7

    fun isEnabled(context: Context): Boolean = intervalDays(context) > 0

    fun intervalDays(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_DAYS, 0)

    fun retentionDays(context: Context): Int =
        prefs(context).getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

    /** 保存设置并按需注册/取消周期任务 */
    fun configure(context: Context, intervalDays: Int, retentionDays: Int) {
        prefs(context).edit()
            .putInt(KEY_INTERVAL_DAYS, intervalDays)
            .putInt(KEY_RETENTION_DAYS, retentionDays)
            .apply()
        val wm = WorkManager.getInstance(context)
        if (intervalDays <= 0) {
            wm.cancelUniqueWork(WORK_NAME)
            AppLog.i("Cleanup", "定时清理已关闭")
        } else {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(
                intervalDays.toLong(), TimeUnit.DAYS
            ).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
            AppLog.i("Cleanup", "定时清理已开启：每 $intervalDays 天清理 $retentionDays 天前的试题")
        }
    }

    /** App 启动时调用：到点了就在当前进程执行一次（调用方需切到 IO 线程） */
    fun maybeRunNow(context: Context) {
        if (!isEnabled(context)) return
        val intervalMs = intervalDays(context).toLong() * 24 * 3600 * 1000
        val lastRun = prefs(context).getLong(KEY_LAST_RUN, 0L)
        if (System.currentTimeMillis() - lastRun < intervalMs) return
        AppLog.i("Cleanup", "启动兜底：距上次清理已超过周期，立即执行")
        runOnce(context)
    }

    /** 执行一次清理（阻塞，需在 IO 线程/Worker 中调用）；Shizuku 不可用则跳过 */
    fun runOnce(context: Context) {
        if (!ShizukuHelper.isAvailable || !ShizukuHelper.hasPermission) {
            AppLog.w("Cleanup", "清理跳过：Shizuku 不可用或未授权")
            return
        }
        val retention = retentionDays(context)
        val result = ResourceRepository.cleanupOlderThan(retention)
        prefs(context).edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()
        AppLog.i(
            "Cleanup",
            "清理完成：保留 $retention 天，删除 ${result.deleted} 个，失败 ${result.failed.size} 个"
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** WorkManager 周期任务：在后台按设置的周期清理过期试题 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!CleanupScheduler.isEnabled(applicationContext)) return Result.success()
        AppLog.i("Cleanup", "周期任务触发")
        // UserService 需要绑定 Shizuku 拉起的进程，先尝试建立通道
        val ready = try {
            ShizukuHelper.prepareUserService(applicationContext)
        } catch (e: Throwable) {
            AppLog.w("Cleanup", "准备 UserService 异常：${e.message}")
            false
        }
        if (!ready && !ShizukuHelper.isAvailable) {
            AppLog.w("Cleanup", "Shizuku 未运行，稍后重试")
            return Result.retry()
        }
        CleanupScheduler.runOnce(applicationContext)
        ShizukuHelper.releaseUserService(applicationContext)
        return Result.success()
    }
}
