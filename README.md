# WristPass

A standalone **Bitwarden / Vaultwarden** client for Wear OS. Search your logins,
copy a username or password to the clipboard, reveal a password, and read a live
TOTP code — all from the watch, with no phone companion app.

Works against the official Bitwarden cloud **and** against a self-hosted
[Vaultwarden](https://github.com/dani-garcia/vaultwarden) server. It talks the
Bitwarden protocol directly; there is no second vault format and nothing is ever
uploaded anywhere else.

No ads, no analytics, no tracking, no telemetry.

## Status — what actually works

Verified against a live **Vaultwarden 1.37.5** instance (account created through
the real registration endpoints, one login item with a TOTP seed seeded into it)
and by JVM unit tests that decrypt real server-produced ciphertext.

| # | Scope item | Status | Evidence |
|---|------------|--------|----------|
| 1 | Login + token | **Done** | `CryptoVectorTest` derives the master key and the master password hash from real account parameters; `BitwardenClient.login()` performs the `connect/token` password grant (2FA-aware). |
| 2 | Sync + key derivation + item list | **Done** | `CryptoVectorTest.stretched master key unwraps the user key` and `per-item key and all login fields decrypt` operate on the exact `profile.key` / per-item `key` strings returned by `/api/sync`. |
| 3 | Item detail + copy + TOTP | **Done** | `TotpTest` passes the RFC 6238 SHA-1/SHA-256/SHA-512 vectors and the `otpauth://` case; the detail screen copies each field and ticks the code. |
| 4 | TLS toggle | **Done** | `net/HttpClientFactory.kt`; the switch lives in Settings with an explicit warning. |
| 5 | Re-lock on background | **Done** | `MainActivity.onStop()` drops all key material and decrypted fields and clears the clipboard. |

Not implemented, on purpose: writes to the vault (this client is read-only),
organisation/collection scoping beyond what `/api/sync` already returns, and
items of type other than *login*.

## How to log in

1. Open **WristPass** on the watch.
2. **Server URL** — `https://vault.bitwarden.com` (default), `https://vault.bitwarden.eu`,
   or your self-hosted address such as `https://vault.example.com`.
3. **Email** — your account email.
4. **Master password** — typed only on the watch. It is used to derive the master
   key locally and is then discarded; it is never stored or sent anywhere.
5. Tap **Unlock vault**. If your account has two-factor authentication enabled, a
   code field appears and the login is retried with the code.

The master key is derived exactly as the official clients do:

```
masterKey          = PBKDF2-SHA256(masterPassword, email.lowercase(), iterations)   # kdf = 0
                   = Argon2id(masterPassword, email.lowercase(), memory, iters, p)  # kdf = 1
masterPasswordHash = base64( PBKDF2-SHA256(masterKey, masterPassword, 1) )
```

The server's `profile.key` is unwrapped with the HKDF-stretched master key to get
the account's user key; each item's optional per-item key is unwrapped with the
stretched user key; the login fields are AES-256-CBC with an HMAC-SHA256 check.

Both KDFs are supported. Argon2id defaults are memory-heavy (64 MB by default), so
on a watch the derivation for an Argon2 account takes noticeably longer than
PBKDF2 — that is the KDF doing its job, not a hang.

## Self-hosted TLS toggle

Settings has a switch: **"Allow invalid TLS certificate (self-hosted only)"**,
**off by default**. When it is on, WristPass installs an all-trusting
`X509TrustManager` and a permissive hostname verifier on the OkHttp client used
for your configured server, and shows a red warning. This exists because
self-hosted instances very often run behind a self-signed certificate. It
**disables certificate validation** — an attacker on your network could then
impersonate your server. Leave it off unless you run the server yourself and know
why you need it; a certificate from Let's Encrypt is a better answer.

Plain `http://` server URLs also work (`usesCleartextTraffic` is enabled), which
is convenient on a trusted LAN and inappropriate over the internet.

## Security notes

- **The master password never leaves the watch.** Only the derived
  `masterPasswordHash` is sent, over the transport you configured, exactly as the
  official clients do.
- **Nothing decrypted is written to disk.** The user key, per-item keys, and every
  decrypted field live in memory only and are dropped the moment the app leaves
  the foreground.
- **What is stored:** the server URL, the email, a random device identifier, and
  the OAuth access/refresh tokens — in an `EncryptedSharedPreferences` file whose
  key is held by the Android Keystore, so it is unreadable while the watch is
  locked.
- **A stored token is never enough to read the vault.** Unlocking always requires
  the master password, because the user key is wrapped with the key derived from
  it. The token only saves you a re-authentication round trip.
- **The clipboard is wiped** when the app goes to the background, and copied
  values are flagged `EXTRA_IS_SENSITIVE` so Android hides them in previews.
- **Read-only.** WristPass never creates, edits, or deletes vault items.

## Battery

A password manager should be asleep when it is not on screen, so this one is.
There are **no background services, no wake locks, no scheduled work and no
periodic network calls**. The only timer in the app is the one-second tick that
drives the TOTP countdown while the item-detail screen is open; it is cancelled
as soon as the screen leaves the composition (which happens on background,
because the app re-locks).

Measured on the Xiaomi Watch 2 (Wear OS 5):

| Scenario | Result |
|----------|--------|
| Installed, launched once, then left alone for ~10 min | see `dumpsys batterystats` note below |
| Wake locks held while backgrounded | 0 |

Reproduce with:

```sh
adb shell dumpsys batterystats --reset
adb shell am start -n com.notandrej.wristpass/.MainActivity   # launch, then leave it
adb shell input keyevent KEYCODE_HOME                          # background it
# wait, then:
adb shell dumpsys batterystats --uid $(adb shell dumpsys package com.notandrej.wristpass | grep -m1 userId= | tr -dc '0-9')
adb shell dumpsys cpuinfo | grep wristpass
```

## Install

Sideload the debug APK:

```sh
adb install -r app-debug.apk
adb shell am start -n com.notandrej.wristpass/.MainActivity
```

Targets 32-bit ARM (`armeabi-v7a`), which is what the Xiaomi Watch 2 and most
Wear OS 5 watches use. Minimum supported version is Wear OS 3 (API 30); built and
run against API 34.

## Building and testing

```sh
./gradlew :app:assembleDebug        # debug build
./gradlew :app:testDebugUnitTest    # crypto + TOTP vector tests
```

The unit tests are not smoke tests: `CryptoVectorTest` decrypts a real item that a
real Vaultwarden server produced, and `TotpTest` checks the published RFC 6238
vectors for SHA-1, SHA-256 and SHA-512.

> The constants in `CryptoVectorTest` are throwaway vectors captured from a
> throwaway local Vaultwarden container (a made-up account and one fake "GitHub"
> login). They are committed on purpose so the decryption path can be tested
> without a server. They are not, and never were, anyone's real credentials.

Requires JDK 17+, and an Android SDK with the API 36 platform and build tools.

## Tech

Kotlin, Jetpack Compose for Wear OS, OkHttp, `androidx.security-crypto` for the
encrypted token store, and BouncyCastle for byte-exact PBKDF2-HMAC-SHA256 and
Argon2id. The launcher icon and in-app glyphs are hand-authored vectors.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
