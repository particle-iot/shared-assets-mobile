/**
 * Tool for converting the pinInfo.json file into something easily consumable for use in the
 * Tinker view of the Particle mobile apps
 *
 * (JSON lives at: https://github.com/particle-iot/docs/blob/master/src/assets/files/pinInfo.json )
 *
 * Requires GSON, Okio, the Particle Android SDK, and a Kotlin environment to run this file from
 * (e.g.: an Android app: just copy the output file from the Device File Explorer in Android Studio)
 */
package io.particle.android.sdk.ui

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.particle.android.sdk.cloud.ParticleDevice.ParticleDeviceType
import io.particle.android.sdk.ui.PinFunction.AnalogRead
import io.particle.android.sdk.ui.PinFunction.AnalogWriteDAC
import io.particle.android.sdk.ui.PinFunction.AnalogWritePWM
import io.particle.android.sdk.ui.PinFunction.DigitalRead
import io.particle.android.sdk.ui.PinFunction.DigitalWrite
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


fun getPlatformsFromJson(jsonString: String): List<ParticlePlatform> {
    val gson = Gson()
    val upstreamPinInfo = gson.fromJson(jsonString, SourcePinDoc::class.java)
    return upstreamPinInfo.platforms
        // just use the electron mapping, since the platform IDs are the same...
        .filter { it.name.toLowerCase() != "e series" }
        .map { it.toPlatform() }
        .plus(corePins)
}


fun writePlatformsToSDCard(
    platforms: List<ParticlePlatform>,
    targetPath: String,
    overwriteExistingFile: Boolean = false
) {
    val targetFile = File(targetPath)
    if (targetFile.exists() and !overwriteExistingFile) {
        throw IOException("Refusing to overwrite existing file!")
    }

    val gson = Gson()
    val asJson = gson.toJson(platforms)

    val fileStream = FileOutputStream(targetPath)
    fileStream.sink().buffer().use {
        it.writeUtf8(asJson)
    }
}


//region Source document JSON types
data class SourcePinDoc(val platforms: List<SourcePlatform>)

data class SourcePlatform(
    val id: Int,
    val name: String,
    val pins: List<SourcePin>
)

data class SourcePin(
    val name: String,
    val altName: String?,
    val digitalRead: String?,
    val digitalWrite: String?,
    val analogRead: String?,
    val analogWriteDAC: String?,
    val analogWritePWM: String?,
    val serial: String?
)
//endregion


//region Result model types
data class ParticlePlatform(
    val deviceTypeId: Int,
    val deviceType: ParticleDeviceType,
    val pins: List<Pin>
)


enum class PinColumn {
    LEFT,
    RIGHT
}


data class Pin(
    val label: String,
    val tinkerName: String,
    val functions: List<PinFunction>,
    val column: PinColumn = PinColumn.LEFT
)


@JsonAdapter(PinFunctionAdapter::class)
sealed class PinFunction(val name: String) {

    object DigitalRead : PinFunction("DigitalRead")
    object DigitalWrite : PinFunction("DigitalWrite")
    object AnalogRead : PinFunction("AnalogRead")
    object AnalogWritePWM : PinFunction("AnalogWritePWM")
    object AnalogWriteDAC : PinFunction("AnalogWriteDAC")

    override fun toString(): String {
        return this.name
    }
}
//endregion


//region Converter functions
fun SourcePlatform.toPlatform(): ParticlePlatform {
    return ParticlePlatform(
        this.id,
        ParticleDeviceType.fromInt(this.id),
        this.pins.map { it.toPin() }
            .filter { it.functions.isNotEmpty() }  // some pins don't support any functions
            .addPositionInfo()
    )
}


fun SourcePin.toPin(): Pin {
    return Pin(
        this.name,
        this.altName ?: this.name,
        if (this.serial.isNullOrEmpty()) this.toFunctions() else listOf()
    )
}


