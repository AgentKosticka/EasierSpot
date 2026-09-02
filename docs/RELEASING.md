# Releasing EasierSpot

## Automatic versioning

`version.properties` anchors versioning to the merge of PR #4 (`7910cceebb5f2b60b18ee237ae9a0b3087cd708a`). The Android build counts Git commits after that baseline and increments both values for every commit in the resulting history:

- `versionName`: `1.3.PATCH`
- `versionCode`: `8 + PATCH`

The repository-health commit is therefore `v1.3.1` / `versionCode 9`. No workflow writes a follow-up "bump version" commit, so there is no recursive Actions loop and commits from humans, bots, merges, or other sources all affect the version the same way.

CI checks out full history (`fetch-depth: 0`). Builds made without Git metadata safely fall back to the baseline offset; `EASIERSPOT_VERSION_OFFSET` can be supplied explicitly for unusual source-package builds.

Run this locally to see the version for the current commit:

```bash
./gradlew -q :app:printVersion
```

## CI behavior

`Android quality gates` runs once per logical change:

- pull-request commits trigger only `pull_request` CI;
- `master` commits trigger only `push` CI;
- concurrency cancels an older in-progress run for the same PR or branch when a newer commit arrives;
- the Linux build job creates the debug APK and instrumentation APK once, uploads them as `android-build`, and the macOS emulator job installs those exact APKs instead of rebuilding them;
- `master` builds additionally include an unsigned release APK in the same artifact;
- artifact compression is disabled because APKs are already ZIP-compressed, reducing upload/download CPU time.

## Creating a release

1. Wait for `Android quality gates` to pass on the desired `master` commit.
2. Read that commit's app version with `./gradlew -q :app:printVersion` or from the CI summary.
3. Create and push the matching tag, for example `v1.3.12`.
4. `Release APK` verifies that the tag exactly matches the APK's `versionName`.
5. The release workflow first reuses the successful `android-build` artifact for the exact tagged commit. If that artifact is missing, expired, or incomplete, it performs a clean fallback `assembleRelease` from the tag.
6. The APK is aligned, signed, verified, checksummed, and attached to a GitHub Release with generated notes.

The release workflow needs these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — the release keystore encoded as base64;
- `ANDROID_KEY_ALIAS` — signing key alias;
- `ANDROID_STORE_PASSWORD` — keystore password;
- `ANDROID_KEY_PASSWORD` — signing key password.

A release fails instead of publishing an unsigned or ephemeral-key APK when any signing secret is missing. This preserves Android update compatibility between releases.

## Starting a new major/minor line

To intentionally change `X` or `Y`, update `version.major` / `version.minor` in `version.properties`. If the patch counter should also restart, update `version.baseCommit`, `version.basePatch`, and `version.baseCode` together so Android's numeric `versionCode` still increases monotonically.
