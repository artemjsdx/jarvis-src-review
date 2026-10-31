"""Login helper tests against a fake Telethon. Run: python3 -m unittest discover -s tools/tg/tests -v"""
import base64
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from contextlib import redirect_stderr
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import fake_telethon  # noqa: E402

fake_telethon.install()
TMP = tempfile.mkdtemp(prefix="tg-tests-")
os.environ["TG_HOME"] = TMP

import tg_common as tc  # noqa: E402
import login  # noqa: E402

TOKEN = "test-token-123"
SESSION_IN = "1" + "B" * 60


def call(port, method, path, body=None, token=TOKEN, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method=method)
    if token is not None:
        req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, resp.read(), resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        with e:
            return e.code, e.read(), e.headers.get("Content-Type", "")


def jcall(port, method, path, body=None, token=TOKEN):
    status, raw, _ = call(port, method, path, body, token)
    return status, json.loads(raw.decode() or "{}")


class HelperTestCase(unittest.TestCase):
    def setUp(self):
        fake_telethon.reset()
        self.restarts = []
        self.service = login.Service(restart=lambda: (self.restarts.append(1), True)[1])
        self.server = login.make_server(self.service, TOKEN, port=0)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        if tc.ENV_FILE.exists():
            tc.ENV_FILE.unlink()
        if login.ACCOUNTS_FILE.exists():
            login.ACCOUNTS_FILE.unlink()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def start_body(self, label="work", **extra):
        body = {"api_id": "12345", "api_hash": "0123456789abcdef0123456789abcdef", "phone": "+7 900 123-45-67",
                "label": label}
        body.update(extra)
        return body


class AuthAndRoutes(HelperTestCase):
    def test_health_is_open_everything_else_needs_the_token(self):
        self.assertEqual(jcall(self.port, "GET", "/health", token=None)[0], 200)
        self.assertEqual(jcall(self.port, "POST", "/start", self.start_body(), token=None)[0], 401)
        self.assertEqual(jcall(self.port, "POST", "/start", self.start_body(), token="wrong")[0], 401)
        self.assertEqual(jcall(self.port, "GET", "/accounts", token=None)[0], 401)

    def test_unknown_route_is_404(self):
        self.assertEqual(jcall(self.port, "POST", "/nope", {})[0], 404)

    def test_bad_json_is_400(self):
        status, _, _ = call(self.port, "POST", "/start", raw=b"{not json")
        self.assertEqual(status, 400)

    def test_binds_to_loopback_only(self):
        with self.assertRaises(ValueError):
            login.make_server(self.service, TOKEN, host="0.0.0.0", port=0)


