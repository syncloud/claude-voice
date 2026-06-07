# claude-voice

A native Android app for **talking to a coding agent on your phone**. Push to
talk, watch the transcript, hear the reply spoken back. The agent itself is the
`claude` CLI running in Termux on the same device — so it has your repos and
tools — and the app is a thin voice front-end that reaches it over localhost.

```
            ┌──────────────────── one phone ────────────────────┐
  Android app (this repo)                Termux (bridge/)
  ┌───────────────────────┐   POST /stt  ┌──────────────────────┐
  │ hold-to-talk button   │──(wav)──────▶│ whisper.cpp  → text  │
  │ transcript (in/out)   │              │                      │
  │ Android TextToSpeech  │◀──(reply)────│ claude -p --continue │
  │                       │──POST /chat─▶│  (your repo + tools) │
  └───────────────────────┘   127.0.0.1  └──────────────────────┘
```

Why this shape: no Android app is itself a coding agent — the brain must run in
Termux where the files and tools are. Push-to-talk removes the speech-endpoint
cutoff that plagues hands-free voice: you control exactly when audio starts and
stops.

## Layout

- `app/` — the Android app (Kotlin, AGP 8.5.2, minSdk 23). Records 16 kHz mono
  PCM on hold-to-talk, POSTs to the bridge, shows the transcript, speaks the reply.
- `bridge/` — the Termux-side localhost server, a single static Go binary
  (`claude-voice-bridge-arm64`): manages N agents (one per dir), `/stt` via
  whisper.cpp, `/chat` via the `claude` CLI. See `bridge/README.md`.
- `.drone.jsonnet` — CI: builds the APK in `runmymind/docker-android-sdk`,
  publishes to GitHub releases on tag, ships the APK to the artifact server.

## Prerequisites (Termux side)

- whisper.cpp built at `~/storage/projects/whisper.cpp` and a model at
  `~/whisper-models/ggml-base.en.bin`
- `claude` CLI on `PATH`

## Quick start

1. Termux: `cd <your-repo> && ./claude-voice-bridge-arm64`
2. Install the app APK (from CI artifacts or a local build).
3. Open the app, confirm the bridge URL (`http://127.0.0.1:8765`), grant the mic
   permission, hold the button, speak, release.

## Build

CI builds it (see `.drone.jsonnet`). Local/manual builds use the Android SDK; the
project targets gradle 8.7 (wrapper vendored) and `compileSdk 34` /
`build-tools;34.0.0`.
