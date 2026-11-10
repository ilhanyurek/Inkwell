# Listing Inkwell on Zapstore

Zapstore is an app store built on Nostr. The listing text, icon and screenshots come from
[`zapstore.yaml`](../zapstore.yaml) and the [Fastlane metadata](../fastlane/metadata/android). APKs come from
GitHub Releases. Only the owner can do the one-time setup, because it needs the app's signing key.

## One-time setup (owner only, on your own computer)

1. **Nostr signer.** Use a remote signer such as Amber on Android, which gives a `bunker://…` link. With a
   bunker, your private key (`nsec`) never has to be pasted anywhere.
2. **Install zsp.** Download a binary from <https://github.com/zapstore/zsp/releases>, or run
   `go install github.com/zapstore/zsp/v2/cmd/zsp@latest`.
3. **Get the signing keystore** (your backup) and its passwords ready. The keystore must be the one that signs
   the GitHub release APKs, otherwise the proof fails.
4. In a clone of this repository, run `zsp` with no arguments. The wizard:
   - reads `zapstore.yaml` and finds the latest release (`Inkwell-x.y.z.apk`);
   - **Prove it's yours:** asks for the keystore and proves that the APK's signing certificate belongs to your
     Nostr key;
   - asks for a signer: choose the bunker;
   - asks how new releases should reach Zapstore: choose **Let Zapstore publish them for you**.
5. If the wizard changed `zapstore.yaml`, commit and push it.

Never put the keystore, its passwords, an `nsec` or a `.env` file in the repository.

## After that

The **Zapstore** workflow (`.github/workflows/zapstore.yml`) runs after every successful Release run and
publishes the new release with `zsp publish -q`. Release notes come from the matching section of
[`CHANGELOG.md`](../CHANGELOG.md), so add a section there before each release.

The workflow signs with your Nostr key, which it reads from a repository secret. Add it once:
**Settings → Secrets and variables → Actions → New repository secret**, name `ZAPSTORE_NSEC`, value your
`nsec1…` key. Without the secret the workflow skips publishing. To stop automatic publishing, delete the secret.

To publish again by hand, run **Actions → Zapstore → Run workflow**, or run `zsp publish` in a clone on your
own computer.

To refresh the screenshots, run **Actions → Store screenshots → Run workflow**. It takes them on an emulator and
commits them to `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
