# glmroid Roadmap

Working notes for the glm (VPS, x86_64, systemd) → glmroid (Android, arm64, on-demand app) port. This is an experiment, not a product.

## Principles


- **On-demand, not 24/7.** Start when needed (short sessions, ≤6 h), clean stop. One Start/Stop button; no daemons outlive the app.
- **No heavy distro / no Termux dependency in the end product.** Target = a single Kotlin APK.
- **Verify, don't assume.** Test each technical gate cheaply before committing the architecture.
- **Changes wait for an explicit go-ahead** — except items tagged P0 (backup, time-sensitive).

## Deadline

The origin VPS expires **~1 week from 2026-11-10**. Priorities:
1. P0 backup while the VPS is alive.
2. Key experiment (P1) while the VPS can still act as a control group.
3. Everything else can proceed after migration too (builds happen in GHA).

## P0 — Final backup (URGENT, while the origin VPS is alive)

- [x] Repo `zunvein/glm` = `633abfc` pushed, secret-scan clean (verifikasi 2026-11-01).
- [x] Repo `zunvein/glmroid` dibuat 2026-11-10; tree dirakit dari glm main + `go.mod`+`go.sum`
      (glm sengaja gak nge-track go.mod; glmroid ikut track biar GHA bisa build tanpa VPS).
- [ ] Secrets handed over privately (never in the repo): bridge AUTH\_TOKEN, upstream Z.AI JWT(s).
      A phone only needs: AUTH\_TOKEN + at least one Z.AI JWT.
- [ ] Opsional: note konfigurasi systemd unit (ZAI_TOKENS format, env knobs) → docs/konfig.md.

## P1 — Key experiment (GO/NO-GO gate for the phone architecture)

Two questions to answer with data before committing to the APK architecture:

1. **Mint dari HP diterima?** — jalankan harvest JS di browser HP (WebView atau Firefox+
   userscript) → token masuk pool → dipake bridge → `computeOK` naik / `computeFail` stabil?
2. **Shelf-life token** — token yang di-mint dari HP, disimpan beberapa jam/sampai besok,
   masih valid buat compute captcha?

Method: small receiver on the phone (or the VPS as control) + pool swap + `/health` observation.

Storm #3 lesson applies: never assume "fresh identity = immune" without testing.

## P2 — Bridge on Android (binary)

- [x] ~~Cross-compile `GOOS=android GOARCH=arm64`~~ **DONE 2026-11-10**: GHA workflow
      `build-android-arm64` green (commit `6a30b67`); vet+test CI PASS; smoke test:
      binary linux hidup + `/health` respond port default 3001.
      Artifact `zai-api-android-arm64` ~5.4 MB ter-upload tiap push.
      (pure-Go, no CGO terverifikasi via go.mod `modernc.org/sqlite`)
- [ ] On-device smoke test: exec the binary from the app data dir, `/health` responds, SSE
      end-to-end (`data: [DONE]` + zero error events), active SQLite pool at the Android path.
- [ ] Port the storm drill: Android version (swap bogus pool → standby → request #4 OK).

### GHA build notes (lessons learned, 10 Nov)
- Boot guard: the binary exits 1 if `tokens.sqlite` is missing → smoke tests must create a
  dummy db first (`CREATE TABLE tokens (id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT NOT NULL)`).
- Port default = **3001** (3012 cuma systemd VPS override).
- Session warmup is asynchronous → `/health` responds before pool warm-up finishes.

## P3 — Kotlin shell (APK)

- [ ] Minimal APK: tombol Start/Stop, foreground service, ekstrak+exec binary dari assets,
      status dari `/health` (breaker, pool size, standbyReady).
- [ ] WebView token-farm: load chat.z.ai, inject harvest JS (porting dari
      `cmd/token-collector/harvest.go`), token → pool sqlite `filesDir`.
- [ ] Konfigurasi in-app: AUTH_TOKEN, .glmkey, ZAI_TOKENS (disimpan di EncryptedSharedPreferences
      atau sejenis — JANGAN plaintext log/journald; pelajaran banner auth token di VPS).
- [ ] Opsional: tunnel on-demand (cloudflared) kalau ada konsumen di luar HP.

## P4 — Android operations

- [ ] Pengganti systemd timers: storm-alert / watchdog jadi in-process loop bridge
      (lebih simpel daripada port cron ke Android).
- [ ] Porting logika refill.sh yang masih relevan (multi-session mint di HP = multi-tab /
      multi-restart WebView; standby pool staged di filesDir).
- [ ] Dokumentasi final: cara pakai harian (Start → pakai → top-up pool → Stop).

## Technical gotchas carried over from glm

- e2e SSE verdict valid cuma kalau tail mengandung `data: [DONE]` + zero error events;
  `curl -s` tanpa `-f` exit 0 walau HTTP error.
- SQLite hot-swap harus ke filename unik per generasi (`tokens-live-<stamp>.sqlite`);
  swap di path yang sama pada DB WAL = replay `-wal` stale (ghost rows).
- Jangan print nilai rahasia; mask by VALUE shape (JWT `eyJ…`, hex 32+, base64 panjang).
- Pre-push secret scan: diff range `origin/main..HEAD`.
- Breaker: 3 consecutive fail → open 90 s; standby swap = same-second recovery (proven drill).
