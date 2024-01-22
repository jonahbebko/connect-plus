package me.andreww7985.connectplus.bluetooth

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import me.andreww7985.connectplus.App
import me.andreww7985.connectplus.dfu.DfuModel
import me.andreww7985.connectplus.helpers.HexHelper.toHexString
import me.andreww7985.connectplus.manager.SpeakerManager
import me.andreww7985.connectplus.protocol.AudioChannel
import me.andreww7985.connectplus.protocol.DataToken
import me.andreww7985.connectplus.protocol.Packet
import me.andreww7985.connectplus.protocol.PacketType
import me.andreww7985.connectplus.speaker.Feature
import me.andreww7985.connectplus.speaker.SpeakerHardware
import me.andreww7985.connectplus.speaker.SpeakerModel
import timber.log.Timber

object BluetoothProtocol {
    fun connect(scanResult: ScanResult) {
        Timber.d("connect ${scanResult.device.address} ${scanResult.device.name}")

        val scanRecord = scanResult.scanRecord ?: return
        if (SpeakerManager.speakers.containsKey(scanResult.device.address)) return

        try {
            val scanRecordBytes = scanRecord.bytes

            val manufacturerData = SparseArray<ByteArray>()
            var currentPos = 0
            while (currentPos < scanRecordBytes.size) {
                val currentPos2 = currentPos + 1
                val length = scanRecordBytes[currentPos].toInt() and 0xFF
                if (length == 0) {
                    break
                }
                val dataLength = length - 1
                currentPos = currentPos2 + 1

                if (scanRecordBytes[currentPos2].toInt() and 0xFF == 0xFF) {
                    manufacturerData.put(
                        (scanRecordBytes[currentPos + 1].toInt() and 0xFF shl 8) + (scanRecordBytes[currentPos].toInt() and 0xFF),
                        scanRecordBytes.copyOfRange(currentPos + 2, currentPos + 2 + dataLength)
                    )
                }

                currentPos += dataLength
            }

            val speakerData = manufacturerData[87] ?: return

            Timber.d("connect parsed ${speakerData.toHexString()}")

            val speaker = SpeakerModel(scanResult.device, speakerData.toHexString())

            val modelId =
                (speakerData[1].toInt() and 0xFF shl 8) + (speakerData[0].toInt() and 0xFF)
            val colorId = speakerData[2].toInt() and 0xFF
            speaker.hardware = SpeakerHardware.from(modelId, colorId)

            SpeakerManager.speakerFound(speaker)
        } catch (e: Exception) {
            Timber.e(e, "failed to parse scan record")
            App.analytics.logEvent("bluetooth_parse_failed")
        }
    }

