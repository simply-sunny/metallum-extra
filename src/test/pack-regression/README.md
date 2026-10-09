# Shader pack regression

`run.sh` plays `packs.txt` in the game (a debug script, see `DebugScript.java`): it opens a test world, looks down at a
red and blue platform with Smooth Edges on, and switches built-in → RedToBlue → BlueToRed → built-in → RedToBlue,
saving a picture each time. `gradlew checkPackImages` then checks the pictures:

| Pack | Red in the picture | Blue in the picture |
|---|---|---|
| built-in | yes | yes |
| RedToBlue | gone | yes |
| BlueToRed | yes | gone |

The log also prints the translation cache counters after each step (`MSL cache hits`, `MSL translations`). After the first
pack, each new pack adds about one translation (only `program/edges.fsh` differs) and switching back to a pack adds none.

Test packs are written by `gradlew writeTestPacks -Pto=<folder>`; there are two more, `SyntaxError.zip` (invalid GLSL) and
`Incomplete.zip` (a required shader missing), for trying by hand: the first must put itself aside with an error in the menu
and leave the game running; the second must be listed in red and not be selectable.

The checks that need no game are `gradlew packTest` (part of `gradlew check`).
