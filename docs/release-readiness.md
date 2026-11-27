# DocNexa 1.9.0 release checklist

Status: release candidate, not approved or published. CI verifies unit tests, lint, signed APK/AAB and release APK 16 KiB ZIP/ELF alignment. Play Console's AAB compatibility report and a 16 KiB device test are still required.

## Physical device QA (record Pass/Fail, OS and file used)
- Android 7 / 11 / 15 / 16: install, upgrade without deleting private files, rotation, large text, navigation, offline and low-memory behavior.
- Scanner: ML Kit availability, camera/gallery, 1 and 100 pages, crop, all filters, save, original quality, print/share.
- PDF: create, merge, split, page reorder/delete/duplicate/extract, numbers, rotate, watermark, password/remove password, compare, details, ZIP, integrity, signature. Test encrypted, malformed and large PDFs.
- Conversion: PDF to JPEG/PNG at free and Premium resolutions, Word/Excel/PowerPoint/text and Office to PDF. Verify text extraction limitations; do not advertise exact layout conversion.
- Image Studio: all formats, compression, resize, 4K/8K memory bounds, transparency, background removal/change, passport, social sizes, OCR, crop, rotate, watermark, batch, ZIP and eraser. 4K/8K uses interpolation, not AI detail generation.
- Invoice: business information, tax, discounts, totals, signature, saved records, PDF/CSV, print.
- Account: sign-up, confirmation, reset, sign-in, account switching, deletion and cloud restore.
- Google Drive: consent, cancel, offline, verified account, upload, restore and original file hash, batch access, revoke/reconnect, expired access. No background automatic sync is claimed.
- Premium: real Play prices, monthly/yearly, cancellation, pending payment, purchase verification, restore, expired/grace period, switching accounts, ad removal and reappearance. Confirm Supabase billing function deployment and Google Play permissions before enabling sales.
- Popups: select, input, custom editing, disabled buttons, error, success, export and billing; check small screens and large fonts.
- Ads: consent, privacy choices, test vs live configuration, no accidental clicks, no ads for verified Premium.

## Play Console owner actions
- Verify developer account; reserve package com.pixelfire.smartpdfscanner; create store listing with accurate screenshots, support contact, public privacy and terms links.
- Upload signed AAB to internal testing; inspect 16 KB report, pre-launch report, target SDK, signing certificate. Register Play app signing SHA-1 in Google OAuth.
- Complete Data safety, ads declaration, app access/reviewer instructions, content rating, target audience, account deletion URL and any applicable AI feature declarations. Check real SDK behavior and live endpoints, not only source.
- Configure docnexa_premium with premium-monthly and premium-yearly active auto-renewing base plans. Deploy verified backend with private service-account secret; use license testers. Do not enable production sales until verified.
- For applicable new personal developer accounts, complete the required closed test (12 opted-in testers continuously for 14 days) and request production access.
- No public release is authorized by this checklist.

Official references: https://developer.android.com/google/play/requirements/target-sdk ; https://developer.android.com/guide/practices/page-sizes ; https://support.google.com/googleplay/android-developer/answer/14151465 ; https://support.google.com/googleplay/android-developer/answer/10787469 .
