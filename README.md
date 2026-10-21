<a href="https://github.com/sponsors/gi-os"><img src="https://raw.githubusercontent.com/gi-os/gi-os/main/images/bright-sponsors.svg" width="100%" alt="Thank you to my sponsors"></a>

# Ledger Lite (BrightLedgerLite)

> **This is the Lite version.** BrightLedger Lite is built only with Light's official SDK, so it can go in the Light Tool Library. It does less than the full app. For the full app, get [BrightLedger](https://github.com/gi-os/BrightLedger) ([on BrightMarket](https://brightmarket.gzl.dev/app/com.gios.brightledger/)).

Bank balances and transactions on the Light Phone III over the
[SimpleFIN](https://www.simplefin.org/protocol.html) protocol, built as an official Light SDK
tool. It is the Tool Library version of [BrightLedger](https://github.com/gi-os/BrightLedger),
cut down to what a Light tool is allowed to do. It shows up on the phone as
**BrightLedger Lite** (`com.gios.brightledgerlite`).

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT). The SDK
modules (`sdk/`, `plugin/`, `lint-rules/`) are unchanged upstream code; the tool lives in
`tool/`.

## There is no server in the middle

The tool has no backend, no account to create and no API key. The phone talks directly to
the SimpleFIN server you chose, with a read-only credential that exists only on that phone.

1. On a computer, go to [bridge.simplefin.org](https://bridge.simplefin.org) and connect your
   bank. You get a **setup token**, a long Base64 string.
2. Turn the token into a QR code and scan it with the tool, or type it in.
3. The tool decodes the token to its claim URL and sends one empty `POST` to it. The server
   answers with a permanent **Access URL**. The token is now spent.
4. Every sync after that is one authenticated `GET <access url>/accounts?version=2&start-date=…&pending=1`.

The claim and the fetch are the same code BrightLedger uses (`SimpleFinClient`, `AccessUrl`).
A `403` on the claim means the token was already used, and the tool says so: if you did not
claim it yourself, someone else did, and you should revoke it on the Bridge.

### Hosts this tool talks to

- The host inside your setup token, once, to claim it. For the SimpleFIN Bridge that is
  `bridge.simplefin.org`.
- The host of the Access URL the claim returns (again `bridge.simplefin.org` for the Bridge),
  for every sync.

Nothing else. There is no analytics, no crash reporter and no update check. HTTPS is required;
an `http://` token or Access URL is refused.

## Where the credential lives

The Access URL carries HTTP Basic credentials inside it, so it is handled as a secret:

- It is split on arrival. `AccessUrl` turns the userinfo into an `Authorization` header and
  keeps a base URL without it, so logs and error text never contain the credential. (OkHttp
  also ignores userinfo in a URL, so the header is the only way the request authenticates.)
- It is stored encrypted with AES-GCM under a non-exportable **Android Keystore** key, the
  same way the SDK's authenticator example stores TOTP secrets. DataStore only ever holds the
  ciphertext.
- Disconnecting deletes the ciphertext, the cached ledger and the Keystore key. It cannot
  revoke the grant on the server; do that on the Bridge. The confirm screen says so.

## What it shows

- **Home**: spent today in large type, spent this month under it, money in this month if any,
  the pending count, and a staleness line that is always visible ("Updated 12 minutes ago").
  Then every account with its balance, and the monthly total of repeating charges.
- **Account**: balance, available balance when it differs, and the last 60 days of
  transactions, pending first. The list ends there. Older history stays on the phone for the
  repeats detector but is not shown as a feed.
- **Transaction**: account, status, transaction date, posted date, reference. It reads the
  live cache, so a pending charge that settles while it is open updates.
- **Repeats**: subscriptions found in your own history (same merchant, about the same amount,
  a steady interval, three times or more) and what they cost per month in total.
- **Settings**: which server you are connected to, last sync, the optional daily background
  sync, and DISCONNECT.

The scroll wheel scrolls every list.

## Syncing and rate limits

- A first sync asks for 400 days, so the repeats detector can see a year. Later syncs re-read
  an overlapping 14-day window, because banks post transactions dated days earlier and pending
  charges change amount when they settle. Rows in the window are replaced; pending rows the
  server stopped returning are removed. This is BrightLedger's logic, ported unchanged.
- Opening the tool refreshes only when the cache is more than 30 minutes old, and a failed
  attempt is not retried automatically for 5 minutes. SYNC refuses a second press within a
  minute. BrightLedger refreshed on every open; the SimpleFIN Bridge limits how many requests
  one Access URL may make per day and only pulls from banks about once a day, so refreshing on
  every glance wastes that quota.
- The daily background sync (Settings, off by default) is a `LightWork` periodic job every 24
  hours. It goes through the same 30-minute gate, so it never adds a request on top of a
  recent one.
- The screens read the local cache only. A failed refresh changes nothing but the status line.

## Money is never a float

Amounts arrive as numeric strings and are parsed straight into whole cents with `BigDecimal`.
Positive is money arriving (SimpleFIN's convention). Deposits are excluded from the spend
figures rather than netted against them, so a paycheck on the 1st does not make the month read
as nothing spent. On the greyscale panel the sign is the only way to tell a credit from a debit,
so credits always carry a `+`.

## What it keeps from BrightLedger, and what it drops

Ported nearly verbatim, with their tests: `AccessUrl` (token decoding, credential split),
`SimpleFinClient` (claim and fetch, 402/403 handling), `Money`, `Summary` (spent today and this
month in the local zone), `Recurring` (descriptor normalization and cadence detection) and
`Format`. The SimpleFIN parser was rewritten from `org.json` to the kotlinx.serialization tree
API with the same rules and the same spec-based tests; it also accepts numbers and flags sent
as strings. The Room cache became one JSON file with a pure merge function, so the sync rules
(upsert keyed on account and transaction id, pending sweep, retention) are unit-tested too.

| BrightLedger feature | In Lite? | Why |
| --- | --- | --- |
| Journal bus (`content://com.gios.brightledger.days`) for BrightNotebook | No | Tools cannot export a `ContentProvider` or reach other apps; `ContentProvider`, `Intent` and `contentResolver` are blocked. |
| Nightly sync while charging | Partly | `LightWork` has no charging constraint exposed. Replaced by an optional daily job. |
| PIN lock and the PBKDF2 second layer | No | Kept the scope to the Keystore layer. A PIN keypad and lockout screen are a lot of surface for a first Tool Library version. |
| `FLAG_SECURE` (no screenshots) | No | Setting window flags needs the `Activity`, which tools cannot reach. |
| Hide amounts | No | Dropped to keep the tool small; it can come back. |
| More than one SimpleFIN server | No | One connection. Accounts are keyed on the server's account id. |
| Pinned accounts, auto-hiding bottom bar | No | The SDK draws its own bars; pins were dropped with the rest of the customisation. |
| Merged activity feed on the home screen | No | Replaced by a bounded 60-day list per account, per Light's "no infinite feeds" guideline. |
| Notifications, widgets | No | Tools have no notifications API (`NotificationManager` needs `getSystemService`) and no app widgets. BrightLedger did not use them either. |
| CameraX + ML Kit QR scanner | Replaced | Uses the SDK's `LightQrCodeScanner`, the only camera access a tool gets. |

Known limits carried over from BrightLedger: a credit card and the checking account that pays it
both connected will double-count the payment (there is no transfer detection), custom currencies
(a URL instead of an ISO code) show without a symbol, and the spend totals assume one currency.

## Setup

1. Install the tool from the Light Tool Library, or build it as below.
2. Open it, press CONNECT, and either SCAN a QR code of your setup token or tap the field and
   type it. Any QR generator works; the code must contain only the token.
3. The first sync can take up to a minute while the Bridge talks to your bank.

The SimpleFIN spec publishes a demo token (`aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw==`)
that claims a demo account, which is a good first test on a device.

## Build

```
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug
```

Tests and lint, as CI runs them:

```
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest :tool:lintDebug :tool:assembleDebug
```

The GitHub Actions workflow in `.github/workflows/build-tool.yml` runs exactly that on every push.

## Tool Library submission notes

- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE` (skip refreshes when offline) and `CAMERA`
  (QR scanning of the setup token through `LightQrCodeScanner`). No capabilities; cleartext
  HTTP is not needed.
- Network: only the user's SimpleFIN server (see "Hosts this tool talks to").
- Data at rest: the Access URL is AES-GCM encrypted under an Android Keystore key. The cached
  ledger (balances and up to 400 days of transactions) is a JSON file in the tool's private
  files directory. Disconnect erases both.
- Intentionality: no feed, no auto-refresh loop. One refresh on open at most every 30 minutes,
  a manual SYNC button, and an opt-in daily background job.
- The parser fixtures come from the SimpleFIN v2.0.0 specification, not recorded Bridge
  traffic. The claim and fetch have not been run against the live Bridge from this build.

## License

MIT, as the upstream SDK. See `LICENSE`.