fun SourcePin.toFunctions(): List<PinFunction> {
    val functions = mutableListOf<PinFunction>()

    fun String?.isFunctionPresent(): Boolean {
        return !(this.isNullOrEmpty() || this == "false")
    }

    if (this.digitalRead.isFunctionPresent()) {
        functions.add(DigitalRead)
    }

    if (this.digitalWrite.isFunctionPresent()) {
        functions.add(DigitalWrite)
    }

    if (this.analogRead.isFunctionPresent()) {
        functions.add(AnalogRead)
    }

    if (this.analogWriteDAC.isFunctionPresent()) {
        functions.add(AnalogWriteDAC)
    }

    if (this.analogWritePWM.isFunctionPresent()) {
        functions.add(AnalogWritePWM)
    }

    return functions
}


/**
 * Adds position info of "LEFT" or "RIGHT" to each pin.
 *
 * In the source document, the pins are described in order starting from the top of the left
 * column and working down, but the right-hand column of pins is in order from bottom to top.
 *
 * e.g.: if the source document described the following pins in this order:
 *
 *  "A0", "A1", "A2", "D0", "D1", "D2"
 *
 * ...it's describing a pin layout that looks like this:
 *
 * <pre>
 *    _________
 *    |A0   D2|
 *    |       |
 *    |A1   D1|
 *    |       |
 *    |A2   D0|
 *    \______/
 * </pre>
 *
 */
fun List<Pin>.addPositionInfo(): List<Pin> {
    val leftColumn: MutableList<Pin> = mutableListOf()
    val rightColumn: MutableList<Pin> = mutableListOf()

    var currentList = leftColumn
    var currentColumn = PinColumn.LEFT
    for (pin in this) {
        if (currentColumn == PinColumn.LEFT && (pin.tinkerName == "D0" || pin.tinkerName == "C4")) {
            currentColumn = PinColumn.RIGHT
            currentList = rightColumn
        }

        currentList.add(pin.copy(column = currentColumn))
    }

    return leftColumn + rightColumn.reversed()
}


class PinFunctionAdapter : TypeAdapter<PinFunction?>() {

    companion object {
        private val nullWriter = Gson()
    }

    @Throws(IOException::class)
    override fun write(writer: JsonWriter, value: PinFunction?) {
        if (value == null) {
            synchronized(this) {
                nullWriter.toJson(value, writer)
            }
        } else {
            writer.value(value.name)
        }
    }

    @Throws(IOException::class)
    override fun read(reader: JsonReader): PinFunction? {
        return when (reader.nextString()) {
            DigitalRead.name -> DigitalRead
            DigitalWrite.name -> DigitalWrite
            AnalogRead.name -> AnalogRead
            AnalogWriteDAC.name -> AnalogWriteDAC
            AnalogWritePWM.name -> AnalogWritePWM
            else -> null
        }
    }
}
//endregion


// included because the Core isn't in the JSON file
val corePins: ParticlePlatform
    get() {
        val allFunctions = listOf(DigitalRead, DigitalWrite, AnalogRead, AnalogWritePWM)
        val noAnalogWrite = listOf(DigitalRead, DigitalWrite, AnalogRead)
        val noAnalogRead = listOf(DigitalRead, DigitalWrite, AnalogWritePWM)
        val digitalOnly = listOf(DigitalRead, DigitalWrite)

        return ParticlePlatform(
            ParticleDeviceType.CORE.intValue,
            deviceType = ParticleDeviceType.CORE,
            pins = listOf(
                Pin("A7", "A7", allFunctions),
                Pin("A6", "A6", allFunctions),
                Pin("A5", "A5", allFunctions),
                Pin("A4", "A4", allFunctions),
                Pin("A3", "A3", noAnalogWrite),
                Pin("A2", "A2", noAnalogWrite),
                Pin("A1", "A1", allFunctions),
                Pin("A0", "A0", allFunctions),
                Pin("D0", "D0", noAnalogRead),
                Pin("D1", "D1", noAnalogRead),
                Pin("D2", "D2", digitalOnly),
                Pin("D3", "D3", digitalOnly),
                Pin("D4", "D4", digitalOnly),
                Pin("D5", "D5", digitalOnly),
                Pin("D6", "D6", digitalOnly),
                Pin("D7", "D7", digitalOnly)
            )
        )
    }

