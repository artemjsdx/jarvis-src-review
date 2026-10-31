#!/usr/bin/env python3
"""Unit tests for tools/mcp-smoke.py: the `requires` mechanism, skip reasons and scenario validation. No phone, no bridge.

Run: python3 tests/mcp/test_smoke_runner.py   (exit code != 0 on any failure)
"""
import importlib.machinery
import importlib.util
import json
import os
import contextlib
import io
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SMOKE = os.path.join(ROOT, "tools", "mcp-smoke.py")


def load_smoke():
    loader = importlib.machinery.SourceFileLoader("mcp_smoke", SMOKE)
    spec = importlib.util.spec_from_loader("mcp_smoke", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


smoke = load_smoke()


class FakeCli:
    """Answers tools/call with canned JSON in the MCP result shape; records the calls."""

    def __init__(self, answers):
        self.answers = answers
        self.calls = []

    def call(self, name, args, timeout=140):
        self.calls.append(name)
        body = self.answers.get(name, {})
        return {"content": [{"type": "text", "text": json.dumps(body)}], "isError": name not in self.answers}


def bridge(allow_destructive=False, allow_audio=False, secrets=()):
    return {
        "app_info": {"mcp": {"allowDestructive": allow_destructive, "allowAudio": allow_audio}},
        "secrets_list": {"count": len(secrets), "secrets": [{"name": n, "last4": "", "length": 1} for n in secrets]},
    }


def runner(answers, jvm=False, lines=None):
    cli = FakeCli(answers)
    return smoke.Runner(cli, "http://127.0.0.1:1", "", {"ui_tree"}, None, jvm, lines if lines is not None else []), cli


class RequirementTests(unittest.TestCase):
    def test_destructive_needs_the_setting(self):
        r, _ = runner(bridge(allow_destructive=False))
        self.assertIn("mcp.allowDestructive", r.requirement_reason("destructive"))
        r, _ = runner(bridge(allow_destructive=True))
        self.assertIsNone(r.requirement_reason("destructive"))

    def test_audio_needs_the_setting(self):
        r, _ = runner(bridge(allow_audio=False))
        self.assertIn("mcp.allowAudio", r.requirement_reason("audio"))
        r, _ = runner(bridge(allow_audio=True))
        self.assertIsNone(r.requirement_reason("audio"))

    def test_key_checks_the_secret_name_with_key_prefix(self):
        r, _ = runner(bridge(secrets=["key.openrouter"]))
        self.assertIn("elevenlabs", r.requirement_reason("key:elevenlabs"))
        self.assertIn("key.elevenlabs", r.requirement_reason("key:elevenlabs"))
        r, _ = runner(bridge(secrets=["key.openrouter", "key.elevenlabs"]))
        self.assertIsNone(r.requirement_reason("key:elevenlabs"))

    def test_seeded_turns_only_on_the_jvm_fake(self):
        r, _ = runner(bridge(), jvm=True)
        self.assertIsNone(r.requirement_reason("seeded-turns"))
        r, _ = runner(bridge(), jvm=False)
        self.assertIn("--jvm", r.requirement_reason("seeded-turns"))

    def test_unknown_requirement_is_an_error_not_a_pass(self):
        r, _ = runner(bridge())
        with self.assertRaises(ValueError):
            r.requirement_reason("destructiv")

    def test_state_is_read_once(self):
        r, cli = runner(bridge(allow_destructive=True, allow_audio=True))
        r.requirement_reason("destructive")
        r.requirement_reason("audio")
        self.assertEqual(cli.calls.count("app_info"), 1)


class SkipTests(unittest.TestCase):
    def scenario(self, **extra):
        sc = {"name": "s", "steps": [{"call": "ui_tree", "args": {}}]}
        sc.update(extra)
        return sc

    def test_skip_reason_reports_the_first_unmet_requirement(self):
        r, _ = runner(bridge(allow_destructive=False, secrets=[]))
        why = r.skip_reason(self.scenario(requires=["destructive", "key:elevenlabs"]), "f")
        self.assertIn("mcp.allowDestructive", why)

    def test_met_requirements_let_the_scenario_run(self):
        r, _ = runner(bridge(allow_destructive=True, secrets=["key.elevenlabs"]))
        self.assertIsNone(r.skip_reason(self.scenario(requires=["destructive", "key:elevenlabs"]), "f"))

    def test_skipped_scenario_is_listed_with_its_reason(self):
        lines = []
        r, _ = runner(bridge(allow_destructive=False), lines=lines)
        with contextlib.redirect_stdout(io.StringIO()):
            r.run_scenario("sessions", self.scenario(name="delete", requires=["destructive"]))
        self.assertEqual(r.counts, {"pass": 0, "fail": 0, "skip": 1})
        self.assertTrue(any(l.startswith("- SKIP **sessions / delete**") and "mcp.allowDestructive" in l for l in lines), lines)

    def test_needs_check_still_comes_first(self):
        r, _ = runner(bridge(allow_destructive=True))
        why = r.skip_reason(self.scenario(needs=["no_such_tool"], requires=["destructive"]), "f")
        self.assertIn("no_such_tool", why)


class LoadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.tmp, "tests", "mcp"))
        self.saved_root = smoke.ROOT
        smoke.ROOT = self.tmp

    def tearDown(self):
        smoke.ROOT = self.saved_root
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, name, data):
        with open(os.path.join(self.tmp, "tests", "mcp", name + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f)

    def test_unknown_requirement_stops_the_load(self):
        self.write("bad", {"scenarios": [{"name": "x", "requires": ["audoi"], "steps": []}]})
        with self.assertRaises(SystemExit) as cm:
            smoke.load_scenarios([])
        self.assertIn("audoi", str(cm.exception))

    def test_key_requirement_needs_a_provider(self):
        self.write("bad", {"name": "x", "requires": ["key:"], "steps": []})
        with self.assertRaises(SystemExit):
            smoke.load_scenarios([])

    def test_valid_requirements_load(self):
        self.write("good", {"scenarios": [{"name": "x", "requires": ["destructive", "audio", "seeded-turns", "key:elevenlabs"], "steps": []}]})
        self.assertEqual(len(smoke.load_scenarios([])), 1)

    def test_repository_scenarios_all_validate(self):
        smoke.ROOT = ROOT
        scenarios = smoke.load_scenarios([])
        self.assertGreater(len(scenarios), 50)
        for fid, sc in scenarios:
            for req in sc.get("requires", []):
                self.assertTrue(req in ("destructive", "audio", "seeded-turns") or req.startswith("key:"), (fid, req))


if __name__ == "__main__":
    unittest.main(verbosity=1)
