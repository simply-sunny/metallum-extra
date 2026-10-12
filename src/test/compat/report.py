"""Prints the compatibility table from what run.sh collected in build/compat."""
import os
import re
import sys

out = sys.argv[1]
packs = [line.rstrip("\n").split("|") for line in open(os.path.join(out, "packs.txt"))]
offline = {}
path = os.path.join(out, "offline.txt")
if os.path.exists(path):
    for line in open(path):
        link, _, result = line.rstrip("\n").split("|", 2)
        offline[link] = result

log = open(os.path.join(out, "game.log"), errors="replace").read() if os.path.exists(os.path.join(out, "game.log")) else ""
perf_text = log + (open(os.path.join(out, "perf.txt")).read() if os.path.exists(os.path.join(out, "perf.txt")) else "")
perf = {m.group(1): m.group(2) for m in re.finditer(r"perf (\S+): \d+ frames, median ([\d.]+) ms", perf_text)}
crashed = "Game crashed" in log
# What the mod logged between choosing one pack and the next.
sections = {}
for m in re.finditer(r"debug script: pack (zz_\d+\.zip)(.*?)(?=debug script: pack zz_|\Z)", log, re.S):
    sections[m.group(1)] = m.group(2)


def game_result(link):
    text = sections.get(link)
    if text is None:
        return "not run" + (" (game crashed)" if crashed else "")
    if "is broken and is switched off" in text:
        reason = re.search(r"is broken and is switched off: ([^\n]*)", text).group(1)
        return "switched off on first frame: " + reason[:90]
    if "cannot be used" in text or "not chosen" in text:
        reason = re.search(r"(?:cannot be used|not chosen): ([^\n]*)", text).group(1)
        return "refused: " + reason[:90]
    if re.search(r"Game crashed|Exception: ", text):
        return "crashed"
    if re.search(r"Shader pack zz_\d+: \d+ programs", text):
        return "runs"
    return "no result"


print("| Pack | Offline compile | In game | Frame ms |")
print("|---|---|---|---|")
for link, name in packs:
    off = offline.get(link, "-")
    off = "ok" if re.search(r"programs, 0 stage", off) else off[:80]
    print(f"| {name} | {off} | {game_result(link)} | {perf.get(link, '-')} |")
print()
print("vanilla frame ms:", perf.get("vanilla", "-"), "  game exit:", re.findall(r"exit \d+", log)[-1:] or "-")
