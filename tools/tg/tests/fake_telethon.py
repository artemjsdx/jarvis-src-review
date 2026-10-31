"""A small stand-in for the parts of Telethon 1.45 that tools/tg/login.py uses.

Behaviour is driven by the module-level SCENARIO dict, which each test resets. Nothing here talks to a network.
"""
import asyncio
import json
import sys
import types

SCENARIO = {}


def reset(**overrides):
    SCENARIO.clear()
    SCENARIO.update({
        "code": "12345", "expired": False, "password": None, "flood": None, "banned": False,
        "api_invalid": False, "network_down": False, "unauthorized": False,
        "session_out": "1" + "A" * 40, "qr_script": ["ok"], "qr_seen": [],
    })
    SCENARIO.update(overrides)


class RPCError(Exception):
    pass


class FloodWaitError(RPCError):
    def __init__(self, seconds):
        super().__init__(f"FLOOD_WAIT_{seconds}")
        self.seconds = seconds


def _rpc(name):
    return type(name, (RPCError,), {})


PhoneCodeInvalidError = _rpc("PhoneCodeInvalidError")
PhoneCodeExpiredError = _rpc("PhoneCodeExpiredError")
PasswordHashInvalidError = _rpc("PasswordHashInvalidError")
PhoneNumberBannedError = _rpc("PhoneNumberBannedError")
PhoneNumberInvalidError = _rpc("PhoneNumberInvalidError")
ApiIdInvalidError = _rpc("ApiIdInvalidError")
AuthKeyUnregisteredError = _rpc("AuthKeyUnregisteredError")
SessionRevokedError = _rpc("SessionRevokedError")
UnauthorizedError = _rpc("UnauthorizedError")
SessionPasswordNeededError = _rpc("SessionPasswordNeededError")


class StringSession:
    def __init__(self, string=None):
        self._string = string or ""
        self._dc = None
        self._key = None

    def set_dc(self, dc_id, server, port):
        self._dc = (dc_id, server, port)

    @property
    def auth_key(self):
        return self._key

    @auth_key.setter
    def auth_key(self, value):
        self._key = value

    def save(self):
        if self._dc is not None:
            return "1" + json.dumps({"dc": self._dc, "key": self._key}).encode().hex()
        return self._string


class SQLiteSession:
    """Reads the fake .session format: 'SQLite format 3\\0' followed by a JSON document."""

    def __init__(self, session_id):
        path = session_id if session_id.endswith(".session") else session_id + ".session"
        with open(path, "rb") as handle:
            raw = handle.read()
        doc = json.loads(raw[len(b"SQLite format 3\x00"):].decode())
        self.auth_key = doc.get("auth_key")
        self.dc_id = doc.get("dc_id")
        self.server_address = doc.get("server")
        self.port = doc.get("port", 443)

    def close(self):
        pass


class Me:
    def __init__(self):
        self.id = 42
        self.first_name = "Иван"
        self.last_name = ""
        self.username = "ivan"
        self.phone = "+79001234567"


class FakeQR:
    def __init__(self, client):
        self._client = client
        self.url = "tg://login?token=AAA0"
        self._n = 0

    async def recreate(self):
        self._n += 1
        self.url = f"tg://login?token=AAA{self._n}"

    async def wait(self, timeout=None):
        step = SCENARIO["qr_script"].pop(0) if SCENARIO["qr_script"] else "ok"
        SCENARIO["qr_seen"].append(step)
        if step == "hang":
            await asyncio.sleep(60)
        if step == "timeout":
            raise asyncio.TimeoutError()
        if step == "password":
            raise SessionPasswordNeededError("2FA")
        self._client.session = StringSession(SCENARIO["session_out"])


class FakeClient:
    def __init__(self, session, api_id, api_hash):
        self.session = session
        self.api_id = api_id
        self.api_hash = api_hash
        self.connected = False

    async def connect(self):
        if SCENARIO["network_down"]:
            raise OSError("network down")
        self.connected = True

    async def disconnect(self):
        self.connected = False

    async def send_code_request(self, phone):
        if SCENARIO["api_invalid"]:
            raise ApiIdInvalidError("API_ID_INVALID")
        if SCENARIO["flood"]:
            raise FloodWaitError(SCENARIO["flood"])
        if SCENARIO["banned"]:
            raise PhoneNumberBannedError("PHONE_NUMBER_BANNED")
        return types.SimpleNamespace(phone_code_hash="hash-1")

    async def sign_in(self, phone=None, code=None, password=None, phone_code_hash=None):
        if code is not None:
            if SCENARIO["expired"]:
                raise PhoneCodeExpiredError("PHONE_CODE_EXPIRED")
            if code != SCENARIO["code"]:
                raise PhoneCodeInvalidError("PHONE_CODE_INVALID")
            if SCENARIO["password"]:
                raise SessionPasswordNeededError("SESSION_PASSWORD_NEEDED")
        if password is not None and password != SCENARIO["password"]:
            raise PasswordHashInvalidError("PASSWORD_HASH_INVALID")
        self.session = StringSession(SCENARIO["session_out"])
        return Me()

    async def get_me(self):
        return Me()

    async def is_user_authorized(self):
        return not SCENARIO["unauthorized"]

    async def qr_login(self, ignored_ids=None):
        return FakeQR(self)


class TelegramClient(FakeClient):
    pass


def install():
    """Puts the fake into sys.modules so that `import telethon` in tools/tg/login.py resolves to it."""
    root = types.ModuleType("telethon")
    root.__version__ = "1.45.0-fake"
    root.TelegramClient = TelegramClient
    errors = types.ModuleType("telethon.errors")
    for name in ("RPCError", "FloodWaitError", "PhoneCodeInvalidError", "PhoneCodeExpiredError",
                 "PasswordHashInvalidError", "PhoneNumberBannedError", "PhoneNumberInvalidError",
                 "ApiIdInvalidError", "AuthKeyUnregisteredError", "SessionRevokedError", "UnauthorizedError",
                 "SessionPasswordNeededError"):
        setattr(errors, name, globals()[name])
    root.errors = errors
    sessions = types.ModuleType("telethon.sessions")
    sessions.StringSession = StringSession
    sessions.SQLiteSession = SQLiteSession
    sys.modules["telethon"] = root
    sys.modules["telethon.errors"] = errors
    sys.modules["telethon.sessions"] = sessions
    return root
