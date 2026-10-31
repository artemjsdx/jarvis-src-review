"""Local login helper for the Telegram MCP server. Listens on 127.0.0.1:8766 only.

The app talks to this helper (never to Telegram directly). Every request needs `Authorization: Bearer <token>`
where the token is read from ~/tg-mcp/helper.token. Codes, passwords and session strings live in memory or in the
0600 .env file and are never logged; responses carry a Russian `message` for the owner and a `technical` code.

Endpoints (JSON in/out):
  GET  /health
  GET  /accounts                      labels + names from accounts.json (no secrets)
  POST /start   {api_id, api_hash, phone, label}      sends the login code
  POST /code    {code}                                 completes the code step (may return state password_needed)
  POST /password {password}                            2FA password
  POST /qr/start {api_id, api_hash, label}             QR login; scan with another device
  GET  /qr/status                                      state of the QR login
  GET  /qr/image                                       PNG of the current QR link
  POST /import  {label, api_id?, api_hash?, string_session? | session_json? | session_file_b64?}
  POST /check   {label}                                get_me for one stored account
  POST /remove  {label}                                wipes one account from .env and accounts.json
  POST /rights  {rights: read|send|all}                 sets TELEGRAM_EXPOSED_TOOLS
  POST /restart                                        restarts the MCP server (run.sh restart)
"""
import asyncio
import base64
import hmac
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import TimeoutError as FutureTimeout
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import status as tg_status  # noqa: E402
import tg_common as tc  # noqa: E402

from telethon import TelegramClient, errors as tge  # noqa: E402
from telethon.sessions import SQLiteSession, StringSession  # noqa: E402

RUN_SH = tc.TG_HOME / "bin" / "run.sh"
ACCOUNTS_FILE = tc.TG_HOME / "accounts.json"
MAX_BODY = 8 * 1024 * 1024
STEP_TIMEOUT = 45
READ_ONLY_TOOLS = "read-only"
SEND_TOOLS = ["send_message", "reply_to_message", "send_file", "send_voice", "send_album", "send_sticker",
              "send_gif", "forward_message", "forward_messages", "edit_message", "send_reaction", "create_poll",
              "vote_poll", "mark_as_read", "send_typing", "save_draft"]
RIGHTS = {"read": READ_ONLY_TOOLS, "send": READ_ONLY_TOOLS + "+" + ",".join(SEND_TOOLS), "all": "all"}
PHONE_RE = re.compile(r"^\+?\d{7,15}$")
CODE_RE = re.compile(r"^\d{4,8}$")


class Failure(Exception):
    """A user-safe failure. `code` is machine-readable; `message` is Russian."""

    def __init__(self, code, message, technical="", **extra):
        super().__init__(message)
        self.code = code
        self.message = message
        self.technical = technical
        self.extra = extra


def map_error(exc):
    """Telethon exception -> Failure with a Russian message. Never includes the code, password or session."""
    if isinstance(exc, Failure):
        return exc
    if isinstance(exc, tge.FloodWaitError):
        return Failure("flood_wait", f"Telegram просит подождать {exc.seconds} с. Повторите позже.",
                       "FloodWaitError", seconds=exc.seconds)
    table = [
        (tge.PhoneCodeInvalidError, "wrong_code", "Неверный код. Проверьте сообщение от Telegram."),
        (tge.PhoneCodeExpiredError, "code_expired", "Код истёк. Запросите новый."),
        (tge.PasswordHashInvalidError, "wrong_password", "Неверный пароль двухэтапной проверки."),
        (tge.PhoneNumberBannedError, "phone_banned", "Этот номер заблокирован Telegram."),
        (tge.PhoneNumberInvalidError, "phone_invalid", "Номер телефона в неверном формате."),
        (tge.ApiIdInvalidError, "api_invalid", "api_id или api_hash не подходят. Проверьте на my.telegram.org/apps."),
        (tge.AuthKeyUnregisteredError, "not_authorized", "Сессия больше не действует. Войдите заново."),
        (tge.SessionRevokedError, "not_authorized", "Сессия отозвана в Telegram. Войдите заново."),
        (tge.UnauthorizedError, "not_authorized", "Аккаунт не авторизован. Войдите заново."),
        (tge.RPCError, "telegram_error", "Telegram вернул ошибку. Попробуйте позже."),
    ]
    for cls, code, message in table:
        if isinstance(exc, cls):
            return Failure(code, message, type(exc).__name__)
    if isinstance(exc, (OSError, asyncio.TimeoutError, FutureTimeout)):
        return Failure("network", "Нет связи с Telegram. Проверьте интернет и повторите.", type(exc).__name__)
    return Failure("internal", "Внутренняя ошибка помощника входа. Подробности в helper.log.",
                   type(exc).__name__ + ": " + str(exc)[:160])


