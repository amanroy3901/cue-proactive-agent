# LFM3-Omni-2B on-device spike: setup and integration notes

Handoff for whoever integrates on-device voice into Cue. Written 2026-11-12, after the demo ran on the demo phone.

**What it is:** Google's `photo_voice_chat` LiteRT sample running **LiquidAI LFM3-Omni-2B**. This is one model that takes audio (plus a photo or video) and answers in **text and speech together**. There is no separate speech recognizer or text-to-speech. Everything runs on the phone with no network. For Cue this is a third voice path besides A and B in `architecture.md` section 7: on-device speech-to-speech.

## Where everything is

### On the Mac

| What | Path |
| --- | --- |
| Spike root | `/Users/amankumar/Documents/workspace/codebase/lfm3-omni-spike/` |
| Sample source (Android project) | `…/lfm3-omni-spike/litert-samples/samples/litert/photo_voice_chat/kotlin_cpu_gpu/android/` |
| Sample README (full design and benchmarks) | `…/lfm3-omni-spike/litert-samples/samples/litert/photo_voice_chat/README.md` |
| Kotlin sources | `…/android/app/src/main/java/com/google/ai/edge/examples/photo_voice_chat/` |
| Built APK (debug) | `…/lfm3-omni-spike/lfm3-omni-demo.apk` |
| Model files | `…/lfm3-omni-spike/models/` (bundle at top, 3 graphs in `speech/`) |
| Python venv with `hf` CLI | `…/lfm3-omni-spike/.venv/` |
| Setup script (re-runnable, skips finished steps) | `/Users/amankumar/Downloads/setup_omni_demo.sh` |

Upstream: `github.com/google-ai-edge/litert-samples`, sparse checkout of `samples/litert/photo_voice_chat`, commit `8630c5bce3ad`.

### On the phone

