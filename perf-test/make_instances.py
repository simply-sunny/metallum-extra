import os, re, shutil, zipfile, sys, json
HOME = os.path.expanduser("~")
ONE = f"{HOME}/Library/Application Support/Polyfrost/OneClient"
RAW = f"{ONE}/clusters/Raw Metal"
PROJ = "/Users/smbp/projects"
PACKS = f"{PROJ}/metallum-extra/run/shaderpacks"
ROOT = f"{PROJ}/metallum-extra/perf-test"

def profile_options(zipname, profile):
    z = zipfile.ZipFile(f"{PACKS}/{zipname}")
    text = z.read("shaders/shaders.properties").decode("utf8", "replace")
    profiles = {}
    for m in re.finditer(r"(?m)^\s*profile\.(\w+)\s*=\s*(.*)$", text):
        profiles[m.group(1)] = m.group(2).split()
    out = {}
    def apply(name):
        for tok in profiles[name]:
            if tok.startswith("profile."):
                apply(tok[len("profile."):])
            elif tok.startswith("!"):
                out[tok[1:]] = "false"
            elif "=" in tok:
                k, v = tok.split("=", 1)
                out[k] = v
            else:
                out[tok] = "true"
    apply(profile)
    return out

def build(name, mods, packs, backend, extra_config=None):
    d = f"{ROOT}/{name}"
    shutil.rmtree(d, ignore_errors=True)
    for sub in ("mods", "config", "shaderpacks", "saves", "resourcepacks"):
        os.makedirs(f"{d}/{sub}")
    for src, dst in mods:
        shutil.copy(src, f"{d}/mods/{dst}")
    for z in packs:
        shutil.copy(f"{PACKS}/{z}", f"{d}/shaderpacks/{z}")
    opts = open(f"{RAW}/options.txt").read()
    def setopt(k, v):
        nonlocal opts
        if re.search(rf"(?m)^{re.escape(k)}:", opts):
            opts = re.sub(rf"(?m)^{re.escape(k)}:.*$", f"{k}:{v}", opts)
        else:
            opts += f"{k}:{v}\n"
    setopt("preferredGraphicsBackend", json.dumps(backend))
    setopt("fullscreen", "false")
    setopt("enableVsync", "false")
    setopt("maxFps", "260")
    setopt("renderDistance", "8")
    setopt("simulationDistance", "5")
    setopt("pauseOnLostFocus", "false")
    setopt("onboardAccessibility", "false")
    setopt("guiScale", "2")
    setopt("inactivityFpsLimit", "\"minimized\"")
    open(f"{d}/options.txt", "w").write(opts)
    cfg = json.load(open(f"{ONE}/clusters/26.3 Fabric/config/sodium-extra-options.json"))
    cfg["extra_settings"]["reduce_resolution_on_mac"] = True
    cfg["extra_settings"]["show_fps"] = False
    json.dump(cfg, open(f"{d}/config/sodium-extra-options.json", "w"), indent=2)
    return d

DL = f"{ROOT}/dl"
M = f"{RAW}/mods"
fabric = (f"{M}/fabric-api-0.161.0+26.2.jar", "fabric-api-0.161.0+26.2.jar")
sodium = (f"{M}/sodium-fabric-0.9.2+mc26.2.jar", "sodium-fabric-0.9.2+mc26.2.jar")
extra = (f"{DL}/sodium-extra-fabric-0.9.4+mc26.2.jar", "sodium-extra-fabric-0.9.4+mc26.2.jar")
iris = (f"{DL}/iris-fabric-1.11.4+mc26.2.jar", "iris-fabric-1.11.4+mc26.2.jar")
harness = (f"{ROOT}/harness/build/libs/perfbench-1.0.jar", "perfbench-1.0.jar")
metallum = (f"{M}/metallum-0.0.24.jar", "metallum-0.0.24.jar")
mextra = (f"{PROJ}/metallum-extra/build/libs/metallum-extra-0.1.0+metallum.0.0.23-0.0.24.jar", "metallum-extra.jar")

a = build("inst-a", [fabric, sodium, extra, iris, harness],
          ["BSL Shaders v10.1.8.zip", "ComplementaryReimagined_r5.9.3.zip"], "opengl")
b = build("inst-b", [fabric, sodium, extra, metallum, mextra, harness],
          ["BSL Shaders v10.1.8.zip", "ComplementaryReimagined_r5.5.1.zip", "ComplementaryReimagined_r5.9.3.zip"], "default")

# Iris keeps a pack's chosen values beside the pack: <pack>.txt
for pack, profile in (("BSL Shaders v10.1.8.zip", "HIGH"), ("ComplementaryReimagined_r5.9.3.zip", "MEDIUM")):
    vals = profile_options(pack, profile)
    open(f"{a}/shaderpacks/{pack}.txt", "w").write("".join(f"{k}={v}\n" for k, v in vals.items()))
    print(pack, profile, len(vals), "options")
print("ok")
