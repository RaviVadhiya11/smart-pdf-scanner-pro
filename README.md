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

## Current access and security behavior
- A validated internet connection is required to use DocNexa. On-device processing remains local unless a remote feature is chosen.
- Optional App Lock (off by default) uses the Android device PIN, pattern or password. It blocks screenshots in DocNexa-owned windows while enabled; it is not file-level encryption.
- PDF document-card thumbnails are rendered off the UI thread. Device verification of the access gate, credential flow and performance remains necessary.

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


## Supabase AI Edge Function
The repository includes `supabase/functions/docnexa-ai/index.ts` for the in-app PDF AI assistant.
Deploy that Edge Function to the same Supabase project used by the app and configure server-side secrets:

- `OPENAI_API_KEY` — your OpenAI API key. Never place it in the Android app or commit it to GitHub.
- `OPENAI_MODEL` — optional model override. If omitted, the function uses `gpt-6.2-astra`.

The function requires a signed-in Supabase user, validates supported tasks and document length, and calls the OpenAI Responses API from the server.

## Known conversion scope
- Office to PDF is an offline text-extraction conversion for DOCX/XLSX/PPTX. It does not reproduce the original Office layout, images or complex tables.
- PDF to Word creates an editable DOCX from selectable PDF text. Scanned/image-only PDFs should use OCR first.
- These tools are intentionally described as document utilities rather than pixel-perfect Microsoft Office renderers.


## Google Play Premium setup (DocNexa 1.7.0)

The app uses the Google Play Billing SDK with a fail-closed server verification flow. No subscription is sold until the authenticated verification backend reports ready. All existing PDF tools remain free. Verified members can hide native ads and use the 4K/8K upscale resolution options; these use ordinary interpolation, not AI super-resolution.

1. Create the app in Play Console with package `com.pixelfire.smartpdfscanner`, and configure your payments profile and app signing.
2. Upload a signed AAB to an internal test track. Create subscription product `docnexa_premium` with auto-renewing base plans `premium-monthly` (suggested India price INR 99) and `premium-yearly` (suggested INR 699). Activate the plans. Prices are fetched from Google Play rather than hardcoded.
3. Enable Google Play Android Developer API in Google Cloud. Create a service account and grant it the minimum Play Console permissions required to read subscription products and verify and acknowledge purchases. Do not commit its private key.
4. Deploy `supabase/functions/docnexa-play-billing/index.ts` to the same Supabase project already configured in the Android app. Set `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` as a private server secret and `PLAY_BILLING_ENABLED=true` only when ready. Never place the service account JSON in the Android app.
5. Use license testers and an internal test build to test purchase, pending payment, cancellation, grace period, expiry, restore, account changes and ads. The backend validates Google purchase tokens and the obfuscated DocNexa account ID, verifies subscription state and expiry, and acknowledges valid initial purchases. Client refreshes on resume and periodically. RTDN/Pub/Sub, durable entitlement storage, monitoring and offline entitlement policy are still recommended before public release. The currently implemented client uses a short in-memory entitlement window, so online verification is required.
6. Complete the Play Console Data safety and subscription disclosures, and ensure the privacy policy explains Google Play purchase processing. Do not activate public sales until the backend and end-to-end license tests pass.