class CodeFlow(HelperTestCase):
    def test_code_login_stores_session_and_restarts_once(self):
        status, out = jcall(self.port, "POST", "/start", self.start_body())
        self.assertEqual((status, out["state"]), (200, "code_sent"))
        status, out = jcall(self.port, "POST", "/code", {"code": "1 2-345"})
        self.assertEqual(status, 200, out)
        self.assertEqual(out["label"], "work")
        self.assertEqual(out["me"]["phone_tail"], "4567")
        self.assertNotIn("+79001234567", json.dumps(out))
        self.assertTrue(out["restarted"])
        self.assertEqual(len(self.restarts), 1)

        env = tc.read_env()
        self.assertEqual(env[tc.session_key("work")], fake_telethon.SCENARIO["session_out"])
        self.assertEqual(env["TELEGRAM_API_ID"], "12345")
        self.assertEqual(oct(os.stat(tc.ENV_FILE).st_mode & 0o777), "0o600")
        self.assertIn("TELEGRAM_SESSION_STRING_WORK", tc.ENV_FILE.read_text())
        listing = jcall(self.port, "GET", "/accounts")[1]
        self.assertEqual([a["label"] for a in listing["accounts"]], ["work"])
        self.assertNotIn("+7900", json.dumps(listing))

    def test_two_factor_asks_for_password_then_stores(self):
        fake_telethon.reset(password="s3cret-pass")
        jcall(self.port, "POST", "/start", self.start_body())
        status, out = jcall(self.port, "POST", "/code", {"code": "12345"})
        self.assertEqual((status, out["state"]), (200, "password_needed"))
        status, out = jcall(self.port, "POST", "/password", {"password": "wrong"})
        self.assertEqual((status, out["code"]), (422, "wrong_password"))
        self.assertIn("пароль", out["message"])
        status, out = jcall(self.port, "POST", "/password", {"password": "s3cret-pass"})
        self.assertEqual((status, out["ok"]), (200, True))

    def test_wrong_and_expired_codes_are_mapped(self):
        jcall(self.port, "POST", "/start", self.start_body())
        status, out = jcall(self.port, "POST", "/code", {"code": "00000"})
        self.assertEqual((status, out["code"]), (422, "wrong_code"))
        fake_telethon.reset(expired=True)
        jcall(self.port, "POST", "/start", self.start_body())
        status, out = jcall(self.port, "POST", "/code", {"code": "12345"})
        self.assertEqual(out["code"], "code_expired")

    def test_flood_wait_reports_the_exact_seconds(self):
        fake_telethon.reset(flood=321)
        status, out = jcall(self.port, "POST", "/start", self.start_body())
        self.assertEqual((status, out["code"], out["seconds"]), (422, "flood_wait", 321))
        self.assertIn("321", out["message"])

    def test_banned_number_and_bad_api_id(self):
        fake_telethon.reset(banned=True)
        self.assertEqual(jcall(self.port, "POST", "/start", self.start_body())[1]["code"], "phone_banned")
        fake_telethon.reset(api_invalid=True)
        self.assertEqual(jcall(self.port, "POST", "/start", self.start_body())[1]["code"], "api_invalid")

    def test_network_failure_is_user_safe(self):
        fake_telethon.reset(network_down=True)
        out = jcall(self.port, "POST", "/start", self.start_body())[1]
        self.assertEqual(out["code"], "network")
        self.assertIn("Нет связи", out["message"])

    def test_input_validation_before_telegram_is_called(self):
        self.assertEqual(jcall(self.port, "POST", "/start", self.start_body(phone="abc"))[1]["code"], "phone_format")
        self.assertEqual(jcall(self.port, "POST", "/start", self.start_body(api_hash="short"))[1]["code"],
                         "api_format")
        self.assertEqual(jcall(self.port, "POST", "/start", self.start_body(label="Bad Label!"))[1]["code"], "label")
        jcall(self.port, "POST", "/start", self.start_body())
        self.assertEqual(jcall(self.port, "POST", "/code", {"code": "abc"})[1]["code"], "code_format")

    def test_code_without_start_is_refused(self):
        self.assertEqual(jcall(self.port, "POST", "/code", {"code": "12345"})[1]["code"], "no_login")

    def test_api_id_must_match_the_stored_one(self):
        jcall(self.port, "POST", "/start", self.start_body())
        jcall(self.port, "POST", "/code", {"code": "12345"})
        jcall(self.port, "POST", "/start", self.start_body(label="second", api_id="99999"))
        out = jcall(self.port, "POST", "/code", {"code": "12345"})[1]
        self.assertEqual(out["code"], "api_mismatch")


class QrFlow(HelperTestCase):
    def test_qr_renews_expired_links_then_stores(self):
        fake_telethon.reset(qr_script=["timeout", "timeout", "ok"])
        status, out = jcall(self.port, "POST", "/qr/start", self.start_body())
        self.assertEqual((status, out["state"]), (200, "qr_pending"))
        for _ in range(100):
            out = jcall(self.port, "GET", "/qr/status")[1]
            if out.get("state") == "ok":
                break
        self.assertEqual(out["state"], "ok", out)
        self.assertEqual(out["label"], "work")
        self.assertEqual(fake_telethon.SCENARIO["qr_seen"], ["timeout", "timeout", "ok"])
        self.assertEqual(tc.read_env()[tc.session_key("work")], fake_telethon.SCENARIO["session_out"])

    def test_qr_status_reports_state_and_link(self):
        fake_telethon.reset(qr_script=["hang"])
        jcall(self.port, "POST", "/qr/start", self.start_body())
        out = jcall(self.port, "GET", "/qr/status")[1]
        self.assertEqual(out["state"], "qr_pending")
        self.assertTrue(out["url"].startswith("tg://login?token="))

    def test_qr_needing_password_then_password_completes(self):
        fake_telethon.reset(qr_script=["password"], password="pw9")
        jcall(self.port, "POST", "/qr/start", self.start_body())
        for _ in range(100):
            out = jcall(self.port, "GET", "/qr/status")[1]
            if out.get("state") == "qr_password_needed":
                break
        self.assertEqual(out["state"], "qr_password_needed")
        status, out = jcall(self.port, "POST", "/password", {"password": "pw9"})
        self.assertEqual(status, 200, out)
        self.assertEqual(out["label"], "work")

    def test_qr_image_is_png_when_qrcode_is_installed(self):
        try:
            import qrcode  # noqa: F401
        except ImportError:
            self.skipTest("qrcode is not installed in this interpreter (the venv has it)")
        fake_telethon.reset(qr_script=["hang"])
        jcall(self.port, "POST", "/qr/start", self.start_body())
        status, raw, ctype = call(self.port, "GET", "/qr/image")
        self.assertEqual((status, ctype), (200, "image/png"))
        self.assertEqual(raw[:8], b"\x89PNG\r\n\x1a\n")


