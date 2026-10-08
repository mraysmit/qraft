"""Phase 2 of task-list-single-pom-and-quorus-removal: merge the Maven modules into one build.

Run from the repository root. Stops before changing anything if a precondition fails, and stops on the
first unexpected file instead of guessing. Files keep their bytes; text edits keep each file's line endings.
"""
import os
import pathlib
import shutil
import sys

R = pathlib.Path('.').resolve()
STAGE = R / 'logs' / 'refactor-baseline-2026-10-04' / 'phase2'
MODULES = ['qraft-raft-engine', 'qraft-distributed-state', 'qraft-core', 'qraft-agent', 'qraft-controller',
           'qraft-runtime']
log = []


def fail(message):
    print('STOP: ' + message)
    sys.exit(1)


# ---------------------------------------------------------------- preconditions
for module in MODULES:
    if not (R / module / 'pom.xml').is_file():
        fail(f'{module}/pom.xml is missing')
if (R / 'src').exists():
    fail('src/ already exists at the repository root')
for staged in ('pom.xml', 'PackageDependencyTest.java'):
    if not (STAGE / staged).is_file():
        fail(f'staged file {staged} is missing')
if (R / 'docker' / 'Dockerfile').exists():
    fail('docker/Dockerfile already exists')


def normalized(path):
    return path.read_bytes().replace(b'\r\n', b'\n')


def move_file(source, target):
    if target.exists():
        fail(f'{target.relative_to(R)} already exists (from {source.relative_to(R)})')
    target.parent.mkdir(parents=True, exist_ok=True)
    os.rename(source, target)
    log.append(f'moved {source.relative_to(R)} -> {target.relative_to(R)}')


def move_tree(source, target):
    for path in sorted(source.rglob('*')):
        if path.is_file():
            move_file(path, target / path.relative_to(source))


# ---------------------------------------------------------------- sources and protos
for module in MODULES:
    for kind in ('main/java', 'test/java', 'main/proto'):
        source = R / module / 'src' / kind
        if source.exists():
            move_tree(source, R / 'src' / kind)

# ---------------------------------------------------------------- resources
# The controller's resources move as they are. Its logback.xml is the one the executable jar already ships.
for kind in ('main/resources', 'test/resources'):
    source = R / 'qraft-controller' / 'src' / kind
    if source.exists():
        move_tree(source, R / 'src' / kind)

dropped = {
    # The agent's logback.xml never reached the executable jar, which kept the controller's.
    R / 'qraft-agent' / 'src' / 'main' / 'resources' / 'logback.xml': None,
    # Identical copies of the controller's test settings.
    R / 'qraft-agent' / 'src' / 'test' / 'resources' / 'junit-platform.properties':
        R / 'src' / 'test' / 'resources' / 'junit-platform.properties',
    R / 'qraft-runtime' / 'src' / 'test' / 'resources' / 'junit-platform.properties':
        R / 'src' / 'test' / 'resources' / 'junit-platform.properties',
}
for path, same_as in dropped.items():
    if not path.is_file():
        fail(f'expected {path.relative_to(R)}')
    if same_as is not None and normalized(path) != normalized(same_as):
        fail(f'{path.relative_to(R)} differs from {same_as.relative_to(R)}')
    path.unlink()
    log.append(f'dropped {path.relative_to(R)}')

# ---------------------------------------------------------------- one image
move_file(R / 'qraft-runtime' / 'Dockerfile', R / 'docker' / 'Dockerfile')
move_file(R / 'qraft-runtime' / 'docker-entrypoint.sh', R / 'docker' / 'docker-entrypoint.sh')
for unused in (R / 'qraft-agent' / 'Dockerfile', R / 'qraft-agent' / 'docker-entrypoint.sh'):
    unused.unlink()
    log.append(f'deleted unused second image file {unused.relative_to(R)}')
# A Quorus-era Docker environment file that nothing reads; the Docker tests set BuildKit themselves.
(R / 'qraft-controller' / '.env').unlink()
log.append('deleted qraft-controller/.env')

# ---------------------------------------------------------------- logs written inside module directories
for module in MODULES:
    logs = R / module / 'logs'
    if logs.exists():
        move_tree(logs, R / 'logs' / 'legacy-module-logs' / module)

# ---------------------------------------------------------------- remove the emptied modules
for module in MODULES:
    root = R / module
    leftovers = [path.relative_to(R) for path in root.rglob('*')
                 if path.is_file() and path.name != 'pom.xml' and 'target' not in path.relative_to(root).parts]
    if leftovers:
        fail(f'{module} still holds {leftovers}')
for module in MODULES:
    shutil.rmtree(R / module)
    log.append(f'removed {module}/ (its pom.xml and build output)')

# ---------------------------------------------------------------- new files
shutil.copyfile(STAGE / 'pom.xml', R / 'pom.xml')
log.append('wrote pom.xml (single module)')
test = R / 'src' / 'test' / 'java' / 'dev' / 'mars' / 'qraft' / 'architecture' / 'PackageDependencyTest.java'
test.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(STAGE / 'PackageDependencyTest.java', test)
log.append(f'wrote {test.relative_to(R)}')

agent_logging_test = R / 'src' / 'test' / 'java' / 'dev' / 'mars' / 'qraft' / 'agent' / 'LoggingConfigurationTest.java'
agent_logging_test.unlink()
log.append(f'deleted {agent_logging_test.relative_to(R)}: it tested the agent logback.xml, which is gone')


# ---------------------------------------------------------------- text edits
def edit(relative, replacements):
    path = R / relative
    raw = path.read_bytes()
    crlf = b'\r\n' in raw
    text = raw.decode('utf-8').replace('\r\n', '\n')
    for old, new, count in replacements:
        found = text.count(old)
        if found != count:
            fail(f'{relative}: expected {count} of {old[:60]!r}, found {found}')
        text = text.replace(old, new)
    if crlf:
        text = text.replace('\n', '\r\n')
    path.write_bytes(text.encode('utf-8'))
    log.append(f'edited {relative}')


def edit_raw(relative, replacements):
    # For files with mixed line endings: replace exact bytes, so every other line keeps its ending.
    path = R / relative
    raw = path.read_bytes()
    for old, new, count in replacements:
        found = raw.count(old)
        if found != count:
            fail(f'{relative}: expected {count} of {old[:60]!r}, found {found}')
        raw = raw.replace(old, new)
    path.write_bytes(raw)
    log.append(f'edited {relative}')


edits_file = STAGE / 'phase2_edits.py'
exec(compile(edits_file.read_text(encoding='utf-8'), str(edits_file), 'exec'))

print('\n'.join(log))
print(f'\n{len(log)} steps done')
