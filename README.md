# RCDoors

> [!IMPORTANT]
> RCDoors is a RepubliCraft-maintained fork of
> [AnimatedArchitecture](https://github.com/PimvanderLoos/AnimatedArchitecture), based on upstream commit
> [`e9dbef69bb6f0ab872130a5922d72a6b0b78c3d6`](https://github.com/PimvanderLoos/AnimatedArchitecture/commit/e9dbef69bb6f0ab872130a5922d72a6b0b78c3d6).
> It is a modified build and is not an official upstream release. See [FORK_NOTICE.md](FORK_NOTICE.md) for attribution
> and compatibility details.

RCDoors provides animated, block-based structures for the RepubliCraft server. It is maintained for the server stack
used by RepubliCraft, with RCDoors branding for players and administrators while retaining the upstream internals needed
to migrate existing installations safely.

## Supported environment

- Paper 26.2
- Java 25 or newer
- RCPlatform 1.0.0
- RCUI 3.0.1
- Vault

RCDoors is built and tested for Paper 26.2. Other server versions or implementations are outside this fork's support
target.

## Structure types

- Big doors
- Clocks that display the in-game time
- Drawbridges
- Flags
- Garage doors
- Portcullises
- Revolving doors
- Sliding doors
- Windmills

## Installation

1. Install RCPlatform, RCUI 3.0.1 and Vault on the server.
2. Place `RCDoors.jar` in the server's `plugins` directory.
3. Start or restart the server.
4. Review the generated files in `plugins/RCDoors` before opening the server to players.

The primary command is `/rcdoors`. The legacy `/animatedarchitecture` and `/aa` aliases remain available for existing
scripts, command blocks, integrations, and administrator workflows.

## Migrating from AnimatedArchitecture

RCDoors deliberately preserves the upstream database format, internal namespaces, extension identifiers, permission
nodes, and legacy command aliases. It also recognizes persistent-data keys written under the legacy plugin namespace.
Existing structures and integrations can therefore move to this fork without a data conversion.

1. Stop the server completely.
2. Back up the server, including the complete `plugins/AnimatedArchitecture` data directory.
3. Remove the old `AnimatedArchitecture-Spigot.jar` from `plugins`. Do not run both plugins at the same time.
4. Leave the existing `plugins/AnimatedArchitecture` directory in place, install `RCDoors.jar`, and start the server.
5. On its first start, RCDoors copies files missing from `plugins/RCDoors` out of the legacy directory. Existing RCDoors
   files are never overwritten, and the original directory is left unchanged.
6. Verify the console startup, open the RCDoors menu, and test representative structures before normal use.

After a successful import attempt, RCDoors writes a marker so later starts do not repeat the migration. If you need to
merge data manually, stop the server and back up both directories first.

Existing permission assignments continue to use the legacy `animatedarchitecture.*` nodes. This is intentional
compatibility behavior, not incomplete branding. Existing custom extensions continue to use their original manifests
and internal API names.

Organization-linked doors retain durable claims while their Business or Criminal Enterprises provider is unloaded. Direct player actions recheck the organization's current policy; denied, removed, or suspended members cannot borrow the original structure owner's access. Claimed doors reject native redstone, proximity and perpetual automation because those paths identify the original owner instead of the actual person who triggered them. Trusted server operations, including RCHeists door control, remain available. Unclaimed structures retain their native automation behavior. Back up the organization claim file with both the structure database and organization data; do not remove claims to work around a provider outage.

### Migrating from BigDoors

Use a compatible BigDoors release to run `BigDoors PrepareDatabaseForV2` from the server console. After BigDoors
finishes exporting, stop the server and follow the migration instructions produced by that BigDoors version. Back up
the complete source and destination data directories first; legacy exports can occasionally require in-game corrections
to opening directions.

## Building

Build requirements:

- JDK 25+
- Maven

From the repository root, create the production package with:

```shell
mvn clean package
```

The deployable plugin is written to:

```text
animatedarchitecture-spigot/spigot-packager/target/RCDoors.jar
```

To run the extended verification suite used by the upstream project:

```shell
mvn -P=errorprone test package checkstyle:checkstyle pmd:check
```

The individual structure extension artifacts are written beneath `structures/StructuresOutput`.

## Developer compatibility

RCDoors retains the upstream Java packages and public API types under `nl.pim16aap2.animatedarchitecture`. Plugins
compiled against the AnimatedArchitecture API should not rename imports merely because the installed plugin is branded
RCDoors. Runtime consumers should account for the RCDoors plugin name while retaining compatibility with the legacy
name where appropriate.

New RepubliCraft integrations should resolve RCPlatform's `DoorService` instead of dispatching RCDoors console
commands. The service accepts decimal AnimatedArchitecture structure UIDs, returns explicit outcomes, and keeps the
upstream implementation types behind the adapter boundary.

RCBusiness and RCCriminalEnterprises register organization access through `DoorAccessService`.
Registered organization doors use their numeric structure UID. Claims are saved atomically in
`organization-door-claims.properties` and survive an organization provider being disabled or a
server restart. A claimed door denies access until its provider supplies an explicit current
allowance. Native door commands and contract requests apply the same organization policy;
authorized door movement does not give members general permission to edit blocks.
Only the claiming plugin and organization may release a claim after durable asset removal.
Back up the claim file with the organization databases. Invalid claim data prevents the door
adapter from starting with unprotected access.

For the original API documentation and project history, refer to the
[AnimatedArchitecture repository](https://github.com/PimvanderLoos/AnimatedArchitecture) and its
[published Javadocs](https://pimvanderloos.github.io/AnimatedArchitecture/javadoc/).

## License and attribution

RCDoors is distributed under the GNU General Public License, version 3. The upstream `LICENSE` file is retained in this
repository. AnimatedArchitecture and its contributors remain credited as the authors of the upstream work; RepubliCraft
maintains the modifications described in [FORK_NOTICE.md](FORK_NOTICE.md).


## Creation fees and recovery

A creation price confirmation records the reviewed amount without debiting the player. RCDoors
constructs the proposed structure first, then persists a creation payment receipt before dispatching
its wallet debit and native database insertion. Success appears only after those operations finish.
Repeated completion callbacks reuse one receipt. Changing the configured price requires a fresh
review. A configured positive fee remains payable when the economy provider is unavailable; it never
silently becomes free. Zero-price structures keep the free creation path.

The Platform-managed `creation-payments` database stores permanent fee identities, player/world/type,
name/cuboid, exact amount, phase, native UID when known, and immutable reconciliation decisions. Back
it up with the native structure database and organization claim file. Do not clear payment rows to
unlock a player. One unresolved paid creation prevents that player from starting another paid creation.

A cancelled native prepare-create event refunds a known debit through a separately journaled wallet
call. Restart recovers a known completed debit that never reached insertion as a refund. A failed or
interrupted native insertion can have an unknown outcome; it requires inspection before either
acknowledging creation or refunding. Unknown debit/refund responses never automatically repeat.
Known rejected refunds retry every 30 seconds.

Console or staff with `rcdoors.fees.admin` can inspect and reconcile receipts:

```text
/rcdoorfees review [page]
/rcdoorfees resolve <receipt UUID> debit-applied <observed outcome reason>
/rcdoorfees resolve <receipt UUID> debit-not-applied <observed outcome reason>
/rcdoorfees resolve <receipt UUID> created <native UID and matching structure evidence>
/rcdoorfees resolve <receipt UUID> not-created <native database inspection evidence>
/rcdoorfees resolve <receipt UUID> refund-applied <observed outcome reason>
/rcdoorfees resolve <receipt UUID> refund-not-applied <observed outcome reason>
```

Use the decision matching the reported phase and verify the economy/native database history first.
`debit-applied` after an interrupted debit and `not-created` after an uncertain insertion schedule a
refund; they do not resume an old creation session. `created` acknowledges the observed native
structure and keeps its fee. `refund-not-applied` permits one new refund dispatch. Reasons are
required and immutable. Active operations cannot be resolved concurrently.

Integrations that previously called `IEconomyManager.buyStructure` for a positive fee must use
`createStructure` with a stable receipt, reviewed amount and native insertion callback. The old
unjournaled positive-fee method now rejects payment; its zero-fee compatibility path remains available.
The interface's default creation implementation supports free structures and rejects paid operations
unless a durable provider implements them.


Creation fee messages and recovery commands use the `fees` section of the same
`plugins/RCUI/messages/rcdoors.yml` catalog as all other RCDoors messages. They share its
prefix. Bundled `messages.yml` supplies every default; player names, receipt descriptions
and operator reasons remain unparsed placeholders.

RCUI imports an existing `rcdoors-fees.yml` into `messages.fees` in `rcdoors.yml` on startup.
Existing destination edits take precedence; custom source messages and intentionally muted
values are preserved. Once the combined catalog is saved, RCUI archives the old source so
there is one active message YAML for RCDoors. Backups remain available in RCUI's backup
storage rather than the active messages directory.

## Native messages and menu buttons through RCUI

RCDoors requires RCUI 3.0.1. Native messages are registered in
`plugins/RCUI/messages/rcdoors.yml`, and menu skins in the `rcdoors` namespace of
`plugins/RCUI/buttons.yml`. The four stable screens are `main`, `info`, `create`, and
`delete`; the bundled `buttons.yml` lists all 31 button/filler IDs. Empty skin settings
preserve the configured native material and all menu actions.

Every bundled native translation key has a `.text` leaf in the RCUI catalog. For example,
`commands.version.success.text` defaults to `<native>`. That placeholder preserves the
configured native language and existing localization patches. Replace it with custom text,
keeping positional tokens such as `{0}` and `{1}` where needed. The `.text` leaf keeps a
message distinct from its nested description/lore keys.

The native `Text` API applies its existing typed colors, highlights, hover text and clickable
arguments after RCUI resolves the content. MiniMessage styling inside these native string
overrides is flattened at this compatibility boundary. Player arguments remain literal text.
Cloud command-help, command-error and startup-error entries under `cloud`/`system` use safe
Adventure components directly and retain RCUI MiniMessage styling. Third-party structure
types with new, unbundled translation keys retain their native localization fallback.

Apply centralized message/button changes with `/rcui reload`; invalid RCUI catalogs retain
their last valid state. A malformed native positional pattern also retains its last valid
value and logs the affected key. The native restart command continues to reload native
configuration/localization patches. Native failure listeners can use plain diagnostics when
the presentation bridge is unavailable. An RCUI dependency or registration startup failure
is reported in Paper's server log.

RCUI and Paper provide the shared Adventure API. These public types are neither shaded nor
relocated in RCDoors, so RCUI calls and Paper audiences use the same runtime classes.

`/rcdoors help [search] [page]` shows eight permitted commands per page, with clickable navigation
and command suggestions. Hidden commands and commands unavailable to the sender are omitted.
Help rows, argument descriptions, pagination and all command failure categories use the RCUI
`cloud.help.*` and `cloud.exception.*` catalog. Cloud parsing remains native; its obsolete Adventure4
presentation extras are excluded so errors and help use Paper26.2 Adventure5 safely.
