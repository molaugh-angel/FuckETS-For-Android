package com.fuckets.etsviewer

import android.animation.Animator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fuckets.etsviewer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = GroupAdapter()
    private val blobAnimators = mutableListOf<Animator>()

    /** Shizuku 未运行的提示弹窗正在显示中（避免下拉刷新连发弹窗） */
    private var shizukuDialogShowing = false

    private val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        AppLog.i("Main", "权限回调 requestCode=$requestCode, grantResult=$grantResult")
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            loadData()
        } else {
            showStatus("Shizuku 权限被拒绝，无法读取受限目录")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("Main", "onCreate，版本 ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // ---- 液态玻璃：背景光斑 + 玻璃面板 ----
        val blobLayer: ViewGroup = findViewById(R.id.blobLayer)
        blobAnimators.addAll(LiquidGlass.applyBackdrop(blobLayer))
        binding.toolbar.background = LiquidGlass.surface(this, radiusTopDp = 0f, radiusBottomDp = 26f)
        binding.statusText.background = LiquidGlass.surface(this, radiusTopDp = 24f, radiusBottomDp = 24f)
        // 底部 Dock：液态玻璃胶囊——与卡片同一套观感（半透明底透出下层内容，
        // 顶部镜面高光 + 1dp 折射亮边），圆角拉满成胶囊形
        binding.dockBar.background = LiquidGlass.surface(
            this,
            radiusTopDp = 30f,
            radiusBottomDp = 30f,
            fillAlpha = if (LiquidGlass.isNight(this)) 0.12f else 0.58f,
            strokeAlpha = if (LiquidGlass.isNight(this)) 0.22f else 0.65f,
            sheenAlpha = if (LiquidGlass.isNight(this)) 0.14f else 0.34f
        )
        // 玻璃浮在列表之上，补一圈柔和投影让层次更分明
        binding.dockBar.elevation = 8f * resources.displayMetrics.density
        binding.dockBar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    30f * resources.displayMetrics.density
                )
            }
        }
        binding.dockBar.clipToOutline = true
        binding.swipeRefresh.setColorSchemeResources(
            R.color.part_a_accent, R.color.part_b_accent, R.color.part_c_accent
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener {
            AppLog.i("Main", "用户下拉刷新")
            ensurePermissionAndLoad()
        }

        setupDock()

        // 长按删除：整组 / 单个 Part
        adapter.onRequestDeleteGroup = { index, group ->
            val names = group.parts.map { it.folderName }.distinct()
            confirmDelete(
                "删除第 $index 组？",
                "将永久删除该组的 ${names.size} 个资源文件夹：\n" +
                    names.joinToString("\n") { "· $it" }
            ) { doDelete(names) }
        }
        adapter.onRequestDeletePart = { part ->
            confirmDelete(
                "删除 ${part.title}？",
                "将永久删除资源文件夹：\n· ${part.folderName}"
            ) { doDelete(listOf(part.folderName)) }
        }

        Shizuku.addRequestPermissionResultListener(permListener)
        ensurePermissionAndLoad()

        // 首次启动：使用须知（5s 确认）→ GPL-2.0 许可证，之后检查更新
        FirstLaunchDialogs.showIfNeeded(this) {
            checkUpdate(manual = false)
        }

        // 定时清理：恢复周期任务注册 + 到点兜底执行一次
        CleanupScheduler.configure(
            this,
            CleanupScheduler.intervalDays(this),
            CleanupScheduler.retentionDays(this)
        )
        lifecycleScope.launch(Dispatchers.IO) {
            CleanupScheduler.maybeRunNow(this@MainActivity)
        }
    }

    /** 底部 Dock：刷新 / 展开全部 / 收起全部 / 更多 */
    private fun setupDock() {
        binding.dockRefresh.setOnClickListener {
            AppLog.i("Main", "Dock：刷新")
            ensurePermissionAndLoad()
        }
        binding.dockExpand.setOnClickListener {
            AppLog.i("Main", "Dock：展开全部")
            adapter.expandAll(true)
        }
        binding.dockCollapse.setOnClickListener {
            AppLog.i("Main", "Dock：收起全部")
            adapter.expandAll(false)
        }
        binding.dockMore.setOnClickListener { anchor ->
            AppLog.d("Main", "Dock：更多")
            GlassMenuPopup.show(
                anchor = anchor,
                above = true,
                items = listOf(
                    GlassMenuPopup.Item(R.drawable.ic_update, "检查更新") {
                        checkUpdate(manual = true)
                    },
                    GlassMenuPopup.Item(R.drawable.ic_auto_delete, "定时清理") {
                        showCleanupSettings()
                    },
                    GlassMenuPopup.Item(R.drawable.ic_log, "运行日志") {
                        AppLog.i("Main", "打开日志页")
                        startActivity(Intent(this, LogActivity::class.java))
                    },
                    GlassMenuPopup.Item(R.drawable.ic_about, "关于") {
                        AppLog.i("Main", "打开关于页")
                        startActivity(Intent(this, AboutActivity::class.java))
                    }
                ),
                dividerAfter = setOf(1)
            )
        }
    }

    // ---- 自动更新 ----

    /** 检查更新。manual=true 时无论有无新版本都给出反馈（菜单手动触发） */
    private fun checkUpdate(manual: Boolean) {
        AppLog.i("Update", "触发检查更新（${if (manual) "手动" else "自动"}）")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check() }
            when {
                result == null -> {
                    if (manual) {
                        Toast.makeText(this@MainActivity, "检查失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                }
                UpdateChecker.isNewer(result.latestVersion, BuildConfig.VERSION_NAME) -> {
                    AppLog.i("Update", "发现新版本 ${result.latestVersion}，弹窗提示")
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("发现新版本 ${result.latestVersion}")
                        .setMessage(
                            "当前版本 ${BuildConfig.VERSION_NAME}\n\n更新内容：\n${result.updateLog}"
                        )
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("前往更新") { _, _ ->
                            AppLog.i("Update", "用户选择前往更新：${result.url}")
                            openUrl(result.url)
                        }
                        .show()
                }
                else -> {
                    AppLog.i("Update", "已是最新版本（${BuildConfig.VERSION_NAME}）")
                    if (manual) {
                        Toast.makeText(this@MainActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ---- Shizuku 未运行提示 ----

    private fun showShizukuNotRunningDialog() {
        if (shizukuDialogShowing) return
        shizukuDialogShowing = true
        AppLog.w("Main", "Shizuku 未运行，弹窗引导")
        MaterialAlertDialogBuilder(this)
            .setTitle("Shizuku 未运行")
            .setMessage("读取受限目录需要 Shizuku 服务。\n\n· 已安装 Shizuku：点「启动 Shizuku」打开它并启动服务\n· 未安装：点「获取 Shizuku」前往官网下载")
            .setPositiveButton("启动 Shizuku") { _, _ ->
                launchShizuku()
            }
            .setNeutralButton("获取 Shizuku") { _, _ ->
                openUrl("https://shizuku.rikka.app/zh-hans/")
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { shizukuDialogShowing = false }
            .show()
    }

    /** 拉起 Shizuku 应用；未安装则引导去下载页 */
    private fun launchShizuku() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            AppLog.i("Main", "拉起 Shizuku 应用")
            startActivity(intent)
        } else {
            AppLog.w("Main", "未安装 Shizuku，打开官网")
            Toast.makeText(this, "未安装 Shizuku，请先获取", Toast.LENGTH_SHORT).show()
            openUrl("https://shizuku.rikka.app/zh-hans/")
        }
    }

    // ---- 定时清理设置 ----

    private fun showCleanupSettings() {
        val freqLabels = arrayOf("关闭", "每天", "每 3 天", "每 7 天")
        val freqValues = intArrayOf(0, 1, 3, 7)
        val curInterval = CleanupScheduler.intervalDays(this)
        val checkedFreq = freqValues.indexOf(curInterval).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("定时清理试题")
            .setSingleChoiceItems(freqLabels, checkedFreq) { freqDialog, which ->
                val interval = freqValues[which]
                if (interval == 0) {
                    CleanupScheduler.configure(this, 0, CleanupScheduler.retentionDays(this))
                    Toast.makeText(this, "定时清理已关闭", Toast.LENGTH_SHORT).show()
                    freqDialog.dismiss()
                } else {
                    freqDialog.dismiss()
                    showRetentionPicker(interval)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 第二步：选择保留天数（清理更早的试题） */
    private fun showRetentionPicker(intervalDays: Int) {
        val retLabels = arrayOf("清理 3 天前的", "清理 7 天前的", "清理 15 天前的", "清理 30 天前的")
        val retValues = intArrayOf(3, 7, 15, 30)
        val curRetention = CleanupScheduler.retentionDays(this)
        val checkedRet = retValues.indexOf(curRetention).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("清理多少天前的试题？")
            .setSingleChoiceItems(retLabels, checkedRet) { dialog, which ->
                val retention = retValues[which]
                CleanupScheduler.configure(this, intervalDays, retention)
                Toast.makeText(
                    this,
                    "已开启：每 $intervalDays 天清理 $retention 天前的试题",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            AppLog.w("Main", "无浏览器可打开链接：$url")
            Toast.makeText(this, "没有可打开链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        // 退后台暂停背景光斑动画，省电省 GPU
        blobAnimators.forEach { it.pause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        blobAnimators.forEach { it.resume() }
    }

    override fun onDestroy() {
        blobAnimators.forEach { it.cancel() }
        blobAnimators.clear()
        ShizukuHelper.releaseUserService(this)
        Shizuku.removeRequestPermissionResultListener(permListener)
        AppLog.i("Main", "onDestroy")
        super.onDestroy()
    }

    private fun ensurePermissionAndLoad() {
        val available = ShizukuHelper.isAvailable
        val granted = ShizukuHelper.hasPermission
        AppLog.i("Main", "检查 Shizuku: available=$available, granted=$granted, version=${ShizukuHelper.serverVersion()}")
        ShizukuHelper.dumpServerInfo()
        when {
            !available -> {
                showStatus("Shizuku 未运行。请先启动 Shizuku 服务后点「刷新」重试。")
                showShizukuNotRunningDialog()
            }
            !granted -> {
                showStatus("正在申请 Shizuku 权限…")
                ShizukuHelper.requestPermission()
            }
            else -> loadData()
        }
    }

    private fun loadData() {
        binding.swipeRefresh.isRefreshing = true
        AppLog.i("Main", "开始加载数据 path=${ResourceRepository.BASE_PATH}")
        val start = System.currentTimeMillis()
        lifecycleScope.launch {
            // 新版 Shizuku 已禁用 Shizuku.newProcess()，先把 UserService 通道建起来
            val viaService = ShizukuHelper.prepareUserService(this@MainActivity)
            AppLog.i("Main", "Shizuku 通道：${if (viaService) "UserService" else "newProcess（回退）"}")
            val groups = withContext(Dispatchers.IO) { ResourceRepository.load(this@MainActivity) }
            val cost = System.currentTimeMillis() - start
            binding.swipeRefresh.isRefreshing = false
            if (groups.isEmpty()) {
                AppLog.w("Main", "加载完成但无数据（耗时 ${cost}ms，通道=${if (viaService) "UserService" else "newProcess"}）")
                showStatus(
                    if (viaService) "没有找到可展示的数据（目录为空或所有文件夹都缺少 content.json）"
                    else "读取失败：Shizuku 命令通道不可用。请确认 Shizuku 已运行并已授权，点「刷新」重试；仍失败请查看运行日志。"
                )
            } else {
                AppLog.i("Main", "加载完成：${groups.size} 组，耗时 ${cost}ms")
                binding.statusText.visibility = View.GONE
                adapter.submit(groups)
            }
        }
    }

    /** 删除前二次确认，避免误触 */
    private fun confirmDelete(title: String, detail: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("$detail\n\n此操作不可恢复，确认删除？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                AppLog.i("Main", "用户确认删除：$title")
                onConfirm()
            }
            .show()
    }

    /** 在 IO 线程执行删除，完成后提示结果并重新加载列表 */
    private fun doDelete(names: List<String>) {
        if (!ShizukuHelper.isAvailable || !ShizukuHelper.hasPermission) {
            Toast.makeText(this, "Shizuku 未就绪，无法删除", Toast.LENGTH_LONG).show()
            AppLog.w("Main", "删除取消：Shizuku 不可用或无权限")
            return
        }
        binding.swipeRefresh.isRefreshing = true
        AppLog.i("Main", "开始删除 ${names.size} 个文件夹：$names")
        val start = System.currentTimeMillis()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ResourceRepository.deleteFolders(names) }
            val cost = System.currentTimeMillis() - start
            binding.swipeRefresh.isRefreshing = false
            val msg = if (result.failed.isEmpty()) {
                "已删除 ${result.deleted} 个文件夹（${cost}ms）"
            } else {
                "删除 ${result.deleted} 个成功，${result.failed.size} 个失败：${result.failed.joinToString()}"
            }
            AppLog.i("Main", "删除结果：$msg")
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            loadData()
        }
    }

    private fun showStatus(msg: String) {
        AppLog.w("Main", "状态提示：$msg")
        binding.swipeRefresh.isRefreshing = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = msg
        adapter.submit(emptyList())
    }
}
