# Signing

The release keystore is committed to this repository, at `keystore/bplay-release.jks`, with its
password in `build.gradle` in plain text.

That is deliberate. It is worth explaining, because a checked-in signing key is normally a serious
mistake.

## Why

Android identifies an app by its signature. An installed app can only be upgraded by a build signed
with the **same** key — otherwise the install fails and the user has to uninstall first, losing
their settings. For an app distributed as a sideloaded APK rather than through a store, that means
every release must be signed with one stable key, forever.

The alternatives are worse:

- **Generate a key in CI.** Every release gets a different signature, so no release can ever
  upgrade another. Users would uninstall and reinstall each time.
- **Keep the key in a GitHub secret.** Only the repository owner could produce an installable
  update, and nobody could verify that the published APK matches the source. For a project whose
  entire premise is that you can check what is running on your TV, that is a step backwards.

## What this key does and doesn't protect

On the Play Store, a signing key is what proves an update came from the original developer. Here,
there is no store and no update channel — you get the APK from a GitHub release you chose to visit.
The signature's job is reduced to one thing: letting one install replace another.

So treat it as **an identifier, not a secret**. Concretely:

- Anyone can build an APK that installs over this one as an "update". The protection against that
  is that you choose where to download from, exactly as with any sideloaded app.
- The key protects nothing of value: no store account, no server, no user data, no permissions
  beyond those the app already declares in its manifest.
- The app has no auto-update mechanism, so a malicious build cannot reach you unless you go and
  install it yourself.

## If you would rather not trust it

Build and sign with your own key — the result is functionally identical:

```bash
keytool -genkeypair -v -keystore my-release.jks -alias mykey \
        -keyalg RSA -keysize 2048 -validity 10950

./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=$PWD/my-release.jks \
  -Pandroid.injected.signing.store.password=... \
  -Pandroid.injected.signing.key.alias=mykey \
  -Pandroid.injected.signing.key.password=...
```

You will need to uninstall any previously installed BPlay first, since the signatures differ.

## Verifying a published APK

```bash
apksigner verify --print-certs bplay-firetv.apk
```

The certificate should read `CN=BPlay Mirror, OU=Open Source, O=BPlay`. Compare the SHA-256 digest
with the one in the GitHub Actions log for that release — the build is public, so you can check that
the published file came from the commit it claims to.
