# DocNexa Google Drive setup

The manual Android file picker works without direct OAuth when a compatible cloud provider is installed. Direct Drive authorization, user-confirmed original-file upload, Premium batch upload (up to 10), and restore of the latest 100 app-created backups are implemented in the Android client. They require Google Cloud configuration and device testing before release.

1. Open the Google Cloud project used for this Android app. Enable Google Drive API.
2. Configure Google Auth Platform branding, audience, support email and privacy policy. Add the drive.file scope. For testing, add intended test Google accounts.
3. Create Android OAuth clients with package `com.pixelfire.smartpdfscanner` and the SHA-1 of each actual signing certificate (debug APK, release/upload certificate as applicable, and Google Play App Signing certificate for Play installs). Do not put a client secret or refresh token in the Android app.
4. Install the build on an Android device with updated Google Play services. In Settings > Google Drive & Backup > Connect Google Drive, grant access. Verify the displayed Google account before uploading.
5. Test cancellation, consent denial, expired token, no internet, 403 API disabled, single and Premium batch upload, original SHA-256 equality after restore, account switching, and removal of Google access through Google Account.
6. Configure production OAuth audience and complete any verification required by Google before distributing.

Direct OAuth uses short-lived tokens, does not persist them, and uses the least-privilege drive.file scope. The selected Google account owns the uploaded file. Direct backups are tagged with a SHA-256 hash of the current DocNexa account ID so the restore list is isolated. Guest backups are tagged as guest, so use the same Google account and DocNexa account mode to restore.

Automatic background backup and continuous multi-device sync are **not implemented**. They require an opt-in design, reliable job scheduling, account binding, conflict policy, entitlement verification and secure handling of long-lived credentials if server-side offline access is used. Do not claim these features are live.
