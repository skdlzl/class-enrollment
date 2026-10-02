"""Assemble Apache's Maven-published JMeter 5.6.3 modules for CI only."""
from pathlib import Path
import shutil
import zipfile

root = Path('/tmp/admission-jmeter')
(root / 'lib/junit').mkdir(parents=True, exist_ok=True)
(root / 'bin').mkdir(parents=True, exist_ok=True)
(root / 'lib/ext').mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(root / 'lib/ApacheJMeter_config-5.6.3.jar') as archive:
    for member in archive.namelist():
        if member.startswith('bin/') and not member.endswith('/'):
            dest = root / member
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.read(member))
shutil.move(root / 'lib/ApacheJMeter-5.6.3.jar', root / 'bin/ApacheJMeter.jar')
for path in (root / 'lib').glob('ApacheJMeter_*.jar'):
    shutil.move(path, root / 'lib/ext' / path.name)
launcher = root / 'bin/jmeter'
launcher.write_text('#!/bin/sh\nexec java -Xms256m -Xmx1g -Djava.awt.headless=true -jar /tmp/admission-jmeter/bin/ApacheJMeter.jar "$@"\n')
launcher.chmod(0o755)
