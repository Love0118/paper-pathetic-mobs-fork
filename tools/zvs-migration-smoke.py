"""Compile and run the migration probe in a disposable server, using an accepted EULA file."""
import argparse
import pathlib
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server-jar', required=True, type=pathlib.Path)
parser.add_argument('--api-jar', required=True, type=pathlib.Path)
parser.add_argument('--eula-file', required=True, type=pathlib.Path)
parser.add_argument('--output', required=True, type=pathlib.Path)
parser.add_argument('--gradle-cache', type=pathlib.Path, default=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1')
args = parser.parse_args()
assert 'eula=true' in args.eula_file.read_text(), 'Supply an already accepted EULA file'
root = args.output.resolve()
root.mkdir(parents=True, exist_ok=False)
classes = root/'classes'
classes.mkdir()
deps = [args.api_jar.resolve()] + [p for p in args.gradle_cache.rglob('*.jar')
    if any(k in str(p) for k in ('net.kyori', 'org.jetbrains', 'org.jspecify', 'com.google.guava'))]
argfile = root/'javac.args'
argfile.write_text('-cp\n"' + ';'.join(p.as_posix() for p in deps) + '"\n-d\n"'
    + classes.as_posix() + '"\n"' + (pathlib.Path(__file__).parent/'ZvsMigrationSmoke.java').resolve().as_posix() + '"\n')
subprocess.run(['javac', '@'+str(argfile)], check=True)
probe = root/'ZvsMigrationSmoke.jar'
with zipfile.ZipFile(probe, 'w') as jar:
    for p in classes.rglob('*.class'):
        jar.write(p, p.relative_to(classes))
    jar.writestr('plugin.yml', "name: ZvsMigrationSmoke\nversion: 1.0\nmain: ZvsMigrationSmoke\napi-version: '26.3'\n")
for mode in ('hybrid', 'trusted'):
    run = root/mode
    (run/'plugins').mkdir(parents=True)
    (run/'config').mkdir()
    shutil.copy2(args.eula_file, run/'eula.txt')
    shutil.copy2(probe, run/'plugins/ZvsMigrationSmoke.jar')
    (run/'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\n'
        'spawn-protection=0\npause-when-empty-seconds=-1\n')
    (run/'config/paper-global.yml').write_text('optimizations:\n  zvs-managed-damage:\n    event-mode: '+mode+'\n')
    with (run/'console.log').open('w', encoding='utf-8') as log:
        subprocess.run(['java', '-Xms2G', '-Xmx2G', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-Dzvs.smoke.mode='+mode, '-jar', str(args.server_jar.resolve()), 'nogui'],
            cwd=run, stdout=log, stderr=subprocess.STDOUT, timeout=240,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0), check=True)
    output = (run/'console.log').read_text(encoding='utf-8', errors='replace')
    assert 'ZVS_MIGRATION_SMOKE_PASS' in output and 'ZVS_MIGRATION_SMOKE_FAIL' not in output, str(run/'console.log')
    print(mode, 'PASS', flush=True)
