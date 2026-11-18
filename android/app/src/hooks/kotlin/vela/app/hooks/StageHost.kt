package vela.app.hooks

import android.os.Bundle
import android.util.Log

/**
 * debug / perf 才编进来。单路仪器测试把 [vela.app.MainActivity] 当成窗口，
 * 自己再起一个 ExoPlayer。完整舞台若同时拉目录并起播，两路会抢伪上游的一次性回退
 * （序号回退、改写地址、过期），测试那一路就看不到原地重新 prepare。
 * 这时只留空宿主。release 没有这个类，真实启动不会问它。
 */
object StageHost {
    const val LANE_INSTRUMENT_CLASS: String = "vela.app.lane.LaneEmulatorTest"

    @JvmStatic
    fun shouldBootStage(): Boolean {
        val boot = try {
            shouldBootStageFor(instrumentationClassArgument())
        } catch (error: Throwable) {
            Log.w(TAG, "stage host check failed; boot stage", error)
            true
        }
        if (!boot) Log.i(TAG, "bare-host: skip stage for lane instrument")
        return boot
    }

    /** 仪器 class 参数点名单路测试时不起舞台。读不到参数、或是别的测试，都按真实启动起。 */
    internal fun shouldBootStageFor(classArgument: String?): Boolean = !isLaneInstrumentClass(classArgument)

    internal fun isLaneInstrumentClass(classArgument: String?): Boolean {
        if (classArgument.isNullOrBlank()) return false
        return classArgument.split(',').any { entry ->
            isLaneInstrumentName(entry)
        }
    }

    internal fun isLaneInstrumentName(name: String): Boolean {
        val bare = name.trim().substringBefore('#').substringBefore('$').trim()
        return bare == LANE_INSTRUMENT_CLASS
    }

    private fun instrumentationClassArgument(): String? {
        val bundle = instrumentationArguments() ?: return null
        return bundle.getString("class")
    }

    /**
     * 仪器参数在测试进程的 AndroidJUnitRunner 上，应用 classpath 没有这个类。
     * 先用 runner 的 classloader 调 InstrumentationRegistry；不行再读 runner 自己的私有字段。
     * debug / perf 可调试，ActivityThread 的反射放行。读不到就当没有参数。
     */
    private fun instrumentationArguments(): Bundle? {
        return try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null) ?: return null
            val instrumentation = threadClass.getMethod("getInstrumentation").invoke(thread) ?: return null
            argumentsFromRegistry(instrumentation) ?: argumentsFromRunner(instrumentation)
        } catch (_: Throwable) {
            null
        }
    }

    private fun argumentsFromRegistry(instrumentation: Any): Bundle? {
        return try {
            val loader = instrumentation.javaClass.classLoader ?: return null
            val registry = Class.forName(
                "androidx.test.platform.app.InstrumentationRegistry",
                true,
                loader,
            )
            val method = registry.getMethod("getArguments")
            method.invoke(null) as? Bundle
        } catch (_: Throwable) {
            null
        }
    }

    private fun argumentsFromRunner(instrumentation: Any): Bundle? {
        var type: Class<*>? = instrumentation.javaClass
        while (type != null && type != Any::class.java) {
            val method = try {
                type.getDeclaredMethod("getArguments")
            } catch (_: NoSuchMethodException) {
                null
            }
            if (method != null) {
                method.isAccessible = true
                val value = method.invoke(instrumentation)
                if (value is Bundle) return value
            }
            for (name in ARGUMENT_FIELDS) {
                val field = try {
                    type.getDeclaredField(name)
                } catch (_: NoSuchFieldException) {
                    null
                } ?: continue
                field.isAccessible = true
                val value = field.get(instrumentation)
                if (value is Bundle) return value
            }
            type = type.superclass
        }
        return null
    }

    private val ARGUMENT_FIELDS = arrayOf("arguments", "mArguments")

    private const val TAG = "vela.lifecycle"
}
