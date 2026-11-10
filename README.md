# PocketGLM 📱

> ⚠️ **Experimental project — proof of concept.** Not affiliated with Z.AI / ChatGLM / Aliyun.

**Run a GLM bridge entirely on an Android phone — bundled in one app.**

PocketGLM is an experiment in taking a self-hosted GLM (chat.z.ai) bridge that normally lives on a server and shrinking it into a single Android APK: the same Go bridge code, cross-compiled to `android/arm64`, launched by a thin Kotlin shell, with the device-token harvester replaced by a WebView — no Termux, no root, no Linux distribution.

The usage model is **on-demand**: start the bridge when you need it, stop it when you're done. The phone stays a phone; nothing runs 24/7.

## Why this might work

- **The bridge is pure Go, no CGO** (`modernc.org/sqlite`) — it cross-compiles to android/arm64 as a small static binary (~5 MB).
- **Storm resilience is in-process** (circuit breaker, pre-minted standby token pool, random token pick) — it moves with the binary, not with the server.
- **Token minting doesn't need a headful desktop browser if you have a real one** — on a phone, the system WebView *is* a real browser stack with a residential IP and a real device, which is exactly what browser-based bot detection wants to see.
- **State is just a SQLite file** — the token pool persists between runs in app-private storage.

## Architecture

| Piece | Role |
|---|---|
| `internal/zbridge/` | Go bridge: OpenAI/Anthropic-compatible proxy for chat.z.ai + captcha machinery, breaker, standby pool |
| `cmd/token-collector/` | Desktop collector (Playwright headful) — reference implementation for the WebView harvester |
| `app/` *(planned)* | Kotlin shell APK: start/stop UI, foreground service, execs the bundled bridge binary |
| WebView token farm *(planned)* | Loads chat.z.ai, injects the Aliyun captcha SDK boot script (same logic as `cmd/token-collector/harvest.go`), harvests device tokens into the local SQLite pool |

## Status

🚧 In progress.

- ✅ android/arm64 cross-compile in CI (GitHub Actions), with vet/tests + smoke test; artifact `zai-api-android-arm64` published on every push
- ✅ Aliyun captcha credentials configurable via env (`ALIYUN_ACCESS_KEY`, `ALIYUN_SECRET_KEY`, `CAPTCHA_SCENE_ID`) with compiled-in fallbacks
- 🔄 Kotlin shell APK scaffold
- 🔄 WebView token-farm port (open question: does the Aliyun risk engine accept device registration from an Android WebView?)
- ⬜ On-device end-to-end + storm-drill

## Build

Everything builds in [GitHub Actions](.github/workflows/build-android.yml) — no local toolchain needed:

1. Push to `main` (or run the workflow manually from the Actions tab).
2. Download the `zai-api-android-arm64` artifact from the completed run.

## Lineage

Derived from [zunvein/glm](https://github.com/zunvein/glm), which carries the operational hardening (id-fix, model supplements, captcha breaker, storm tooling, privacy redaction) on top of [izaart95-jpg/GLM-Free-API](https://github.com/izaart95-jpg/GLM-Free-API). Same bridge, new host.

## License

See [LICENSE](LICENSE).
