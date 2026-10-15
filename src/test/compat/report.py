"""Prints the compatibility table from the files src/test/compat/run.sh leaves in build/compat."""
import re, sys, os

out = sys.argv[1] if len(sys.argv) > 1 else "build/compat"

def lines(name):
    p = os.path.join(out, name)
    return open(p, errors="replace").read().splitlines() if os.path.exists(p) else []

packs = [l.split("|", 1) for l in lines("packs.txt") if "|" in l]
offline = {}
for l in lines("offline.txt"):
    link, rest = l.split("|", 1)
    offline[link] = rest
game = "\n".join(lines("game.log"))
# One section per `pack <link>` line of the script's log.
sections = {}
for m in re.finditer(r"debug script: pack (zz_\d+\.zip)(.*?)(?=debug script: pack zz_|\Z)", game, re.S):
    sections[m.group(1)] = m.group(2)
perf = {}
for m in re.finditer(r"(?:perf|frame)[^\n]*?(zz_\d+\.zip|vanilla)[^\n]*?([0-9]+\.[0-9]+)\s*ms", game):
    perf[m.group(1)] = m.group(2)
for l in lines("perf.txt"):
    m = re.match(r"(\S+)\D+([0-9]+\.[0-9]+)", l)
    if m: perf[m.group(1)] = m.group(2)

def offline_text(r):
    m = re.search(r"(\d+) programs, (\d+) stage", r or "")
    if not m: return (r or "not run")[:90]
    return "ok" if m.group(2) == "0" else f"{m.group(1)} programs, {m.group(2)} stage(s) failed"

def game_text(link):
    if not game: return "not run"
    s = sections.get(link)
    if s is None: return "not run (game crashed)" if "exit 0" not in game else "not run"
    for pat, what in ((r"refus\w+[^\n]*", "refused: "), (r"switched? off[^\n]*", ""), (r"[Ff]ailed to find or load pipeline[^\n]*", "switched off on first frame: ")):
        m = re.search(pat, s)
        if m: return (what + m.group(0))[:140]
    return "runs"

print("| Pack | Offline compile | In game | Frame ms |")
print("|---|---|---|---|")
for link, name in packs:
    print(f"| {name} | {offline_text(offline.get(link))} | {game_text(link)} | {perf.get(link, '')} |")
if "vanilla" in perf:
    print(f"\nvanilla frame ms: {perf['vanilla']}")
