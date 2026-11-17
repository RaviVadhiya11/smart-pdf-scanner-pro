# Smart PDF Scanner Pro — Native Kotlin

Native Android app using Kotlin and Google ML Kit Document Scanner.

## Included
- Premium dark scanner home screen
- Google ML Kit document scanning UI
- Auto edge detection, crop and scanner filters supplied by ML Kit
- Gallery import and multi-page scans (up to 100 pages)
- PDF/JPEG result formats
- Save scanned PDF to Downloads/SmartPDFScanner
- Open/share the last PDF during the current session
- GitHub Actions cloud build for an installable debug APK and release AAB

## Build on a phone via GitHub Actions
1. Open the Actions tab.
2. Select **Build Smart PDF Scanner Pro** and tap **Run workflow** on main.
3. Open the completed run and download **Smart-PDF-Scanner-Pro-Android-Builds**.
4. Extract the artifact ZIP:
   - `app-debug.apk` is for installing and testing on an Android phone.
   - `app-release.aab` is the release bundle, currently unsigned.

## Local build requirements
JDK 17, Android SDK Platform 36, Build Tools 36.0.0 and Gradle 8.13.
Run `gradle :app:assembleDebug` for APK or `gradle :app:bundleRelease` for AAB.

## Play Store signing — keep credentials private
The workflow currently produces an unsigned release AAB. A private upload keystore is required for a Play Store upload. Do not commit a keystore or passwords to this public repository.
To enable signed releases, create an upload keystore privately and configure GitHub Actions repository secrets named `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The workflow must decode the keystore to a temporary runner file and use these secrets only during signing. Never paste passwords or keystore data into chat or a public issue.

## Scope and testing
This is a first native scanning version, not yet a full PDF editor. OCR/search, merging existing PDFs, compression settings, password protection, persistent scan history and in-app PDF editing are not included. Test on physical devices before Play submission.


## Play Store readiness checklist
- The app targets Android 16 / API 36, as required for new mobile apps submitted from 31 August 2026.
- The public privacy policy and account-deletion request pages are in `docs/`.
- To publish those pages, open **Repository Settings → Pages**, select **GitHub Actions** as the build/deployment source, then run the **Publish DocNexa policy pages** workflow. The intended URLs are:
  - `https://ravivadhiya11.github.io/smart-pdf-scanner-pro/privacy-policy.html`
  - `https://ravivadhiya11.github.io/smart-pdf-scanner-pro/delete-account.html`
- The app contains Google Mobile Ads and must be declared as **Contains ads** in Play Console. Complete the Data safety, target audience, content rating, app access and account deletion declarations accurately.
- The current release bundle is unsigned unless an upload keystore is configured. Create a private upload keystore and add the four GitHub Actions secrets documented above before attempting a Play Console upload. Never commit the keystore or its passwords.
- Complete a physical-device test of sign-up, email confirmation, sign-in, account switching, scan/save/open/share, cloud sync, account deletion, notifications, offline behaviour and ads before production release.