def parse_phone(raw):
    value = re.sub(r"[\s\-()]", "", str(raw or ""))
    if not PHONE_RE.match(value):
        raise Failure("phone_format", "Введите номер в международном формате, например +79001234567.")
    return value if value.startswith("+") else "+" + value


def parse_api(api_id, api_hash):
    try:
        number = int(str(api_id).strip())
    except ValueError:
        raise Failure("api_format", "api_id — это число с my.telegram.org/apps.")
    hash_value = str(api_hash or "").strip()
    if not re.match(r"^[0-9a-fA-F]{32}$", hash_value):
        raise Failure("api_format", "api_hash — 32 символа (латиница и цифры) с my.telegram.org/apps.")
    return number, hash_value


def parse_label(raw):
    try:
        return tc.canonical_label(raw)
    except ValueError as e:
        raise Failure("label", str(e))


class Pending:
    """The one login in progress. Holds the Telethon client and the phone_code_hash until the flow ends."""

    def __init__(self, label, api_id, api_hash, phone, client):
        self.label = label
        self.api_id = api_id
        self.api_hash = api_hash
        self.phone = phone
        self.client = client
        self.phone_code_hash = None
        self.qr = None
        self.state = "new"
        self.error = None
        self.result = None
        self.task = None


class Service:
    def __init__(self, restart=None):
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, name="telethon", daemon=True).start()
        self.lock = threading.Lock()
        self.pending = None
        self.restart_hook = restart or self._restart_server

    # ---- plumbing -------------------------------------------------------------------------------------------
    def call(self, coro, timeout=STEP_TIMEOUT + 15):
        try:
            return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)
        except FutureTimeout:
            return {"ok": False, "code": "network", "message": "Telegram не ответил вовремя. Повторите.",
                    "technical": "timeout"}
        except Exception as e:
            return self._fail(map_error(e))

    def sync(self, fn):
        try:
            return fn()
        except Exception as e:
            return self._fail(map_error(e))

    @staticmethod
    def _fail(f):
        out = {"ok": False, "code": f.code, "message": f.message, "technical": f.technical}
        out.update(f.extra)
        return out

    def _restart_server(self):
        try:
            subprocess.run(["bash", str(RUN_SH), "restart"], timeout=30, capture_output=True)
            return True
        except (OSError, subprocess.SubprocessError):
            return False

    def _check_pending(self, label=None):
        if self.pending is None:
            raise Failure("no_login", "Сначала введите api_id, api_hash и номер телефона.")
        if label is not None and self.pending.label != label:
            raise Failure("no_login", "Этот вход начат для другого аккаунта.")
        return self.pending

    async def _drop_pending(self):
        p, self.pending = self.pending, None
        if p is not None:
            if p.task is not None:
                p.task.cancel()
            try:
                await p.client.disconnect()
            except Exception:
                pass

    # ---- storage --------------------------------------------------------------------------------------------
    def _me_dict(self, me, phone):
        name = " ".join(x for x in [getattr(me, "first_name", "") or "", getattr(me, "last_name", "") or ""] if x)
        tail = (getattr(me, "phone", None) or phone or "")[-4:]
        return {"id": getattr(me, "id", 0), "name": name, "username": getattr(me, "username", None) or "",
                "phone_tail": tail}

    def _store_account(self, label, session_string, api_id, api_hash, me):
        env = tc.read_env()
        if env.get("TELEGRAM_API_ID") and env["TELEGRAM_API_ID"] != str(api_id):
            raise Failure("api_mismatch", "Все аккаунты используют один api_id. Этот отличается от сохранённого.")
        if env.get("TELEGRAM_API_HASH") and env["TELEGRAM_API_HASH"] != api_hash:
            raise Failure("api_mismatch", "Все аккаунты используют один api_hash. Этот отличается от сохранённого.")
        with self.lock:
            tc.write_env({tc.session_key(label): session_string, "TELEGRAM_API_ID": str(api_id),
                          "TELEGRAM_API_HASH": api_hash})
            data = self._read_accounts()
            data[label] = dict(me, added_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
            self._write_accounts(data)
        restarted = self.restart_hook()
        return {"ok": True, "label": label, "me": me, "restarted": bool(restarted)}

    @staticmethod
    def _read_accounts():
        if not ACCOUNTS_FILE.exists():
            return {}
        try:
            return json.loads(ACCOUNTS_FILE.read_text(encoding="utf-8"))
        except ValueError:
            return {}

    @staticmethod
    def _write_accounts(data):
        ACCOUNTS_FILE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        tmp = ACCOUNTS_FILE.with_suffix(".tmp")
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=1)
        os.replace(tmp, ACCOUNTS_FILE)

    async def _finish(self, p, me):
        session_string = p.client.session.save()
        result = self._store_account(p.label, session_string, p.api_id, p.api_hash, self._me_dict(me, p.phone))
        await p.client.disconnect()
        return result

    # ---- code login -----------------------------------------------------------------------------------------
    async def _start(self, body):
        label = parse_label(body.get("label"))
        api_id, api_hash = parse_api(body.get("api_id"), body.get("api_hash"))
        phone = parse_phone(body.get("phone"))
        await self._drop_pending()
        client = TelegramClient(StringSession(), api_id, api_hash)
        await asyncio.wait_for(client.connect(), STEP_TIMEOUT)
        sent = await asyncio.wait_for(client.send_code_request(phone), STEP_TIMEOUT)
        p = Pending(label, api_id, api_hash, phone, client)
        p.phone_code_hash = sent.phone_code_hash
        p.state = "code_sent"
        self.pending = p
        return {"ok": True, "state": "code_sent", "label": label,
                "message": "Код отправлен в Telegram на ваш телефон или в приложение Telegram."}

    async def _code(self, body):
        p = self._check_pending()
        code = re.sub(r"[\s-]", "", str(body.get("code") or ""))
        if not CODE_RE.match(code):
            raise Failure("code_format", "Введите код из Telegram (цифры).")
        if p.state != "code_sent":
            raise Failure("no_login", "Сначала запросите код.")
        try:
            me = await asyncio.wait_for(p.client.sign_in(phone=p.phone, code=code,
                                                         phone_code_hash=p.phone_code_hash), STEP_TIMEOUT)
        except tge.SessionPasswordNeededError:
            p.state = "password_needed"
            return {"ok": True, "state": "password_needed",
                    "message": "Включена двухэтапная проверка. Введите пароль."}
        result = await self._finish(p, me)
        self.pending = None
        return result

    async def _password(self, body):
        p = self._check_pending()
        password = str(body.get("password") or "")
        if not password or len(password) > 512:
            raise Failure("password_format", "Введите пароль двухэтапной проверки.")
        if p.state not in ("password_needed", "qr_password_needed"):
            raise Failure("no_login", "Пароль пока не нужен.")
        me = await asyncio.wait_for(p.client.sign_in(password=password), STEP_TIMEOUT)
        result = await self._finish(p, me)
        self.pending = None
        return result

    # ---- QR login -------------------------------------------------------------------------------------------
    async def _qr_start(self, body):
        label = parse_label(body.get("label"))
        api_id, api_hash = parse_api(body.get("api_id"), body.get("api_hash"))
        await self._drop_pending()
        client = TelegramClient(StringSession(), api_id, api_hash)
        await asyncio.wait_for(client.connect(), STEP_TIMEOUT)
        qr = await asyncio.wait_for(client.qr_login(), STEP_TIMEOUT)
        p = Pending(label, api_id, api_hash, "", client)
        p.qr = qr
        p.state = "qr_pending"
        self.pending = p
        p.task = asyncio.ensure_future(self._qr_watch(p))
        return {"ok": True, "state": "qr_pending", "label": label,
                "message": "Откройте Telegram на другом устройстве: Настройки → Устройства → Подключить устройство."}

    async def _qr_watch(self, p):
        """Waits for the scan; the QR link expires about every 30 s, so it is renewed for up to ~20 min."""
        try:
            for _ in range(40):
                try:
                    await p.qr.wait(timeout=30)
                except asyncio.TimeoutError:
                    await p.qr.recreate()
                    continue
                me = await p.client.get_me()
                p.result = await self._finish(p, me)
                p.state = "ok"
                return
            p.state = "expired"
            p.error = Failure("qr_expired", "QR-код не отсканировали за 20 минут. Начните заново.")
        except tge.SessionPasswordNeededError:
            p.state = "qr_password_needed"
        except asyncio.CancelledError:
            raise
        except Exception as e:
            p.state = "error"
            p.error = map_error(e)

    def _qr_status(self):
        p = self.pending
        if p is None or p.qr is None:
            return {"ok": False, "code": "no_login", "message": "QR-вход не начат.", "technical": "no_qr"}
        out = {"ok": True, "state": p.state, "label": p.label}
        if p.state == "qr_pending":
            out["url"] = p.qr.url
        if p.state == "qr_password_needed":
            out["message"] = "Включена двухэтапная проверка. Введите пароль."
        if p.state == "ok":
            out.update(p.result)
            self.pending = None
        if p.state in ("expired", "error"):
            out = dict(self._fail(p.error), state=p.state)
        return out

    def qr_png(self):
        import qrcode
        p = self.pending
        if p is None or p.qr is None or p.state != "qr_pending":
            return None
        buf = io.BytesIO()
        qrcode.make(p.qr.url).save(buf, format="PNG")
        return buf.getvalue()

    # ---- import ---------------------------------------------------------------------------------------------
    async def _import(self, body):
        label = parse_label(body.get("label"))
        api_id, api_hash = body.get("api_id"), body.get("api_hash")
        session_string = str(body.get("string_session") or "").strip()
        if body.get("session_json") is not None:
            doc = body["session_json"]
            if isinstance(doc, str):
                doc = json.loads(doc)
            if not isinstance(doc, dict):
                raise Failure("import_format", "JSON сессии должен быть объектом.")
            api_id = api_id or doc.get("api_id")
            api_hash = api_hash or doc.get("api_hash")
            if not session_string:
                for key in ("string_session", "session_string", "session"):
                    value = doc.get(key)
                    if isinstance(value, str) and value.startswith("1") and len(value) > 40:
                        session_string = value.strip()
                        break
        if body.get("session_file_b64"):
            session_string = self._session_file_to_string(body["session_file_b64"])
        if not session_string:
            raise Failure("import_format", "Нет данных сессии: строка Telethon, JSON или файл .session.")
        if api_id is None or not api_hash:
            raise Failure("import_format", "Для сессии нужны api_id и api_hash (с my.telegram.org/apps).")
        api_id, api_hash = parse_api(api_id, api_hash)
        if not re.match(r"^1[A-Za-z0-9+/=_-]{20,}$", session_string):
            raise Failure("import_format", "Строка сессии не похожа на сессию Telethon.")
        client = TelegramClient(StringSession(session_string), api_id, api_hash)
        try:
            await asyncio.wait_for(client.connect(), STEP_TIMEOUT)
            if not await client.is_user_authorized():
                raise Failure("not_authorized", "Сессия не авторизована. Войдите заново.")
            me = await client.get_me()
            stored = client.session.save()
        finally:
            await client.disconnect()
        return self._store_account(label, stored, api_id, api_hash, self._me_dict(me, ""))

    @staticmethod
    def _session_file_to_string(b64):
        try:
            raw = base64.b64decode(b64, validate=True)
        except ValueError:
            raise Failure("import_format", "Файл сессии не читается (ожидается base64).")
        if not raw.startswith(b"SQLite format 3"):
            raise Failure("import_format", "Это не файл сессии Telethon (.session).")
        with tempfile.TemporaryDirectory(dir=str(tc.TG_HOME)) as tmp:
            base = os.path.join(tmp, "import")
            with open(base + ".session", "wb") as handle:
                os.fchmod(handle.fileno(), 0o600)
                handle.write(raw)
            sess = SQLiteSession(base)
            if sess.auth_key is None or sess.dc_id is None:
                raise Failure("import_format", "В файле .session нет ключа авторизации.")
            out = StringSession()
            out.set_dc(sess.dc_id, sess.server_address, sess.port)
            out.auth_key = sess.auth_key
            sess.close()
            return out.save()

    # ---- accounts -------------------------------------------------------------------------------------------
    def accounts(self):
        env = tc.read_env()
        meta = self._read_accounts()
        labels = tc.accounts(env)
        return {"ok": True, "rights": env.get("TELEGRAM_EXPOSED_TOOLS", "all"),
                "accounts": [dict(label=label, **{k: v for k, v in meta.get(label, {}).items() if k != "id"})
                             for label in labels]}

    async def _check(self, body):
        label = parse_label(body.get("label"))
        env = tc.read_env()
        value = env.get(tc.session_key(label), "")
        if not value:
            raise Failure("no_account", "Такого аккаунта нет.")
        if not env.get("TELEGRAM_API_ID") or not env.get("TELEGRAM_API_HASH"):
            raise Failure("api_missing", "В .env нет api_id и api_hash.")
        client = TelegramClient(StringSession(value), int(env["TELEGRAM_API_ID"]), env["TELEGRAM_API_HASH"])
        try:
            await asyncio.wait_for(client.connect(), STEP_TIMEOUT)
            if not await client.is_user_authorized():
                raise Failure("not_authorized", "Сессия не авторизована. Войдите заново.")
            me = self._me_dict(await client.get_me(), "")
        finally:
            await client.disconnect()
        with self.lock:
            data = self._read_accounts()
            data[label] = dict(data.get(label, {}), **me)
            self._write_accounts(data)
        return {"ok": True, "label": label, "me": me}

    def _remove(self, body):
        label = parse_label(body.get("label"))
        with self.lock:
            tc.write_env({}, remove=(tc.session_key(label),))
            data = self._read_accounts()
            data.pop(label, None)
            self._write_accounts(data)
        return {"ok": True, "label": label, "restarted": bool(self.restart_hook())}

    def _server(self, body):
        action = str(body.get("action") or "")
        if action not in ("start", "stop"):
            raise Failure("server_action", "Действие сервера: start или stop.")
        done = subprocess.run(["bash", str(RUN_SH), action], timeout=30, capture_output=True, text=True)
        return {"ok": done.returncode == 0, "action": action, "message": done.stdout.strip()[-200:]}

    def _rights(self, body):
        mode = str(body.get("rights") or "")
        if mode not in RIGHTS:
            raise Failure("rights", "Права: read, send или all.")
        with self.lock:
            tc.write_env({"TELEGRAM_EXPOSED_TOOLS": RIGHTS[mode]})
        return {"ok": True, "rights": RIGHTS[mode], "restarted": bool(self.restart_hook())}


