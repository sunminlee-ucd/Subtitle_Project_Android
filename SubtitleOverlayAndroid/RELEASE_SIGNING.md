# Release signing setup

Customer-facing Android APKs must always be signed with the same release key. Do not distribute debug APKs to customers.

## 1. Create the release key once

Run this on a trusted local machine and keep the generated file private:

```bash
keytool -genkeypair -v \
  -storetype JKS \
  -keystore subtitle-overlay-release.jks \
  -alias subtitle-overlay \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

The explicit `JKS` store type allows the keystore password and key password to be managed independently. Use strong passwords and back up the `.jks` file and its passwords in a secure password manager/offline backup. Do not commit the keystore or passwords to Git.

## 2. Convert the keystore to Base64

### Windows PowerShell

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("subtitle-overlay-release.jks")) | Set-Content -NoNewline keystore.base64.txt
```

### macOS / Linux

```bash
base64 < subtitle-overlay-release.jks | tr -d '\n' > keystore.base64.txt
```

## 3. Add GitHub Actions Secrets

In the repository, open **Settings → Secrets and variables → Actions** and create these repository secrets:

- `ANDROID_KEYSTORE_BASE64`: contents of `keystore.base64.txt`
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: `subtitle-overlay` (or the alias you chose)
- `ANDROID_KEY_PASSWORD`: key password

Never paste these values into source files or workflow YAML.

## 4. Build a customer release

Open **Actions → Build signed release APK → Run workflow** and enter a public version name such as `0.3.0`.

The workflow will:

1. restore the release key only inside the temporary GitHub runner;
2. run unit tests and Android lint;
3. build the release APK;
4. verify the APK signature with `apksigner`;
5. assign an automatically increasing `versionCode` (`1000 + workflow run number`);
6. upload the signed APK plus a SHA-256 checksum as a workflow artifact.

Only distribute the artifact whose filename ends in `-signed.apk`.

## Update rule

Once the first customer release is distributed, keep using the same release signing key for every update. Replacing the key will make Android reject the new APK as an update to the installed app.

Do not delete or recreate `.github/workflows/build-signed-release.yml` after customer distribution unless you also preserve monotonic version codes. Its run number is used to generate the customer APK's `versionCode`.
