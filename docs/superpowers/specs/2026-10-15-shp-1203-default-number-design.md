# Keep the device's default number across Google sync (SHP-1203, epic SHP-1194)

Base branch: `release/1.1.0`. Branch: `task/SHP-1203-default-number`. Companion work under SHP-1203 in
KompaktOS-Contacts and KompaktOS-phone.

## Goal

A contact's default phone number is something the user chooses on the Kompakt ("Set default" in the Contacts
editor, "Remember this choice" in a number picker). Google sync must neither invent one nor lose one:

- A contact the user gave no default must ask which number to call, even though Google marks one number
  preferred on every card.
- A default the user chose on the Kompakt must survive every later download of that contact from Google.
- A default changed on Google's side (the web, another device) must not be silently overridden by the
  Kompakt.

Default email is handled the same way; no Kompakt flow picks an email automatically today.

## The rule shared with Contacts and Phone

The default number is `IS_SUPER_PRIMARY`. `IS_PRIMARY` only says which value a server's card prefers and is
never read as the user's choice. Contacts and Phone dial without asking only on a super-primary number, and
every user action that sets a default writes super-primary (the provider then sets primary as well). That is
AOSP's model: AOSP Dialer's `PhoneNumberInteraction` skips the picker only for super-primary, and AOSP's own
vCard importer maps `PREF` to `IS_PRIMARY` only.

DAVx5's part is the vCard side: `PREF` ↔ `IS_PRIMARY`, and keeping super-primary across a download.

## Contract change

None in [`docs/app-integration.md`](../../app-integration.md): no intent, broadcast, permission or provider
changes. What the other apps rely on is the data rule above.

## Facts this design rests on

### Android's two flags

Both are columns of a data row (phone, email, …) in ContactsProvider.

| Flag | Meaning | Scope |
|---|---|---|
| `IS_PRIMARY` | the preferred value of its kind within **one raw contact** (one account's copy) | per raw contact |
| `IS_SUPER_PRIMARY` | the preferred value of its kind for the **whole person** (all merged copies); implies primary | per aggregate |

### What Google does

Measured on a Kompakt (MuditaOS K 2.0.0-a21, DAVx5 1.1.0-SNAPSHOT, the Google test account), reading
Google's copy back after upload or by forcing a re-download:

| Sent from the phone | Google's copy |
|---|---|
| 2–3 numbers, `PREF` on #2 | the same number kept as `PREF`, moved to first place; the other numbers keep their order |
| 1 number, no `PREF` | `PREF` added to it |
| 3 numbers, no `PREF` | `PREF` added to the **first** number |
| `PREF` cleared | `PREF` comes back on the first number of the card |
| `PREF` moved to #3 | #3 kept as `PREF`, moved to first place |

So Google always keeps exactly one preferred number (and email) per contact: the user's choice when there is
one, otherwise the first value. Its automatic pick and a real choice are byte-identical on the wire. vCard
itself has no notion of a user's default: RFC 2426 `TYPE=pref` marks a preferred value with no limit on how
many, and RFC 6350 `PREF=1…100` is a rank chosen by the card's author.

### What DAVx5 did

Upstream synctools writes `PREF` as **both** flags (`PhoneBuilder`; `EmailBuilder` copies it). It dates from
2014, when download wrote both and upload read super-primary; upload later switched to `IS_PRIMARY` without
a stated reason and download was never changed. Upload (`PhoneHandler` / `EmailHandler`) sends `PREF` from
`IS_PRIMARY`. With Google that turned its automatic pick into every contact's default: Call dialled the first
number without asking, and a default the user cleared came back at the next download.

Removing super-primary from the builders alone is not enough: `AndroidContact.update` applies a download by
deleting every data row of the raw contact and inserting new ones, and super-primary exists only on the
device, so every download would drop the user's default and the picker would come back.

### How ContactsProvider treats the flags

Measured on the Kompakt with sync-adapter writes like DAVx5's, and read in `DataRowHandler`:

| Write | Result |
|---|---|
| insert with `is_primary=1` only | primary only; no super-primary is added |
| insert with `is_super_primary=0` | super-primary is **cleared** across the raw contact — so the key must be omitted, not 0 |
| insert with `is_primary=1` while the raw contact already has a super-primary row | the new row takes super-primary over |
| set super-primary on a row | the provider also sets primary on it and clears both flags elsewhere: primary on the raw contact's other rows, super-primary on the person's other copies |
| delete + re-insert a raw contact's rows | super-primary is lost |
| merged person, user's super-primary on copy A; copy B re-inserted with primary only | A's super-primary survives |
| two copies that each hold a super-primary are joined | the provider clears both |
| a non-sync-adapter write (Contacts, Phone) | marks the raw contact dirty, so DAVx5 uploads it |

Inside one raw contact the super-primary row is therefore always the primary one, and a person holds at most
one super-primary.

## Design

### 1. `PREF` maps to `IS_PRIMARY` only (`PhoneBuilder`, `EmailBuilder`)

The builders never write the `IS_SUPER_PRIMARY` key, not even as 0. When a card has several preferred values,
only the most preferred becomes primary: lowest `PREF`, a bare `TYPE=pref` counting as 1, the first on a tie.
Without that, the provider would keep whichever primary row happened to be inserted last. Google sends one,
so it changes nothing there. Upload is unchanged.

### 2. Keep super-primary across a download (`AndroidContact.update`, `RawContactBuilder.insertDataRows`)

- Before the delete, read the raw contact's super-primary phone number and email, and for the number the
  provider's `Phone.NORMALIZED_NUMBER` (its E.164 form, computed with the device's country).