class Handler(BaseHTTPRequestHandler):
    server_version = "tg-helper"
    service = None
    token = ""

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, self.path.split("?")[0]))

    def _send(self, status, payload, ctype="application/json"):
        data = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        return header.startswith("Bearer ") and hmac.compare_digest(header[7:].strip(), self.token)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            raise Failure("too_big", "Файл слишком большой для входа (до 8 МБ).")
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8") or "{}")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            return self._send(200, {"ok": True, "service": "tg-login-helper"})
        if not self._authorized():
            return self._send(401, {"ok": False, "code": "unauthorized", "message": "Нет доступа к помощнику."})
        svc = self.service
        if path == "/accounts":
            return self._send(200, svc.accounts())
        if path == "/status":
            return self._send(200, svc.sync(lambda: dict(ok=True, **tg_status.collect())))
        if path == "/qr/status":
            return self._send(200, svc.sync(svc._qr_status))
        if path == "/qr/image":
            png = svc.qr_png()
            if png is None:
                return self._send(404, {"ok": False, "code": "no_qr", "message": "QR-код пока не готов."})
            return self._send(200, png, "image/png")
        return self._send(404, {"ok": False, "code": "not_found", "message": "Нет такого действия."})

    def do_POST(self):
        path = self.path.split("?")[0]
        if not self._authorized():
            return self._send(401, {"ok": False, "code": "unauthorized", "message": "Нет доступа к помощнику."})
        svc = self.service
        try:
            body = self._body()
        except (ValueError, Failure) as e:
            msg = e.message if isinstance(e, Failure) else "Тело запроса не JSON."
            return self._send(400, {"ok": False, "code": "bad_request", "message": msg})
        routes = {
            "/start": lambda: svc.call(svc._start(body)),
            "/code": lambda: svc.call(svc._code(body)),
            "/password": lambda: svc.call(svc._password(body)),
            "/qr/start": lambda: svc.call(svc._qr_start(body)),
            "/import": lambda: svc.call(svc._import(body)),
            "/check": lambda: svc.call(svc._check(body)),
            "/remove": lambda: svc.sync(lambda: svc._remove(body)),
            "/rights": lambda: svc.sync(lambda: svc._rights(body)),
            "/restart": lambda: svc.sync(lambda: {"ok": True, "restarted": bool(svc.restart_hook())}),
            "/server": lambda: svc.sync(lambda: svc._server(body)),
        }
        if path not in routes:
            return self._send(404, {"ok": False, "code": "not_found", "message": "Нет такого действия."})
        result = routes[path]()
        return self._send(200 if result.get("ok") else 422, result)


def make_server(service, token, host=tc.HELPER_HOST, port=tc.HELPER_PORT):
    if host != "127.0.0.1":
        raise ValueError("the login helper binds to loopback only")
    handler = type("BoundHandler", (Handler,), {"service": service, "token": token})
    return ThreadingHTTPServer((host, port), handler)


def main():
    token = tc.read_token()
    if not token:
        print("helper.token is missing; run setup.sh first", file=sys.stderr)
        return 1
    server = make_server(Service(), token)
    print(f"[helper] listening on {tc.HELPER_HOST}:{tc.HELPER_PORT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
