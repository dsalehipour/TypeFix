# Releasing the TypeFix Android app

Android releases are built, signed, and published automatically by the
[`Android Release`](../.github/workflows/android-release.yml) GitHub Actions
workflow whenever an `android-v*` tag is pushed. You do **not** build or upload
APKs by hand.

## How a release is cut (the normal flow)

1. Bump the version in [`android/app/build.gradle.kts`](app/build.gradle.kts):
   - Increment `versionCode` (integer, +1 each release).
   - Set `versionName` to the new semantic version, e.g. `0.1.21`.
2. Commit the bump (and whatever changes ship in the release).
3. Tag and push. **The tag version must match `versionName`**, prefixed with
   `android-v`:

   ```bash
   git tag android-v0.1.21
   git push origin android-v0.1.21
   ```

That's it — the workflow takes over and:

- Sets up JDK 17 + the Android SDK (platform 35).
- Recreates the signing keystore from repo secrets (see below).
- Runs `./gradlew :app:assembleRelease` to produce a signed APK.
- Renames it to `TypeFix-Keyboard.apk` (the exact name the in-app updater and
  the README download button expect).
- Creates the GitHub Release for the tag, marked **Latest** (not a pre-release),
  titled `TypeFix Keyboard for Android v0.1.21`, with auto-generated notes and
  the APK attached.
- Refreshes the rolling **`android-latest`** release by re-uploading the same
  APK (its pre-release flag is left untouched).

If a release for the tag already exists, the workflow just re-uploads the APK
(`--clobber`) instead of failing, so re-runs are safe.

> [!IMPORTANT]
> Every release must be signed with the **same** keystore. If the key changes,
> the in-app updater can no longer install over an existing install and every
> user would have to uninstall + reinstall. Keep the keystore and its passwords
> backed up.

## Re-running / publishing manually

Open **Actions → Android Release → Run workflow**. Leave the `tag` input blank to
publish the current ref, or enter an existing `android-v*` tag to (re)publish it.
The tag must already exist.

## One-time secret setup

The workflow signs the build with credentials stored as GitHub Actions repo
secrets. These are derived from the local, gitignored signing material:

- Keystore: `android/keystore/typefix-release.jks`
- `android/keystore.properties` (`storePassword`, `keyAlias`, `keyPassword`)

The four secrets the workflow reads:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | base64 of `android/keystore/typefix-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore `storePassword` |
| `ANDROID_KEY_ALIAS` | the key alias (`typefix`) |
| `ANDROID_KEY_PASSWORD` | the `keyPassword` |

These have already been configured for `dsalehipour/typefix`. To regenerate them
(e.g. after rotating the keystore), run from the repo root:

```bash
# Encode the keystore and store it (newlines are stripped; the workflow's
# `base64 --decode` tolerates either form).
base64 -i android/keystore/typefix-release.jks | tr -d '\n' \
  | gh secret set ANDROID_KEYSTORE_BASE64 -R dsalehipour/typefix

# Passwords / alias (read them from android/keystore.properties).
printf '%s' 'Tf-release-2026!' | gh secret set ANDROID_KEYSTORE_PASSWORD -R dsalehipour/typefix
printf '%s' 'Tf-release-2026!' | gh secret set ANDROID_KEY_PASSWORD      -R dsalehipour/typefix
printf '%s' 'typefix'          | gh secret set ANDROID_KEY_ALIAS         -R dsalehipour/typefix

# Verify (names only, never values):
gh secret list -R dsalehipour/typefix
```

> The `GITHUB_TOKEN` used to publish releases is provided automatically by
> Actions; no secret is needed for it.

## Release notes convention

Release bodies are a **clean changelog only** — no "how to update" instructions
(those already appear inside the app's Updates card). The workflow uses
`--generate-notes`; if you prefer a hand-written changelog, edit the release
notes in the GitHub UI after the run finishes.
