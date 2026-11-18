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
   - `app-release.aab` is the release bundle. It is signed only when all four required signing secrets are configured; CI now fails if any are missing.

## Local build requirements
JDK 17, Android SDK Platform 36, Build Tools 36.0.0 and Gradle 8.14.4.
Run `gradle :app:assembleDebug` for APK or `gradle :app:bundleRelease` for AAB.

## Play Store signing — keep credentials private
The GitHub Actions workflow stops with an explicit error when any required signing secret is missing; it does not publish an unsigned release artifact. A private upload keystore is required for a Play Store upload. Do not commit a keystore or passwords to this public repository.
To enable signed releases, create an upload keystore privately and configure GitHub Actions repository secrets named `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The workflow decodes the keystore to a temporary runner file and signs the release AAB when all four secrets are present. Never paste passwords or keystore data into chat or a public issue.

## Scope and testing
The current app includes code and dependencies for scanning, OCR, PDF editing and document utilities, cloud features, and advertising. Their complete behavior is not established by a successful CI build. Test every user-facing action on physical devices, including error states, large files, permissions, consent, authentication and account deletion, before Play submission.


## Play Store readiness checklist
- The app targets Android 16 / API 36, as required for new mobile apps submitted from 31 August 2026.
- The public privacy policy and account-deletion request pages are in `docs/`.
- To publish those pages, open **Repository Settings → Pages**, select **GitHub Actions** as the build/deployment source, then run the **Publish DocNexa policy pages** workflow. The intended URLs are:
  - `https://ravivadhiya11.github.io/smart-pdf-scanner-pro/privacy-policy.html`
  - `https://ravivadhiya11.github.io/smart-pdf-scanner-pro/delete-account.html`
- The app contains Google Mobile Ads and must be declared as **Contains ads** in Play Console. Complete the Data safety, target audience, content rating, app access and account deletion declarations accurately.
- The release workflow requires all four signing secrets and fails if they are missing. Create a private upload keystore and add the four GitHub Actions secrets documented above before attempting a Play Console upload. Never commit the keystore or its passwords.
- Complete a physical-device test of sign-up, email confirmation, sign-in, account switching, scan/save/open/share, cloud sync, account deletion, notifications, offline behaviour and ads before production release.
