package us.theworkmans.proseplow.llama

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec

/*
 * How fast the models run when the app is not what the user is looking at: with its activity in
 * front (the reference), with no activity of it in front and the screen on, and with the screen
 * off. Nothing here is required; every line is a measurement, taken for the plan to let an
 * answer finish after the app was put in the background or the phone was locked.
 *
 * What these numbers are and are not. Android decides which cores a process may use by what it
 * is doing, and each line reports the set the test process was in while it measured (its
 * cpuset and the CPUs its thread was allowed). The test process is an instrumented one, which
 * Android does not treat as it treats an app that merely went to the background: read a line as
 * "this is the speed in that set of cores", and look at the set, not at the name of the
 * condition, to know what an app in the same position would get. The phone is on USB power
 * while it is measured, which also keeps it from suspending the way it would on battery.
 */

/** Plain prose to make a prompt of; written for this project. */
private const val SPEED_PASSAGE: String =
    "The lighthouse keeper wrote the weather into a ledger every evening: the direction of the wind, the height " +
        "of the swell, the ships that had passed and the ones that were late. After forty years the ledgers filled " +
        "a cupboard, and nobody had ever asked to read them. He kept writing all the same, because a record that " +
        "stops is a record that lies about the days it leaves out. "

private const val SPEED_PROMPT_TOKENS: Int = 160
private const val SPEED_REPLY_TOKENS: Int = 24

/** The two models the measurement was asked for, the largest and the one with the largest memory per token. */
private val SPEED_MODELS: List<ModelSpec> = listOf(ModelCatalog.QWEN35_0_8B, ModelCatalog.PEACEBELL_V1_291M)

private fun powerManager(): PowerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

/** "on USB or mains power" or "on battery", from the system's last battery broadcast. */
private fun powerSource(): String {
    val battery: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    return if (plugged != 0) "on external power" else "on battery"
}

/**
 * Opens [model] with the settings the app uses and reports one line for [condition]: the speed
 * of a prompt of [SPEED_PROMPT_TOKENS] tokens in the engine's chunks and of [SPEED_REPLY_TOKENS]
 * generated tokens, the threads the session ended up with, where the process was allowed to
 * run before and after, and whether the screen was on before and after. A model that is not
 * present is passed over.
 */
private fun reportSpeed(condition: String, model: ModelSpec) {
    if (ModelFiles.locate(appContext, model) == null) {
        report("${model.id} $condition: the model is not in this test APK")
        return
    }
    val power = powerManager()
    val screenBefore = power.isInteractive
    val groupBefore = schedulingGroup()
    val native = open(model)
    try {
        val passage = native.tokens(SPEED_PASSAGE.repeat(4), false)
        val prompt = passage.copyOf(minOf(passage.size, SPEED_PROMPT_TOKENS))
        // Once unmeasured: the first evaluation builds the thread pool and touches every page.
        native.mustEvaluate(prompt.copyOf(ENGINE_CHUNK))
        native.clearMemory()
        val promptNanos = native.mustEvaluate(prompt)
        native.configureSampler(greedy(native.contextSize(), model.bannedTokenIds))
        val start = System.nanoTime()
        val reply = native.continueReply(SPEED_REPLY_TOKENS) { false }
        val replyNanos = System.nanoTime() - start
        val threads = native.threads()
        report(
            "${model.id} $condition: prompt %.1f tokens/s (%d tokens in chunks of %d), generation %.1f tokens/s (%d tokens); %d thread(s)%s; screen %s before and %s after; %s; before: %s; after: %s".format(
                prompt.size * 1e9 / promptNanos, prompt.size, ENGINE_CHUNK, reply.size * 1e9 / replyNanos, reply.size,
                threads.count, if (threads.pinned) ", pinned" else ", not pinned",
                if (screenBefore) "on" else "off", if (power.isInteractive) "on" else "off", powerSource(), groupBefore, schedulingGroup(),
            ),
        )
    } finally {
        native.close()
    }
}

/** In front, then with nothing of the test in front and the screen still on. */
@RunWith(AndroidJUnit4::class)
class BackgroundSpeedDeviceTest {

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Test
    fun inFrontAndThenInTheBackground() {
        assumeTrue("neither model of this measurement is present", SPEED_MODELS.any { ModelFiles.locate(appContext, it) != null })
        for (model in SPEED_MODELS) reportSpeed("with the test's activity in front", model)
        inFront.scenario.close()
        Thread.sleep(1500) // the system moves the process to its new set of cores a moment after the activity has gone
        for (model in SPEED_MODELS) reportSpeed("with nothing of the test in front, screen on", model)
    }
}

/**
 * With the screen off. The test turns the screen off itself, with the test's activity in front
 * as an app's would be when its user presses the power button, and measures twice: as the
 * system leaves the process, and with a partial wake lock held by the test process.
 *
 * Turning the screen off locks a phone that has a lock, and nothing here can unlock it again,
 * so this test does nothing unless it is asked for by name:
 *
 *     adb shell am instrument -w -e class us.theworkmans.proseplow.llama.ScreenOffSpeedDeviceTest \
 *         -e screenOff true us.theworkmans.proseplow.llama.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Run it last. The key event is only sent while the test's own activity is the one in front.
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffSpeedDeviceTest {

    @Test
    fun withTheScreenOff() {
        assumeTrue(
            "not asked for: pass -e screenOff true (the test turns the screen off, which locks the phone)",
            InstrumentationRegistry.getArguments().getString("screenOff") == "true",
        )
        assumeTrue("neither model of this measurement is present", SPEED_MODELS.any { ModelFiles.locate(appContext, it) != null })
        val power = powerManager()
        val scenario = ActivityScenario.launch(ForegroundActivity::class.java)
        try {
            assumeTrue("the screen is not on, so there is no screen to turn off", power.isInteractive)
            assertEquals("the test's own activity is in front before a key is sent", Lifecycle.State.RESUMED, scenario.state)
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            var waited = 0
            while (power.isInteractive && waited < 10_000) {
                Thread.sleep(100)
                waited += 100
            }
            assumeTrue("the screen did not go off within ten seconds", !power.isInteractive)
            Thread.sleep(3000) // what the system does to a process when the screen goes off, it does a moment later
            report("the screen went off after $waited ms; ${powerSource()}; device idle mode: ${power.isDeviceIdleMode}")

            for (model in SPEED_MODELS) reportSpeed("with the screen off, no wake lock", model)

            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proseplow:measurement")
            lock.acquire(10 * 60 * 1000L)
            try {
                for (model in SPEED_MODELS) reportSpeed("with the screen off, a partial wake lock held", model)
            } finally {
                lock.release()
            }
        } finally {
            try {
                scenario.close()
            } catch (_: RuntimeException) {
                // An activity behind a lock screen may not finish in the time the scenario allows;
                // the process ends with the test run either way.
            }
        }
    }
}
