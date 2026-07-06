# OSS obfuscation corpus (Hybrid 1.1)

Ground-truth jars for the OSS deobfuscation round-trip benchmark: each jar ships
with real, human-authored names. The pipeline renames them to opaque tokens with
tiny-remapper, then measures how well the LLM recovers the originals.

A mix of varying fame is used deliberately, so results separate genuine
context-based recovery from possible memorization of well-known APIs:

| jar                        | coordinates                              | fame   | notes                                  |
|----------------------------|------------------------------------------|--------|----------------------------------------|
| gson-2.11.0.jar            | com.google.code.gson:gson:2.11.0         | high   | clean idiomatic naming, small          |
| commons-lang3-3.14.0.jar   | org.apache.commons:commons-lang3:3.14.0  | high   | very idiomatic, larger surface         |
| xz-1.9.jar                 | org.tukaani:xz:1.9                        | niche  | LZMA/XZ codec, unlikely memorized      |

The jars themselves are git-ignored (binaries, reproducible). Re-fetch with:

```sh
b=https://repo1.maven.org/maven2
curl -fsSL -o gson-2.11.0.jar          $b/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar
curl -fsSL -o commons-lang3-3.14.0.jar $b/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar
curl -fsSL -o xz-1.9.jar               $b/org/tukaani/xz/1.9/xz-1.9.jar
```
