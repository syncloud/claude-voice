# bridge

Termux-side localhost server that the Android app talks to, as a single static
Go binary (`CGO_ENABLED=0`, no runtime deps). The Claude agents run **here**,
where your repos and tools live; the app is only voice-in / speech-out.

## Get it

Download `claude-voice-bridge-arm64` from the GitHub release (or the artifact
server), make it executable, and run it from the dir you want the default agent
to work in:

```bash
chmod +x claude-voice-bridge-arm64
cd ~/some-repo && ./claude-voice-bridge-arm64
```

Or build it yourself:

```bash
cd bridge && go build -o claude-voice-bridge .            # native
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o claude-voice-bridge-arm64 .   # release
```

Listens on `http://127.0.0.1:8765` (loopback is reachable cross-app on Android,
so the app on the same phone connects to it).

## API

| Method | Path        | Body                | Returns                          |
|--------|-------------|---------------------|----------------------------------|
| GET    | `/health`   | —                   | `ok`                             |
| GET    | `/agents`   | —                   | `[{id,name,dir,branch,dirty}]`   |
| POST   | `/agents`   | `{"dir":"~/repo"}`  | current agent list               |
| DELETE | `/agents/<id>` | —                | current agent list               |
| POST   | `/stt`      | WAV bytes           | transcript (whisper.cpp)         |
| POST   | `/chat`     | `{"text","agent":id}` | agent reply (`claude -p`)      |

One agent per directory; each keeps its own `claude --continue` conversation, so
per-dir continuity is automatic.

## Config (env)

- `VOICE_PORT` (default `8765`), `VOICE_HOST` (default `127.0.0.1`)
- `VOICE_PERM` claude permission mode (default `bypassPermissions` for hands-free
  tool use; set `acceptEdits` to gate Bash/tool calls — note gating makes
  tool-using turns stall, since headless mode can't answer prompts)
- `VOICE_TIMEOUT` seconds before a stuck agent turn is aborted (default `180`)
- `VOICE_WORKDIR` directory of the initial agent (default: cwd)
- `WHISPER_BIN`, `WHISPER_MODEL` paths to the whisper.cpp cli + ggml model

Requires the whisper.cpp build at `~/storage/projects/whisper.cpp` and a model at
`~/whisper-models/ggml-base.en.bin`, plus the `claude` CLI on `PATH`.
