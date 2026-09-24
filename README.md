# GearCam

GearCam is a video and audio recorder for musical jams, forked from [Open Camera](https://opencamera.org.uk/) 1.56.2. It installs as `app.gearcam`, alongside Open Camera. Copyright and GPLv3+ attribution to Mark Harman and the other Open Camera contributors remain in the source and About screen; see [gpl-3.0.txt](gpl-3.0.txt).

## Features

- **Video + audio, or audio only.** Record video with a mixed soundtrack, or run as a pure audio recorder with a live waveform and no camera.
- **Multitrack input.** The phone microphone, a USB audio interface, or a wired microphone — up to 32 channels per device, and USB together with the phone mic on phones that otherwise refuse the combination.
- **Full mixer.** Gain, pan, mute, stereo pairing, level meters and clipping indicators per channel, with a limiter on the stereo bus. Everything stays adjustable while recording.
- **Headphone monitoring.** Hear the mix on wired, USB or Bluetooth headphones during soundcheck and recording.
- **Studio-quality files.** 48 kHz throughout, with an optional 24-bit WAV master alongside the video.
- **Audio-only formats.** WAV, MP3 or FLAC, written straight to your chosen folder.
- **Noise tools.** A noise gate for the phone mic, a USB whine filter for interfaces, and high-pass/low-pass filters on every channel.
- **Creative video filters.** Eight looks including Color Accent, which keeps one colour and turns the rest monochrome.
- **Start on MIDI play.** A USB MIDI Start or Continue message begins a take, so a sequencer can roll the camera.
- **Six colour themes** and a compact camera top bar.

## Recording and mixer

Choose **Video + audio** or **Audio only** at the top of Settings. Audio-only mode replaces the camera with a live stereo waveform. In video mode the camera switch changes cameras and the old photo/video switch opens the mixer; photo capture is not part of GearCam. Open Camera's video resolution, frame rate, exposure, focus, stabilisation, zoom, camera selection and storage controls remain available.

The full-screen mixer has horizontally scrolling channel strips and a pinned master section. Each strip has:

- Digital gain from −60 to +24 dB on a vertical fader, constant-power pan, mute, a level meter and latched input/gain clipping indicators.
- Three icon switches: the noise gate or USB whine filter, a 20 kHz low-pass and a 30 Hz high-pass. Gain, pan, mute and the filters can all be changed mid-take.
- Stereo pairing for adjacent inputs (1/2, 3/4 and so on), sharing gain, mute, recording selection and a balance control that preserves left/right separation.

The master bar carries the stereo meters, the peak limiter (0.98 full-scale ceiling), **Monitor** and **Soundcheck**. Soundcheck uses the same capture and mixing path as recording and saves nothing. Channel selection is fixed once a take starts; everything else stays live.

**Monitor** plays the mix to connected headphones, during soundcheck and while recording. It stays silent unless a wired, USB or Bluetooth headset is connected, because on the loudspeaker the phone microphone would feed straight back, and it closes itself within half a second of headphones being unplugged. Bluetooth runs roughly 0.2 s behind — good for hearing a take, not for playing along. A wired or USB headset is much closer.

Compact L/R meters and clipping indicators also appear on the camera preview, beside the ISO and storage readout.

**Audio/video sync** can be calibrated before recording, from −500 to +500 ms, with positive values delaying the audio. Check a handclap on your actual phone and interface before a long take.

### Noise tools

- **Noise gate** (phone microphone) learns the background level and turns it down 20 dB between phrases, so hum and room noise drop out in the pauses. It cannot remove noise underneath the voice.
- **USB whine filter** (every other input) cancels the whistle at multiples of 1 kHz — the USB frame rate — that an adapter's own electronics add to its signal. It tracks the tones as the device's clock drifts and removes over 20 dB of each within a fraction of a second, with no audible effect on music: it only touches a steady note landing within a few Hz of an exact 1 kHz multiple. Leave it off on inputs that do not whine.
- **High-pass and low-pass filters** (30 Hz and 20 kHz, −18 dB/octave) remove DC, infrasonic and ultrasonic content that eats headroom.

Neither the gate nor the whine filter adds any delay.

### Reliability

GearCam asks Android for each selected input explicitly and checks what it actually got, when capture starts and throughout the take. Rejected routing, an input Android silences, USB buffer overflow or sustained gaps stop the recording with an error rather than saving something wrong. **It never silently substitutes a different microphone.**

A selected device that is not plugged in is skipped instead of blocking the take: GearCam says which device the take ran without, keeps it in your settings, and picks it up again when it returns. It refuses to start only when nothing you selected is connected.

## Start on MIDI play

Turn on **Start recording on MIDI play** in Settings, or in the audio recorder's Config. A Start or Continue message from any USB MIDI device then begins a take while GearCam is on screen. It only starts takes — Stop is ignored.

Android carries a device's MIDI on the same driver as its audio, so MIDI from the interface being recorded is unavailable during a take and comes back about a second afterwards. For the same reason the audio recorder leaves its live waveform off between takes while this option is on; it starts with the take. A separate MIDI device is unaffected.

## Creative video filters

The overlapping-circles icon in the camera's top bar opens **Original · no filter**, **Black & white**, **B&W Noir**, **Warm Film**, **Cool Chrome**, **Sepia**, **Fade** and **Color Accent**. Original is selected by default; select it again to turn filtering off. The choice is remembered and the icon is highlighted while a filter is active.

- **Black & white** removes colour; **B&W Noir** adds deeper contrast and a vignette.
- **Warm Film** warms the highlights, **Cool Chrome** is cooler and muted, **Sepia** adds amber tones, **Fade** softens colour and contrast.
- **Color Accent** keeps one hue and turns everything else monochrome, with more contrast than Black & white and none of Noir's crush or vignette. Choosing it opens a rainbow slider for the kept colour that updates the preview as you drag; pick the filter again to reopen the slider. Colours within about 18° of your choice are kept fully and fade out by 40°, while washed-out and very dark areas stay monochrome.

Filters apply to the live preview and the saved MP4, including silent video, and the interface colour theme never changes the recorded image. They need Camera2 at a normal frame rate: choosing one switches supported phones to Camera2, and high-speed capture always records as Original.

## Audio-only recording

In **Settings → Recording mode**, choose **Audio only · live waveform**. The recorder shows a live L/R scope of the mix, with the same gains, pan, filters, mute and limiter as video. **Mixer** selects inputs; **Config** chooses the format, save location, theme, MIDI start, or returns to video. Tap **Record** and **Stop**.

| Format | Output at 48 kHz stereo | Use |
| --- | --- | --- |
| WAV | 24-bit PCM; about 1.04 GB/hour | Editing and archival audio; limited to a standard WAV's 4 GB size |
| MP3 | 320 kb/s; about 144 MB/hour | Smaller, plays anywhere |
| FLAC | 24-bit lossless; size depends on the signal | Lossless with compression |

Switching apps or locking the screen stops and finishes the take; background recording is not supported. Format and destination cannot change mid-take, and no video track or extra WAV master is created in audio-only mode.

## Files and saving

**Settings → Save location** opens Android's folder picker: choose your SD card or internal storage, select a folder, then tap **Use this folder**. The same choice covers videos, audio-only takes and WAV masters, and persists across restarts. **Use defaults** restores video to `DCIM` and audio to `Music/GearCam`. Use a local or SD-card folder; cloud providers may not support live recording.

Video takes contain MP4 video (H.264 or HEVC) and a 48 kHz stereo AAC soundtrack up to 512 kb/s, written live into the final file — stopping closes it within moments, with nothing to copy afterwards. Picture and sound share one clock, and rotation and location metadata are written into the file.

A 24-bit WAV master is recorded alongside by default and saved to the same folder (about 1.04 GB/hour); turn it off in the mixer if space is short. Use it for editing and archiving, since AAC is lossy. Separate input stems are not available.

If the card is removed or folder access is revoked, recording reports an error instead of saving somewhere else. A take stops cleanly before free space runs out. Mixed recordings use one continuous timeline, so pause, file rollover, slow motion and timelapse are unavailable; turning off Record audio gives silent video.

**Recovery:** an MP4 only becomes playable once it is closed, so if the app or phone dies mid-take the video is lost — but the WAV master's audio survives in the app's recovery folder, and GearCam reports that folder when something fails. Do not uninstall or clear app data before recovering it.

## USB audio devices

Anything presenting a USB Audio Class 1 or 2 capture stream with PCM or 32-bit float at **exactly 48 kHz** should work. GearCam lists a device's capture channels once Android grants USB access, which it requests automatically after microphone permission; the mixer's access button retries a denied request. Your selection, stereo links and strip settings are remembered.

| Device | Notes |
| --- | --- |
| BOYA BY-K4 | TRS microphone adapter, about €16. Two channels of 16-bit PCM at 48 kHz. Mic level only, so anything hotter needs a line-to-mic attenuator ahead of it: roughly 20–25 dB from consumer line, 30–35 dB from pro line, 40–45 dB straight from Eurorack. Without it the signal clips inside the adapter, which no fader can undo. |
| RØDE AI-Micro | Two TRS inputs, USB-C. |
| Saramonic SmartRig UC | XLR/TRS preamp, USB-C. |
| Zoom H6studio | Multitrack recorder in interface mode. |
| 1010music Bluebox (Eurorack) | 18 capture channels, 24-bit at 48 kHz. Also carries USB MIDI alongside its audio. |
| Behringer CU1A (Eurorack) | USB audio interface module. |
| After Later Audio USB-2CH (Eurorack) | Class-compliant interface module. |
| Expert Sleepers ES-9 (Eurorack) | Class-compliant multichannel interface. |

Practical limits:

- **A device must offer 48 kHz.** One locked to another rate reports an error. Compressed formats and USB playback are not supported.
- **Channel count follows the device's own descriptor.** "Input 1" and "Input 2" appear only when it advertises a two-channel format; a stereo TRS socket may still deliver one summed channel.
- **Bus speed is not sound quality.** A full-speed interface (often sold as USB 1.1) stays full speed in any port, and its 12 Mb/s ceiling is what limits high channel counts — 18 channels of 24-bit audio at 48 kHz already need 20.7 Mb/s, so they require a high-speed device. Quality comes from the converters, not the bus.
- **Power matters.** The phone powers the interface, so a powered hub or Y cable helps with hungry ones. Hubs and two identical interfaces are not a supported setup, and unplugging USB stops the take.
- **Gain is applied after capture.** It cannot drive the adapter's analog preamp or repair samples clipped before they reached the phone.
- **GearCam holds the interface** while soundcheck or recording runs, so other apps, and audio playback on the same device, may lose access meanwhile.

Capture uses 32-bit float when a device offers it, otherwise its deepest PCM format at 48 kHz, and keeps that precision through mixing. There is no 96/192 kHz upsampling, and independent USB and phone clocks are corrected in software, so a long take still deserves a clap check for sync.

## Build

Android SDK 36, NDK 28.2.13676358, CMake 3.22.1, and the Java 17 toolchain configured by the project:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.sourceforge.opencamera.GearCamRecordingTest,net.sourceforge.opencamera.audio.AudioOnlyRecordingTest,net.sourceforge.opencamera.CreativeFiltersTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Builds for arm64-v8a, armeabi-v7a and x86_64.

Emulator runs cannot establish USB or SD-card behaviour. Before trusting a phone and interface combination, test each USB channel with a tone, USB plus room mic with a clap, unplug and replug, a 30–60 minute sync run, clipping, an app interruption and low storage.

Bundled libraries: [libuac](https://github.com/nExtCamera/libuac) and [libusb](https://github.com/libusb/libusb) for direct USB capture, [LAME 3.100](https://lame.sourceforge.io/) (LGPL) for MP3 and [libFLAC 1.5.0](https://xiph.org/flac/) (BSD) for FLAC, with their licences under `app/src/main/cpp/vendor/`. GearCam's own text is English; inherited camera translations remain.
