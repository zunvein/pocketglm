# glmroid Roadmap — GLM bridge di Android

Dokumen kerja migrasi glm (VPS, x86_64, systemd) → glmroid (HP Android, arm64, on-demand app).

## Prinsip

- **On-demand, bukan 24/7.** Model pemakaian owner: nyala pas dipake (≤6 jam), mati bersih.
  Gak ada daemon yang harus idup saat HP mati. Satu tombol Start/Stop.
- **Gak ada distro heavy / Termux dependency di produk akhir.** Target = satu APK Kotlin.
- **Verifikasi, bukan ingatan.** Tiap gerbang teknis dites murah dulu sebelum commit arsitektur.
- **Semua perubahan nunggu GO owner** — kecuali item yang dicap P0 (backup, time-sensitive).

## Batas waktu

VPS expire **±1 minggu dari 2026-11-10**. Prioritas:
1. P0 backup selagi VPS idup.
2. Eksperimen kunci (P1) selagi VPS masih bisa jadi control group.
3. Sisanya setelah VPS mati juga bisa dikerjakan (build di GHA).

## P0 — Backup final (SEGERA, selagi VPS idup)

- [x] Repo `zunvein/glm` = `633abfc` pushed, secret-scan clean (verifikasi 2026-11-01).
- [x] Repo `zunvein/glmroid` dibuat 2026-11-10; tree dirakit dari glm main + `go.mod`+`go.sum`
      (glm sengaja gak nge-track go.mod; glmroid ikut track biar GHA bisa build tanpa VPS).
- [ ] Backup rahasia milik owner diserahkan via jalur privat (bukan repo):
      `.swapenv` (AUTH_TOKEN), `.glmkey`, ZAI_TOKENS (2 JWT), `workers/.tgtoken`.
      Owner yang simpan; HP baru butuh: AUTH_TOKEN + .glmkey + minimal 1 Z.AI JWT.
- [ ] Opsional: note konfigurasi systemd unit (ZAI_TOKENS format, env knobs) → docs/konfig.md.

## P1 — Eksperimen kunci (gerbang GO/NO-GO arsitektur HP)

Dua pertanyaan yang harus dijawab pakai data sebelum serius bikin APK:

1. **Mint dari HP diterima?** — jalankan harvest JS di browser HP (WebView atau Firefox+
   userscript) → token masuk pool → dipake bridge → `computeOK` naik / `computeFail` stabil?
2. **Shelf-life token** — token yang di-mint dari HP, disimpan beberapa jam/sampai besok,
   masih valid buat compute captcha?

Metode: receiver kecil di HP (atau VPS sebagai control) + pool swap + observasi `/health`.

Pelajaran storm #3 berlaku: jangan asumsi "fresh identity = immune" tanpa tes.

## P2 — Bridge di Android (binary)

- [x] ~~Cross-compile `GOOS=android GOARCH=arm64`~~ **DONE 2026-11-10**: GHA workflow
      `build-android-arm64` green (commit `6a30b67`); vet+test CI PASS; smoke test:
      binary linux hidup + `/health` respond port default 3001.
      Artifact `zai-api-android-arm64` ~5.4 MB ter-upload tiap push.
      (pure-Go, no CGO terverifikasi via go.mod `modernc.org/sqlite`)
- [ ] Smoke test di HP: exec binary dari data dir, `/health` jalan, SSE end-to-end
      (`data: [DONE]` + zero error events), pool sqlite aktif di path Android.
- [ ] Storm-drill port: drill versi Android (swap bogus pool → standby → request #4 OK).

### Catatan build GHA (pelajaran 10 Nov)
- Boot guard: binary exit-1 kalau `tokens.sqlite` gak ada → smoke test harus bikin
  dummy db dulu (`CREATE TABLE tokens (id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT NOT NULL)`).
- Port default = **3001** (3012 cuma systemd VPS override).
- `zai-smoke` warmup session jalan asinkron → `/health` respond walau pool warmup belum selesai.

## P3 — Kotlin shell (APK)

- [ ] Minimal APK: tombol Start/Stop, foreground service, ekstrak+exec binary dari assets,
      status dari `/health` (breaker, pool size, standbyReady).
- [ ] WebView token-farm: load chat.z.ai, inject harvest JS (porting dari
      `cmd/token-collector/harvest.go`), token → pool sqlite `filesDir`.
- [ ] Konfigurasi in-app: AUTH_TOKEN, .glmkey, ZAI_TOKENS (disimpan di EncryptedSharedPreferences
      atau sejenis — JANGAN plaintext log/journald; pelajaran banner auth token di VPS).
- [ ] Opsional: tunnel on-demand (cloudflared) kalau ada konsumen di luar HP.

## P4 — Operasional di Android

- [ ] Pengganti systemd timers: storm-alert / watchdog jadi in-process loop bridge
      (lebih simpel daripada port cron ke Android).
- [ ] Porting logika refill.sh yang masih relevan (multi-session mint di HP = multi-tab /
      multi-restart WebView; standby pool staged di filesDir).
- [ ] Dokumentasi final: cara pakai harian (Start → pakai → top-up pool → Stop).

## Catatan teknis penting (bawa dari glm)

- e2e SSE verdict valid cuma kalau tail mengandung `data: [DONE]` + zero error events;
  `curl -s` tanpa `-f` exit 0 walau HTTP error.
- SQLite hot-swap harus ke filename unik per generasi (`tokens-live-<stamp>.sqlite`);
  swap di path yang sama pada DB WAL = replay `-wal` stale (ghost rows).
- Jangan print nilai rahasia; mask by VALUE shape (JWT `eyJ…`, hex 32+, base64 panjang).
- Pre-push secret scan: diff range `origin/main..HEAD`.
- Breaker: 3 consecutive fail → open 90 s; standby swap = same-second recovery (proven drill).
