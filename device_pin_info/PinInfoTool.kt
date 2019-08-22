/**
 * Tool for converting the pinInfo.json file into something easily consumable for use in the
 * Tinker view of the Particle mobile apps
 *
 * NOTE: due to design constraints, the mobile team + mgmt (Raimis & Jens + Julien) have decided
 * not to show the extra Electron pins (i.e.: the "B" and "C" pins)  This is view-level information,
 * but we address it in the code below because the mobile team believes that, in this case, having
 * the result of that decision about the pins live in *one* codebase is more important than the
 * dissonance/incorrectness of putting view information in our data model.)
 *
 * FURTHER NOTE: because of some conditional behavior with certain pins, we're only going to show
 * pins labeled "DAC", "WKP", "A[number]", or "D[number]".  See this Slack thread for more info:
 * https://s.slack.com/archives/C04EP77AF/p1566419278030700
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
import io.particle.android.sdk.cloud.ParticleDevice.ParticleDeviceType.A_SOM
import io.particle.android.sdk.cloud.ParticleDevice.ParticleDeviceType.B_SOM
import io.particle.android.sdk.cloud.ParticleDevice.ParticleDeviceType.X_SOM
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


fun writePlatformsToFile(
    platforms: List<ParticlePlatform>,
    targetPath: String = "/sdcard/tinker_pin_data.json",
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
    val label: String,      // "name" in the original JSON
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
    val deviceType = ParticleDeviceType.fromInt(this.id)

    val platformPins = this.pins.map { it.toPin() }
        // some pins don't support any functions
        .filter { it.functions.isNotEmpty() }
        .filter { isValidTinkerPin(it) }

    val pinsWithLocation = if (deviceType.isSoM()) {
        platformPins.addPositionInfoSom()
    } else {
        platformPins.addPositionInfo()
    }

    return ParticlePlatform(this.id, deviceType, pinsWithLocation)
}


fun ParticleDeviceType.isSoM(): Boolean {
    return when(this) {
        A_SOM,
        B_SOM,
        X_SOM -> true
        else -> false
    }
}


fun isValidTinkerPin(pin: Pin): Boolean {
    val label = pin.label.toUpperCase()
    if (label in listOf("DAC", "WKP")) {
        return true
    }

    // Match pins that start with "A" or "D", followed by a number
    return (label.startsWith("A") || label.startsWith("D")
            && (label.substring(1).isNumeric()))
}


fun String.isNumeric(): Boolean {
    return (this.toIntOrNull() != null)
}


fun SourcePin.toPin(): Pin {
    return Pin(
        this.name,
        this.altName ?: this.name,
        this.toFunctions()
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


fun List<Pin>.addPositionInfoSom(): List<Pin> {
    val leftColumn: MutableList<Pin> = mutableListOf()
    val rightColumn: MutableList<Pin> = mutableListOf()

    for (pin in this) {
        if (pin.label.startsWith("A")) {
            leftColumn.add(pin.copy(column = PinColumn.LEFT))
        } else if (pin.label.startsWith("D")) {
            rightColumn.add(pin.copy(column = PinColumn.RIGHT))
        }
    }

    return leftColumn.sortedBy { it.label } + rightColumn.sortedByDescending { it.label }
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
                Pin("D0", "D0", noAnalogRead, PinColumn.RIGHT),
                Pin("D1", "D1", noAnalogRead, PinColumn.RIGHT),
                Pin("D2", "D2", digitalOnly, PinColumn.RIGHT),
                Pin("D3", "D3", digitalOnly, PinColumn.RIGHT),
                Pin("D4", "D4", digitalOnly, PinColumn.RIGHT),
                Pin("D5", "D5", digitalOnly, PinColumn.RIGHT),
                Pin("D6", "D6", digitalOnly, PinColumn.RIGHT),
                Pin("D7", "D7", digitalOnly, PinColumn.RIGHT)
            )
        )
    }
