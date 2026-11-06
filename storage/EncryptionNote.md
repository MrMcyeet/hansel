# Storage encryption status

**Current state (Phase 1):** `hansel.db` (Room) and `hansel_settings` (DataStore) are plain files in the app's
private data directory. They are protected only by Android's file-based encryption (credential-encrypted storage,
unlocked after first unlock) and app sandboxing. The database is **not** SQLCipher-encrypted.

**Why that is acceptable right now:** trip recording is off by default (`HanselSettings.recordTrips = false`), only
active navigation on an unlocked device would write traces, and the device is a single-user flagship with FBE on.

**Plan (DESIGN §13):**

1. Generate an AES-256 key in Android Keystore (`setUserAuthenticationRequired(false)`, device-bound, no backup).
2. Wrap the SQLCipher passphrase with that key and store the wrapped blob in a private file; open Room through a
   SQLCipher `SupportSQLiteOpenHelper.Factory`. Alternative with fewer moving parts: keep Room unencrypted but encrypt the
   `gps_samples` payload columns per row. SQLCipher is preferred because queries stay simple.
3. Exclude `hansel.db*` and the wrapped-key file from auto backup (`android:allowBackup`/`dataExtractionRules`) because
   Keystore keys do not survive a device transfer.
4. GPX/JSON exports are sensitive: write them through `FileProvider` to cache only, and offer an encrypted export
   (password-based) before any share action.
5. Per-trip delete and "delete all" already exist on `TripRecorder`; the passive recorder must also honor
   `recordTrips = false` immediately.

**Gate:** encryption at rest for `gps_samples`/`trips` (steps 1-3) is a **Phase 5 gate** and must land before passive
(background) recording ships. Do not enable `SOURCE_PASSIVE` recording until this note is updated.
