#!/usr/bin/env python3
"""Run the actual packaged Runify jar on a clean Paper 1.21.11 server.

This integration smoke check would have caught the invalid plugin.yml that
was previously invisible to Maven compilation. It sends /qa to the console
and requires Runify's enable banner and the command help to appear.
"""
import json
import queue
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

USER_AGENT = "RunifyPaperSmokeTest/1.0 (https://github.com/darkness22s/runify)"
PAPER_BUILDS = "https://fill.papermc.io/v3/projects/paper/versions/1.21.11/builds"


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=45) as response:
        return json.loads(response.read())


def download_server(dest: Path) -> None:
    builds = get_json(PAPER_BUILDS)
    if isinstance(builds, dict):
        builds = builds.get("builds", [])
    if not builds:
        raise RuntimeError("No Paper 1.21.11 builds found")
    stable = next((b for b in builds if b.get("channel") == "STABLE"), builds[0])
    source = stable["downloads"]["server:default"]["url"]
    print(f"Downloading Paper 1.21.11 build {stable.get('id')} from PaperMC", flush=True)
    request = urllib.request.Request(source, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=100) as response, dest.open("wb") as output:
        shutil.copyfileobj(response, output)
    if dest.stat().st_size < 1_000_000:
        raise RuntimeError("Paper download was unexpectedly small")


def run() -> None:
    jar = Path(sys.argv[1] if len(sys.argv) > 1 else "target/QualityArmory.jar").resolve()
    if not jar.is_file():
        raise RuntimeError(f"Runify JAR not found: {jar}")

    home = Path("paper-smoke").resolve()
    plugins = home / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, plugins / "Runify-QualityArmory.jar")
    (home / "eula.txt").write_text("eula=true\n")
    (home / "server.properties").write_text(
        "online-mode=false\nserver-port=25568\nmax-players=2\n"
        "enable-rcon=false\nlevel-name=smoke-world\nmotd=Runify Smoke Test\n"
    )
    download_server(home / "paper.jar")

    process = subprocess.Popen(
        ["java", "-Xms512M", "-Xmx2G", "-jar", "paper.jar", "--nogui"],
        cwd=home, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, bufsize=1
    )
    messages = queue.Queue()
    log_lines = []

    def read_output():
        for line in process.stdout:
            messages.put(line)
    threading.Thread(target=read_output, daemon=True).start()

    deadline = time.monotonic() + 180
    sent_command = False
    command_sent_at = 0
    while time.monotonic() < deadline:
        if process.poll() is not None and messages.empty():
            break
        try:
            line = messages.get(timeout=1)
        except queue.Empty:
            continue
        print(line, end="", flush=True)
        log_lines.append(line)
        if "Done (" in line and not sent_command:
            process.stdin.write("qa\n")
            process.stdin.flush()
            sent_command = True
            command_sent_at = time.monotonic()
        if sent_command and time.monotonic() - command_sent_at >= 6:
            break

    try:
        if process.poll() is None:
            process.stdin.write("stop\n")
            process.stdin.flush()
        process.wait(timeout=40)
    except (BrokenPipeError, subprocess.TimeoutExpired):
        process.kill()
        process.wait(timeout=10)

    while not messages.empty():
        log_lines.append(messages.get_nowait())

    output = "".join(log_lines)
    required = {
        "Plugin loaded and command listeners registered": "Runify Crownlands registered",
        "Minecraft server reached ready state": "Done (",
        "The /qa command responded with a help menu": "/QA",
    }
    missing = [label for label, snippet in required.items() if snippet not in output]
    if missing:
        tail = "\n".join(output.splitlines()[-120:])
        raise RuntimeError(f"Paper 1.21.11 smoke test failed: {missing}\n{tail}")
    if "Error occurred while enabling QualityArmory" in output or "Error loading plugin" in output:
        raise RuntimeError("Paper reported an error while enabling Runify")
    print("SUCCESS: Paper 1.21.11 loaded Runify and /qa responded on the real server.", flush=True)


if __name__ == "__main__":
    run()
