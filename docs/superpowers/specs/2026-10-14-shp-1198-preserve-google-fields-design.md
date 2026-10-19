# Preserve Google contact fields across a device edit (SHP-1198, epic SHP-1194)

Base branch: `release/1.1.0`. Branch: `task/SHP-1198-preserve-unsupported-fields`.

## Goal

Editing a Google contact on the Kompakt must not change anything the user did not edit. Kompakt Contacts
hides several fields (instant messaging, events, phonetic name, photo, group membership) and edits a
per-row diff, so the device never touches them — but DAVx5 uploads the **whole** vCard on any edit of a
contact, and Google takes every field it serves over CardDAV from that vCard. A field therefore survives
only if DAVx5 reads Google's vCard and writes it back in a form Google maps to the same value.

These fields do not survive today:

| Field | What happens on a device edit today | Cause |
|---|---|---|
| IM protocol | every IM loses its protocol in Google ("Jabber" → none) | DAVx5 drops `X-SERVICE-TYPE`, Apple's way of naming the protocol — any server using it |
| Phone, email, address or website with no label | becomes "Other" on the device at download, and on the server at the next upload | DAVx5 turns an empty `X-ABLabel` into no label, in both directions — any server |
| Address with a custom label ("Summer") | becomes "other" in Google | DAVx5 writes a labelled address without Apple's companion `X-ABADR`, which Google needs — any server following Apple's form |
| Yearless custom date | gains year 1 in Google ("9 Sep" → "9 Sep 1") | Google's own date form — Google only |
| SIP address (shown and edited on Kompakt) | Google stores it as an IM; the next download makes it a hidden IM row, so the SIP disappears from Contacts (AC 6) | Google rewrites the standard `IMPP:sip:` — Google only |

Everything else Google serves already round-trips: names, nickname, organisation, note, phones and emails
with a type or a custom label, phones beyond the two the editor shows, typed addresses, birthdays with and
without a year, custom dates with a year, relations, photo. Fields Google never serves over CardDAV
(custom fields, occupation, interests, file-as, its own SIP field, label memberships, …) are untouched by a
PUT, because Google merges a PUT into the stored contact instead of replacing it.

## Contract change

None. Nothing in [`docs/app-integration.md`](../../app-integration.md) changes.

## Scope

The first three are general DAVx5 mapping losses and are fixed in upstream synctools, for every server. The
last two exist only because of how Google reads and writes vCards; they are fixed only for Google address
books, recognised by `KompaktGoogleContactName.appliesTo` (host `apidata.googleusercontent.com` or
`www.googleapis.com`, path under `/carddav/`). Other servers keep upstream's date and SIP forms, which they
store as sent and DAVx5 reads back.

## Google's dialect — what it serves and what it accepts

Measured 2026-10-12 and 2026-10-14 against the Google test account: contacts created in the Google Contacts
web page and through the People API (`mudita-ai/tools/google-sync`), synced to a Kompakt by DAVx5
1.1.0-SNAPSHOT, data rows read with `adb shell content query`, request bodies read from DAVx5's extended
log, and vCard forms sent by direct CardDAV PUT with Google's stored result read back through the People
API.

### Labels and types

What the Google Contacts web page offers and what reaches the device (before this change):

| Created in Google | Google serves | Device row |
|---|---|---|
| Phone Home, Work, Mobile, Home Fax, Work Fax, Pager | `TEL;TYPE=…` | the matching Android type |
| Phone Other, **Main** | bare `TEL` for both | Other |
| Phone Google Voice, custom label | `itemN.TEL` + `itemN.X-ABLabel:<label>` | custom label |
| Phone, email, address or website with **no label** | `itemN.<property>` + `itemN.X-ABLabel:` (empty) | **Other** — `ContactReader.findAndRemoveLabel` turns the empty label into null |
| Email or address Other | bare `EMAIL` / `ADR;TYPE=OTHER` | Other |
| Email or address with a custom label | `itemN.<property>` + `itemN.X-ABLabel:<label>` | custom label |
| Website Other | `itemN.URL` + `itemN.X-ABLabel:Other` | custom label "Other" |

