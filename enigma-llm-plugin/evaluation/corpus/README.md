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

The jars themselves are git-ignored (binaries, reproducible). Fetch them with the
Gradle task, which uses FabricMC/Loom's downloader (sha1 verification + retries) and is
idempotent — a local copy whose sha1 already matches is reused, nothing re-downloads:

```sh
./gradlew :enigma-llm-plugin:downloadCorpus
```

The coordinates and pinned sha1 hashes live in `enigma-llm-plugin/build.gradle`
(`downloadCorpus` task). To add or swap a jar, edit that list, not this file.
