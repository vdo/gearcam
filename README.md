# GearCam

GearCam is a video and audio recorder fork of [Open Camera](https://opencamera.org.uk/) 1.56.2 for recording musical jams. It installs as `app.gearcam`, alongside Open Camera. The upstream Java namespace is retained to make camera fixes easier to merge. Copyright and GPLv3+ attribution to Mark Harman and the other Open Camera contributors remain in the source and About screen; see [gpl-3.0.txt](gpl-3.0.txt).

## Recording and mixer

- Choose **Video + audio** or **Audio only** at the top of Settings. Audio-only mode replaces the camera with a live stereo waveform and does not open the camera. Photo capture intents, photo widgets, the photo tile, photo settings and snapshots during video are unavailable. The camera switch changes cameras; the old photo/video switch opens the audio mixer.
- Choose one of six color themes in **Settings → Color theme** or the audio recorder’s **Config → Color theme**: Mint (default), Ocean, Violet, Rose, Amber or Graphite. The choice persists across launches. Camera controls use a compact, centred top bar with camera switch, orientation lock, exposure, an overlay button (grid → RGB histogram → off), filters, popup and settings; face detection is disabled and its settings are removed.
- Open Camera's video resolution, frame rate, exposure, focus, stabilization, zoom, camera selection and storage controls remain available.
- Select the phone microphone, a direct USB Audio Class input, or an Android-routed wired microphone. Pick individual channels, up to 32 channels per device, and combine direct USB with the phone microphone.
- The full-screen mixer has compact, horizontally scrolling channel strips, vertical gain faders, adjacent level meters, and a pinned master section. Shorter buttons and record/mute on one row leave room for the whole strip. Three live icon switches at the bottom of each strip toggle the noise gate, LPF and HPF; the gate is available on the phone mic only. Landscape puts the fader beside the controls; strips adapt to available height when rotated. Adjacent inputs can be linked in pairs (1/2, 3/4, etc.), with shared gain, mute, recording selection, and stereo balance that preserves left/right separation.
- Each channel has digital gain from −60 to +24 dB, constant-power pan, mute, a level meter, and latched input/gain clipping indicators. The stereo bus has an optional linked peak limiter with a 0.98 full-scale ceiling. Changes to gain, pan and mute are smoothed and applied during recording.
- Soundcheck uses the same capture/routing/mixing path as recording and saves no files. Channel selection is fixed for a recording; gain, pan, mute and the limiter remain adjustable.
- Recordings contain MP4 video (H.264 or HEVC) and a 48 kHz stereo AAC mix at the encoder’s advertised maximum bitrate, capped at 512 kb/s. A lossless 48 kHz / 24-bit stereo WAV master is enabled by default and saved to the chosen folder, or `Music/GearCam` by default (about 1.04 GB/hour). Disable it in the mixer if space is limited. Separate input stems and headphone monitoring are not implemented.
- Compact L/R level meters and latched clipping indicators appear beside the ISO/storage information on the camera preview. They show the recording mix and remain dim when capture is inactive. The old configuration tip is removed.
- The phone microphone strip has a light, self-adjusting noise gate: it learns the background level and turns it down 20 dB between phrases, so hum and room noise drop out in the pauses. It adds no delay and cannot remove noise under the voice.
- Each input strip has optional 30 Hz high-pass and 20 kHz low-pass filters (3-pole Butterworth, −18 dB/octave) that remove DC, infrasonic and ultrasonic content eating headroom.
- Audio/video sync can be calibrated before recording (−500 to +500 ms; positive values delay audio). Check a handclap on the actual phone/interface before a long take.

The mixer requests each selected Android input explicitly and verifies its actual route after capture starts and throughout recording. Direct USB is claimed before the phone `AudioRecord` starts, preventing Android from redirecting the phone-mic stream to USB. Rejected routing, Android-reported silencing, USB buffer overflow and sustained input gaps stop the recording with an error. A selected device that is not connected is skipped rather than blocking the take, stays in the settings and rejoins by itself when plugged back in; the app says which device a take ran without, and refuses only when nothing selected is connected. It does not silently substitute a different microphone.

## Creative video filters

The overlapping-circles filter icon in the camera’s top bar opens **Original · no filter**, **Black & white**, **B&W Noir**, **Warm Film**, **Cool Chrome**, **Sepia**, and **Fade**. **Original is selected by default**; no creative filter is applied until you choose one. Select Original again to turn filtering off. The active choice is remembered, and the icon is accented while a filter is enabled. UI color themes do not change the recorded image.

Filters apply to both the live preview and saved MP4, including silent video. B&W removes color; Noir adds deeper contrast and a vignette. Warm Film warms the highlights, Cool Chrome adds a cooler muted look, Sepia adds amber tones, and Fade softens color and contrast. Filters run on the GPU and preserve the camera’s frame timestamps for audio sync.

Creative filters require Camera2 at normal video frame rates. Choosing one enables Camera2 on supported phones; stop an existing legacy-camera take first. High-speed or legacy-camera capture uses Original, with a message if an active filter is reset. The original hardware color-effect menu is replaced by this consistent set of filters.

## Hardware limits

Direct USB capture bundles [libuac](https://github.com/nExtCamera/libuac) and [libusb](https://github.com/libusb/libusb). It uses Android's `UsbManager` permission and the already-open device file descriptor, then receives USB isochronous packets outside Android's audio router. This is the workaround for phones whose audio policy cannot keep a USB input and the handset microphone active together.

Current direct-driver limitations:

- USB Audio Class 1 and 2 input, uncompressed PCM or 32-bit float, at exactly 48 kHz are supported. UAC2 devices get their clock set to 48 kHz and GearCam checks it; a device locked to another rate reports an error. Compressed formats, sample-rate conversion at USB ingress, USB output and MIDI are not implemented.
- The USB descriptor must expose the desired capture channel count at 48 kHz; GearCam shows the channels of the device's capture stream once USB access is granted. “Input 1” and “Input 2” appear only for a two-channel capture format; a stereo TRS socket may still expose one summed channel.
- GearCam enumerates USB audio interfaces on launch, on returning to the app, and on USB/audio-device changes. Already-connected devices appear without unplugging them. Android USB access is requested automatically after microphone permission is granted; the mixer’s access button can retry a denied request. Selection, stereo links and strip controls are remembered across sessions. Android may ask for USB access again after physical disconnection; GearCam cannot bypass that system permission. GearCam claims the audio interfaces while soundcheck or recording runs, so other Android apps and audio output on the same composite device may temporarily lose access.
- Compatibility depends on the interface firmware, phone USB host controller, cable, power budget and available isochronous bandwidth. Hubs and two identical interfaces are not yet a supported setup. Disconnecting USB stops the take.
- Software gain is post-capture. It cannot control the adapter's analog preamp or restore samples clipped before they reach GearCam. The limiter protects only the mixed output.
- USB timing starts from monotonic host receipt time and advances continuously by frame count, with smoothed clock-rate estimates; packet delivery jitter no longer shifts every audio block. Handset mic timestamps use `AudioRecord` hardware timestamps on Android 7+. A 32-tap windowed-sinc fractional delay aligns independent clocks at the same nominal 48 kHz rate. This necessary clock correction is not bit-perfect passthrough, and USB buffering/OEM camera latency still require clap calibration and a long-take sync test.

## USB 1.1, USB 2.0 and audio classes

**Bus speed and USB Audio Class are separate specifications.** USB 1.1 full speed is 12 Mb/s; USB 2.0 adds 480 Mb/s high speed while retaining full-speed compatibility. Plugging a full-speed interface into a USB 2.0/3.x port does not make the interface high speed. These are bus signaling rates, not usable audio payload rates. See the [USB-IF USB 2.0 specification](https://www.usb.org/document-library/usb-20-specification).

| | Full-speed audio (often sold as USB 1.1) | High-speed audio (USB 2.0) |
| --- | --- | --- |
| Typical use | Small microphone adapters and interfaces with fewer channels | Interfaces carrying more simultaneous channels and/or higher sample rates |
| Audio protocol | Commonly USB Audio Class 1 (UAC1) | Commonly USB Audio Class 2 (UAC2), with explicit clock controls |
| Sound quality | Set by the ADC, analog electronics, bit depth and sample rate | Faster USB alone does not improve those properties |
| GearCam | UAC1 capture at 48 kHz, as advertised by the device | UAC2 capture at 48 kHz; the driver sets and checks the clock |

For example, two channels of 24-bit audio at 48 kHz contain 2.304 Mb/s of PCM before USB overhead; 18 channels contain 20.736 Mb/s. The latter cannot fit on a 12 Mb/s bus. Actual limits also depend on endpoint packet sizes, sample packing, simultaneous playback, hubs and the phone’s host controller. GearCam supports up to 32 input channels per device, subject to those limits; it does not change its 48 kHz mix rate when a faster device is connected.

UAC1/UAC2 describe descriptors, controls and streaming behavior; they do not by themselves guarantee a channel count, bit depth, rate or lower latency. Check the interface’s actual descriptors and supported modes. References: [USB-IF Audio 1.0](https://www.usb.org/sites/default/files/audio10.pdf), [USB-IF Audio 2.0](https://www.usb.org/document-library/audio-devices-rev-20-and-adopters-agreement), and [Android USB audio overview](https://source.android.com/docs/core/audio/usb).

## Devices

Anything that presents a USB Audio Class 1 or 2 capture stream with PCM or 32-bit float at 48 kHz should work, plus the
phone's own microphone. The phone powers the device, so a powered hub or a Y cable helps with hungry interfaces. GearCam
lists a device's capture channels once USB access is granted.

| Device | Notes |
| --- | --- |
| BOYA BY-K4 | TRS microphone adapter, about €16. Enumerates as `0c76:153f` "USB PnP Audio Device": class 1, full speed, capture of 2 channels of 16-bit PCM at 48 kHz. Mic level only, so anything hotter needs a line-to-mic attenuator ahead of it: roughly 20–25 dB from consumer line, 30–35 dB from pro line, 40–45 dB straight from Eurorack. Without it the signal clips in the adapter before the phone sees it, which no fader can undo. A TRS socket alone does not mean stereo or line level. |
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

## Audio-only recording

In **Settings → Recording mode**, choose **Audio only · live waveform**. The recorder shows a live 10 ms L/R scope of the selected inputs after mixing, with the same gains, pan, filters, mute and limiter as video. **Mixer** selects inputs; **Config** chooses the format, save location, or returns to video. Tap **Record** / **Stop**. The timer and save-location card stay visible beside the scope in landscape, or below it in portrait. No video track or additional WAV master is created in audio-only mode.

| Format | Output at 48 kHz stereo | Use |
| --- | --- | --- |
| WAV | 24-bit PCM; about 1.04 GB/hour | Uncompressed editing/master audio; limited to a standard WAV’s 4 GB size |
| MP3 | 320 kb/s; about 144 MB/hour | Smaller, widely playable lossy audio |
| FLAC | 24-bit lossless; size depends on the signal | Lossless audio with compression |

MP3 uses bundled [LAME 3.100](https://lame.sourceforge.io/) (LGPL); FLAC uses bundled [libFLAC 1.5.0](https://xiph.org/flac/) (BSD). Their source and license files are in `app/src/main/cpp/vendor/lame` and `app/src/main/cpp/vendor/flac`. Encoding runs while recording, directly into the selected destination. Switching apps or locking the screen stops and finishes the take; background recording is not implemented. Format and destination cannot change during a take. Audio/video sync calibration applies only to video.

## Saving and recovery

**Settings → Save location** opens Android’s folder picker. Open its storage menu, choose your SD card or internal storage, create/select a folder, then tap **Use this folder** and allow access. Select a subfolder if Android blocks a drive’s root. The same choice applies to videos, audio-only files and WAV masters, and persists across app restarts. The selected path appears in Settings and on the audio recorder. **Use defaults** restores video to the configured `DCIM` folder and audio to `Music/GearCam`.

If the SD card is removed or folder access is revoked, recording reports an error; it does not silently save somewhere else. Choose an available folder again in Config. Use a local or SD-card folder that supports seeking; cloud providers may not support live recording. WAV masters accompanying video are still staged in the recovery folder and copied to the selected destination when the take ends. Audio-only files and camera2 MP4s are written directly to the selected destination. Interrupted or failed audio writes can leave a partial file there; normal stop/backgrounding finalizes its header.

Mixed takes are written live: camera frames go to a video encoder (H.264 or HEVC, from the video quality settings) and the audio mix to an AAC encoder, and both feed one MP4 in its final place while recording. Stopping closes the file within moments; nothing is copied afterwards. Video frames keep the camera's sensor timestamps and the mix starts at the first recorded frame, so picture and sound share one clock. Rotation and location metadata are written into the file.

A take needs free space for the video, its AAC track and the optional WAV master; it stops cleanly at a cap computed from free space (and the video maximum file size, if set). Mixed recordings use a continuous timeline: pause, automatic file rollover/restart, slow motion and timelapse are unavailable. Disabling Record audio allows silent video. Phones on the old camera API record with MediaRecorder instead and join the mix to the video after stopping, which needs room for a second copy.

The 24-bit WAV master is written to the take's recovery folder under the app's external files `Movies/recovery/<session>/` while recording, then saved to the chosen folder (or Music/GearCam by default). An MP4 becomes playable only when it is closed, so if the app or phone dies mid-take the video is lost but the WAV master's audio remains in the recovery folder; the app reports that folder when something fails. Do not uninstall or clear app data before recovery.

## Build and test

Use Android SDK 36, NDK 28.2.13676358, CMake 3.22.1, a JDK capable of running Gradle, and the Java 17 toolchain configured by the project:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.sourceforge.opencamera.GearCamRecordingTest,net.sourceforge.opencamera.audio.AudioOnlyRecordingTest,net.sourceforge.opencamera.CreativeFiltersTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Validation on 2026-09-17: builds for arm64-v8a, armeabi-v7a and x86_64 and all 58 JVM tests pass. Sixteen focused instrumentation checks have passed on an Android 16 emulator: encoder output, all three audio-only formats, saving on backgrounding and reopening inputs, waveform/control visibility in both orientations, chosen-folder audio/master publication, unavailable-folder rejection, settings cleanup and refresh after pausing, explicit video launches, preview histogram recovery after backgrounding, missing-input handling, video/AAC/WAV recording, mixer rotation with the pan and tone controls fully visible, all seven themes across activity recreation, all six creative filters decoded from MP4, and B&W matching preview and saved video with or without audio. The chosen-folder check uses a folder granted through Android’s real picker and is skipped until such a folder is configured; run `AudioOnlyRecordingTest#chosenFolderReceivesAllFormatsAndMaster` separately after selecting a test folder. Independent FFmpeg decoding confirms that FLAC and WAV fixtures contain identical 24-bit PCM and MP3 is 320 kb/s, stereo, 48 kHz.

The code review fixed preference listeners surviving closed settings screens, preview snapshots being read off the UI thread, abandoned preview-result bitmaps, file-handle cleanup when a WAV header cannot be written, and explicit video launches incorrectly following the saved audio-only mode. Histogram processing remains on a worker thread. The live-muxer file-descriptor constructor now has an explicit Android 8 API guard. Lint reports no new errors; 650 inherited translation errors and three legacy-tile API lint errors remain (those calls already have OS-version guards). Physical USB reuse and removable-SD-card behavior still require a phone/interface/card check; emulator tests cannot establish those hardware properties.

The GearCam unit tests cover pan, gain, mute ramps, clipping, limiter behavior, channel order, ring-buffer boundaries, timestamp alignment and input gaps. The targeted instrumented tests exercise video-only behavior, missing-device rejection, photo-setting removal, and actual camera/audio/AAC/MediaStore recording on Android. Upstream photo instrumentation suites remain as historical source and do not describe GearCam's intended behavior.

Before relying on a phone/interface combination, test each USB channel with an isolated tone, USB plus room mic with a clap, input unplug/replug, microphone permission denial, a 30–60 minute sync run, clipping, app interruption and low storage. Emulator results cannot establish physical USB compatibility. New mixer copy currently uses English; inherited camera translations remain.
