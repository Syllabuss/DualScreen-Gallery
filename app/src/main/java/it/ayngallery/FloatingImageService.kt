package it.ayngallery

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.FrameLayout
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import coil3.asDrawable
import coil3.gif.repeatCount
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Two image-only overlay windows; independent from Android's single video PiP activity. */
class FloatingImageService : Service() {
    private val app get() = application as GalleryApp
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windows = linkedMapOf<String, Window>()
    private class Window(val view: FrameLayout, val image: ImageView, val layout: WindowManager.LayoutParams, val manager: WindowManager, val displayId: Int) {
        var docked = 0
        var attached = false
        var job: Job? = null
        var controlsTimeout: Job? = null
        var hideControls: (() -> Unit)? = null
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        jobs.launch { app.preferences.state.map { it.previewNearest }.distinctUntilChanged().collect { nearest ->
            windows.values.forEach { applyComparisonFilter(it.image, nearest) }
        } }
        jobs.launch { app.catalog.snapshot.collect { data ->
            if (data.initialized) windows.keys.filter { id -> data.byId[id]?.let(data::usable) != true }.toList().forEach(::remove)
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CLOSE") { stopSelf(); return START_NOT_STICKY }
        if (!app.preferences.state.value.comparisonEnabled) { stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("media_id") ?: run { stopSelf(); return START_NOT_STICKY }
        notifySharing()
        if (id in windows) { remove(id); return START_NOT_STICKY }
        if (windows.size >= 2) {
            app.session.fail(localized(R.string.preview_compare_limit, "Sono già aperti due confronti. Chiudine uno prima di aggiungerne un altro."))
            return START_NOT_STICKY
        }
        val data = app.catalog.snapshot.value
        val item = data.byId[id]?.takeIf { it.kind in setOf("IMAGE", "GIF") && data.usable(it) }
        if (item == null || !Settings.canDrawOverlays(this)) { if (windows.isEmpty()) stopSelf(); return START_NOT_STICKY }
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(intent.getIntExtra("display_id", android.view.Display.DEFAULT_DISPLAY))
            ?: getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        app.sounds.play(if (windows.isEmpty()) "comparison-first" else "comparison-second")
        val windowContext = try {
            val displayContext = createDisplayContext(display ?: error(localized(R.string.text_unavailable_ce1391, "Non disponibile")))
            if (android.os.Build.VERSION.SDK_INT >= 30) displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
        } catch (error: RuntimeException) {
            app.session.fail(error.message ?: localized(R.string.text_unavailable_ce1391, "Non disponibile"))
            if (windows.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        val manager = windowContext.getSystemService(WindowManager::class.java)
        val density = windowContext.resources.displayMetrics.density
        val width = (220 * density).toInt()
        val params = WindowManager.LayoutParams(width, width, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = (32 * density).toInt() + windows.size * width; y = (80 * density).toInt() }
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (android.os.Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0)
        val view = FrameLayout(windowContext)
        val image = ImageView(windowContext).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = item.name }
        view.addView(image, FrameLayout.LayoutParams(-1, -1))
        val close = ImageButton(windowContext).apply {
            setImageResource(R.drawable.ph_x)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xBB000000.toInt()) }
            contentDescription = localized(R.string.text_close_32d407, "Chiudi")
            visibility = View.GONE
            setOnClickListener { remove(id) }
        }
        view.addView(close, FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt(), Gravity.TOP or Gravity.RIGHT))
        val resize = ImageButton(windowContext).apply {
            setImageResource(R.drawable.ph_arrows_out_simple)
            scaleX = -1f
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 6 * density; setColor(0xBB000000.toInt()) }
            contentDescription = localized(R.string.comparison_resize, "Ridimensiona")
            visibility = View.GONE
        }
        view.addView(resize, FrameLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt(), Gravity.TOP or Gravity.LEFT))
        val bubble = ImageView(windowContext).apply {
            setImageResource(R.drawable.ph_browsers)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(0f, 0f, 9 * density, 9 * density, 9 * density, 9 * density, 0f, 0f); setColor(0xEE202020.toInt()); setStroke((1 * density).toInt().coerceAtLeast(1), 0x55FFFFFF) }
            contentDescription = localized(R.string.preview_restore_overlay, "Mostra di nuovo")
            visibility = View.GONE
            setPadding((3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt())
        }
        view.addView(bubble, FrameLayout.LayoutParams(-1, -1))
        val window = Window(view, image, params, manager, display.displayId)
        windows[id] = window
        activeCount.value = windows.size
        window.job = jobs.launch {
            try {
                val result = SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(app.catalog.uri(item, data)).allowHardware(false).size(1024, 1024).repeatCount(-1).build())
                val drawable = (result as? SuccessResult)?.image?.asDrawable(resources) ?: error(localized(R.string.text_preview_unreadable_9374e0, "Anteprima non leggibile"))
                ensureActive()
                if (windows[id] !== window) return@launch
                val ratio = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f) / drawable.intrinsicWidth.coerceAtLeast(1)
                val screen = android.graphics.Point()
                @Suppress("DEPRECATION")
                display.getRealSize(screen)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bounds = manager.maximumWindowMetrics.bounds
                    screen.set(bounds.width(), bounds.height())
                }
                val maximumWidth = minOf(screen.x * .75f, screen.y * .75f / ratio).toInt().coerceAtLeast(1)
                params.width = width.coerceAtMost(maximumWidth)
                params.height = (params.width * ratio).toInt().coerceAtLeast(1)
                fun keepOnScreen() {
                    val inset = if (window.docked == 0) (12 * density).toInt() else 0
                    params.x = params.x.coerceIn(inset, (screen.x - params.width - inset).coerceAtLeast(inset))
                    params.y = params.y.coerceIn(inset, (screen.y - params.height - inset).coerceAtLeast(inset))
                }
                keepOnScreen()
                image.setImageDrawable(drawable)
                applyComparisonFilter(image, app.preferences.state.value.previewNearest)
                (drawable as? Animatable)?.start()
                var restoreWidth = params.width
                var restoreHeight = params.height
                val bubbleSize = (26 * density).toInt()
                val bubbleHeight = (64 * density).toInt()
                fun updateWindow() {
                    if (window.docked != 0) {
                        val dock = app.session.previewDocks.getOrPut("comparison:$id") { PreviewDockState() }
                        dock.edge = window.docked; dock.vertical = params.y.toFloat() / (screen.y - bubbleHeight).coerceAtLeast(1)
                    }
                    runCatching { manager.updateViewLayout(view, params) }.onFailure { remove(id) } }
                fun restore() {
                    val edge = window.docked
                    window.docked = 0
                    app.session.previewDocks.remove("comparison:$id")
                    params.width = restoreWidth; params.height = restoreHeight
                    params.x = if (edge < 0) 0 else screen.x - params.width
                    keepOnScreen()
                    image.visibility = View.VISIBLE; bubble.visibility = View.GONE; close.visibility = View.GONE; resize.visibility = View.GONE
                    (drawable as? Animatable)?.start()
                    updateWindow()
                }
                fun park(edge: Int) {
                    window.docked = edge
                    restoreWidth = params.width; restoreHeight = params.height
                    params.width = bubbleSize; params.height = bubbleHeight
                    params.x = if (edge < 0) 0 else screen.x - bubbleSize
                    bubble.drawable.isAutoMirrored = true
                    bubble.layoutDirection = if (edge < 0) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    bubble.background = GradientDrawable().apply {
                        val r = 9 * density
                        cornerRadii = if (edge < 0) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                        setColor(0xEE202020.toInt()); setStroke(density.toInt().coerceAtLeast(1), 0x55FFFFFF)
                    }
                    keepOnScreen()
                    val gap = (8 * density).toInt()
                    val occupied = windows.values.firstOrNull { it !== window && it.displayId == window.displayId && it.docked == edge && kotlin.math.abs(it.layout.y - params.y) < bubbleHeight + gap }
                    if (occupied != null) {
                        params.y = (occupied.layout.y + bubbleHeight + gap).coerceAtMost((screen.y - bubbleHeight).coerceAtLeast(0))
                        if (kotlin.math.abs(occupied.layout.y - params.y) < bubbleHeight + gap) params.y = (occupied.layout.y - bubbleHeight - gap).coerceAtLeast(0)
                    }
                    val reserved = app.session.previewDocks.filterKeys { it != "comparison:$id" }.values.filter { it.edge == edge }.map { (it.vertical * (screen.y - bubbleHeight)).toInt() }
                    if (reserved.any { kotlin.math.abs(it - params.y) < bubbleHeight + gap }) {
                        params.y = (0..(screen.y - bubbleHeight).coerceAtLeast(0) step (bubbleHeight + gap)).firstOrNull { y -> reserved.none { kotlin.math.abs(it - y) < bubbleHeight + gap } } ?: params.y
                    }
                    image.visibility = View.GONE; close.visibility = View.GONE; resize.visibility = View.GONE; bubble.visibility = View.VISIBLE
                    (drawable as? Animatable)?.stop()
                    updateWindow()
                }
                fun hideControls() { window.controlsTimeout?.cancel(); close.visibility = View.GONE; resize.visibility = View.GONE }
                fun interaction() {
                    windows.values.filter { it !== window }.forEach { it.hideControls?.invoke() }
                    window.controlsTimeout?.cancel()
                    if (close.visibility == View.VISIBLE) window.controlsTimeout = jobs.launch { delay(10_000); hideControls() }
                }
                window.hideControls = ::hideControls
                view.setOnClickListener {
                    if (window.docked != 0) restore() else {
                        close.visibility = if (close.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        resize.visibility = close.visibility
                        interaction()
                    }
                }
                var resizeX = 0f; var resizeWidth = 0; var right = 0; var bottom = 0
                resize.setOnClickListener { close.visibility = View.GONE; resize.visibility = View.GONE }
                resize.setOnTouchListener { _, event ->
                    interaction()
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { resizeX = event.rawX; resizeWidth = params.width; right = params.x + params.width; bottom = params.y + params.height }
                        MotionEvent.ACTION_MOVE -> {
                            val limit = minOf(maximumWidth, right - (12 * density).toInt(), ((bottom - 12 * density) / ratio).toInt()).coerceAtLeast(1)
                            params.width = (resizeWidth - (event.rawX - resizeX)).toInt().coerceIn(minOf((96 * density).toInt(), limit), limit)
                            params.height = (params.width * ratio).toInt().coerceAtLeast(1)
                            params.x = right - params.width; params.y = bottom - params.height
                            keepOnScreen(); updateWindow()
                        }
                        MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.rawX - resizeX) < ViewConfiguration.get(windowContext).scaledTouchSlop) resize.performClick()
                    }
                    true
                }
                val scale = ScaleGestureDetector(windowContext, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        if (window.docked != 0) return false
                        params.width = (params.width * detector.scaleFactor).toInt().coerceIn(minOf((96 * density).toInt(), maximumWidth), maximumWidth)
                        params.height = (params.width * ratio).toInt().coerceAtLeast(1)
                        keepOnScreen()
                        runCatching { manager.updateViewLayout(view, params) }.onFailure { remove(id) }
                        return true
                    }
                })
                var dockingX = params.x.toFloat()
                var lastX = 0f; var lastY = 0f
                var downX = 0f; var downY = 0f; var moved = false
                val slop = ViewConfiguration.get(windowContext).scaledTouchSlop
                view.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { hideControls(); return@setOnTouchListener false }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) interaction()
                    if (window.docked == 0) scale.onTouchEvent(event)
                    if (event.pointerCount > 1) moved = true
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { dockingX = params.x.toFloat(); lastX = event.rawX; lastY = event.rawY; downX = lastX; downY = lastY; moved = false }
                        MotionEvent.ACTION_POINTER_UP -> {
                            val remaining = if (event.actionIndex == 0) 1 else 0
                            lastX = event.getX(remaining) + event.rawX - event.x
                            lastY = event.getY(remaining) + event.rawY - event.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (kotlin.math.abs(event.rawX - downX) > slop || kotlin.math.abs(event.rawY - downY) > slop) moved = true
                            if (window.docked != 0 && moved) {
                                params.y = (params.y + (event.rawY - lastY).toInt()).coerceIn(0, (screen.y - params.height).coerceAtLeast(0)); updateWindow()
                            }
                            if (window.docked != 0 && moved && (event.rawX - downX) * window.docked < -slop * 2) { restore(); dockingX = params.x.toFloat(); downX = event.rawX; lastX = event.rawX }
                            if (moved && event.pointerCount == 1 && !scale.isInProgress && window.docked == 0) {
                                dockingX = (dockingX + event.rawX - lastX).coerceIn((-params.width + bubbleSize).toFloat(), (screen.x - bubbleSize).toFloat())
                                val inset = (12 * density).toInt()
                                params.x = dockingX.toInt().coerceIn(inset, (screen.x - params.width - inset).coerceAtLeast(inset))
                                params.y = (params.y + (event.rawY - lastY).toInt()).coerceIn(inset, (screen.y - params.height - inset).coerceAtLeast(inset))
                                runCatching { manager.updateViewLayout(view, params) }.onFailure { remove(id) }
                            }
                            lastX = event.rawX; lastY = event.rawY
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) view.performClick()
                            else if (window.docked == 0) {
                                val edge = previewDockSide(dockingX, params.width.toFloat(), screen.x.toFloat())
                                if (edge != 0) park(edge) else { keepOnScreen(); updateWindow() }
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> { keepOnScreen(); updateWindow() }
                    }
                    true
                }
                manager.addView(view, params); window.attached = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { app.session.fail(error.message ?: localized(R.string.text_preview_unreadable_9374e0, "Anteprima non leggibile")); remove(id) }
        }
        return START_NOT_STICKY
    }
    private fun notifySharing() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("image-comparison", localized(R.string.preview_compare_setting, "Confronti in finestre mobili"), NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 0, Intent(this, FloatingImageService::class.java).setAction("CLOSE"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(432, androidx.core.app.NotificationCompat.Builder(this, "image-comparison").setSmallIcon(R.drawable.ph_images)
            .setContentTitle(localized(R.string.preview_compare_setting, "Confronti in finestre mobili"))
            .setOngoing(true).addAction(0, localized(R.string.text_close_32d407, "Chiudi"), close).build())
    }
    private fun remove(id: String) {
        app.session.previewDocks.remove("comparison:$id")
        windows.remove(id)?.let { window ->
            app.sounds.play("comparison-close")
            window.job?.cancel(); window.controlsTimeout?.cancel()
            if (window.attached) runCatching { window.manager.removeView(window.view) }
            (window.image.drawable as? Animatable)?.stop()
            (window.image.drawable as? NearestComparisonDrawable)?.dispose()
            window.image.setImageDrawable(null)
        }
        activeCount.value = windows.size
        if (windows.isEmpty()) stopSelf()
    }
    override fun onDestroy() {
        windows.keys.toList().forEach(::remove)
        jobs.cancel()
        super.onDestroy()
    }
    companion object {
        internal val activeCount = kotlinx.coroutines.flow.MutableStateFlow(0)
        fun open(activity: PanelActivity, id: String) {
            val app = activity.application as GalleryApp
            if (!app.preferences.state.value.comparisonEnabled) return
            val media = app.catalog.snapshot.value.byId[id]
            if (media?.kind !in setOf("IMAGE", "GIF") || media?.let(app.catalog.snapshot.value::usable) != true) return
            if (!Settings.canDrawOverlays(activity)) { app.session.floatingImageRequestId = id; return }
            try { ContextCompat.startForegroundService(activity, Intent(activity, FloatingImageService::class.java).putExtra("media_id", id).putExtra("display_id", activity.windowManager.defaultDisplay.displayId)) }
            catch (error: Exception) { app.session.fail(error.message ?: localized(R.string.text_unavailable_ce1391, "Non disponibile")) }
        }
    }
}

