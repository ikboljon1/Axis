"""Axis server: phone screenshot → Claude Code (your Claude subscription) → answer.

Runs on the server where Claude Code is logged in to the subscription
(`claude login`, or CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token`) — the same
way scripts/claude_chat_worker.py in ERP_Textile works. No API key is used:
ANTHROPIC_API_KEY is removed from the environment so Claude Code answers via the
subscription.

Per request:
  - a separate `claude -p` process in an empty temp folder, no saved session;
  - no tools and no MCP servers (--tools "" --strict-mcp-config): the model only
    looks at the picture and answers;
  - the screenshot goes in as a stream-json message (base64), never written to disk.

Access: one personal token (AXIS_TOKEN). Only the phone that has it can ask.

API
  GET  /v1/ping                          → {"ok": true, "busy": n}
  POST /v1/analyze  Authorization: Bearer <AXIS_TOKEN>
       {"system": "...", "prompt": "...", "image": "<base64 jpeg>", "precise": false}
       → {"ok": true, "text": "..."} | {"ok": false, "error": "..."}
  POST /v1/chess    Authorization: Bearer <AXIS_TOKEN>
       {"image": "<base64 jpeg of the board>", "precise": false}
       Claude reads the position, Stockfish chooses the move (see axis_chess.py).
       → {"ok": true, "text": "Ход: Кg1–f3  (+0.4)", "best": "g1f3", "alternatives": [...],
          "white_bottom": true, "fen": "...", "board": [l, t, r, b]}
       → 503 {"ok": false, "code": "no_engine"} if Stockfish is not installed

Run
  AXIS_TOKEN=$(openssl rand -hex 32) python3 axis_server.py --port 8787
Settings (env)
  AXIS_TOKEN           required, the token you put into the app
  AXIS_HOST/AXIS_PORT  where to listen (default 127.0.0.1:8787 — put nginx with HTTPS in front)
  CLAUDE_CMD           claude command (default: claude from PATH)
  AXIS_MODEL_FAST      model for quick answers (default: sonnet)
  AXIS_MODEL_PRECISE   model for "precise analysis" (default: opus)
  AXIS_PARALLEL        answers at the same time (default 2)
  AXIS_TIMEOUT         limit per answer, s (default 180)
  STOCKFISH_PATH       stockfish binary (default: stockfish from PATH or /usr/games/stockfish)
  AXIS_ENGINE_FAST     Stockfish think time, s (default 1.5)
  AXIS_ENGINE_PRECISE  Stockfish think time in "precise analysis", s (default 5)
"""
from __future__ import annotations

import argparse
import base64
import binascii
import hmac
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import axis_chess

