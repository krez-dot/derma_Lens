# Firebase Auth — Integration Notes

Status: **Implemented and live.** Registered accounts (Login/Register/password change/reset) go
through real Firebase Authentication. Guest mode was scoped and briefly implemented on
2026-08-24, then **removed on 2026-08-25 per the team's tech adviser** — registration is now
required to use the app. See "Guest Mode — Removed" below before touching auth code or updating
the manuscript.

Originally decided by the team on 2026-08-24: add Firebase Authentication to solve two real gaps
found during Sprint 7 testing:

1. **No real email verification.** Local-only validation can only check an email's *shape*
   (`local@domain.tld`), never whether the address is real or owned by the registrant — that
   requires actually sending mail, which needs a backend.
2. **No real "how many users" visibility.** All accounts previously lived in a Room DB local to
   whichever single device runs the app, so there was no way to see a genuine cross-device user
   count without a server.

Firebase Authentication solves both without requiring a full backend rewrite.

---

## What's Actually Implemented

**Firebase Auth handles identity** — email/password accounts, real email verification, password
reset, and the authoritative user count (visible directly in the Firebase Console, no extra
code).

**Everything else stays local, same as before:**
- Scan history, condition tracking, progress data → Room DB, fully local, works offline —
  unless the user opts in to **Back Up Scan History** (below).
- Care Guide, Skincare Guidance → unchanged, bundled locally.
- Clinic Locator → Google Maps SDK + Places API (New) + Routes API (moved off
  OpenStreetMap/Overpass in Sept 2026; see HANDOFF.md), requires internet.

### Opt-in scan history backup (added 2026-09-28)

Profile → Account → **Back Up Scan History** (off by default, behind a consent dialog). When on,
scan *records* — condition, confidence, severity, date, notes, timeline grouping — are copied to
Firestore under `users/{uid}/scans/{scanDate}` so they're restored when the same account logs in
on another phone. **Photos are never uploaded** (cloud file storage needs the paid Blaze plan, and
keeping skin photos off servers is the stronger privacy default). Code: `data/sync/ScanHistorySync.kt`.

- Room stays the working copy; Firestore is the backup. Writes are fire-and-forget — Firestore's
  built-in offline persistence queues them and sends them when back online (no WorkManager queue).
- A scan's cross-device id is its `scanDate` (Room ids differ per phone); timelines travel as
  `groupKey` = the timeline's first scan's `scanDate`.
- Deleting a scan writes a **tombstone** (`deleted: true`, health fields dropped) so other phones
  delete their copy instead of re-uploading it.
- Home runs a two-way reconcile on open: learns the account's backup choice, applies tombstones,
  restores missing records (without photos), pulls note edits, uploads local-only scans.
- Turning backup off deletes the cloud copy (local scans kept). Delete Account deletes the cloud
  copy **before** the Firebase account, and stops if that fails.
- `firestore.rules`: `users/{uid}/**` is readable/writable only by that uid, shape-checked; the
  rules must be published in the Firebase Console for this to work.
- Verified on the emulator 2026-09-28: enable + backfill, restore on a wiped "new phone",
  offline note edit queued and synced, delete propagated via tombstone (incl. the photo on the
  other device), backup-off deleted the cloud copy.

**Paper impact:** IC2 ("store all user data ... in a local Room DB") needs an opt-in carve-out;
the in-app Privacy Policy (section 3) is already updated. Scan records are health information —
sensitive personal information under RA 10173 — which is why this is opt-in with explicit consent.

So the app now has one account type: a **registered account** (Firebase Auth) — email verified,
persists in the cloud, counted in the Firebase Console. A local Room DB row still exists per
user (name, prefs, scan history), keyed by the Firebase UID, but there is no local-only account
path anymore.

---

## Guest Mode — Removed

Guest mode (a local-only account with no email/password, for using the app without registering)
was implemented alongside Firebase Auth on 2026-08-24 and removed the next day on direct
instruction from the team's tech adviser. What was rolled back:

- The "Continue as Guest" button on the Login screen.
- The single persistent local guest profile (`guest@dermalens.local`) and all `isGuest`
  branching in `Screens.kt` and `ProfileScreen.kt`.
- `User.isGuest` (Room model field) and `DermaPrefs.KEY_IS_GUEST`.

