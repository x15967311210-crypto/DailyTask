package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.pengxh.daily.app.databinding.WindowFloatingBinding
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingWindowService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main) {

    private val kTag = "FloatingWindowService"
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val activityManager by lazy { getSystemService(ActivityManager::class.java) }
    private lateinit var binding: WindowFloatingBinding
    private var floatViewParams: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var memoryMonitorJob: Job? = null

    /** 是否有倒计时正在进行中 */
    private var countdownActive = false

    /** 倒计时停止发 tick 后的自动收起任务 */
    private var idleJob: Job? = null

    /** 蒙层（伪装息屏）是否正在显示 */
    private var maskVisible = false
    private var lastMemoryWarningTime = 0L

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        binding = WindowFloatingBinding.inflate(LayoutInflater.from(this))
        floatViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }.also {
            windowManager.addView(binding.root, it)
        }

        // 布局完成后，将悬浮窗移动到屏幕右侧垂直居中
        binding.root.post {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val viewWidth = binding.root.width
            val viewHeight = binding.root.height

            floatViewParams?.let {
                it.x = screenWidth - viewWidth
                it.y = (screenHeight - viewHeight) / 2
                windowManager.updateViewLayout(binding.root, it)
            }
        }

        // 收集悬浮窗控制事件
        launch {
            FloatingWindowController.timeTick.collect { tick ->
                binding.timeView.text = "${tick}s"
                countdownActive = tick >= 1
                applyWindowVisibility()
                // 兜底：倒计时异常中断（页面销毁、协程被取消）时没人发 0，超时自动收起
                idleJob?.cancel()
                if (countdownActive) {
                    idleJob = launch {
                        delay(5_000L)
                        countdownActive = false
                        applyWindowVisibility()
                    }
                }
            }
        }
        launch {
            FloatingWindowController.overtime.collect { seconds ->
                binding.timeView.text = "${seconds}s"
            }
        }
        launch {
            FloatingWindowController.visibility.collect { visible ->
                // visible = true 表示蒙层已退出（亮屏），false 表示正在伪装息屏
                maskVisible = !visible
                applyWindowVisibility()
            }
        }

        // 获取目标应用任务超时时间
        val time = SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        binding.timeView.text = "${time}s"

        // 空闲状态下一律不显示，只有倒计时进行中才出现
        applyWindowVisibility()

        // 移动悬浮窗
        onDragMove()

        startMemoryMonitoring()
    }

    private fun startMemoryMonitoring() {
        val mode = SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
        // 悬浮窗默认不显示，没必要保持 1s 采样一次，省电优先
        val interval = if (mode) 60_000L else 30_000L
        memoryMonitorJob = launch {
            // 立即更新一次
            updateMemoryInfo()

            while (isActive) {
                delay(interval)
                updateMemoryInfo()
            }
        }
    }

    private suspend fun updateMemoryInfo() {
        withContext(Dispatchers.IO) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalMem = memoryInfo.totalMem
            val availMem = memoryInfo.availMem
            val usedMem = totalMem - availMem
            val usagePercent = ((usedMem * 100.0) / totalMem).toInt()

            withContext(Dispatchers.Main) {
                binding.waveProgressView.setProgress(usagePercent)
                val currentTime = System.currentTimeMillis()
                if (usagePercent >= 90 && currentTime - lastMemoryWarningTime >= 5 * 60 * 1000) {
                    lastMemoryWarningTime = currentTime
                    MessageDispatcher.sendMessage("内存使用预警", "当前内存使用已超过90%，请关注设备运行情况")
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onDragMove() {
        binding.root.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                event ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = floatViewParams?.x ?: 0
                        initialY = floatViewParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        floatViewParams?.let {
                            it.x = initialX + (event.rawX - initialTouchX).toInt()
                            it.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(binding.root, it)
                        }
                        return true
                    }

                    else -> return false
                }
            }
        })
    }

    /**
     * 收敛显示范围：只有「倒计时进行中」且「未显示蒙层」时才可见。
     * 不可见时同时置为不可触摸，避免透明窗口吞掉目标 App 的点击事件。
     * */
    private fun applyWindowVisibility() {
        val params = floatViewParams ?: return
        val visible = countdownActive && !maskVisible
        binding.root.alpha = if (visible) 1.0f else 0.0f
        params.flags = if (visible) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (binding.root.isAttachedToWindow) {
            windowManager.updateViewLayout(binding.root, params)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryMonitorJob?.cancel()
        idleJob?.cancel()
        cancel()
        if (::binding.isInitialized && binding.root.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: IllegalArgumentException) {
                Log.w(kTag, "View not attached to window manager", e)
            }
        }
        Log.d(kTag, "onDestroy: FloatingWindowService")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
