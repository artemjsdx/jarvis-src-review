#!/usr/bin/env python3
"""add-task.py NAME TASK_DOC.md [--model M] [--priority N] [--after NAME ...]
Creates the worktree ~/jarvis-wt/NAME (branch NAME from master, .cache symlinked) if needed and queues the task in the farm supervisor
(the standard prompt: read AGENT_BRIEF + the task file, work autonomously, commit per step, final report of <= 40 lines)."""
import argparse, os, subprocess, sys
ap = argparse.ArgumentParser(); ap.add_argument("name"); ap.add_argument("doc")
ap.add_argument("--model", default="anthropic/claude-haiku-5.5"); ap.add_argument("--priority", type=int, default=3); ap.add_argument("--after", nargs="*", default=[])
a = ap.parse_args()
H = os.path.expanduser("~"); repo = H + "/jarvis"; wt = "%s/jarvis-wt/%s" % (H, a.name)
if not os.path.isdir(wt):
    subprocess.run(["git", "-C", repo, "worktree", "add", "-q", "-b", a.name, wt, "master"], check=True)
    os.symlink(repo + "/.cache", wt + "/.cache")
    print("worktree", wt)
if not os.path.isfile("%s/docs/%s" % (wt, a.doc)): sys.exit("docs/%s is not in the worktree: commit it to master first, then `git -C %s merge --ff-only master`" % (a.doc, wt))
prompt = ("You are the engineering agent '%s' on project Jarvis (a native Android voice assistant built entirely on a phone in Termux). Your working directory is your own git worktree on branch '%s'; "
          "never edit ~/jarvis itself. First read, in this order: docs/AGENT_BRIEF.md (shared rules; the sections 'Wave 2 rules' and 'Wave 3 rules' both apply; the owner's phone is NOT reachable over ADB, "
          "so verify with JVM tests, builds and the local ART/preview tools and write the rest into your DEVICE_CHECKLIST), then your task file docs/%s and the parts of docs/BACKLOG.md, docs/MCP.md and docs/SPEED.md it references; "
          "look at the owner's screenshots in docs/ref/owner-screens/ if your task is UI. master already contains the work of earlier agents: read docs/STATE.md and the relevant docs/*.md before touching shared code. "
          "Then do the task end to end: implement, test, build, commit on your branch after every finished step. Work autonomously and do not ask questions; if an environment problem blocks you and cannot be worked around, document it and continue. "
          "Your final answer must be a report of at most 40 lines: what was built (files), how it was verified (exact commands and results), measured numbers, what is NOT verified and why, known issues, and which BACKLOG IDs you consider done. "
          "User-facing strings are Russian, code identifiers English. Never print or commit secrets." % (a.name, a.name, a.doc))
sup = os.path.dirname(os.path.abspath(__file__)) + "/supervisor.py"
cmd = [sys.executable, sup, "add", a.name, "--cwd", wt, "--prompt", prompt, "--model", a.model, "--room", "wave3", "--max-turns", "300", "--priority", str(a.priority)]
if a.after: cmd += ["--after"] + a.after
subprocess.run(cmd, check=True)
