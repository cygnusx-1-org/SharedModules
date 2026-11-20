package com.liskovsoft.sharedutils.cronet

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import java.io.File
import org.chromium.net.CronetEngine
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.impl.NativeCronetProvider

object CronetManager {
    private val TAG = CronetManager::class.java.simpleName
    /**
     * Of the engine's making: one thread makes it, the others wait for it (see warmUp)
     */
    private val lock = Any()
    @Volatile
    private var engine: CronetEngine? = null
    // Under the lock
    private var isWarmUpStarted = false

    /**
     * Makes the engine ahead, on a thread of its own: making it loads its native library, which the run's first video did on the main
     * thread while it opened (6EZTNXBF: 833 ms of a start's media source, 16-38 ms for the others). Once a run. A call for the engine
     * meanwhile waits for it, no longer than it took to make it itself.
     */
    @JvmStatic
    fun warmUp(context: Context) {
        synchronized(lock) {
            if (engine != null || isWarmUpStarted) {
                return
            }

            isWarmUpStarted = true
        }

        val appContext = context.applicationContext

        Thread({
            try {
                getEngine(appContext)
            } catch (e: Throwable) {
                // Made when it's asked for, as before: what fails there is told there
                Log.e(TAG, "Can't make the engine ahead: " + e.message)
            }
        }, "CronetWarmUp").apply {
            isDaemon = true
            start()
        }
    }

    @JvmStatic
    fun getEngine(context: Context): CronetEngine? {
        engine?.let { return it }

        return synchronized(lock) { makeEngine(context) }
    }

    /**
     * Under the lock
     */
    private fun makeEngine(context: Context): CronetEngine? {
        if (engine == null) {
            //val wrapper = CronetEngineWrapper(context)
            //engine = wrapper.cronetEngine

            //engine = CronetEngine.Builder(context)
            //    .enableQuic(true)
            //    .enableHttp2(true)
            //    .enableBrotli(true)
            //    .build()

            try {
                // Cache dir for QUIC cache support.
                // NOTE: the caching is mainly intended to fix the bot guard check errors (e.g. auto-translated subtitles).
                // NOTE: Cronet QUIC/HTTP cache is not suitable here. Use ExoPlayer CacheDataSource for media/subtitle caching instead.
                // More info: https://github.com/yuliskov/SharedModules/pull/11
                //val cacheDir = File(context.cacheDir, "StCronet")
                //cacheDir.mkdirs()

                val builder = NativeCronetProvider(context).createBuilder()

                builder
                    .enableQuic(true)
                    .enableHttp2(true)
                    .enableBrotli(true)
                    //.setStoragePath(cacheDir.absolutePath) // cache dir
                    //.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 2 * 1024 * 1024) // cache size
                    //.addQuicHint("youtube.com", 80, 80)

                // Do these tweaks have negative side effects?
                //if (builder is ExperimentalCronetEngine.Builder) {
                //    val experimentalOptions = """
                //        {
                //            "AsyncResolver": {
                //                "enable_async_resolver": true
                //            },
                //            "StaleDNS": {
                //                "enable": true,
                //                "max_expired_time_ms": 86400000,
                //                "max_stale_uses": 5,
                //                "persist_to_disk": false,
                //                "use_stale_on_name_not_resolved": true
                //            },
                //            "QUIC": {
                //                "delay_tcp_race": true,
                //                "estimate_initial_rtt": true,
                //                "max_server_configs_stored_in_properties": 8,
                //                "idle_connection_timeout_seconds": 30,
                //                "goaway_sessions_on_ip_change": true,
                //                "initial_rtt_for_handshake_milliseconds": 100,
                //                "allow_other_network_reuse": true
                //            }
                //        }
                //    """.trimIndent()
                //
                //    builder.setExperimentalOptions(experimentalOptions)
                //}

                engine = builder.build()
            } catch (e: UnsatisfiedLinkError) {
                // Fatal Exception: java.lang.UnsatisfiedLinkError
                // Cannot load library: soinfo_relocate(linker.cpp:982): cannot locate symbol "getauxval" referenced by "libcronet.101.0.4951.41.so".
                e.printStackTrace()
                Log.e(TAG, e.message)
            }
        }

        return engine
    }
}
