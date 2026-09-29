package com.screenpro.recorder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTO_START = "screenpro_auto_start"

        private const val REQ_CAPTURE = 1001
        private const val REQ_AUDIO = 1002
        private const val REQ_NOTIFICATIONS = 1003
        private const val REQ_DELETE = 2001
    }

    // PALETTE
    private val cBg = Color.WHITE
    private val cBar = Color.WHITE
    private val cCard = Color.parseColor("#F5F6F8")
    private val cCard2 = Color.parseColor("#FFF1EA")
    private val cTint = Color.parseColor("#FFE6DA")
    private val cText = Color.parseColor("#1F2430")
    private val cMuted = Color.parseColor("#7B8496")
    private val cAccent = Color.parseColor("#FF5722")
    private val cOrange = Color.parseColor("#FF5722")
    private val cGradientEnd = Color.parseColor("#FF8A50")

    private lateinit var projectionManager: MediaProjectionManager
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val prefs get() = getSharedPreferences("screenpro", MODE_PRIVATE)

    private var recording = false
    private var paused = false
    private var saving = false
    private var pendingStart = false
    private var receiverRegistered = false
    private var currentTab = 0
    private var pendingDelete: Uri? = null

    // Views
    private lateinit var pages: List<View>
    private lateinit var tabViews: List<TextView>
    private lateinit var statusChip: TextView
    private lateinit var timerView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var hintView: TextView
    private lateinit var recordRing: View
    private lateinit var recordInner: View
    private lateinit var pauseButton: TextView
    private lateinit var chipsHolder: LinearLayout
    private lateinit var warnCard: LinearLayout
    private lateinit var libraryHolder: LinearLayout
    private lateinit var libraryCount: TextView
    private lateinit var settingsHolder: LinearLayout

    private var pulse: ObjectAnimator? = null

    private val tick = object : Runnable {
        override fun run() {
            if (recording && !paused) {
                timerView.text = RecordingService.formatElapsed(
                    RecordingService.elapsedMs()
                )
                ui.postDelayed(this, 300)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingService.ACTION_STATE_CHANGED -> {
                    val was = recording

                    recording = intent.getBooleanExtra(
                        RecordingService.EXTRA_IS_RECORDING,
                        false
                    )

                    paused = recording && intent.getBooleanExtra(
                        RecordingService.EXTRA_IS_PAUSED,
                        false
                    )

                    if (was && !recording) {
                        saving = true
                        ui.postDelayed({
                            saving = false
                            render()
                        }, 30000)
                    }

                    render()
                }

                RecordingService.ACTION_SAVED -> {
                    saving = false
                    render()
                    refreshLibrary()

                    if (intent.getBooleanExtra(RecordingService.EXTRA_SAVED, false)) {
                        toast("Saved to Movies/ScreenPro")
                    }
                }
            }
        }
    }

    // LIFECYCLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        window.statusBarColor = cBg
        window.navigationBarColor = cBar

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }

        buildUi()
        showTab(0)
        render()
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(launch: Intent?) {
        if (launch?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            launch.removeExtra(EXTRA_AUTO_START)
            ui.post { requestPermissionsAndCapture() }
        }
    }

    override fun onResume() {
        super.onResume()

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(RecordingService.ACTION_STATE_CHANGED)
                addAction(RecordingService.ACTION_SAVED)
            }

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }

            receiverRegistered = true
        }

        recording = RecordingService.isActive
        paused = RecordingService.isPausedNow

        refreshAll()
        refreshLibrary()
    }

    override fun onPause() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }

            receiverRegistered = false
        }

        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        pulse?.cancel()
        io.shutdown()
        super.onDestroy()
    }

    // UI SHELL

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)

            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(6))
        }

        header.addView(
            label("Screen", 24, cText, true).apply { typeface = Fonts.bold(this@MainActivity) },
            LinearLayout.LayoutParams(-2, -2)
        )

        header.addView(
            label("Pro", 24, cAccent, true).apply { typeface = Fonts.bold(this@MainActivity) },
            LinearLayout.LayoutParams(-2, -2)
        )

        root.addView(header)

        // Pages
        val holder = FrameLayout(this)

        val recordPage = buildRecordPage()
        val libraryPage = buildLibraryPage()
        val settingsPage = buildSettingsPage()

        pages = listOf(recordPage, libraryPage, settingsPage)
        pages.forEach { holder.addView(it) }

        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom tabs
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBar)
            elevation = dp(8).toFloat()
            setPadding(0, dp(8), 0, dp(8))
        }

        val titles = listOf("🎬\nRecord", "🎞\nLibrary", "⚙\nSettings")

        tabViews = titles.mapIndexed { index, title ->
            TextView(this).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { showTab(index) }
            }
        }

        tabViews.forEach {
            bar.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
        }

        root.addView(bar)
        setContentView(root)
    }

    private fun showTab(index: Int) {
        currentTab = index

        pages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        tabViews.forEachIndexed { i, tab ->
            tab.setTextColor(if (i == index) cAccent else cMuted)
            tab.typeface = if (i == index) Fonts.semiBold(this@MainActivity) else Fonts.medium(this@MainActivity)
        }

        if (index == 1) refreshLibrary()
    }

    // RECORD PAGE

    private fun buildRecordPage(): View {
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        statusChip = TextView(this).apply {
            textSize = 12f
            typeface = Fonts.bold(this@MainActivity)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }

        col.addView(statusChip, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(10)
        })

        timerView = TextView(this).apply {
            text = "00:00"
            textSize = 58f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(cText)
            gravity = Gravity.CENTER
            letterSpacing = 0.03f
        }

        col.addView(timerView, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(6)
        })

        subtitleView = label("", 13, cMuted, false).apply {
            gravity = Gravity.CENTER
        }

        col.addView(subtitleView, LinearLayout.LayoutParams(-2, -2))

        // Big record button
        val buttonRoot = FrameLayout(this).apply {
            setOnClickListener { onRecordPressed() }
        }

        recordRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), cAccent)
            }
        }

        buttonRoot.addView(
            recordRing,
            FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER)
        )

        val circle = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(cAccent, cGradientEnd)
            ).apply { shape = GradientDrawable.OVAL }
            elevation = dp(8).toFloat()
        }

        buttonRoot.addView(
            circle,
            FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER)
        )

        recordInner = View(this).apply {
            elevation = dp(10).toFloat()
        }

        buttonRoot.addView(
            recordInner,
            FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
        )

        col.addView(buttonRoot, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            topMargin = dp(18)
        })

        hintView = label("", 13, cMuted, false).apply {
            gravity = Gravity.CENTER
        }

        col.addView(hintView, LinearLayout.LayoutParams(-2, -2))

        pauseButton = TextView(this).apply {
            textSize = 14f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(cAccent)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(11), dp(28), dp(11))
            background = rounded(cCard2, 26)
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_TOGGLE_PAUSE
                    }
                )
            }
        }

        col.addView(pauseButton, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(14)
        })

        // Permission warning
        warnCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.parseColor("#FFF3E8"), 16)
        }

        warnCard.addView(label("Floating controls are off", 14, cOrange, true))
        warnCard.addView(
            label(
                "Allow \"Display over other apps\" to get the floating ball, pen, screenshots and the 3-2-1 countdown.",
                12,
                cMuted,
                false
            ).apply { setPadding(0, dp(4), 0, dp(10)) }
        )

        warnCard.addView(
            TextView(this).apply {
                text = "Enable"
                textSize = 13f
                typeface = Fonts.bold(this@MainActivity)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(9), dp(20), dp(9))
                background = rounded(cOrange, 20)
                setOnClickListener { openOverlaySettings() }
            },
            LinearLayout.LayoutParams(-2, -2)
        )

        col.addView(warnCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(20)
        })

        chipsHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        col.addView(chipsHolder, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
        })

        scroll.addView(col)
        return scroll
    }

    private fun refreshChips() {
        chipsHolder.removeAllViews()

        val cards = listOf(
            chipCard("🎙", "Audio", audioShort(), true) { chooseAudioSource() },
            chipCard("⏱", "Countdown", onOff("countdown_enabled", true), isOn("countdown_enabled", true)) {
                toggle("countdown_enabled", true)
            },
            chipCard("🫧", "Floating ball", onOff("floating_ball", true), isOn("floating_ball", true)) {
                toggle("floating_ball", true)
            },
            chipCard("📳", "Shake to stop", onOff("shake_to_stop", false), isOn("shake_to_stop", false)) {
                toggle("shake_to_stop", false)
            }
        )

        for (i in cards.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(
                cards[i],
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
            )

            row.addView(
                cards[i + 1],
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) }
            )

            chipsHolder.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            )
        }
    }

    private fun chipCard(
        emoji: String,
        title: String,
        state: String,
        active: Boolean,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))

            background = GradientDrawable().apply {
                setColor(if (active) cCard2 else cCard)
                cornerRadius = dp(18).toFloat()

                if (active) setStroke(dp(1), Color.parseColor("#66FF5722"))
            }

            addView(label(emoji, 22, cText, false))
            addView(label(title, 14, cText, true).apply { setPadding(0, dp(6), 0, 0) })
            addView(
                label(state, 12, if (active) cOrange else cMuted, true)
            )

            setOnClickListener { onClick() }
        }
    }

    // LIBRARY PAGE

    private fun buildLibraryPage(): View {
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        col.addView(label("Recordings", 20, cText, true))

        libraryCount = label("", 12, cMuted, false).apply {
            setPadding(0, dp(2), 0, dp(12))
        }

        col.addView(libraryCount)

        libraryHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        col.addView(libraryHolder)
        scroll.addView(col)
        return scroll
    }

    private fun refreshLibrary() {
        if (!::libraryHolder.isInitialized) return

        io.execute {
            val items = try {
                RecordingStore.listItems(this)
            } catch (_: Exception) {
                emptyList()
            }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                showLibrary(items)
            }
        }
    }

    private fun showLibrary(items: List<RecordingStore.Item>) {
        libraryHolder.removeAllViews()

        libraryCount.text = when (items.size) {
            0 -> ""
            1 -> "1 video"
            else -> "${items.size} videos"
        }

        if (items.isEmpty()) {
            libraryHolder.addView(
                label(
                    "No recordings yet.\nYour videos will show up here.",
                    14,
                    cMuted,
                    false
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(60), 0, 0)
                },
                LinearLayout.LayoutParams(-1, -2)
            )

            return
        }

        val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(6))
                background = rounded(cCard, 18)
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(cCard2, 12)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                setOnClickListener { play(item.uri) }
            }

            top.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(64)))
            loadThumbnail(thumb, item.uri)

            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            texts.addView(
                label(item.name.removePrefix("ScreenPro_").removeSuffix(".mp4"), 13, cText, true)
            )

            texts.addView(
                label(
                    RecordingService.formatElapsed(item.durationMs) + "  ·  " +
                        formatSize(item.sizeBytes) + "  ·  " +
                        dateFormat.format(Date(item.dateAddedSec * 1000)),
                    11,
                    cMuted,
                    false
                ).apply { setPadding(0, dp(4), 0, 0) }
            )

            top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(top)

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            actions.addView(
                actionText("▶  Play", cText) { play(item.uri) },
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            actions.addView(
                actionText("↗  Share", cText) { share(item.uri) },
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            actions.addView(
                actionText("🗑  Delete", cAccent) { confirmDelete(item) },
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            })

            libraryHolder.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            )
        }
    }

    private fun actionText(text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun loadThumbnail(view: ImageView, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        io.execute {
            val bitmap = try {
                contentResolver.loadThumbnail(uri, Size(360, 200), null)
            } catch (_: Exception) {
                null
            }

            if (bitmap != null) {
                runOnUiThread { view.setImageBitmap(bitmap) }
            }
        }
    }

    private fun play(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: Exception) {
            toast("No video player found")
        }
    }

    private fun share(uri: Uri) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(send, "Share recording"))
        } catch (_: Exception) {
            toast("Couldn't share this video")
        }
    }

    private fun confirmDelete(item: RecordingStore.Item) {
        dialog()
            .setTitle("Delete recording?")
            .setMessage("This will permanently delete ${item.name}.")
            .setPositiveButton("Delete") { _, _ -> deleteVideo(item.uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteVideo(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
            refreshLibrary()
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    pendingDelete = uri

                    val request = MediaStore.createDeleteRequest(
                        contentResolver,
                        listOf(uri)
                    )

                    startIntentSenderForResult(
                        request.intentSender,
                        REQ_DELETE,
                        null,
                        0,
                        0,
                        0
                    )
                } catch (e: Exception) {
                    toast("Couldn't delete this video")
                }
            } else {
                toast("Couldn't delete this video")
            }
        } catch (_: Exception) {
            toast("Couldn't delete this video")
        }
    }

    // SETTINGS PAGE

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        settingsHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        scroll.addView(settingsHolder)
        return scroll
    }

    private fun refreshSettings() {
        settingsHolder.removeAllViews()

        settingsHolder.addView(sectionTitle("AUDIO"))
        settingsHolder.addView(
            card(
                settingRow("🎙", "Audio source", audioLabel()) { chooseAudioSource() }
            )
        )

        settingsHolder.addView(sectionTitle("VIDEO"))
        settingsHolder.addView(
            card(
                settingRow("🖥", "Resolution", prefs.getString("resolution", "1080p") ?: "1080p") {
                    choose(
                        "Resolution",
                        listOf("720p", "1080p", "1440p", "Native"),
                        prefs.getString("resolution", "1080p") ?: "1080p"
                    ) { prefs.edit().putString("resolution", it).apply() }
                },
                divider(),
                settingRow("✨", "Quality", "${prefs.getInt("bitrate_mbps", 8)} Mbps") {
                    choose(
                        "Quality (bitrate)",
                        listOf("4 Mbps", "8 Mbps", "12 Mbps", "16 Mbps"),
                        "${prefs.getInt("bitrate_mbps", 8)} Mbps"
                    ) {
                        prefs.edit().putInt("bitrate_mbps", it.substringBefore(" ").toInt()).apply()
                    }
                },
                divider(),
                settingRow("🎞", "Frame rate", "${prefs.getInt("fps", 30)} fps") {
                    choose(
                        "Frame rate",
                        listOf("24 fps", "30 fps", "60 fps"),
                        "${prefs.getInt("fps", 30)} fps"
                    ) {
                        prefs.edit().putInt("fps", it.substringBefore(" ").toInt()).apply()
                    }
                }
            )
        )

        settingsHolder.addView(sectionTitle("CONTROLS"))
        settingsHolder.addView(
            card(
                switchRow("⏱", "3-2-1 countdown", "Get ready before recording starts", "countdown_enabled", true),
                divider(),
                switchRow("🫧", "Floating ball", "Timer, pause, screenshot and pen on top of any app", "floating_ball", true),
                divider(),
                switchRow("📳", "Shake to stop", "Shake your phone to end the recording", "shake_to_stop", false)
            )
        )

        settingsHolder.addView(sectionTitle("PERMISSIONS"))
        settingsHolder.addView(
            card(
                settingRow(
                    "🛡",
                    "Display over other apps",
                    if (canOverlay()) "Allowed" else "Tap to allow"
                ) { openOverlaySettings() }
            )
        )
    }

    private fun card(vararg rows: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = rounded(cCard, 18)
            rows.forEach { addView(it) }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return label(text, 11, cMuted, true).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), dp(18), 0, dp(8))
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#14000000"))
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
    }

    private fun iconBadge(emoji: String): View {
        return TextView(this).apply {
            text = emoji
            textSize = 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = rounded(cTint, 10)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(14)
            }
        }
    }

    private fun settingRow(
        icon: String,
        title: String,
        value: String,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))

            addView(iconBadge(icon))

            addView(
                label(title, 14, cText, false).apply { typeface = Fonts.medium(this@MainActivity) },
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(label(value, 13, cOrange, true))
            setOnClickListener { onClick() }
        }
    }

    private fun switchRow(
        icon: String,
        title: String,
        subtitle: String,
        key: String,
        default: Boolean
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        row.addView(iconBadge(icon))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        texts.addView(label(title, 14, cText, false))
        texts.addView(label(subtitle, 11, cMuted, false).apply {
            setPadding(0, dp(2), dp(12), 0)
        })

        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )

        val toggle = Switch(this).apply {
            isChecked = isOn(key, default)

            thumbTintList = ColorStateList(
                states,
                intArrayOf(cAccent, Color.WHITE)
            )

            trackTintList = ColorStateList(
                states,
                intArrayOf(Color.parseColor("#80FF5722"), Color.parseColor("#D5D9E2"))
            )

            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                refreshChips()
            }
        }

        row.addView(toggle)
        return row
    }

    // CHOICES

    private fun dialog(): AlertDialog.Builder {
        return AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
    }

    private fun choose(
        title: String,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        dialog()
            .setTitle(title)
            .setSingleChoiceItems(
                options.toTypedArray(),
                options.indexOf(current)
            ) { d, which ->
                onPick(options[which])
                d.dismiss()
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseAudioSource() {
        val labels = mutableListOf("Microphone")
        val values = mutableListOf("mic")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            labels += "Internal audio (game / video sound)"
            values += "internal"
            labels += "Microphone + internal audio"
            values += "both"
        }

        labels += "No audio"
        values += "none"

        val current = RecordingService.audioSource(this)

        dialog()
            .setTitle("Audio source")
            .setSingleChoiceItems(
                labels.toTypedArray(),
                values.indexOf(current)
            ) { d, which ->
                prefs.edit().putString("audio_source", values[which]).apply()
                d.dismiss()
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // PREF HELPERS

    private fun isOn(key: String, default: Boolean) = prefs.getBoolean(key, default)

    private fun onOff(key: String, default: Boolean) =
        if (isOn(key, default)) "ON" else "OFF"

    private fun toggle(key: String, default: Boolean) {
        prefs.edit().putBoolean(key, !isOn(key, default)).apply()
        refreshAll()
    }

    private fun audioShort(): String = when (RecordingService.audioSource(this)) {
        "internal" -> "Internal"
        "both" -> "Mic + Internal"
        "none" -> "Off"
        else -> "Mic"
    }

    private fun audioLabel(): String = when (RecordingService.audioSource(this)) {
        "internal" -> "Internal audio"
        "both" -> "Mic + Internal"
        "none" -> "No audio"
        else -> "Microphone"
    }

    private fun refreshAll() {
        refreshChips()
        refreshSettings()
        render()
    }

    // STATE RENDERING

    private fun render() {
        if (!::statusChip.isInitialized) return

        val (text, color) = when {
            saving -> Pair("SAVING…", cOrange)
            recording && paused -> Pair("PAUSED", cOrange)
            recording -> Pair("● RECORDING", cAccent)
            else -> Pair("READY", Color.parseColor("#1FA35B"))
        }

        statusChip.text = text
        statusChip.setTextColor(color)
        statusChip.background = GradientDrawable().apply {
            setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = dp(20).toFloat()
        }

        subtitleView.text = "🎙 ${audioShort()}  ·  " +
            "${prefs.getString("resolution", "1080p")}  ·  " +
            "${prefs.getInt("fps", 30)} fps  ·  " +
            "${prefs.getInt("bitrate_mbps", 8)} Mbps"

        // Timer
        ui.removeCallbacks(tick)

        if (recording) {
            timerView.text = RecordingService.formatElapsed(RecordingService.elapsedMs())
            if (!paused) ui.post(tick)
        } else {
            timerView.text = "00:00"
        }

        // Record button
        val inner = recordInner.layoutParams as FrameLayout.LayoutParams

        recordInner.background = GradientDrawable().apply {
            setColor(Color.WHITE)

            if (recording) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                inner.width = dp(38)
                inner.height = dp(38)
            } else {
                shape = GradientDrawable.OVAL
                inner.width = dp(46)
                inner.height = dp(46)
            }
        }

        recordInner.layoutParams = inner
        recordInner.alpha = if (saving) 0.4f else 1f

        pulse?.cancel()
        pulse = null
        recordRing.scaleX = 1f
        recordRing.scaleY = 1f
        recordRing.alpha = 0.35f

        if (recording && !paused) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                recordRing,
                PropertyValuesHolder.ofFloat("scaleX", 0.85f, 1.25f),
                PropertyValuesHolder.ofFloat("scaleY", 0.85f, 1.25f),
                PropertyValuesHolder.ofFloat("alpha", 0.8f, 0f)
            ).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        hintView.text = when {
            saving -> "Saving your video…"
            recording -> "Tap to stop"
            else -> "Tap to start recording"
        }

        hintView.setPadding(0, dp(4), 0, 0)

        pauseButton.visibility = if (recording && !saving) View.VISIBLE else View.GONE
        pauseButton.text = if (paused) "▶   Resume" else "❚❚   Pause"

        val needsOverlay =
            (isOn("floating_ball", true) || isOn("countdown_enabled", true)) &&
                !canOverlay()

        warnCard.visibility = if (needsOverlay) View.VISIBLE else View.GONE
    }

    // START / STOP

    private fun onRecordPressed() {
        when {
            saving -> toast("Still saving your last recording…")
            recording -> stopRecording()
            else -> requestPermissionsAndCapture()
        }
    }

    private fun stopRecording() {
        saving = true
        render()

        startService(
            Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
        )
    }

    private fun requestPermissionsAndCapture() {
        if (recording || saving || RecordingService.isCountingDown) {
            toast("A recording is already active.")
            return
        }

        val needsAudio = RecordingService.audioSource(this) != "none"

        if (
            needsAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }

        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQ_CAPTURE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)

        if (requestCode == REQ_AUDIO) {
            val granted = results.isNotEmpty() &&
                results[0] == PackageManager.PERMISSION_GRANTED

            if (granted && pendingStart) {
                pendingStart = false
                requestPermissionsAndCapture()
            } else {
                pendingStart = false

                dialog()
                    .setTitle("Microphone permission needed")
                    .setMessage(
                        "Audio recording needs microphone access. You can allow it in " +
                            "app settings, or record without audio."
                    )
                    .setPositiveButton("Open settings") { _, _ ->
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                    .setNeutralButton("Record without audio") { _, _ ->
                        prefs.edit().putString("audio_source", "none").apply()
                        refreshAll()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    @Deprecated("Uses compatible activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) refreshLibrary()
            pendingDelete = null
            return
        }

        if (requestCode != REQ_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(service)
                } else {
                    startService(service)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toast("Could not start recording")
            }
        } else {
            toast("Screen capture permission was not granted")
        }
    }

    // OVERLAY PERMISSION

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            toast("Open Settings → Apps → ScreenPro → Display over other apps")
        }
    }

    // SMALL HELPERS

    private fun label(text: String, sp: Int, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sp.toFloat()
            setTextColor(color)
            includeFontPadding = false
            typeface = if (bold) Fonts.semiBold(this@MainActivity) else Fonts.regular(this@MainActivity)
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)

        return if (mb >= 1024) {
            String.format(Locale.US, "%.1f GB", mb / 1024)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
