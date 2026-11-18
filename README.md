<p align="center">
  <img src="docs/logo.png" alt="CheatBreaker" width="160">
</p>

<h1 align="center">OpenCheatBreaker</h1>

This branch is the CheatBreaker 1.8.9 client. The CheatBreaker 1.7.10 client lives on the [`1.7.10`](https://github.com/GzSakura1338/OpenCheatBreaker/tree/1.7.10) branch.

## Build and run

Requires JDK 25 and network access to Maven Central (test dependencies). The game's natives and assets are copied from an installed CheatBreaker launcher (`%APPDATA%/CheatBreaker/downloads`); run the launcher once, or pass `-Pcb.launcherDir=<launcher data dir>`.

```
./gradlew jar          # output is in build/libs/
./gradlew test
./gradlew releaseZip   # standalone version package: build/distributions/OpenCheatBreaker-1.8.9.zip
./gradlew runGame      # copies the natives and assets into run/ and starts the game with an offline test account (game data in run/mc/)
```

IntelliJ IDEA: `File > Open` and select this directory. The project settings (`.idea/`) and the **OpenCheatBreaker** run configuration (`.run/OpenCheatBreaker.run.xml`) are included in the repository; set the Gradle JVM to JDK 25. The working directory is `run/` and game data lives in `run/mc/`.

Game sounds use Paulscode's pure Java Sound backend (`LibraryJavaSound`) instead of OpenAL, so the OpenAL native libraries are not needed (LWJGL's OpenAL could not be loaded from a path with non-ASCII characters, which left the game silent). Only the LWJGL and JInput natives for display and input remain in `run/natives`.

## Connection diagnostics

The **Connection Diagnostics** mod (Server / Misc) is enabled by default and can be toggled in the mod menu. It passively observes existing remote Minecraft connections, including a connection already active when the mod is enabled. It sends no additional packets. Changing the setting does not disconnect the client.

Logs are written to `<gameDir>/logs/connection-diagnostics.jsonl`, with three older segments named `.jsonl.1` through `.jsonl.3`. Each segment is limited to 16 MiB. The workspace launcher uses `run/mc` as its game directory. Preserve all four files promptly after an incident; older records are overwritten as the files rotate. Each record has a UTC timestamp, wall-clock milliseconds, monotonic nanoseconds, a process run ID, a capture ID and a connection session ID. Use those IDs when comparing reconnects or toggling the mod.

The log includes local/remote socket endpoints, connect failures, protocol state, network exception types/stacks, transport error categories, server disconnect packets and local close requests. Socket-delivered byte counts, decoded packet counts, outbound packet counts, write completions, queue depths and activity gaps are sampled every second. Keepalives have separate receive, handler-entry, send-request, write-start and write-completion events, correlated by their key. In this client the keepalive handler runs on the network thread; these timings measure local handling and sending delays, not network RTT.

Client samples include frame intervals, focus and frame-limit settings, main-thread packet queue/work delays, cumulative GC counts/time and heap use. One outstanding local event-loop task measures scheduling delay without sending network traffic. A stalled main thread or overdue event-loop task also triggers a bounded thread stack snapshot. Frame intervals include intentional frame limiting; GC totals and stack samples are evidence to correlate, not automatic root-cause verdicts. Interval maxima reset at each connection snapshot; other counters marked `Total` are cumulative for that session.

Only packet class names and keepalive keys are recorded. Chat, commands, packet payloads, credentials and arbitrary exception messages are excluded; disconnect text is represented by its translation key or a literal-reason marker. Socket addresses are retained to align the trace with a server-side capture. The bounded asynchronous writer keeps disk I/O off game/network threads, flushes about once per second, and reports dropped records and write errors in samples and the normal client log. An abnormal process exit can lose the tail of the log.

A successful Netty write reports local completion; it does not prove TCP acknowledgement or receipt by the server. Raw byte activity is observed in the Netty pipeline, not a TCP packet capture. These logs can separate client stalls, event-loop delays, codec/handler problems and connection silence/closure. Identifying which remote host or middlebox generated a reset still requires matching packet captures from both endpoints.

[![QQ Group 1078719379](https://img.shields.io/badge/QQ%20Group-1078719379-12B7F5?logo=tencentqq&logoColor=white)](https://qm.qq.com/q/OnrCkjX7Y4)
