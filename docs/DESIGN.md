# PocketGLM interface

The app is one person's tool for running a local bridge on their own phone.
It is opened to answer three questions, in this order: is it running, does the
pool have tokens, and what do I copy to point another app at it. Everything on
screen serves one of those, plus the once-in-a-while settings behind Settings.

## Design read

Reading this as: a single-user utility app for someone who also reads logs and
pastes keys, in a warm-paper, editorial-minimal language, dial
**ENERGY 1 / RHYTHM 2 / MOTION 1**.

Direction source: the owner asked for the PocketSeek app's look and type, so the
palette, spacing grid and type scale below are shared with that app on purpose.
This is a sanctioned reference, not a copied identity (antislop R-30 is about
cloning a popular product nobody asked for).

- ENERGY 1: the app is read at a glance, in a hurry, next to a failing request.
  No decorative color, no illustration, no hero.
- RHYTHM 2: the same card grammar on every screen, with three deliberate
  breaks: the big pool number on Home, the 2x2 figure grid on Stats, and the
  log block (a sunk surface, not a card).
- MOTION 1: 160 ms screen fade, 220 ms card cascade, 260 ms tab highlight. No
  loops, no pulses, nothing moves on its own.

## Tokens

Colors live in `res/values/colors.xml` and `res/values-night/colors.xml`.

| Role | Light | Dark | Why |
|---|---|---|---|
| bg | `#F4F2EC` | `#121411` | warm paper, easier on the eye at night than pure white or blue-black |
| card | `#FFFDF8` | `#1A1D18` | one step up from bg so cards read as surfaces without shadows |
| sunk | `#F4F2EC` | `#141613` | stat tiles and log blocks: recede instead of lift |
| fg | `#1A1A18` | `#E8E6DF` | 14:1 on card |
| muted | `#5F5D57` | `#A3A29A` | 6.2:1 on bg, still legal for 12.5 sp text |
| line | `#E0DBCF` | `#2E322B` | 1dp hairline, the only separation device |
| accent | `#1D4B3F` | `#2A6B58` | deep green: the one filled button and the active tab |
| err / ok / cool | `#93362B` / `#2A5B22` / `#7A5B16` | `#EBA497` / `#A8D49A` / `#DFBD72` | state only (failed, ready, waiting), never decoration |

Two cores (bg, card) plus one accent, per antislop R-29. State colors are
semantic, not palette.

- Spacing: 4dp grid, 16dp page padding, 8/12/16dp inside cards.
- Radii: 8dp controls, 12dp cards, 999 pills, 10dp tiles.
- Tap targets: 48dp for buttons and inputs, 56dp for a switch row or a
  tappable address, 44dp for a compact row action.
- Type: system sans, weight carries hierarchy (15sp body, 17sp card title,
  22sp screen title, 28sp the one number that matters). Mono is used only where
  a character-by-character read matters: keys, URLs, logs, masked tokens.

## Layout

Card, not table. Each card is one question with the action that answers it,
because the app is used in bursts: open, check, act, close.

- Home: bridge state, pool size, two counters, addresses, the farm controls,
  and the phone's own battery settings.
- Stats: the same numbers in detail, the breaker, and bridge.log with the
  share/copy actions that end up in a bug report.
- Accounts: who the bridge talks to Z.AI as.
- Chat: the end-to-end test, because "the client works but my app does not" is
  the most common failure.
- Settings: key, public link, friend keys, bypass switch, crash log, version.

## States

Every card that shows live data has three states, written in the source: no
bridge (says so and points at Home), bridge up but warming, bridge up and
healthy. Empty lists say what to do next ("No friend keys yet" sits above the
button that creates one). Nothing shows a number it does not have: a
disconnected bridge shows an en dash, never a zero.

## What is deliberately absent

No onboarding carousel, no gauges, no charts (there is no time series to draw
that a number would not answer faster), no icon per card, no uppercase labels,
no shadows, no gradients, no glow.
