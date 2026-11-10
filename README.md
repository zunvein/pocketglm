# glmroid 🤖

**GLM bridge, bundled buat Android.** Fork arsitektur dari [zunvein/glm](https://github.com/zunvein/glm) — same battle-tested bridge code, new home: HP lo.

## Kenapa repo ini ada

VPS asal bakal expire. Proyek ini mindahin GLM bridge ke HP Android sebagai **app on-demand**:
nyala pas dipake (≤6 jam), mati bersih pas selesai. Bukan server 24/7 — HP lo tetap HP lo.

Target bentuk akhir: **satu APK Kotlin** yang bundle binary bridge (Go, `GOOS=android GOARCH=arm64`)
plus token-farm via WebView (inject harvest JS ke chat.z.ai → device token masuk pool sqlite lokal).

## Kenapa ini viable (hasil verifikasi, bukan asumsi)

- Bridge = **pure-Go, no CGO** (`modernc.org/sqlite`) → cross-compile android/arm64 gampang.
- Storm-hardening (standby pool + swap instan, breaker 3-strike, random token pick) = logika in-process → ikut pindah, gak tergantung VPS.
- Pool sqlite = file di `filesDir` app → persist antar session.
- Token mint dari HP = identity **residensial** (IP rumah + device real) — kualitas identity lebih bagus dari datacenter VPS.

Roadmap & arsitektur detail: lihat `docs/ROADMAP.md`.

## Struktur repo

Import dari `glm` `main` @ `633abfc` (minus tooling VPS-specific) + `go.mod`/`go.sum` (yang sengaja gak di-track di glm).

| Path | Isi |
|---|---|
| `internal/zbridge/` | Bridge core: SSE proxy, captcha, standby pool, breaker |
| `cmd/token-collector/` | Collector desktop (referensi harvest JS & FeiLin) |
| `pool/` | Script operasional (refill, storm-alert, storm-drill) — bakal di-port ke Android |
| `tests/` | Test suite |
| `app/` (todo) | Kotlin shell APK |
| `docs/ROADMAP.md` | Rencana migrasi bertahap |

## Status

🚧 **Baru mulai.** Repo ter-rakit dari glm main + go.mod/go.sum. Belum ada Kotlin, belum ada workflow build.

Lisensi: ikut glm (lihat `LICENSE`).