class ImportFormats(HelperTestCase):
    def test_plain_string_session_is_validated_and_stored(self):
        status, out = jcall(self.port, "POST", "/import", {
            "label": "imp", "api_id": 12345, "api_hash": "0123456789abcdef0123456789abcdef",
            "string_session": SESSION_IN})
        self.assertEqual(status, 200, out)
        self.assertEqual(out["label"], "imp")
        self.assertEqual(tc.read_env()[tc.session_key("imp")], SESSION_IN)

    def test_json_with_session_string(self):
        doc = {"api_id": 12345, "api_hash": "0123456789abcdef0123456789abcdef", "phone": "+79001234567",
               "session": SESSION_IN}
        out = jcall(self.port, "POST", "/import", {"label": "j", "session_json": doc})[1]
        self.assertTrue(out["ok"], out)

    def test_session_file_from_tdata_style_pair(self):
        doc = {"auth_key": "AB" * 8, "dc_id": 2, "server": "149.154.167.51", "port": 443}
        raw = b"SQLite format 3\x00" + json.dumps(doc).encode()
        body = {"label": "file", "api_id": 12345, "api_hash": "0123456789abcdef0123456789abcdef",
                "session_file_b64": base64.b64encode(raw).decode()}
        out = jcall(self.port, "POST", "/import", body)[1]
        self.assertTrue(out["ok"], out)
        self.assertTrue(out["restarted"])

    def test_not_a_session_file_is_refused(self):
        body = {"label": "x", "api_id": 1, "api_hash": "0123456789abcdef0123456789abcdef",
                "session_file_b64": base64.b64encode(b"hello").decode()}
        self.assertEqual(jcall(self.port, "POST", "/import", body)[1]["code"], "import_format")

    def test_missing_api_or_data_is_refused(self):
        self.assertEqual(jcall(self.port, "POST", "/import", {"label": "x", "string_session": SESSION_IN})[1]["code"],
                         "import_format")
        self.assertEqual(jcall(self.port, "POST", "/import", {"label": "x", "api_id": 1,
                                                              "api_hash": "0" * 32})[1]["code"], "import_format")
        self.assertEqual(jcall(self.port, "POST", "/import", {"label": "x", "api_id": 1,
                                                              "api_hash": "0" * 32,
                                                              "string_session": "nope"})[1]["code"], "import_format")

    def test_unauthorized_session_is_rejected(self):
        fake_telethon.reset(unauthorized=True)
        out = jcall(self.port, "POST", "/import", {"label": "x", "api_id": 1, "api_hash": "0" * 32,
                                                   "string_session": SESSION_IN})[1]
        self.assertEqual(out["code"], "not_authorized")


