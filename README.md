# PocketGLM

> **Alpha.** Usable day to day, but expect rough edges. Unofficial, and not affiliated with Z.AI, ChatGLM or Aliyun.

PocketGLM runs a local OpenAI and Anthropic compatible API for [chat.z.ai](https://chat.z.ai) (GLM models) on an Android phone. One app, no Termux, no root.

The bridge is the Go server from [zunvein/glm](https://github.com/zunvein/glm), cross-compiled to `android/arm64` and started by a small Kotlin app. The app also gets the device tokens the bridge needs, using the phone's own WebView.

## What you get

- A bridge on `127.0.0.1:3001` for other apps on the phone, and on your Wi-Fi address for other devices.
  Paths: `/v1/chat/completions`, `/v1/messages`, `/v1/models`.
- Your own API key (`sk-pgl-...`, generated on first run, or set your own).
- Optional Z.AI accounts, so models beyond the guest tier work.
- A built-in chat box to test the bridge from inside the app.
- Token supply handled for you: one WebView visit adds about 60 device tokens.

## Install

1. Open the [latest release](../../releases/latest) and download the APK.
2. Allow installing from unknown sources when Android asks.
3. Open the app, choose an account or guest, and press **Start bridge** on the Home tab.

Needs an arm64 phone with Android 8 or newer. Updates install over an older build, because every build is signed with the same key.

## How it works

| Piece | Role |
|---|---|
| `internal/zbridge/` | The bridge: OpenAI/Anthropic compatible proxy for chat.z.ai, captcha handling, circuit breaker, token pool |
| `internal/devmint/` | Builds a batch of device tokens from one real browser visit. Used by `POST /admin/mint` |
| `cmd/token-collector/` | Desktop collector (Playwright). The reference the phone farm was ported from |
| `app/` | Kotlin app: tabs UI, foreground service that runs the bridge, WebView token farm, account storage |

Every chat request uses one device token. The farm opens chat.z.ai in a WebView, captures the device session and one genuine token, and sends both to the bridge. The bridge mints a batch from that session and checks it against Aliyun before adding it to the pool. A batch Aliyun refuses is thrown away.

## Limits to know about

- Each chat reply costs one device token. An empty pool means failing chats until you farm again.
- Guests only reach the flash models. Sign in or paste a token in the Accounts tab for the rest.
- Google sign-in is often refused inside an app. Email and GitHub work.
- The background farm (hidden WebView inside the service) is experimental. Android does not officially support it, so it may not work on every phone.
- Some makers (Xiaomi, Oppo, Vivo, Huawei, Samsung) have their own battery or autostart lists. Allow PocketGLM there if the bridge keeps getting stopped.
- Builds are debug-signed APKs, sideloaded. No Play Store.

## Build

Everything builds in GitHub Actions ([build-apk.yml](.github/workflows/build-apk.yml)), so no local toolchain is needed. A push to `main` builds the bridge and the APK and publishes a release named after the version in `app/app/build.gradle.kts`. Release notes come from `docs/releases/v<version>.md`.

## Lineage

Derived from [zunvein/glm](https://github.com/zunvein/glm), which adds operational hardening (id fix, model supplements, captcha breaker, privacy redaction) on top of [izaart95-jpg/GLM-Free-API](https://github.com/izaart95-jpg/GLM-Free-API). Same bridge, new host.

## License

See [LICENSE](LICENSE).
