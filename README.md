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

1. Install RCPlatform and Vault on the server.
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

### Migrating from BigDoors

Use a compatible BigDoors release to run `BigDoors PrepareDatabaseForV2` from the server console. After BigDoors
finishes exporting, stop the server and follow the migration instructions produced by that BigDoors version. Back up
the complete source and destination data directories first; legacy exports can occasionally require in-game corrections
to opening directions.

## Building

Build requirements:

- JDK 25+
- Maven
- `net.republicraft.platform:rcplatform-api:1.0.0` in the local Maven repository

`rcplatform-api` is not published to any Maven repository, so it has to be built
from source before RCDoors can be built. Once per machine, or whenever the
contract changes:

```shell
git clone https://github.com/CoffeePNG/RCPlatform
mvn -f RCPlatform/pom.xml -pl rcplatform-api -am -DskipTests install
```

Skipping this step fails the build at `spigot-core` with a dependency
resolution error, and takes every module downstream of it with it.

CI does the same thing in `.github/workflows/build.yml`. Because RCPlatform is a
private repository, that step needs an `RCPLATFORM_TOKEN` repository secret
holding a token with read access to it. Pull requests from forks do not receive
secrets, so they cannot build RCDoors until `rcplatform-api` is published
somewhere reachable.

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

For the original API documentation and project history, refer to the
[AnimatedArchitecture repository](https://github.com/PimvanderLoos/AnimatedArchitecture) and its
[published Javadocs](https://pimvanderloos.github.io/AnimatedArchitecture/javadoc/).

## License and attribution

RCDoors is distributed under the GNU General Public License, version 3. The upstream `LICENSE` file is retained in this
repository. AnimatedArchitecture and its contributors remain credited as the authors of the upstream work; RepubliCraft
maintains the modifications described in [FORK_NOTICE.md](FORK_NOTICE.md).