/** Animated platform drawables have no bitmap-filter switch; sample each native-sized frame. */
internal class NearestComparisonDrawable(val source: android.graphics.drawable.Drawable) : android.graphics.drawable.Drawable(), android.graphics.drawable.Drawable.Callback, Animatable {
    private val frame = android.graphics.Bitmap.createBitmap(source.intrinsicWidth.coerceAtLeast(1), source.intrinsicHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
    private val frameCanvas = android.graphics.Canvas(frame)
    private val paint = android.graphics.Paint().apply { isFilterBitmap = false }
    private var frameScheduled = false
    private val retryFrame = Runnable { if (isRunning()) invalidateSelf() }
    init { source.callback = this }
    override fun getIntrinsicWidth() = source.intrinsicWidth
    override fun getIntrinsicHeight() = source.intrinsicHeight
    override fun draw(canvas: android.graphics.Canvas) {
        if (frame.isRecycled) return
        unscheduleSelf(retryFrame)
        frameScheduled = false
        frame.eraseColor(android.graphics.Color.TRANSPARENT)
        source.setBounds(0, 0, frame.width, frame.height)
        source.draw(frameCanvas)
        canvas.drawBitmap(frame, null, bounds, paint)
        // A software draw can return a zero delay just before a frame deadline,
        // including redraws caused by dragging/resizing. Keep that animation alive.
        if (isRunning() && !frameScheduled) scheduleSelf(retryFrame, android.os.SystemClock.uptimeMillis() + 16)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: android.graphics.drawable.Drawable) = invalidateSelf()
    // Android truncates software GIF deadlines to milliseconds. Drawing just before the
    // next frame returns a zero delay and stops scheduling; cross that boundary by 1 ms.
    override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, whenMillis: Long) {
        frameScheduled = true
        scheduleSelf(what, whenMillis + 1)
    }
    override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) = unscheduleSelf(what)
    override fun start() { (source as? Animatable)?.start(); invalidateSelf() }
    override fun stop() { (source as? Animatable)?.stop(); unscheduleSelf(retryFrame) }
    override fun isRunning() = (source as? Animatable)?.isRunning == true
    fun dispose() { stop(); source.callback = null; if (!frame.isRecycled) frame.recycle() }
}
internal fun applyComparisonFilter(image: ImageView, nearest: Boolean) {
    val current = image.drawable ?: return
    val source = if (current is NearestComparisonDrawable) current.source else current
    if (source is android.graphics.drawable.BitmapDrawable) {
        source.isFilterBitmap = !nearest
        image.invalidate()
    } else if (nearest && current !is NearestComparisonDrawable) {
        val filtered = NearestComparisonDrawable(source)
        image.setImageDrawable(filtered)
        source.callback = filtered
        filtered.start()
    } else if (!nearest && current is NearestComparisonDrawable) {
        current.dispose()
        image.setImageDrawable(source)
        (source as? Animatable)?.start()
    }
}
