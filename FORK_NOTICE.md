# RCDoors Fork Notice

RCDoors is a modified fork of
[PimvanderLoos/AnimatedArchitecture](https://github.com/PimvanderLoos/AnimatedArchitecture). This fork was created from
upstream commit
[`e9dbef69bb6f0ab872130a5922d72a6b0b78c3d6`](https://github.com/PimvanderLoos/AnimatedArchitecture/commit/e9dbef69bb6f0ab872130a5922d72a6b0b78c3d6).

**Modification notice: AnimatedArchitecture was changed for RCDoors on 2026-08-28 by RepubliCraft.**

RCDoors is not an official AnimatedArchitecture release and is not maintained or supported by the upstream project.
Issues specific to this fork should be reported to the RepubliCraft maintainers.

## Fork changes

Relative to the identified upstream revision, this fork:

- targets the RepubliCraft Paper 1.21.4 server environment and Java 21;
- presents the plugin to players and administrators as RCDoors;
- produces the deployable artifact as `RCDoors.jar`;
- can import an existing `plugins/AnimatedArchitecture` data directory on the first RCDoors startup without modifying
  the legacy source directory;
- preserves the upstream Java packages, API types, database format, extension identifiers, and permission nodes, and
  recognizes persistent keys written by the legacy plugin; and
- keeps `/animatedarchitecture` and `/aa` as legacy command aliases alongside `/rcdoors` so existing integrations do
  not break during migration.

For exact implementation changes, consult this repository's commit history and compare it with the upstream revision
linked above.

## License

The upstream project and this modified fork are licensed under the
[GNU General Public License, version 3](LICENSE). The original copyright notices and the complete GPL-3.0 license text
are retained. Distribution of modified binaries must comply with the GPL-3.0, including the corresponding-source
requirements that apply to the distributor.