class AccountsAndRights(HelperTestCase):
    def test_rights_remove_and_check(self):
        jcall(self.port, "POST", "/import", {"label": "keep", "api_id": 12345,
                                             "api_hash": "0123456789abcdef0123456789abcdef",
                                             "string_session": SESSION_IN})
        out = jcall(self.port, "POST", "/rights", {"rights": "send"})[1]
        self.assertTrue(out["rights"].startswith("read-only+") and "send_message" in out["rights"], out)
        self.assertEqual(jcall(self.port, "POST", "/rights", {"rights": "everything"})[1]["code"], "rights")
        self.assertEqual(jcall(self.port, "POST", "/rights", {"rights": "all"})[1]["rights"], "all")
        check = jcall(self.port, "POST", "/check", {"label": "keep"})[1]
        self.assertTrue(check["ok"])
        self.assertEqual(jcall(self.port, "POST", "/check", {"label": "missing"})[1]["code"], "no_account")
        removed = jcall(self.port, "POST", "/remove", {"label": "keep"})[1]
        self.assertTrue(removed["ok"])
        self.assertNotIn(tc.session_key("keep"), tc.read_env())
        self.assertEqual(jcall(self.port, "GET", "/accounts")[1]["accounts"], [])

    def test_secret_values_never_reach_stderr_or_responses(self):
        fake_telethon.reset(password="hunter2-pass")
        captured = io.StringIO()
        with redirect_stderr(captured):
            jcall(self.port, "POST", "/start", self.start_body())
            _, a, _ = call(self.port, "POST", "/code", {"code": "12345"})
            _, b, _ = call(self.port, "POST", "/password", {"password": "hunter2-pass"})
        text = captured.getvalue()
        blob = (a + b).decode()
        for secret in ("12345", "hunter2-pass", fake_telethon.SCENARIO["session_out"], "0123456789abcdef0123456789abcdef"):
            self.assertNotIn(secret, text)
            self.assertNotIn(secret, blob)


class ServerAndStatusRoutes(HelperTestCase):
    def test_status_reports_without_secrets(self):
        tc.write_env({tc.session_key("work"): "1" + "Z" * 50})
        status, out = jcall(self.port, "GET", "/status")
        self.assertEqual(status, 200)
        self.assertEqual(out["accounts"], ["work"])
        self.assertNotIn("Z" * 50, json.dumps(out))

    def test_server_action_is_restricted(self):
        out = jcall(self.port, "POST", "/server", {"action": "rm -rf"})[1]
        self.assertEqual(out["code"], "server_action")


class EnvFile(unittest.TestCase):
    def setUp(self):
        self.path = Path(tempfile.mkdtemp()) / ".env"

    def test_write_keeps_other_lines_and_sets_mode(self):
        self.path.write_text("# comment\nMCP_PORT=8765\nTELEGRAM_EXPOSED_TOOLS=all\n", encoding="utf-8")
        tc.write_env({"TELEGRAM_EXPOSED_TOOLS": "read-only", "TELEGRAM_SESSION_STRING_A": "x"}, path=self.path)
        text = self.path.read_text(encoding="utf-8")
        self.assertIn("# comment", text)
        self.assertIn("MCP_PORT=8765", text)
        self.assertIn("TELEGRAM_EXPOSED_TOOLS=read-only", text)
        self.assertNotIn("TELEGRAM_EXPOSED_TOOLS=all", text)
        self.assertEqual(oct(os.stat(self.path).st_mode & 0o777), "0o600")

    def test_remove_and_accounts(self):
        tc.write_env({"TELEGRAM_SESSION_STRING_A": "1abc", "TELEGRAM_SESSION_STRING_B": "1def"}, path=self.path)
        tc.write_env({}, remove=("TELEGRAM_SESSION_STRING_A",), path=self.path)
        env = tc.read_env(self.path)
        self.assertEqual(tc.accounts(env), ["b"])

    def test_labels_fold_like_upstream(self):
        self.assertEqual(tc.canonical_label(" My-Work "), "my_work")
        self.assertEqual(tc.session_key("My-Work"), "TELEGRAM_SESSION_STRING_MY_WORK")
        with self.assertRaises(ValueError):
            tc.canonical_label("1bad")


class StatusRender(unittest.TestCase):
    def test_status_collect_without_server_reports_stopped_and_no_secrets(self):
        import status
        tc.write_env({tc.session_key("work"): "1SECRETVALUE" * 4, "TELEGRAM_API_ID": "1",
                      "TELEGRAM_API_HASH": "0" * 32, "TELEGRAM_EXPOSED_TOOLS": "read-only"})
        s = status.collect()
        self.assertEqual(s["accounts"], ["work"])
        self.assertFalse(s["mcp_ok"])
        self.assertEqual(s["exposed_tools"], "read-only")
        self.assertNotIn("SECRETVALUE", json.dumps(s))
        self.assertNotIn("SECRETVALUE", status.render(s))


if __name__ == "__main__":
    unittest.main()
