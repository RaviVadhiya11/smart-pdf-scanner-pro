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
- GitHub Actions workflow to build a release AAB

## Cloud build from a phone
1. Open the Actions tab.
2. Select **Build Smart PDF Scanner Pro**.
3. Tap **Run workflow** on the main branch.
4. Open the completed run and download **Smart-PDF-Scanner-Pro-AAB**.
5. Extract the artifact ZIP to get `app-release.aab`.

## Build requirements
JDK 17, Android SDK Platform 36, Build Tools 36.0.0 and Gradle 8.13.
Run `gradle :app:bundleRelease` to build locally.

## Release signing
The workflow currently creates an unsigned release AAB. A Play upload key is required for production upload. Keep the keystore and passwords private; never commit them to this public repository. Configure signing using GitHub Actions secrets before Play Store release.

## Scope
This is a first native scanning version, not yet a full PDF editor. OCR/search, merging existing PDFs, compression settings, password protection, persistent scan history and in-app PDF editing are not included. Test on physical devices before Play submission.