The web page has no field for IM or SIP. On upload, `DataRowHandler` strips empty strings, so a row stored
as `TYPE_CUSTOM` + `""` is written as a bare property, which Google stores as "other". Google keeps "no type"
when it receives `itemN.TEL` / `EMAIL` / `URL` + `itemN.X-ABLabel:`. ContactsProvider keeps an empty `LABEL`.

Addresses are the exception. What Google stores for an address after a PUT:

| Sent | No-label address | "Summer" address |
|---|---|---|
| Google's own vCard, GET and PUT back unchanged (`itemN.ADR` + `itemN.X-ABLabel`) | other | other |
| DAVx5's form (all labels first, `X-ABLABEL`) | other | other |
| label right after its `ADR` | other | other |
| `ADR;TYPE=Summer`, `ADR;TYPE=X-Summer` | other | other |
| `itemN.ADR` + `itemN.X-ABLabel` + **`itemN.X-ABADR:`** (Apple's form, any value, also empty; any order) | **no type** | **Summer** — stable over repeated PUTs |

After a PUT without `X-ABADR`, Google serves the labels it dropped as stray ungrouped `X-ABLABEL` lines,
which DAVx5 then retains as unknown properties.

Kompakt Contacts (SHP-1214) shows a phone, email or address with `TYPE_CUSTOM` + `""`, or a null type, by
its generic name ("Phone", "Email", "Address") and makes it read-only, so storing that form is what makes
"no type" visible as such on the device.

### IM

Google serves `itemN.IMPP;X-SERVICE-TYPE=<Service>:<scheme>:<handle>` plus `itemN.X-ABLabel:Other`. The
scheme does not identify the protocol; only `X-SERVICE-TYPE` does:

| People API protocol | Scheme served (stored as `Im.CUSTOM_PROTOCOL` before this change) |
|---|---|
| aim, **icq** | `aim` |
| jabber, **googleTalk** | `xmpp` |
| msn | `msnim` |
| yahoo | `ymsgr` |
| skype | `skype` |
| qq, netMeeting, any custom name | `x-apple` |
| none ("Other") | `x-apple` with `X-SERVICE-TYPE=Other` |

`ImBuilder` drops `X-SERVICE-TYPE`, and `ImHandler` uploads `IMPP:<scheme>:<handle>`; Google then stores
an IM with no protocol. A PUT of `IMPP;X-SERVICE-TYPE=Jabber:xmpp:…` restores `protocol: jabber`.

### Yearless custom dates (`X-ABDATE`)

Google renders a yearless date as year 0 (1 BC) converted through the Julian/Gregorian cutover — two days
later — and printed without an era, so it is served with year `0001`:

| Yearless date in Google | Served |
|---|---|
| 9 Sep | `0001-09-11` |
| 27 Feb | `0001-02-29` — a date that does not exist, since year 1 is not a leap year |
| 29 Feb | `0001-03-02` |
| 1 Jan | `0001-01-03` |
| 29 Dec | `0001-12-31` |
| 30 Dec, 31 Dec | `0001-01-01`, `0001-01-02` |

DAVx5 stores `0001-09-11` as a full date and uploads it unchanged; Google stores 9 Sep **year 1**. It cannot
store `0001-02-29` at all: ez-vcard reads it as a partial date with year 1, `EventBuilder` fails with
`DateTimeException`, and the whole contact is skipped on every sync. What Google stores for each form sent:

| Sent | Google stores |
|---|---|
| `0001-09-11` | 9 Sep year 1 |
| `1604-09-09` with or without `X-APPLE-OMIT-YEAR=1604` (DAVx5's form for a yearless Android date) | 9 Sep year 1604 — omit-year is honoured on `BDAY` only |
| `--09-09`, `--0909` | **the date is deleted** |
| `0000-09-11` | yearless 9 Sep, stable over repeated uploads |
| `0000-03-02`, `0000-01-03`, `0000-02-29` | yearless 29 Feb, 1 Jan, 27 Feb |
| `0001-01-01`, `0001-01-02` | yearless 30 Dec, 31 Dec (`0000-01-02` would be 31 Dec year −1) |

So both directions are the same shift: served month/day **minus two days**, computed in a leap year;
upload month/day **plus two days** in a leap year, written in year `0000` — or `0001` when the shift crosses
into January (30 and 31 Dec). Birthdays are not affected: Google serves and accepts
`BDAY;X-APPLE-OMIT-YEAR=1604:1604-12-24`.

### SIP

| Sent | Google |
|---|---|
| `IMPP:sip:…` (what `SipAddressHandler` writes) | an IM with no protocol; next served as `IMPP;X-SERVICE-TYPE=Other:x-apple:…`, which DAVx5 stores as a hidden IM row |
| `IMPP;X-SERVICE-TYPE=SIP:sip:…` | an IM with protocol "Sip", served back as `IMPP;X-SERVICE-TYPE=Sip:x-apple:…` |
| `X-SIP:…` | kept and **served back verbatim**, also after an edit made in Google; not shown in Google's web UI or the People API |
| `TEL;TYPE=sip:sip:…` | a phone number |

Google's own SIP field is never served over CardDAV, in either direction. `X-SIP` is the only form that
comes back as SIP; it is invisible in Google, which is accepted. `ContactReader` reads `X-SIP:<address>` as
a SIP row, but drops its `TYPE` and label.

## Design

### Upstream (synctools), every server

**IM service type — `ImBuilder`, `ImHandler`.** An IM row has one column for its protocol,
`Im.CUSTOM_PROTOCOL`.

- Download: `ImBuilder` stores the `X-SERVICE-TYPE` verbatim ("ICQ", "GoogleTalk", "Signal") when there is
  one other than `Other`; otherwise the URI scheme, as before. Google writes the value unquoted, so a name
  with a comma (`X-SERVICE-TYPE=Foo, Bar`) is parsed as two values; `ImBuilder` joins them back.
- Upload: `ImHandler` adds `X-SERVICE-TYPE` = the stored protocol to the URI it already builds
  (`IMPP;X-SERVICE-TYPE=ICQ:icq:…`), except `x-apple`, the "no protocol" scheme, which goes up without one as
  before. When the stored name has no valid URI scheme ("微信" strips to nothing) or its scheme would be
  `sip` ("Sip", which DAVx5 would read back as a SIP address), the URI uses `x-apple`; the service type
  still names the protocol (`IMPP;X-SERVICE-TYPE=Sip:x-apple:…`, as Google serves it). The same applies to
  a name whose scheme keeps a character a URI scheme cannot hold ("Foo, Bar").

For every server this changes the URI an IM goes up with: the scheme now follows the stored service name
(`IMPP;X-SERVICE-TYPE=Jabber:jabber:…` instead of `xmpp:`; QQ as `qq:` instead of `x-apple:`), and an IM
whose protocol name strips to no scheme ("微信", "123"), which upstream dropped, goes up as `x-apple`. A row
with no custom protocol at all goes up as before (`IMPP:<handle>`). Rows with a standard Android
protocol (`PROTOCOL_JABBER`, …) keep upstream's URI and get no service type; DAVx5 itself never stores
those.

No table and no case folding: the name a server sends is the name it gets back. Servers that do not use
`X-SERVICE-TYPE` ignore the parameter.

**No label — `ContactReader`, `PhoneHandler`, `EmailHandler`, `StructuredPostalHandler`,
`WebsiteHandler`.**

- Download: `ContactReader.findAndRemoveLabel(group, keepEmpty = …)` keeps an empty label as `""` for
  phones, emails, addresses and websites that carry no type other than `PREF` (other properties keep
  upstream's behaviour). The builders then store `TYPE_CUSTOM` + `""`. A typed value with an empty label
  (`item1.TEL;TYPE=CELL` + `item1.X-ABLabel:`) keeps its type, since the builders store any non-null label
  as `TYPE_CUSTOM`; so does an email with `TYPE=INTERNET`, which then becomes Other as before (Google
  never sends one).
- Upload: the four handlers give a row with `TYPE_CUSTOM` and no label, or with no type at all, the label
  `""` (`DataRowHandler` strips the empty string before they see it). `ContactWriter` writes
  `itemN.<property>` + `itemN.X-ABLabel:`. A row with `TYPE_OTHER` still goes up as a bare property.

**Labelled address — `ContactWriter`, `ContactReader`.** `ContactWriter.addLabeledProperty` adds
`itemN.X-ABADR:` (empty) next to every address it writes with a label, empty or not, as Apple does.
`ContactReader` no longer retains a served `X-ABADR` among the unknown properties, since the writer
regenerates it.

**`X-SIP` type and label — `ContactReader`.** `ContactReader` keeps an `X-SIP`'s `TYPE` and label, as for an
`IMPP:sip:`.

### Google only — `KompaktGoogleVCard`

A new object in `core/.../sync/`, called at the two points where a vCard meets the mapping layer, each a
one-line wrap in `ContactsSyncManager` in the style of `KompaktGoogleContactName.keepNamesAround`:

```kotlin
// processCard (download)
KompaktGoogleVCard.read(collection.url, vCard) { ContactReader.fromVCard(it, downloader) }

// generateUpload (upload)
KompaktGoogleVCard.write(collection.url, contact) {
    ContactWriter(it, vCardVersion, productIds.vCardProdId)
}.writeVCard(writer)
```

For any other collection both calls just run the lambda.

- **Dates, download:** before `ContactReader`, an `X-ABDATE` in year 1 without `X-APPLE-OMIT-YEAR` — a full
  date, or `0001-02-29`, which ez-vcard reads as a partial date — becomes a partial date without a year:
  month/day of the date minus two days, taken in a leap year.
  `EventBuilder` stores it as `--MM-DD`.
- **Dates, upload:** on `ContactWriter.vCard`, an `X-ABDATE` without a year — a partial date, or a date
  whose year equals its `X-APPLE-OMIT-YEAR` (what `ContactWriter.rewritePartialDate` writes for vCard 3) —
  gets month/day plus two days, taken in a leap year, and is written as `0000-MM-DD`, or `0001-01-0x` when
  the shift crosses into January, without the omit-year parameter. It is written as a raw property, because
  ez-vcard prints year 0 as `0001` (year of era) for a date and as `0` for a partial date. `BDAY` is left
  alone.
- **SIP, upload:** on `ContactWriter.vCard`, each `sip:` `IMPP` is replaced by `X-SIP:<handle>` with the
  same group and its types as `TYPE` parameters. The download side is upstream's `ContactReader`.

### Why this split

- **Upstream** where DAVx5 loses data on any server, and where the information is dropped inside
  synctools before Kompakt code could see it (`ImBuilder` discards the service type, `DataRowHandler`
  strips the empty label, `ContactWriter` builds the address group). Fixing those from outside would mean
  folding names into URI schemes and guessing them back, or re-querying rows and matching them by value.
- **Kompakt, scoped to Google** where the form is Google's own: the shifted year-0 date and `X-SIP`. Other
  servers keep `1604` + `X-APPLE-OMIT-YEAR` and `IMPP:sip:`, which they store as sent and DAVx5 reads back;
  sending them Google's forms would shift their dates by two days and hide SIP from their other clients.

All upstream lines touched are listed under *Known rebase hazards* in
[`docs/upstream-maintenance.md`](../../upstream-maintenance.md).

## Known limitations

| Case | Behaviour |
|---|---|
| Phone Main (and, from the People API, work mobile, work pager, other fax) | Google serves it as a bare `TEL`, the same as Other, so it reaches the device as Other and goes back as other. Google limitation. |
| NetMeeting IM | Google serves it without a usable service type (it reaches the device as `x-apple`), so it goes back with no protocol. Google limitation. |
| Custom IM protocol with inner capitals | Sent verbatim, but Google re-cases a custom name it receives over CardDAV: "GChat" comes back as "Gchat". Google limitation. |
| SIP visibility in Google | `X-SIP` is invisible in Google's UI (AC 6.3 asks for an unsupported, non-editable field there — Google offers no such form). |
| Contact downloaded before this change, edited on the device before Google changes it | Its rows still hold what the old mapping stored: IMs carry the served scheme, which now goes up as the service type (ICQ as AIM, Google Talk and Jabber as "xmpp", `x-apple` with no protocol); no-label values are Other; yearless dates are year 1. The next download after any change on Google stores them correctly. |
| Stray ungrouped `X-ABLABEL` lines | A contact whose address labels Google already dropped carries Google's leftover lines among its unknown properties and re-sends them. Harmless; they go away when Google serves the contact without them. |
| Yearless Android anniversary (`TYPE_ANNIVERSARY`) | In vCard 3 `ContactWriter` writes it as a new `X-ABDATE` without the omit-year parameter, so it goes up as year 1604. Not reached: Google serves its anniversary as a custom date labelled "Anniversary" (stored as `TYPE_CUSTOM`), and Kompakt cannot create events. |

## Testing

JVM unit tests:

- synctools: `ImBuilderTest` (service type stored verbatim, also "Sip" and one split at a comma; `Other`
  and a blank one keep the scheme),
  `ImHandlerTest` (stored name → `X-SERVICE-TYPE`; `x-apple` without one; a name without a valid scheme, one
  with a comma and "Sip" go up with `x-apple`; a null custom protocol and a standard protocol get no service
  type), `ContactReaderTest` (empty label kept for untyped phone, email, address and website, also with
  `TYPE=PREF` or `PREF=1`, not for a typed one; `X-SIP` type and label; `X-ABADR` not retained), `PhoneHandlerTest`,
  `EmailHandlerTest`, `StructuredPostalHandlerTest`, `WebsiteHandlerTest` (custom type without label and no
  type → `""`, `TYPE_OTHER` → none), `ContactWriterTest` (empty label written as a grouped `X-ABLabel`;
  an empty `X-ABADR` only next to a labelled address). Upstream's `EmailHandlerTest.testTypeCustom_NoLabel` is
  updated for the new label.
- core: `KompaktGoogleVCardTest`, through the real parser, reader and writer: every row of the served date
  table in both directions, including 27 Feb, 29 Feb and 30/31 Dec, and the year-0 text the generator prints; the
  vCard 4 partial-date path; other `X-ABDATE` parameters kept; a dated `X-ABDATE` and `BDAY` untouched;
  `sip:` → `X-SIP` with type and label, and back; an IM with protocol "Sip" stays an IM; a non-Google
  collection gets upstream's forms.

Device round trips (Kompakt + debug builds of this branch, 2026-10-14). Each: create the contact in Google,
sync, check the data rows, change one phone number on the device (a provider update of that column, as the
editor writes it), sync, read Google's copy through the People API.

- **Contact made in the Google Contacts web page** with every phone label the page offers, a phone, email,
  address and website with no label, Other and custom-labelled emails and addresses, and a yearless
  "Party" date.
- **Contacts seeded through the People API** with every IM protocol, yearless and dated events, a yearless
  birthday, and a work SIP address added on the device; the note was then changed in Google to force a
  re-download.

| | Device after download | Google after the device edit |
|---|---|---|
| Phones with a type, custom label, Google Voice | matching type / label | unchanged |
| Phone Main | Other | other (limitation) |
| Phone, email, address, website with no label | `TYPE_CUSTOM` + `""` | no type |
| Address "Summer", email "Club" | custom label | unchanged |
| IM protocols | Google's names verbatim (`AIM`, `ICQ`, `GoogleTalk`, `Jabber`, …, `Signal`); NetMeeting `x-apple` | all kept; NetMeeting none; "GChat" → "Gchat" |
| Yearless dates | `--09-09`, `--12-31`, `--01-01` | still yearless |
| Dated event, yearless birthday | unchanged | unchanged |
| SIP (work) | — | not visible (as designed); after re-download a SIP row, type work |
