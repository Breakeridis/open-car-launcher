package com.minimal.carlauncher.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.minimal.carlauncher.CarLauncherApp
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.location.HeadingSource
import com.minimal.carlauncher.location.VehicleState
import com.minimal.carlauncher.update.ApkInstaller
import com.minimal.carlauncher.update.UpdateState
import com.minimal.carlauncher.util.IntentUtil
import kotlinx.coroutines.launch

/**
 * The HOME activity.
 *
 * Nothing heavy or fallible happens in onCreate: the app list loads asynchronously and every
 * pref decode falls back to defaults. A launcher that throws here on a head unit with no
 * second home app leaves the device recoverable only over adb.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var dock: DockController
    private lateinit var drawer: DrawerController
    private lateinit var cards: QuickCardsController

    private val app: CarLauncherApp get() = application as CarLauncherApp

    private var lastCardinal: String? = null
    private var leftAtElapsedMs = 0L

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.onPermissionResult()
    }

    private val unknownSources = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // The user may have declined; re-check rather than assuming.
        val state = app.updateRepository.state.value
        if (state is UpdateState.ReadyToInstall && ApkInstaller.canInstall(this)) {
            ApkInstaller.install(this, state.file)?.let { toast(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repository = app.appRepository

        dock = DockController(this, binding.dockStrip, repository, lifecycleScope)
        drawer = DrawerController(this, binding, repository, lifecycleScope) { entry ->
            dock.pin(entry)
        }
        cards = QuickCardsController(this, binding, repository, lifecycleScope)

        dock.bind()
        drawer.bind()
        cards.bind()

        bindDashboardClicks()
        bindDockActions()
        bindBackBehaviour()
        observe()
    }

    // ------------------------------------------------------------------ wiring

    private fun bindDashboardClicks() {
        binding.gaugeSpeed.setOnClickListener { view ->
            val state = viewModel.vehicle.value
            when {
                !state.permissionGranted -> requestLocation()
                !state.gpsEnabled -> IntentUtil.openLocationSettings(this)
                else -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.toggleSpeedUnit()
                    render(viewModel.vehicle.value)
                }
            }
        }
    }

    private fun bindDockActions() {
        binding.dockStrip.btnAllApps.setOnClickListener { drawer.open() }
        // Tap goes to Android's own settings, long-press to this launcher's own settings -
        // so the common case stays one tap and the dock keeps its three fixed actions.
        binding.dockStrip.btnSettings.setOnClickListener {
            if (!IntentUtil.openSystemSettings(this)) toast(getString(R.string.could_not_launch))
        }
        binding.dockStrip.btnSettings.setOnLongClickListener {
            openSettings()
            true
        }
        binding.dockStrip.btnAbout.setOnClickListener { openAbout() }
    }

    /** A launcher must never finish itself - on some ROMs that leaves a blank screen. */
    private fun bindBackBehaviour() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isOpen) drawer.close()
            }
        })
    }

    private fun observe() {
        val repository = app.appRepository

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.apps.collect { apps ->
                    drawer.submitApps(apps)
                    dock.bind()
                    cards.refreshLabels()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.packageRemoved.collect { packageName ->
                    dock.onPackageRemoved(packageName)
                    cards.onPackageRemoved(packageName)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vehicle.collect { render(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.updateRepository.badgeVisible.collect { visible ->
                    binding.dockStrip.updateBadge.visibility =
                        if (visible) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ------------------------------------------------------------- dashboard render

    private fun render(state: VehicleState) {
        val unit = viewModel.speedUnit.value

        binding.textSpeed.text = Format.speedText(unit, state.speedMps, state.hasFix)
        binding.textSpeedUnit.text = Format.unitLabel(unit)
        binding.textSpeedStatus.setText(
            when {
                !state.permissionGranted -> R.string.gps_permission_needed
                !state.gpsEnabled -> R.string.gps_disabled
                !state.hasFix -> R.string.gps_acquiring
                else -> R.string.gps_ready
            }
        )

        val heading = state.headingDeg
        if (heading == null) {
            lastCardinal = null
            binding.textCardinal.setText(R.string.speed_placeholder)
            binding.textDegrees.text = ""
            binding.textHeadingSource.setText(
                if (viewModel.compassAvailable) R.string.heading else R.string.no_compass
            )
        } else {
            val cardinal = Format.cardinalWithHysteresis(
                heading, lastCardinal, Prefs.compass16Point
            )
            lastCardinal = cardinal
            binding.textCardinal.text = cardinal
            binding.textDegrees.text = Format.degreesText(heading)
            // Showing the source keeps a wrong stationary magnetometer reading from being
            // mistaken for the truth.
            binding.textHeadingSource.setText(
                if (state.headingSource == HeadingSource.GPS) R.string.heading_source_gps
                else R.string.heading_source_mag
            )
        }
    }

    // -------------------------------------------------------------------- dialogs

    private fun openAbout() {
        AboutDialog.show(this, app.updateRepository) { state ->
            // Never put an update prompt in front of a driver who is moving.
            if (viewModel.vehicle.value.speedMps > MOVING_MPS) {
                toast(getString(R.string.update_deferred_while_driving))
                return@show
            }
            val release = when (state) {
                is UpdateState.Available -> state.release
                is UpdateState.ReadyToInstall -> state.release
                else -> return@show
            }
            UpdateDialog.show(this, app.updateRepository, release) {
                toast(getString(R.string.update_unknown_sources))
                unknownSources.launch(ApkInstaller.unknownSourcesIntent(this))
            }
        }
    }

    private fun openSettings() {
        SettingsDialog.show(
            activity = this,
            repository = app.appRepository,
            scope = lifecycleScope,
            onDockReset = { dock.reset() },
            onPrefsChanged = {
                viewModel.refreshPrefs()
                lastCardinal = null
                render(viewModel.vehicle.value)
            }
        )
    }

    private fun requestLocation() {
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        // Coming back from another app after a while: a driver should never find a stale
        // search box waiting for them.
        if (leftAtElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - leftAtElapsedMs > STALE_RETURN_MS
        ) {
            resetToDashboard()
        }
        app.updateRepository.maybeCheck()
    }

    override fun onResume() {
        super.onResume()
        viewModel.start()
        viewModel.refreshPrefs()
        cards.refreshLabels()
        render(viewModel.vehicle.value)
        maybeAutoStartDashcam()
    }

    /**
     * Starts the DVR app once per launcher process so its floating overlay comes up on the
     * dashboard. Deliberately delayed: launching anything before the home screen has drawn
     * makes a head unit look like it is booting into the wrong app.
     */
    private fun maybeAutoStartDashcam() {
        if (app.dashcamAutoStartDone || !Prefs.dashcamAutoStart) return
        val stored = Prefs.dashcamPackage ?: return
        app.dashcamAutoStartDone = true

        binding.root.postDelayed({
            if (!IntentUtil.launchStored(this, stored, null)) {
                Prefs.dashcamPackage = null
                toast(getString(R.string.app_not_installed))
                return@postDelayed
            }
            if (Prefs.dashcamReturnHome) {
                // Best effort. Android 10+ restricts background activity starts, so some ROMs
                // will drop this and the user presses HOME once instead.
                binding.root.postDelayed({ returnToDashboard() }, DASHCAM_RETURN_DELAY_MS)
            }
        }, DASHCAM_START_DELAY_MS)
    }

    private fun returnToDashboard() {
        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Blocked by the background-activity-start policy; nothing useful to do.
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister the moment anything else comes to the foreground - this process is alive
        // for as long as the head unit is powered.
        viewModel.stop()
        leftAtElapsedMs = SystemClock.elapsedRealtime()
    }

    /** HOME pressed while already home: singleTask re-delivers the intent here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) resetToDashboard()
    }

    private fun resetToDashboard() {
        drawer.reset()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /** ~5 km/h. */
        const val MOVING_MPS = 1.4f
        const val STALE_RETURN_MS = 30_000L
        const val DASHCAM_START_DELAY_MS = 1_500L
        const val DASHCAM_RETURN_DELAY_MS = 3_000L
    }
}