    fun onPacket(speaker: SpeakerModel, packet: Packet) {
        Timber.d("onPacket ${packet.type} ${packet.payload.toHexString()}")

        val payload = packet.payload

        when (packet.type) {
            PacketType.ACK -> {
                speaker.featuresChanged()
            }
            PacketType.RES_SPEAKER_INFO -> {
                speaker.index = payload[0].toInt() and 0xFF

                val feature = speaker.getOrCreateFeature<Feature.BatteryName>()
                var name = feature.deviceName
                var batteryCharging = feature.batteryCharging
                var batteryLevel = feature.batteryLevel

                var modelId: Int? = null
                var colorId: Int? = null

                var pointer = 1
                while (pointer < payload.size) {
                    when (DataToken.from(payload[pointer].toInt() and 0xFF)) {
                        DataToken.TOKEN_MODEL -> {
                            modelId = (payload[pointer + 1].toInt() and 0xFF shl 8) or (payload[pointer + 2].toInt() and 0xFF)

                            pointer += 3
                        }
                        DataToken.TOKEN_COLOR -> {
                            colorId = payload[pointer + 1].toInt()

                            pointer += 2
                        }
                        DataToken.TOKEN_BATTERY_STATUS -> {
                            val batteryStatus = payload[pointer + 1].toInt() and 0xFF
                            pointer += 2

                            batteryCharging = batteryStatus > 100
                            batteryLevel = batteryStatus % 128
                        }
                        DataToken.TOKEN_LINKED_DEVICE_COUNT -> {
                            // val likedDeviceCount = payload[pointer + 1].toInt() and 0xFF
                            pointer += 2
                        }
                        DataToken.TOKEN_AUDIO_CHANNEL -> {
                            speaker.updateAudioChannel(AudioChannel.from(payload[pointer + 1].toInt() and 0xFF))
                            pointer += 2
                        }
                        DataToken.TOKEN_AUDIO_SOURCE -> {
                            speaker.isPlaying = (payload[pointer + 1].toInt() and 0xFF) == 1
                            pointer += 2
                        }
                        DataToken.TOKEN_MAC -> {
                            pointer += 8
                        }
                        DataToken.TOKEN_NAME -> {
                            val nameBytesLength = payload[pointer + 1].toInt() and 0xFF
                            val nameBytes = ByteArray(nameBytesLength)
                            for (i in 0 until nameBytesLength) nameBytes[i] = payload[pointer + 2 + i]
                            pointer += 2 + nameBytesLength

                            name = String(nameBytes)

                        }
                        else -> {
                            Timber.w("onPacket RES_SPEAKER_INFO unknown token ${payload[pointer].toInt() and 0xFF}")
                            pointer++
                        }
                    }
                }

                if (name != null && batteryLevel != null && batteryCharging != null) {
                    feature.batteryCharging = batteryCharging
                    feature.batteryLevel = batteryLevel
                    feature.deviceName = name

                    speaker.featuresChanged()
                }

                if (!speaker.isDiscovered) {
                    if (modelId != null && colorId != null) {
                        speaker.hardware = SpeakerHardware.from(modelId, colorId)
                    }

                    speaker.discovered()
                }
            }
            PacketType.RES_FIRMWARE_VERSION -> {
                val feature = speaker.getOrCreateFeature<Feature.FirmwareVersion>()

                if (payload.size <= 2) {
                    feature.major = payload[0].toInt() shr 4 and 0xF
                    feature.minor = payload[0].toInt() and 0xF
                    feature.build = null
                } else {
                    feature.major = payload[0].toInt() and 0xFF
                    feature.minor = payload[1].toInt() and 0xFF
                    feature.build = payload[2].toInt() and 0xFF
                }

                App.analytics.logSpeakerEvent("speaker_firmware") {
                    putString("speaker_firmware", "${feature.major}.${feature.minor}${if (feature.build != null) ".${feature.build}" else ""}")
                }

                speaker.featuresChanged()
            }
            PacketType.RES_FEEDBACK_SOUNDS -> {
                speaker.getOrCreateFeature<Feature.FeedbackSounds>().enabled = payload[0] == 1.toByte()
                speaker.featuresChanged()
            }
            PacketType.RES_SPEAKERPHONE_MODE -> {
                speaker.getOrCreateFeature<Feature.SpeakerphoneMode>().enabled = payload[0] == 1.toByte()
                speaker.featuresChanged()
            }
            PacketType.RES_DFU_STATUS_CHANGE -> {
                val dfu = speaker.dfuModel
                val status = if (payload.size == 1) payload[0] else payload[1]

                when (status.toInt()) {
                    0 -> if (dfu.status != DfuModel.Status.CANCELED) dfu.dfuFinished(DfuModel.Status.ERROR)
                    1 -> dfu.dfuStarted()
                    2 -> if (dfu.state == DfuModel.State.FLASHING_DFU) dfu.sendNextPacket()
                    3 -> dfu.dfuFinished(DfuModel.Status.SUCCESS)
                }
            }
            PacketType.RES_BASS_LEVEL -> {
                val feature = speaker.getOrCreateFeature<Feature.BassLevel>()

                feature.level = payload[0].toInt() and 0xFF
                speaker.featuresChanged()
            }
            PacketType.RES_ANALYTICS_DATA -> {
                val names = arrayOf(
                        "JBLConnect",
                        "DurationJBLConnect",
                        "CriticalTemperature",
                        "PowerBank",
                        "Playtime",
                        "PlaytimeInBattery",
                        "ChargingTime",
                        "PowerONCount",
                        "DurationPowerONOFF",
                        "Speakerphone"
                )

                names.forEachIndexed { index, s ->
                    val vv = ((payload[index * 2].toInt() and 0xFF) shl 8) or (payload[index * 2 + 1].toInt() and 0xFF)

                    Timber.d("$s: $vv")
                }
            }
            PacketType.UNKNOWN -> Timber.w("Unknown packet type")
            else -> Timber.w("Wrong packet type")
        }
    }
}