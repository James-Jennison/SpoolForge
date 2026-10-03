# Host build result — action30 snapshot

Command: heavy-gradle :core:test :app:assembleDebug :app:lintDebug
Exit1; BUILD FAILED. No tests passed and no APK validated.

1. app kapt JVM target17 conflicts with Java default1.8. Set source/target Java17 and Kotlin JVM17 consistently.
2. OpenSpoolCodecTest.kt line35 has malformed string quoting; actual String where ByteArray expected, missing quote at line40. Correct fixture syntax and rerun.

Host cache/build wrapper works. Worker sandbox failure does not block host validation. Read HOST_CODE_REVIEW.md additional current import and manifest checks.

Host correction pass: added Java/Kotlin17 targets and Room schema export to app/build.gradle.kts; fixed Compose function type syntax, Material3 opt-in and FAB overload in MainActivity. Second Gradle :core:test PASS, app compile failed before these UI fixes; worker concurrently added rejectBeforeWrite to resolve stale core/app snapshot. Re-run required on settled source.

Host runtime finding and correction: first APK installed/launched successfully on2023, but catalog failed because Android asset merger decompressed/renamed .gz to .tsv. APK ZIP inspection proved no .gz entry. Renamed compressed asset and all app/build references to .tsv.gzip so bytes and digest remain stable in APK. Rebuild/reinstall/first-launch acceptance required. Earlier build PASS does not imply runtime PASS.