DROP_ENV = ('ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN', 'CLAUDECODE', 'AXIS_TOKEN', 'DATABASE_URL',
            'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'GH_TOKEN', 'GITHUB_TOKEN')
MAX_BODY = 12 * 1024 * 1024          # base64 screenshot ~1–2 MB, leave headroom
MAX_TEXT = 8000                      # system / prompt length limit

TOKEN = os.environ.get('AXIS_TOKEN', '')
MODEL_FAST = os.environ.get('AXIS_MODEL_FAST', 'sonnet')
MODEL_PRECISE = os.environ.get('AXIS_MODEL_PRECISE', 'opus')
TIMEOUT = int(os.environ.get('AXIS_TIMEOUT', 180))
ENGINE_FAST = float(os.environ.get('AXIS_ENGINE_FAST', 1.5))
ENGINE_PRECISE = float(os.environ.get('AXIS_ENGINE_PRECISE', 5))
SLOTS = threading.BoundedSemaphore(int(os.environ.get('AXIS_PARALLEL', 2)))
_busy = 0
_busy_lock = threading.Lock()


def log(msg: str) -> None:
    print(time.strftime('%Y-%m-%d %H:%M:%S'), msg, flush=True)


def claude_cmd() -> str:
    return os.environ.get('CLAUDE_CMD') or shutil.which('claude') or 'claude'


def clean_env() -> dict:
    env = {k: v for k, v in os.environ.items() if k not in DROP_ENV}
    env['PYTHONIOENCODING'] = 'utf-8'
    return env


def detect_media_type(raw: bytes) -> str | None:
    if raw.startswith(b'\xff\xd8\xff'):
        return 'image/jpeg'
    if raw.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'image/png'
    if raw[:4] == b'RIFF' and raw[8:12] == b'WEBP':
        return 'image/webp'
    return None


def parse_result(stdout: str) -> dict:
    """stream-json output: the line with type=result at the end."""
    for line in reversed(stdout.splitlines()):
        line = line.strip()
        if line.startswith('{'):
            try:
                data = json.loads(line)
            except json.JSONDecodeError:
                continue
            if data.get('type') == 'result':
                return data
    raise ValueError('no result in stream-json')


def run_claude(system: str, prompt: str, image_b64: str, media_type: str, precise: bool) -> dict:
    workdir = tempfile.mkdtemp(prefix='axis_')
    try:
        system_path = os.path.join(workdir, 'system.txt')
        with open(system_path, 'w', encoding='utf-8') as f:
            f.write(system)
        message = {'type': 'user', 'message': {'role': 'user', 'content': [
            {'type': 'image', 'source': {'type': 'base64', 'media_type': media_type, 'data': image_b64}},
            {'type': 'text', 'text': prompt},
        ]}}
        cmd = [claude_cmd(), '-p',
               '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
               '--system-prompt-file', system_path,
               '--tools', '',
               '--strict-mcp-config',
               '--setting-sources', 'project',
               '--no-session-persistence',
               '--model', MODEL_PRECISE if precise else MODEL_FAST]
        started = time.monotonic()
        try:
            p = subprocess.run(cmd, input=json.dumps(message, ensure_ascii=False) + '\n', cwd=workdir,
                               env=clean_env(), capture_output=True, text=True, encoding='utf-8',
                               errors='replace', timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            return {'ok': False, 'error': f'Claude не уложился в {TIMEOUT} с. Попробуйте ещё раз.'}
        except FileNotFoundError:
            return {'ok': False, 'error': 'На сервере не найден Claude Code (команда claude).'}
        ms = int((time.monotonic() - started) * 1000)
        try:
            payload = parse_result(p.stdout)
        except ValueError:
            tail = (p.stderr or p.stdout or '').strip()[-300:]
            log(f'claude failed rc={p.returncode}: {tail}')
            return {'ok': False, 'error': 'Claude Code не ответил: ' + (tail or f'код {p.returncode}')}
        text = (payload.get('result') or '').strip()
        log(f'answer ok={not payload.get("is_error")} {ms} ms, {len(text)} chars, precise={precise}')
        if payload.get('is_error') or not text:
            return {'ok': False, 'error': (text or payload.get('subtype') or 'пустой ответ')[:300]}
        return {'ok': True, 'text': text}
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    server_version = 'Axis/1'

    def log_message(self, fmt, *args):  # quieter default log, without query/body
        log(f'{self.client_address[0]} {fmt % args}')

    def _send(self, status: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self) -> bool:
        header = self.headers.get('Authorization', '')
        given = header[7:].strip() if header.lower().startswith('bearer ') else ''
        return bool(TOKEN) and hmac.compare_digest(given.encode(), TOKEN.encode())

    def do_GET(self):
        if self.path.rstrip('/') != '/v1/ping':
            return self._send(404, {'ok': False, 'error': 'not found'})
        if not self._authorized():
            return self._send(401, {'ok': False, 'error': 'Неверный токен доступа'})
        self._send(200, {'ok': True, 'busy': _busy})

    def _read_json(self) -> dict | None:
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
            self._send(413, {'ok': False, 'error': 'Слишком большой или пустой запрос'})
            return None
        try:
            body = json.loads(self.rfile.read(length))
            image_b64 = str(body['image'])
            body['_media_type'] = detect_media_type(base64.b64decode(image_b64, validate=True)[:16])
        except (ValueError, KeyError, TypeError, binascii.Error):
            self._send(400, {'ok': False, 'error': 'Неверный формат запроса'})
            return None
        if not body['_media_type']:
            self._send(400, {'ok': False, 'error': 'Изображение должно быть JPEG, PNG или WebP'})
            return None
        return body

    def do_POST(self):
        path = self.path.rstrip('/')
        if path not in ('/v1/analyze', '/v1/chess'):
            return self._send(404, {'ok': False, 'error': 'not found'})
        if not self._authorized():
            return self._send(401, {'ok': False, 'error': 'Неверный токен доступа'})
        if path == '/v1/chess' and not axis_chess.stockfish_path():
            return self._send(503, {'ok': False, 'code': 'no_engine', 'error': 'На сервере не установлен Stockfish'})
        body = self._read_json()
        if body is None:
            return
        precise = bool(body.get('precise'))

        with Slot() as ok:
            if not ok:
                return self._send(503, {'ok': False, 'error': 'Сервер занят, попробуйте позже'})
            if path == '/v1/chess':
                result = chess_answer(body['image'], body['_media_type'], precise)
            else:
                system = str(body.get('system') or '')[:MAX_TEXT]
                prompt = str(body.get('prompt') or 'Что на этом экране?')[:MAX_TEXT]
                result = run_claude(system, prompt, body['image'], body['_media_type'], precise)
        self._send(200 if result['ok'] else 502, result)


class Slot:
    """One of AXIS_PARALLEL answer slots; counts busy answers for /v1/ping."""

    def __enter__(self) -> bool:
        global _busy
        self.acquired = SLOTS.acquire(timeout=TIMEOUT)
        if self.acquired:
            with _busy_lock:
                _busy += 1
        return self.acquired

    def __exit__(self, *exc) -> None:
        global _busy
        if self.acquired:
            with _busy_lock:
                _busy -= 1
            SLOTS.release()


def chess_answer(image_b64: str, media_type: str, precise: bool) -> dict:
    """Claude reads the board (one retry with the problem named), Stockfish picks the move."""
    prompt = 'Перепиши позицию с этой доски.'
    problem = ''
    for attempt in range(2):
        rec = run_claude(axis_chess.RECOGNIZE_SYSTEM, prompt + problem, image_b64, media_type, precise)
        if not rec['ok']:
            return rec
        try:
            recognition = axis_chess.parse_recognition(rec['text'])
            started = time.monotonic()
            answer = axis_chess.solve(recognition, ENGINE_PRECISE if precise else ENGINE_FAST)
            log(f'chess: {answer["fen"]} → {answer["best"]} ({int((time.monotonic() - started) * 1000)} ms engine)')
            return answer
        except axis_chess.ChessError as e:
            log(f'chess: recognition problem (attempt {attempt + 1}): {e}')
            if str(e).startswith('На экране не видно') or attempt == 1:
                return {'ok': False, 'error': str(e)}
            problem = (f'\n\nПредыдущая попытка распознавания была неверной: {e}. '
                       'Посмотри на доску ещё раз внимательнее, клетку за клеткой.')
        except FileNotFoundError:
            return {'ok': False, 'code': 'no_engine', 'error': 'На сервере не установлен Stockfish'}
        except RuntimeError as e:
            return {'ok': False, 'error': f'Stockfish: {e}'}
    return {'ok': False, 'error': 'Не удалось распознать позицию'}


def main() -> None:
    ap = argparse.ArgumentParser(description='Axis server: screenshots → Claude Code subscription')
    ap.add_argument('--host', default=os.environ.get('AXIS_HOST', '127.0.0.1'))
    ap.add_argument('--port', type=int, default=int(os.environ.get('AXIS_PORT', 8787)))
    args = ap.parse_args()
    if len(TOKEN) < 24:
        sys.exit('Задайте AXIS_TOKEN (не короче 24 символов), например: export AXIS_TOKEN=$(openssl rand -hex 32)')
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    log(f'Axis server on http://{args.host}:{args.port} (claude: {claude_cmd()}, '
        f'models: {MODEL_FAST}/{MODEL_PRECISE})')
    srv.serve_forever()


if __name__ == '__main__':
    main()
