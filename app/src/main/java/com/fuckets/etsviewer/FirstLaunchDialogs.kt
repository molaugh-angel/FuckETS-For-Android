package com.fuckets.etsviewer

import android.content.Context
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 首次启动弹窗：用户使用须知（5 秒倒计时后才能确认）→ GPL-2.0 开源许可证提示。
 * 两个都用 SharedPreferences 记录"已同意"，只弹一次；依次串联弹出。
 */
object FirstLaunchDialogs {

    private const val PREFS = "ets_first_launch"
    private const val KEY_NOTICE_ACCEPTED = "notice_accepted"
    private const val KEY_LICENSE_ACCEPTED = "license_accepted"

    private const val CONFIRM_COUNTDOWN_MS = 5_000L

    /** 依次弹出未确认的首次弹窗，全部完成后回调 [onAllDone]（无论是否弹过都会回调） */
    fun showIfNeeded(activity: AppCompatActivity, onAllDone: () -> Unit = {}) {
        showUsageNotice(activity) {
            showLicense(activity) {
                onAllDone()
            }
        }
    }

    /** 用户使用须知：确认按钮 5 秒倒计时结束后才可点击 */
    private fun showUsageNotice(activity: AppCompatActivity, onDone: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTICE_ACCEPTED, false)) {
            onDone()
            return
        }
        AppLog.i("Dialog", "首次启动：弹出用户使用须知")

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("用户使用须知")
            .setMessage(
                "1. 本应用用于查看本机 ETS 应用产生的资源文件，所有数据仅保存在本机，不会上传。\n\n" +
                    "2. 本应用通过 Shizuku 获取受限目录的读取权限，请勿用于未经授权的设备。\n\n" +
                    "3. 长按可删除资源文件夹，删除后不可恢复，请谨慎操作。\n\n" +
                    "4. 定时清理功能会按你设置的周期删除过期试题，请确认保留天数后再开启。\n\n" +
                    "5. 本应用按 GPL-2.0 协议开源，仅供学习交流使用。\n\n" +
                    "继续使用即表示你已阅读并同意以上须知。"
            )
            .setCancelable(false)
            .setPositiveButton("我已阅读并同意（5s）", null)   // 监听稍后挂，避免点击即关
            .create()

        var timer: CountDownTimer? = null
        dialog.setOnShowListener {
            val button = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            timer = object : CountDownTimer(CONFIRM_COUNTDOWN_MS, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val sec = millisUntilFinished / 1000 + 1
                    button.text = "我已阅读并同意（${sec}s）"
                }

                override fun onFinish() {
                    button.isEnabled = true
                    button.text = "我已阅读并同意"
                }
            }.start()
        }
        dialog.setOnDismissListener { timer?.cancel() }
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            prefs.edit().putBoolean(KEY_NOTICE_ACCEPTED, true).apply()
            AppLog.i("Dialog", "用户已同意使用须知")
            dialog.dismiss()
            onDone()
        }
    }

    /** 开源许可证提示（GPL-2.0） */
    private fun showLicense(activity: AppCompatActivity, onDone: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LICENSE_ACCEPTED, false)) {
            onDone()
            return
        }
        AppLog.i("Dialog", "首次启动：弹出开源许可证提示")

        MaterialAlertDialogBuilder(activity)
            .setTitle("开源许可证")
            .setMessage(
                "本应用基于 GPL-2.0 license 开源。\n\n" +
                    "你可以自由使用、复制、修改和分发本软件，但衍生作品必须以相同的 GPL-2.0 协议开源并保留版权声明。\n\n" +
                    "许可证全文：https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"
            )
            .setCancelable(false)
            .setPositiveButton("我已知晓") { _, _ ->
                prefs.edit().putBoolean(KEY_LICENSE_ACCEPTED, true).apply()
                AppLog.i("Dialog", "用户已知晓 GPL-2.0 许可证")
                onDone()
            }
            .show()
    }
}
