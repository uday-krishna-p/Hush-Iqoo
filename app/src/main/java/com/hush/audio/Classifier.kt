package com.hush.audio

import android.content.Context
import com.hush.HLog
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runs the stock YAMNet model on 0.975 s of 16 kHz audio and folds its 521 classes into
 * HUMAN / MACHINE / OTHER buckets. Create once, call [classify] from the audio thread.
 */
class Classifier(context: Context) {

    data class Result(
        val human: Float,
        val machine: Float,
        val topClass: String,
        val top5: List<Pair<String, Float>>
    )

    companion object {
        const val INPUT_SAMPLES = 15_600
        private const val TARGET_PEAK = 0.8f
        // Measured 26 Sep: with a x1000 cap a quiet room becomes full-scale rumble that YAMNet calls
        // "Vehicle"/"Aircraft". x20 keeps speech and knocks well above the model's silence level while a
        // quiet room still reads as Silence / Inside, small room.
        private const val MAX_GAIN = 20f

        // Exact YAMNet display names. Tune these after watching the top-5 list on the phone.
        val HUMAN = setOf(
            "Speech", "Shout", "Yell", "Children shouting", "Screaming",
            "Whistling", "Whistle",
            "Tap", "Knock", "Thump, thud", "Wood", "Bang", "Slap, smack",
            "Tick", "Tick-tock", "Clapping", "Finger snapping"
        )
        val MACHINE = setOf(
            "Engine", "Light engine (high frequency)", "Medium engine (mid frequency)",
            "Heavy engine (low frequency)", "Engine starting",
            "Power tool", "Tools", "Drill", "Hammer", "Jackhammer",
            "Vehicle", "Motor vehicle (road)"
        )
    }

    private val interpreter: Interpreter
    private val labels: List<String>
    private val humanIdx: IntArray
    private val machineIdx: IntArray
    private val input = FloatArray(INPUT_SAMPLES)
    private val input2d = arrayOf(input)
    private val inputIs2d: Boolean
    private val scores: Array<FloatArray>
    private val scoresOutputIndex: Int

    init {
        labels = loadLabels(context)
        HLog.d("Loaded ${labels.size} YAMNet labels")
        (HUMAN + MACHINE).filter { it !in labels }.forEach { HLog.d("WARNING bucket name not in class map: '$it'") }
        humanIdx = labels.indices.filter { labels[it] in HUMAN }.toIntArray()
        machineIdx = labels.indices.filter { labels[it] in MACHINE }.toIntArray()

        val options = Interpreter.Options().setNumThreads(2)
        interpreter = Interpreter(loadModel(context), options)

        val inShape = interpreter.getInputTensor(0).shape()
        HLog.d("YAMNet input shape ${inShape.contentToString()}")
        inputIs2d = inShape.size == 2
        interpreter.resizeInput(0, if (inputIs2d) intArrayOf(1, INPUT_SAMPLES) else intArrayOf(INPUT_SAMPLES))
        interpreter.allocateTensors()

        var found = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            HLog.d("YAMNet output $i shape ${shape.contentToString()}")
            if (found < 0 && shape.isNotEmpty() && shape.last() == labels.size) found = i
        }
        if (found < 0) {
            HLog.d("ERROR: no YAMNet output has ${labels.size} columns, using output 0")
            found = 0
        }
        scoresOutputIndex = found
        val outShape = interpreter.getOutputTensor(found).shape()
        val frames = if (outShape.size >= 2) outShape[0] else 1
        scores = Array(frames) { FloatArray(labels.size) }
        HLog.d("Classifier ready: scores from output $found, $frames frame(s)")
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd("yamnet.tflite")
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /** The csv is `index,mid,display_name`; display names with commas are wrapped in quotes. */
    private fun loadLabels(context: Context): List<String> =
        context.assets.open("yamnet_class_map.csv").bufferedReader().readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val second = line.indexOf(',', line.indexOf(',') + 1)
                line.substring(second + 1).trim().removeSurrounding("\"")
            }

    private var calls = 0

    /** Last gain applied before the model, for the debug screen. */
    @Volatile var lastGain: Float = 1f
        private set

    /**
     * Classifies the last [INPUT_SAMPLES] samples of [pcm16k] (16 kHz floats in -1..1).
     * The window is peak-normalised first: phone mics deliver very quiet audio and YAMNet
     * expects roughly full-scale input, otherwise everything scores as "Silence".
     */
    fun classify(pcm16k: FloatArray, n: Int): Result {
        val offset = maxOf(0, n - INPUT_SAMPLES)
        var peak = 0f
        for (i in 0 until INPUT_SAMPLES) {
            val j = offset + i
            val v = if (j < n) pcm16k[j] else 0f
            input[i] = v
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
        }
        val gain = if (peak > 1e-5f) (TARGET_PEAK / peak).coerceIn(1f, MAX_GAIN) else 1f
        if (gain != 1f) for (i in 0 until INPUT_SAMPLES) input[i] *= gain
        lastGain = gain
        calls++
        if (calls % 10 == 1) HLog.d("classifier peak=%.5f gain=%.1f".format(peak, gain))
        val outputs = mapOf<Int, Any>(scoresOutputIndex to scores)
        val inputs = arrayOf<Any>(if (inputIs2d) input2d else input)
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        // If the model returned several frames, average them.
        val avg = FloatArray(labels.size)
        for (frame in scores) for (k in avg.indices) avg[k] += frame[k] / scores.size

        var human = 0f
        for (k in humanIdx) human += avg[k]
        var machine = 0f
        for (k in machineIdx) machine += avg[k]

        val top5 = avg.indices.sortedByDescending { avg[it] }.take(5).map { labels[it] to avg[it] }
        return Result(
            human = human.coerceIn(0f, 1f),
            machine = machine.coerceIn(0f, 1f),
            topClass = top5.first().first,
            top5 = top5
        )
    }

    fun close() {
        interpreter.close()
    }
}