**Practical effect: registration (a real Firebase account) is now required to use the app at
all.** There is no offline/no-account path anymore — this reverses the "still usable fully
offline for anyone who doesn't want to create a real account" framing from the original plan.

---

## What Actually Changed in Code

| File | Change |
|---|---|
| `Screens.kt` (Login/Register) | Register calls Firebase's `createUserWithEmailAndPassword()` + `sendEmailVerification()` instead of writing directly to Room's `users` table. Login calls `signInWithEmailAndPassword()` instead of a local hash check. Guest button removed. |
| `data/model/User.kt` / Room `users` table | Local *profile* record (name, prefs) keyed by the Firebase UID. `isGuest` field removed (DB version bumped again). |
| `DermaColors.kt` (`DermaPrefs`) | `KEY_IS_GUEST` removed. |
| `ProfileScreen.kt` (Edit Profile) | Password changes for registered accounts go through Firebase reauthentication + `updatePassword()`. Email changes are not implemented yet (see Open Questions). Guest-specific branching removed. |

Nothing in `ScanResultScreen.kt`, `ClinicLocatorScreen.kt`, `ProgressTrackerScreen.kt`,
`CareGuideScreen.kt`, or `YoloDetector.kt` needed to change — none of them touch auth.

---

## Manuscript / Paper Impact

This reverses part of an already-approved Form 12 revision (the "eliminates server-side
scripting" claim), so it needs to be handled deliberately in the documentation, not silently.
With guest mode now removed, the picture is actually **simpler** than the original hybrid plan —
there's no guest carve-out to explain, just "authentication now involves Firebase for identity
only." Specific sections that need updating:

| Section | Current text (approved) | What needs to change |
|---|---|---|
| **Scope and Limitations** (Ch. 1) | States the system "eliminates server-side scripting" — added specifically per Form 12 panel feedback | Needs rewording: server-side involvement is now limited *only* to authentication (identity + email verification) via Firebase. No guest exception to document since registration is mandatory. |
| **IC1** (Design and Implementation Constraints, Ch. 4) | "The system shall require user authentication before accessing any feature of the application." | **Stays as originally written** — no guest carve-out needed anymore, since registration is required. This is actually simpler to keep than the guest-aware version would have been. |
| **IC2** (Design and Implementation Constraints, Ch. 4) | "The system shall store all user data, scan history, and detection results in a local Room DB database residing on the user's Android device." | Still true for scan history/detection results. Needs a carve-out: *account identity* (email, verification status) is managed by Firebase Authentication, not Room DB. |
| **SS1** (Non-Functional Requirements — Safety and Security) | Describes local authentication/local profile protection | Needs a line describing what Firebase actually stores (email + hashed credentials, managed by Google's infrastructure, not DermaLens's own servers) vs. what stays local (scan history, profile prefs). |
| **SS3** (Non-Functional Requirements — Safety and Security) | "The application will not send any user-captured skin images or personal data to external servers... because all YOLOv11 model inference operates completely on-device" | Still fully true and unaffected — this is specifically about image/inference data, not auth. Worth a sentence clarifying the two are separate to avoid a panelist conflating them. |
| **Table 4** (Software Requirements, Ch. 3) | Lists Room DB, no auth-backend entry | Add a row: Firebase Authentication — email/password identity management and verification for all accounts. |
| **Figure 3** (System Architecture Overview, Ch. 3) | No external auth service shown | Add Firebase Authentication as a new external entity that every user's login/register flow goes through (no bypass path anymore). |
| **In-app Privacy Policy text** (`ProfileScreen.kt`) | Currently states data is stored locally | Needs an added disclosure: account emails and verification status are held by Firebase (Google's infrastructure); everything else (scans, profile details) stays on-device. |

None of this needs to happen before further implementation, but it should land before Sprint 8
documentation work locks in, so the manuscript and the shipped app don't quietly diverge the way
the confidence-threshold table almost did.

---

## Open Questions for the Team

- Firebase email-change (`verifyBeforeUpdateEmail()`) is not implemented — the Edit Profile
  email field is read-only. Worth scoping as a follow-up if the team wants it.
- "Forgot password?" email flow is implemented but hasn't been verified end-to-end (i.e.
  actually clicking the reset link from a real inbox and confirming login with the new
  password).