- In the same batch as the re-insert, set `IS_SUPER_PRIMARY=1` on the new row holding the same value. One
  batch keeps the update atomic.
- Only the raw contact being rewritten is read. Since a person holds at most one super-primary, that is the
  person's current default; restoring it cannot take the default from another copy of a merged contact.

### 3. The server wins a conflict

- If the card prefers the same value, or prefers nothing at all, the default is kept.
- If the card prefers **another** value, the default was changed on Google's side, so super-primary is dropped:
  the new rows carry Google's preference as primary only and the picker asks. Nothing is uploaded back —
  re-asserting the device's choice would silently undo the change made on Google.
- A choice made on the Kompakt reaches Google through the normal path: the app's write marks the contact
  dirty, and a sync uploads dirty contacts before it downloads, so Google already holds the choice as `PREF`
  when the card next arrives.
- The comparison uses the card's preferred row itself, so a second copy of the same number elsewhere in the
  card does not hide that Google agrees.

### 4. Same value

- A number matches when it is equal after `PhoneNumberUtils.normalizeNumber`, or when it equals the old row's
  E.164 form — a number the user saved nationally that Google now sends internationally. A value without
  digits (normalising to an empty string) never matches.
- Never by its last digits (`PhoneNumberUtils.compare`): a changed number loses the default instead of moving
  it to another number that shares the tail.
- An email matches case-insensitively.

### Outcomes

| Situation | Result |
|---|---|
| Google contact, no default chosen on the Kompakt | Google's pick is primary only; the picker asks |
| Default chosen on the Kompakt | uploads as `PREF`; dials directly; survives later downloads (a rename on Google, …) |
| Default changed on Google | dropped; the picker asks |
| Default number deleted or changed on Google | dropped; the picker asks |
| Default chosen on another device | arrives as primary only; the picker asks — indistinguishable from Google's own pick |
| Local contact with a default merged with a Google copy | the local copy keeps the default; the Google copy is primary only |

### Why upstream code

Both defects are DAVx5's, not Kompakt-specific: any server's `PREF` overrode a user's aggregate default on
every download, contrary to AOSP's importer. The edits are therefore made in upstream synctools, marked
`// Kompakt:`, and listed under *Known rebase hazards* in
[`upstream-maintenance.md`](../../upstream-maintenance.md). `AndroidContact` is deprecated upstream, but its
replacement does not cover data rows yet, so it is still the only place a download rewrites them. The builder
change is worth proposing to bitfireAT/synctools.

### Alternatives rejected

| Alternative | Why not |
|---|---|
| Device always wins: restore whatever `PREF` says, mark the contact dirty and upload it | overrides a default changed on Google, and needs an upload trigger from inside the sync, because a sync adapter's `DIRTY=1` requests no sync |
| Detect Google's automatic `PREF` | impossible: a real choice is moved to first place too, so both are byte-identical |
| Store a "user chose" bit or the last uploaded `PREF` in the raw contact | `SYNC1`–`SYNC4` are all taken; cannot recover a choice made on Google |
| Update data rows in place instead of delete + insert | flags would survive by themselves, but it rewrites upstream `AndroidContact.update`; too large |
| Map `PREF` to nothing | a choice made on the Kompakt would never upload |
| Match numbers loosely (`PhoneNumberUtils.compare`) | can move the default to another number sharing the last digits |

## Known limitations

- A number saved internationally that Google returns in national form does not match, so the picker asks.
  Formatting the new national number would need the country, which only the old row carries.
- A device choice is lost when its upload fails on an edit conflict (HTTP 412 / 409: the card changed on
  Google meanwhile), or when it is made during a sync after the upload phase: the download then applies
  Google's card, and if that prefers another value the default is dropped.
- A name change in a download can re-aggregate the DAVx5 copy into a person whose other copy holds a
  super-primary; the provider then clears it and the picker asks.

## Testing

- `PhoneBuilderTest`, `EmailBuilderTest`: `IS_SUPER_PRIMARY` is absent; several `PREF` values keep only the
  most preferred primary; `TYPE=pref` counts.
- `RawContactBuilderTest` (Robolectric): kept when the card prefers the same value or nothing, dropped when it
  prefers another value or the value is gone, a duplicate number in the card; `findSameValue` — reformatted,
  national → international, international → national (no match), shared tail with another prefix, changed
  number, no digits, email case.
- `AndroidContactTest` (instrumented, real ContactsProvider): a Contacts-style write sets `DIRTY`; the default
  survives an update when the card agrees (reformatted number and email), when it prefers nothing, with a
  duplicate number, and for a national number Google sends internationally; it is dropped when Google's
  `PREF` moved or the number was deleted; a merged local + Google contact keeps the local default and exactly
  one super-primary; a new card is primary only.
- On a Kompakt with the Google test account, reading flags with
  `adb shell content query --uri content://com.android.contacts/data --projection _id:raw_contact_id:data1:is_primary:is_super_primary --where "contact_id=<id> AND mimetype='vnd.android.cursor.item/phone_v2'"`:
  1. A contact with 3 numbers and no default, synced: no row has `is_super_primary=1`; Call shows the picker.
  2. "Remember" #3 in Contacts, then sync: Google's card has #3 as `PREF`.
  3. Rename the contact on Google, then sync: #3 still has `is_super_primary=1`.
  4. Move the default to #1 on Google, then sync: no super-primary; the picker asks.
  5. Delete #3 on Google, then sync: no super-primary remains.
