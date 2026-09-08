"""Select exactly one built plugin archive and give its retained artifact a stable name."""
import argparse
from pathlib import Path
import shutil
from deploy_pterodactyl import DeploymentError, plugin_name


def prepare(directory, identity, destination):
    matches = []
    for jar in directory.glob("*.jar"):
        if jar.name.startswith("original-") or jar.stem.endswith(("-sources", "-javadoc", "-tests")):
            continue
        if plugin_name(jar.read_bytes()) == identity:
            matches.append(jar)
    if len(matches) != 1:
        raise DeploymentError(f"Expected one built {identity} plugin in {directory}; found {len(matches)}.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(matches[0], destination)
    print(f"Selected {matches[0]} -> {destination}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("identity")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.directory, args.identity, args.destination)
