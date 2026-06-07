#!/data/data/com.termux/files/usr/bin/env python3
"""Termux-side bridge for the Claude Voice Android app (multi-agent).

One bridge, one connection from the app, N agents. Each agent is a working
directory with its own `claude --continue` conversation chain.

  GET    /health                       -> "ok"
  GET    /agents                       -> [{id,name,dir,branch}]
  POST   /agents   {"dir":"~/repo"}    -> create/return agent, then current list
  DELETE /agents/<id>                  -> remove agent
  POST   /stt      WAV bytes           -> transcript (whisper.cpp, stateless)
  POST   /chat     {"text","agent":id} -> agent reply (claude -p, routed to dir)

The agents run here, in Termux, where the repos and tools live. The app is just
voice in / text + speech out.
"""
import json
import os
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WHISPER = os.environ.get("WHISPER_BIN",
                         os.path.expanduser("~/storage/projects/whisper.cpp/build/bin/whisper-cli"))
MODEL = os.environ.get("WHISPER_MODEL",
                       os.path.expanduser("~/whisper-models/ggml-base.en.bin"))
PERM = os.environ.get("VOICE_PERM", "bypassPermissions")
TIMEOUT = int(os.environ.get("VOICE_TIMEOUT", "180"))
HOST = os.environ.get("VOICE_HOST", "127.0.0.1")
PORT = int(os.environ.get("VOICE_PORT", "8765"))
START_DIR = os.environ.get("VOICE_WORKDIR", os.getcwd())

_lock = threading.Lock()
_agents = {}      # id -> {"dir": str, "started": bool}
_next = [1]


def add_agent(d):
    d = os.path.abspath(os.path.expanduser(d.strip()))
    with _lock:
        for aid, a in _agents.items():
            if a["dir"] == d:
                return aid
        aid = _next[0]
        _next[0] += 1
        _agents[aid] = {"dir": d, "started": False}
        return aid


def branch(d):
    try:
        r = subprocess.run(["git", "-C", d, "rev-parse", "--abbrev-ref", "HEAD"],
                           capture_output=True, text=True, timeout=5)
        if r.returncode == 0:
            return r.stdout.strip() or None
    except Exception:
        pass
    return None


def agent_list():
    with _lock:
        ids = sorted(_agents.items())
    out = []
    for aid, a in ids:
        out.append({"id": aid, "dir": a["dir"],
                    "name": os.path.basename(a["dir"]) or a["dir"],
                    "branch": branch(a["dir"])})
    return out


def transcribe(wav_bytes):
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "in.wav")
        out = os.path.join(d, "out")
        with open(wav, "wb") as f:
            f.write(wav_bytes)
        subprocess.run(
            [WHISPER, "-m", MODEL, "-f", wav, "-l", "en", "-nt", "-np", "-otxt", "-of", out],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
        )
        try:
            with open(out + ".txt") as f:
                text = f.read()
        except FileNotFoundError:
            return ""
    for junk in ("[BLANK_AUDIO]", "(silence)"):
        text = text.replace(junk, "")
    return " ".join(text.split()).strip()


def ask(aid, text):
    if not text:
        return ""
    with _lock:
        a = _agents.get(aid)
        started = a["started"] if a else False
        d = a["dir"] if a else None
    if not a:
        return "Unknown agent."
    cmd = ["claude", "-p"]
    if started:
        cmd.append("--continue")
    cmd += ["--permission-mode", PERM, text]
    try:
        r = subprocess.run(cmd, cwd=d, capture_output=True, text=True, timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return f"The agent took longer than {TIMEOUT} seconds, so I stopped it. Try a smaller step."
    with _lock:
        if aid in _agents:
            _agents[aid]["started"] = True
    out = (r.stdout or "").strip()
    return out or (r.stderr or "").strip() or "No response."


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="text/plain; charset=utf-8"):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj), "application/json; charset=utf-8")

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, "ok")
        elif self.path == "/agents":
            self._json(200, agent_list())
        else:
            self._send(404, "not found")

    def do_POST(self):
        data = self._body()
        if self.path == "/stt":
            self._send(200, transcribe(data))
        elif self.path == "/chat":
            try:
                p = json.loads(data.decode("utf-8"))
            except Exception:
                p = {}
            text = p.get("text", "")
            aid = p.get("agent")
            if aid is None:
                lst = agent_list()
                aid = lst[0]["id"] if lst else None
            self._send(200, ask(aid, text))
        elif self.path == "/agents":
            try:
                d = json.loads(data.decode("utf-8")).get("dir", "")
            except Exception:
                d = ""
            if not d:
                self._send(400, "missing dir")
                return
            add_agent(d)
            self._json(200, agent_list())
        else:
            self._send(404, "not found")

    def do_DELETE(self):
        if self.path.startswith("/agents/"):
            try:
                aid = int(self.path.rsplit("/", 1)[1])
            except ValueError:
                self._send(400, "bad id")
                return
            with _lock:
                _agents.pop(aid, None)
            self._json(200, agent_list())
        else:
            self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    add_agent(START_DIR)
    print(f"claude-voice bridge on http://{HOST}:{PORT}  (perm={PERM}, start_dir={START_DIR})")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
