# GearCam

GearCam is a video-only fork of [Open Camera](https://opencamera.org.uk/) 1.56.2 for recording musical jams. It installs as `app.gearcam`, alongside Open Camera. The upstream Java namespace is retained to make camera fixes easier to merge. Copyright and GPLv3+ attribution to Mark Harman and the other Open Camera contributors remain in the source and About screen; see [gpl-3.0.txt](gpl-3.0.txt).

## Recording and mixer

- Video is the only capture mode. Photo capture intents, photo widgets, the photo tile, photo settings and snapshots during video are unavailable. The camera switch changes cameras; the old photo/video switch opens the audio mixer.
- Open Camera's video resolution, frame rate, exposure, focus, stabilization, zoom, camera selection and storage controls remain available.
- Select the phone microphone, a direct USB Audio Class input, or an Android-routed wired microphone. Pick individual channels, up to 32 channels per device, and combine direct USB with the phone microphone.
- The full-screen mixer has horizontally scrolling channel strips, vertical gain faders, adjacent level meters, and a pinned master section. Landscape uses compact strips; controls adapt when rotated. Adjacent inputs can be linked in pairs (1/2, 3/4, etc.), with shared gain, mute, recording selection, and stereo balance that preserves left/right separation.
- Each channel has digital gain from −60 to +24 dB, constant-power pan, mute, a level meter, and latched input/gain clipping indicators. The stereo bus has an optional linked peak limiter with a 0.98 full-scale ceiling. Changes to gain, pan and mute are smoothed and applied during recording.
- Soundcheck uses the same capture/routing/mixing path as recording and saves no files. Channel selection is fixed for a recording; gain, pan, mute and the limiter remain adjustable.
- Recordings contain MP4 video (H.264 or HEVC) and a 48 kHz stereo AAC mix at the encoder’s advertised maximum bitrate, capped at 512 kb/s. A lossless 48 kHz / 24-bit stereo WAV master is enabled by default and saved separately to `Music/GearCam` (about 1.04 GB/hour). Disable it in the mixer if space is limited. Separate input stems and headphone monitoring are not implemented.
- Compact L/R level meters and latched clipping indicators appear beside the ISO/storage information on the camera preview. They show the recording mix and remain dim when capture is inactive. The old configuration tip is removed.
- The phone microphone strip has a light, self-adjusting noise gate: it learns the background level and turns it down 20 dB between phrases, so hum and room noise drop out in the pauses. It adds no delay and cannot remove noise under the voice.
- Each input strip has optional 30 Hz high-pass and 20 kHz low-pass filters (3-pole Butterworth, −18 dB/octave) that remove DC, infrasonic and ultrasonic content eating headroom.
- Audio/video sync can be calibrated before recording (−500 to +500 ms; positive values delay audio). Check a handclap on the actual phone/interface before a long take.

The mixer requests each selected Android input explicitly and verifies its actual route after capture starts and throughout recording. Direct USB is claimed before the phone `AudioRecord` starts, preventing Android from redirecting the phone-mic stream to USB. Rejected routing, Android-reported silencing, USB buffer overflow and sustained input gaps stop the recording with an error. A selected device that is not connected is skipped rather than blocking the take, stays in the settings and rejoins by itself when plugged back in; the app says which device a take ran without, and refuses only when nothing selected is connected. It does not silently substitute a different microphone.

## Hardware limits

Direct USB capture bundles [libuac](https://github.com/nExtCamera/libuac) and [libusb](https://github.com/libusb/libusb). It uses Android's `UsbManager` permission and the already-open device file descriptor, then receives USB isochronous packets outside Android's audio router. This is the workaround for phones whose audio policy cannot keep a USB input and the handset microphone active together.

Current direct-driver limitations:

- USB Audio Class 1 and 2 input, uncompressed PCM or 32-bit float, at exactly 48 kHz are supported. UAC2 devices get their clock set to 48 kHz and GearCam checks it; a device locked to another rate reports an error. Compressed formats, sample-rate conversion at USB ingress, USB output and MIDI are not implemented.
- The USB descriptor must expose the desired capture channel count at 48 kHz; GearCam shows the channels of the device's capture stream once USB access is granted. “Input 1” and “Input 2” appear only for a two-channel capture format; a stereo TRS socket may still expose one summed channel.
- USB access must be granted after connecting the device; a selected interface that is connected but not yet allowed is requested automatically while the mixer is open. GearCam claims the audio interfaces while soundcheck or recording runs, so other Android apps and audio output on the same composite device may temporarily lose access.
- Compatibility depends on the interface firmware, phone USB host controller, cable, power budget and available isochronous bandwidth. Hubs and two identical interfaces are not yet a supported setup. Disconnecting USB stops the take.
- Software gain is post-capture. It cannot control the adapter's analog preamp or restore samples clipped before they reach GearCam. The limiter protects only the mixed output.
- USB timing starts from monotonic host receipt time and advances continuously by frame count, with smoothed clock-rate estimates; packet delivery jitter no longer shifts every audio block. Handset mic timestamps use `AudioRecord` hardware timestamps on Android 7+. A 32-tap windowed-sinc fractional delay aligns independent clocks at the same nominal 48 kHz rate. This necessary clock correction is not bit-perfect passthrough, and USB buffering/OEM camera latency still require clap calibration and a long-take sync test.

## Devices

Anything that presents a USB Audio Class 1 or 2 capture stream with PCM or 32-bit float at 48 kHz should work, plus the
phone's own microphone. The phone powers the device, so a powered hub or a Y cable helps with hungry interfaces. GearCam
lists a device's capture channels once USB access is granted.

| Device | Notes |
| --- | --- |
| BOYA BY-K4 | TRS microphone adapter, about €16. [BOYA does not publish](https://store.boyamic.com/products/microphone-data-cable) its class version or channel and rate descriptors; a TRS socket alone does not mean stereo or line level. |
| RØDE AI-Micro | Two TRS inputs, USB-C. |
| Saramonic SmartRig UC | XLR/TRS preamp, USB-C. |
| Zoom H6studio | Multitrack recorder in interface mode. |
| 1010music Bluebox (Eurorack) | Class 2, high speed: 18 capture channels, 24-bit at 48 kHz. Also carries USB MIDI alongside its audio. |
| Behringer CU1A (Eurorack) | USB audio interface module. |
| After Later Audio USB-2CH (Eurorack) | Class-compliant interface module; GearCam handles both class 1 and class 2. |
| Expert Sleepers ES-9 (Eurorack) | Class-compliant multichannel interface; GearCam offers whatever capture channels it reports. |

Android references: [USB host access](https://developer.android.com/develop/connectivity/usb/host), [preferred and actual input routing](https://developer.android.com/reference/android/media/AudioRecord), [concurrent capture](https://source.android.com/docs/core/audio/concurrent). Vendored licenses are retained at `app/src/main/cpp/vendor/libuac/LICENSE` and `app/src/main/cpp/vendor/libusb/COPYING`.

## Capture quality

Direct USB prefers 32-bit float when the device offers it, otherwise the highest advertised PCM bit depth at 48 kHz for the requested channels. Signed PCM is normalized directly to float, keeping 24-bit sample precision through buffering and mixing instead of truncating to PCM16. Phone capture requests float PCM and prefers `UNPROCESSED` when the device advertises it; otherwise it uses `VOICE_RECOGNITION`. Phone ADC resolution and OEM processing still depend on the hardware; a float API alone does not upgrade them.

There is no 96/192 kHz upsampling. Independent USB and phone clocks still require small fractional timing corrections to stay aligned. The sinc interpolator preserves high frequencies substantially better than the previous linear interpolator. The AAC encoder receives float when it accepts that format; otherwise PCM16 conversion uses triangular dither. AAC remains lossy, so use the separate 24-bit WAV master for editing and archival audio. The master contains the same gain, pan/balance, mute and limiter processing as the video mix. Recording time is capped before the standard WAV 4 GB limit.

## Saving and recovery

Mixed takes are written live: camera frames go to a video encoder (H.264 or HEVC, from the video quality settings) and the audio mix to an AAC encoder, and both feed one MP4 in its final place while recording. Stopping closes the file within moments; nothing is copied afterwards. Video frames keep the camera's sensor timestamps and the mix starts at the first recorded frame, so picture and sound share one clock. Rotation and location metadata are written into the file.

A take needs free space for the video, its AAC track and the optional WAV master; it stops cleanly at a cap computed from free space (and the video maximum file size, if set). Mixed recordings use a continuous timeline: pause, automatic file rollover/restart, slow motion and timelapse are unavailable. Disabling Record audio allows silent video. Phones on the old camera API record with MediaRecorder instead and join the mix to the video after stopping, which needs room for a second copy.

The 24-bit WAV master is written to the take's recovery folder under the app's external files `Movies/recovery/<session>/` while recording, then saved to Music/GearCam. An MP4 becomes playable only when it is closed, so if the app or phone dies mid-take the video is lost but the WAV master's audio remains in the recovery folder; the app reports that folder when something fails. Do not uninstall or clear app data before recovery.

## Build and test

Use Android SDK 36, NDK 28.2.13676358, CMake 3.22.1, a JDK capable of running Gradle, and the Java 17 toolchain configured by the project:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.sourceforge.opencamera.GearCamRecordingTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Local validation on 2026-09-11: the debug build for arm64-v8a, armeabi-v7a and x86_64 and all 48 JVM tests (21 mixer/timing/USB-descriptor/high-resolution tests plus 27 upstream tests) pass with the direct USB implementation. GearCam recording and UI instrumentation runs on an Android 16 / API 36 emulator; it checks video/AAC/WAV publication and fader visibility across portrait/landscape rotation. Final lint reports no errors in the new audio or native-build code. The inherited project still reports 650 missing-translation errors, three legacy tile API errors and two preview-thread errors; the existing build configuration does not fail on lint errors.

The GearCam unit tests cover pan, gain, mute ramps, clipping, limiter behavior, channel order, ring-buffer boundaries, timestamp alignment and input gaps. The targeted instrumented tests exercise video-only behavior, missing-device rejection, photo-setting removal, and actual camera/audio/AAC/MediaStore recording on Android. Upstream photo instrumentation suites remain as historical source and do not describe GearCam's intended behavior.

Before relying on a phone/interface combination, test each USB channel with an isolated tone, USB plus room mic with a clap, input unplug/replug, microphone permission denial, a 30–60 minute sync run, clipping, app interruption and low storage. Emulator results cannot establish physical USB compatibility. New mixer copy currently uses English; inherited camera translations remain.