| What | Path |
| --- | --- |
| Package | `com.google.ai.edge.examples.photo_voice_chat` |
| Model files (pushed with `adb push`) | `/sdcard/Android/data/<pkg>/files/` (the app's `getExternalFilesDir(null)`) |
| Sections copied out of the bundle on first start | `files/lfm3_sections/<sha16>/*.tflite` (internal storage) |
| XNNPACK weight cache and GPU program cache | `files/xnnpack_cache/` |
| Per-answer records (timings, text, 24 kHz speech) | `files/runs/<id>.json` and `.f32` |

The app looks for model files in `filesDir` first, then `getExternalFilesDir(null)` (`Lfm3OmniEngine.modelDirs`). Internal paths can be read with `adb shell run-as <pkg> …` because the build is debuggable. These paths come from the code: the phone was unplugged before the folder could be listed, so the internal folders haven't been checked on the device.

## Model files (4.0 GB total)

From Hugging Face `litert-community/LFM3-Omni-2B-LiteRT`. SHA-256 hashes are in the sample README.

| File | Size | Contents |
| --- | --- | --- |
| `LFM3-Omni-2B_int8.litertlm` | 3.23 GB | Bundle: int8 language model (4,096-token context), embedder, audio encoder, vision encoder and adapter, tokenizer |
| `speech/LFM3-Omni-2B_depth_frame2_f32.tflite` | 435 MB | Depth decoder (speech codes per 80 ms frame) |
| `speech/LFM3-Omni-2B_frame_embed_f32.tflite` | 198 MB | Frame embedding (codes back into the language model) |
| `speech/LFM3-Omni-2B_codec_decoder_f12_f32.tflite` | 152 MB | Codec decoder (codes to 24 kHz audio) |

The byte offsets of the bundle's sections are in `app/src/main/assets/bundle_sections.json`.

## Build stack (copy these into Cue's Gradle if integrating)

- `com.google.ai.edge.litert:litert:2.3.0` and `com.google.ai.edge.litert:litert-gpu:2.3.0`
- AGP 8.9.1, Kotlin 2.2.21 with compiler flag `-Xskip-metadata-version-check` (LiteRT 2.3.0 ships Kotlin 2.4 metadata), JDK 17, Gradle 8.11.1
- compileSdk and targetSdk 35, minSdk 26, **arm64-v8a only**
- `packaging.jniLibs.useLegacyPackaging = true` (the GPU accelerator is opened by path)
- `androidResources.noCompress += json, bin, wav`
- Manifest: `RECORD_AUDIO`, `android:largeHeap="true"`, and `<uses-native-library>` for `libOpenCL.so`, `libOpenCL-pixel.so`, `libvndksupport.so` and `libcdsprpc.so` (all `required="false"`)

## Measured on the demo phone

Phone: motorola razr fold 2026, Snapdragon SM8845, Android 17, airplane mode off, the app on the cover screen.

| Metric | Result |
| --- | --- |
| Where each part ran | Language model: CPU · speech decoder: GPU · audio encoder: CPU · vision encoder: GPU |
| NPU | Not used: `NPU accelerator could not be loaded` (this APK doesn't include the Qualcomm NPU libraries) |
| First start, load to Ready | 48.4 s (includes the one-time section copy; later starts not measured yet) |
| Photo ready for questions | 4.2 s |
| Typed question to first sound | **2.7 s** |
| App memory after loading | **about 5.2 GB PSS** |
| Battery at start | 30 °C, 90% |
| Answer quality | "What fruit is in this bowl?" got the fruit types right and the apple count wrong (4 green; the photo shows more) |

### Inference speed: 17 spoken questions, 20:25–20:48

These come from the app's records in `files/runs/run001–017.json`, covering photos, camera shots and one video.

| Metric | Median | Range |
| --- | --- | --- |
| Language model decode (CPU, 4 threads) | **23.3 tokens/s (43 ms per token)** | 18.5–24.4 tokens/s |
| Prefill (question into the language model) | about 107 tokens/s | |
| Audio encoder | 0.23 s per second of question | |
| Depth decoder per speech frame (GPU fp16) | 11.5 ms | 25.7 ms in the one slow answer |
| Time to make 1 s of speech | **0.86 s** (just faster than real time) | 0.81–1.15 s |
| End of spoken question to first sound | **3.23 s** | 3.05–4.67 s |
| Answers whose audio paused | 1 of 17 | run003: 5 underruns, generating slower than playback (RTF 1.29) |
| Heat after about 20 min of use | Battery 30 → 33 °C, thermal status 0 (no throttling) | |
| Context used (video conversation) | 3,468–4,024 of 4,096 tokens | |

How the 3.23 s to first sound breaks down (medians, from the end of the question):

| From | To | What happens |
| --- | --- | --- |
| 0 | 0.77 s | Audio encoder reads the question |
| 0.77 s | 1.23 s | Prefill |
| 1.23 s | 2.23 s | First 6 text tokens and 12 speech frames generated, first 0.96 s audio chunk decoded |
| 2.23 s | 3.23 s | **About 1 s waiting before the speaker starts** |

The last gap is most likely the player's 1 s `AudioTrack` buffer (`AudioIO.kt`, `setBufferSizeInBytes(rate * 4)` with float samples). The first chunk is only 0.96 s, so playback doesn't start until the second chunk arrives. A smaller start threshold (`setStartThresholdInFrames`, API 31+) could save up to about 1 s. This hasn't been tested. After the first sound, the model's own near-silent lead (0.7–1.3 s) still comes before the first word.

**Load time on later starts:** 14–21 s once the sections and caches exist (48 s the first time). Opening the depth decoder on the GPU accounts for 7–10 s of that on every start, and reading the tokenizer for 1.8 s.

## Optimisation results (2026-11-12, measured on the demo phone)

**Method:**
- The sample's measuring tool (`AutoRun.kt`) asked the same example question ("What is this thing?" about the Umbrella photo) with seed 0, so every answer is the same 8.8 s of speech.
- Each setting got 2–3 answers with 15–20 s rests in between. The first answer after each engine reload is cold and listed separately.
- Battery was at 32–36 °C, thermal status 0.
- Reports are in the app's `files/threads.json` and `files/latency.json`.

**Code changes (to the spike's sample only):** saved as [lfm3-omni-latency.patch](lfm3-omni-latency.patch), against the sample commit in this doc.
- Optional 1-frame codec: `Config.codecFrames`, which opens `LFM3-Omni-2B_codec_decoder_f1_f32.tflite`.
- Optional `AudioTrack` start threshold: `AudioIO.answerStartMs`, which calls `setStartThresholdInFrames`.
- Measuring-tool keys `codec_frames`, `start_ms` and `audio`.

The f1 codec file (152 MB) must also be on the phone. It's on Hugging Face next to the f12 one.

### Result: time to first sound 3.3 s → 1.6 s

Warm answers (second and third after a load):

| Setting | Audio encoder | Decode | First sound | Audio pauses (underruns) |
| --- | --- | --- | --- | --- |
| Baseline: f12 codec, default player, 4 threads | 941–952 ms | 22.5–22.9 tok/s | 3.33–3.34 s | 0 |
| f12 codec, playback starts at the first chunk (`start_ms` 0) | 648–654 ms | 23.1–23.2 tok/s | 2.10–2.20 s | 2, 0 |
| f1 codec, `start_ms` 300 | 643–650 ms | 21.0–22.7 tok/s | 1.48–1.56 s | 3, 0 (cold answer: 7) |
| **f1 codec, `start_ms` 500, language model 6 threads, audio encoder 8 threads, codec 2 threads** | **633–658 ms** | **23.1–23.2 tok/s** | **1.61–1.64 s** | **0, 0 (cold answer: 0)** |
| Same as above, language model on the GPU, depth decoder on the CPU | 698–756 ms | 20.2–20.3 tok/s | 1.64–1.88 s | 20–59 (generates slower than it plays: 1.27–1.42 s per second of speech) |

**Recommended setting for Cue: the bold row.** It also needs a warm-up: the first answer after a load came at 2.09 s instead of 1.6 s. Run one dummy audio-encoder pass and one short prefill right after loading, before the call rings. The warm-up hasn't been tested yet.

What else the runs showed:
- **CPU threads don't change decode speed**: 21.0 / 22.8 / 23.3 / 21.8 tok/s at 3 / 4 / 6 / 8 threads. Decode is limited by memory bandwidth (the int8 decoder section is 1.79 GB, read once per token).
- **The audio encoder does scale with threads**: 1,148 / 950 / 734 / 640 ms at 3 / 4 / 6 / 8 threads.
- **The audio encoder can't run on this phone's GPU.** The GPU didn't open the graph, the app fell back to the CPU, and the encode took the same ~950 ms as on 4 CPU threads.
- **The language model on the GPU is worse.** Decode is slower (20 tok/s), the first load compiles for 83 s, and the depth decoder pushed back to the CPU slows to 37–47 ms per frame, so the speech pauses. Prefill is a little faster (250 vs 306 ms). Don't use it.
- **The 1-frame codec costs about 14–22 ms per frame** on 2 threads and doesn't slow decoding.

### Further options, not tried yet

Researched on 2026-11-12. "Verified" means taken from the source.

| Option | Expected gain | Status and cost |
| --- | --- | --- |
| Cache the depth decoder's GPU programs (as the sample already does for the vision encoder) | Load time −5 to −8 s (depth GPU open is 7–10 s now, 3 s on an S26 per the README) | Small code change; not tried |
| Re-export the language model as int4 with a 2,048-token KV cache | Decode about 23 → 30+ tok/s, so the first sound comes sooner and long answers stop pausing. Verified: the same-backbone LFM3-1.7B does 35.5 tok/s (int4, 2K cache) against 25.9 for Omni int8 on an S26 CPU ([LFM3-1.7B-LiteRT](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT)). | No int4 Omni file is published. The conversion tooling is public: `lfm3omni_work/` in [john-rocky/hf-to-litertlm](https://github.com/john-rocky/hf-to-litertlm). Verified: int4 top-token agreement is 78–80%, against 89–96% for int8. A 2K context holds about 100 s of call. Medium effort. |
| llama.cpp (Q4_0 GGUF, CPU / OpenCL / Hexagon backends) | Possibly about 2× decode, and spoken input can be read while the user is still talking. Verified: Qualcomm lists LFM3-1.7B q4_0 at 40–51 tok/s on the 8 Elite Gen 5; the backend isn't stated. | Verified: speech output for LFM3-Omni was merged on 2026-10-27 ([llama.cpp#31257](https://github.com/ggml-org/llama.cpp/pull/31257), build b11960 or newer), but only smoke-tested on Mac and CUDA. No Android numbers. Needs an NDK/JNI port. Check first with `llama-bench` over adb (about 1 day). |
| NPU (LiteRT Qualcomm, QNN) | Unknown for this chip | Verified: the Omni card says the NPU is "Not supported in this release". The sibling LFM3-Audio NPU file is compiled for SM8850 only, and SM8845 isn't on LiteRT's SoC list. |
| LiteRT-LM runtime, ONNX + QNN, Liquid LEAP SDK, speculative decoding | None | Verified: LiteRT-LM gives text only for this model; the ONNX build targets WebGPU/CUDA only; LEAP is deprecated; LFM has no MTP heads.

The README notes that the model's speech starts with a near-silent lead of 0.7–1.3 s, so the first *word* comes later than the first *sound*. Reference numbers from the README on a Galaxy S26 (SM8850): first sound 2.0–2.2 s after a spoken question, and 0.75 s to generate each second of speech.

## How the code is organised (what to reuse)

| File | Role |
| --- | --- |
| `Lfm3OmniEngine.kt` | `Lfm3OmniEngine.open(context, Config, log)`: finds the files, copies the sections, opens every graph, checks free memory before each stage (needs about 1.2 GB plus a 0.5 GB reserve to start). Picks the accelerators per chip (Qualcomm: depth on `gpu16`, vision on `gpu32`). |
| `Conversation.kt` | `Conversation(engine)`, `.setPhoto(PhotoInput)`, `.ask(Question(clip, typed, label), instruction, listener = …)`. `Listener.onText` and `Listener.onAudio(pcm)` stream as the answer is generated. `Output.timing` holds the timings. Turns continue in the KV state, so a follow-up only costs its own tokens. |
| `SpeechGenerator.kt` | Text and speech turn loop: 6 text tokens, then 12 speech frames, repeated |
| `LmRunner.kt` | Prefill and decode on the language model, state updated in place |
| `Parts.kt` | Depth decoder, frame embedding, codec decoder with overlap-add, audio encoder, vision encoder |
| `Lfm3Tokenizer.kt` | Byte-level BPE tokenizer and chat prompt |
| `AudioIO.kt` | Recording (16 kHz, up to 30 s), silence check, playback (24 kHz) |
| `AutoRun.kt` | Scripted measuring tool: runs a JSON plan and writes a report with per-answer timings (see the README section "The measuring tool"). Useful for spike S2 numbers. |

Assets to carry over: `bundle_sections.json` and `codec_window_f32.bin`.

## Gaps and risks for Cue

1. **`Conversation.ask()` needs a photo or video.** It fails with `check(image != null || video != null)`. Cue's check-in calls are voice-only, so a voice-only conversation start (system prompt plus audio, no `<|image|>` rows) must be added. `answerIds(prompt_ids)` shows that a no-photo prompt works at the language-model level.
2. **Memory, about 5.2 GB.** Android won't keep this resident in a background service. Load the model only when a call is about to start, and keep in mind that a cold load took 48 s here. That conflicts with "ring, then talk right away" unless loading starts well before the ring (Cue's PREPARING state).
3. **Latency.** 3.23 s median from a spoken question to first sound with the stock sample. With the 1-frame codec, a 500 ms start threshold and 8 audio-encoder threads it is **1.61–1.64 s** with no pauses (see "Optimisation results"). That's close to spike S2's 1.5 s target, but the model's near-silent lead (0.7–1.3 s) still comes before the first word. The NPU build (README: no faster than the GPU on the S26) and spoken-question numbers are still open.
4. **4,096-token context.** Fine for a 60-second check-in, but there's little room for Cue's memory context in the prompt.
5. **Model ability.** It's a 2B model: no tool calling has been checked, and details can be wrong (the apple count). Cue's night call needs to file tasks, notes and journal entries, which may still need the cloud LLM for extraction after the call.
6. **License.** Sample code is Apache-2.0. Model weights are under the **LFM Open License v1.0** (LiquidAI). Check its terms before pitching this to Motorola.
7. **Model delivery.** 4 GB can't ship in an APK. The demo pushes it with `adb`. A real build needs a first-run download.

## Reproduce

```bash
bash /Users/amankumar/Downloads/setup_omni_demo.sh
```

Needs the phone on USB with USB debugging on, the Android SDK, a JDK 17–23 (Android Studio's bundled one works), and about 7 GB free on the phone. If more than one device is connected, set `ANDROID_SERIAL` first.
