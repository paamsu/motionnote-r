"""Replay preserved synthetic scenes; never rewrites sensor input or inference."""

from pathlib import Path
import argparse, json, sqlite3, shutil, sys, uuid, subprocess, os, time, urllib.request, socket

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "src"))
from evidence.store import Store

CAT = json.loads((ROOT / "scenes.json").read_text())


def prepare(row):
    source = ROOT / "scenes" / row["scene"]
    dest = ROOT / "runs" / (row["scene"] + "-" + uuid.uuid4().hex[:8])
    shutil.copytree(source, dest)
    with sqlite3.connect(dest / "events.sqlite3") as c:
        for eid, raw in c.execute("select event_id,manifest from media").fetchall():
            c.execute(
                "update media set manifest=? where event_id=?",
                (raw.replace("@SCENE@", str(dest.resolve())), eid),
            )
    store = Store(dest / "events.sqlite3")
    for e in store.list():
        assert e["evidence_valid"], e["id"]
        d = store.get(e["id"])
        if d.get("video"):
            assert (
                d["video"]["integrity_valid"]
                and d["video"]["input_photo"]["integrity_valid"]
            ), e["id"]
        if d.get("surround_views"):
            assert d["surround_views"]["integrity_valid"], e["id"]
    if row["scene"] == "summary":
        from contest_replay_summary import refresh_summary

        refresh_summary(dest)
    return dest


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--check", action="store_true")
    p.add_argument("--scene", choices=[r["scene"] for r in CAT], default="road")
    p.add_argument("--serial", default="emulator-5554")
    p.add_argument("--user", default="10")
    args = p.parse_args()
    if args.check:
        for row in CAT:
            prepare(row)
            print("PASS", row["scene"], flush=True)
        sys.exit(0)
    row = next(r for r in CAT if r["scene"] == args.scene)
    dest = prepare(row)
    adb = os.environ.get("ROAD_GUARDIAN_ADB", "adb")
    prefix = [adb, "-s", args.serial]
    with socket.socket() as probe:
        if probe.connect_ex(("127.0.0.1", 9460)) == 0:
            raise RuntimeError("Port 9460 already in use; existing server preserved")
    if b"tcp:9460" in subprocess.check_output(prefix + ["reverse", "--list"]):
        raise RuntimeError("Existing reverse mapping preserved")
    forwarded = False
    server = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "backend.server",
            "--port",
            "9460",
            "--database",
            str(
                dest
                / (
                    "replay-events.sqlite3"
                    if args.scene == "summary"
                    else "events.sqlite3"
                )
            ),
        ],
        env=dict(os.environ, PYTHONPATH=str(ROOT / "src")),
    )
    try:
        for _ in range(30):
            if server.poll() is not None:
                raise RuntimeError("Server stopped; inspect port 9460")
            try:
                urllib.request.urlopen(
                    "http://127.0.0.1:9460/health", timeout=1
                ).close()
                break
            except OSError:
                time.sleep(0.2)
        else:
            raise RuntimeError("Server unavailable")
        subprocess.run(prefix + ["reverse", "tcp:9460", "tcp:9460"], check=True)
        forwarded = True
        extra = (
            ["--ez", "review_road_summary", "true"]
            if args.scene == "summary"
            else ["--es", "review_event_id", row["event_id"]]
        )
        subprocess.run(
            prefix
            + [
                "shell",
                "am",
                "force-stop",
                "--user",
                args.user,
                "ai.teraq.roadguardian.contest.release",
            ],
            check=True,
        )
        subprocess.run(
            prefix
            + [
                "shell",
                "am",
                "start",
                "--user",
                args.user,
                "--activity-clear-top",
                "-n",
                "ai.teraq.roadguardian.contest.release/ai.teraq.roadguardian.MainActivity",
                *extra,
            ],
            check=True,
        )
        print(
            "합성 보관 자료 재생; 보험·신고·예약은 모의 처리입니다. 종료: Ctrl+C",
            flush=True,
        )
        while server.poll() is None:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        if forwarded:
            subprocess.run(prefix + ["reverse", "--remove", "tcp:9460"], check=False)
        server.terminate()
        server.wait(timeout=10)
