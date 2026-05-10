# X-RavScan Desktop — Compose Multiplatform

Native Windows / macOS / Linux desktop build of X-RavScan, written in Kotlin + Compose Multiplatform. Replaces the legacy Python + CustomTkinter `.exe` (which now lives in `tools/X-RavScan/` as legacy).

## Stack

- Kotlin 2.0.21, JDK 17
- Compose Multiplatform 1.7.3, Compose Material 3
- Coroutines 1.9.0 (`Dispatchers.IO` for sockets / SQLite, `Dispatchers.Default` for parsing / Smart Append)
- Kotlinx Serialization 1.7.3 (seed JSON)
- Distribution via `compose.desktop` plugin → jpackage on Windows runners (`.exe` + `.msi`)

## Roadmap

| Phase | Status | Content |
|------|--------|---------|
| **D1** | shipping | Skeleton: theme, glassmorphism, animated background, sidebar nav, 5 placeholder screens, GlobalErrorScreen, CI wiring `:app:packageDistributionForCurrentOS` on Windows |
| **D2** | next | SQLDelight (KMP-ready) for `providers` / `cidr_ranges` / `scan_results` / `discoveries`; seed JSON + per-provider CIDR text files at first launch |
| **D3** | pending | Network: Ktor client (Engine.OkHttp) on Dispatchers.IO, BGPView prefix walk, Smart Append, TCP/TLS quick scan with bare TLS handshake for cert CN |
| **D4** | pending | Wire real data: Dashboard live counts, Providers LazyColumn + search, Discovery activity log, Results LazyColumn + RTT colour badges |
| **D5** | pending | JFileChooser export, ProGuard-trimmed packaging, custom Window decor, log panel inside the app, signed `.exe`/`.msi` artifacts |

## Local development

```bash
cd tools/X-RavScanDesktop
./gradlew :app:run                       # run from source
./gradlew :app:createDistributable       # portable folder under app/build/compose/binaries/main/app/
./gradlew :app:packageDistributionForCurrentOS  # native installer for the current OS
```

JDK 17 is required (set `JAVA_HOME` if running outside an IDE).

## CI

`.github/workflows/x_ravscan_desktop_cmp.yml` runs `:app:packageDistributionForCurrentOS` on `windows-latest`, uploading `.exe`, `.msi`, and the portable app folder as separate artifacts.
