"""Explicit maintainer-only refresh of pinned official Material Symbols; not a build step.
Downloads individual Android vectors, not a font, dependency or repository checkout.
"""
from pathlib import Path
import hashlib
import json
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '737e3324305806514d7909874fa1818ae1808232'
BASE = f'https://raw.githubusercontent.com/google/material-design-icons/{COMMIT}/'
ANDROID = '{http://schemas.android.com/apk/res/android}'
NAV = ['home', 'assignment', 'settings', 'info']
ACTION = ['refresh', 'search', 'download', 'upload', 'expand_more', 'check_circle',
          'warning', 'delete', 'content_copy', 'description', 'folder_open', 'sync', 'error', 'cancel']
SPECS = [(name, fill, 24) for name in NAV for fill in (0, 1)]
SPECS += [(name, 0, 24) for name in ACTION] + [('flash_on', 1, 48)]


def main():
    destination = ROOT / 'app/src/main/res/drawable'
    destination.mkdir(parents=True, exist_ok=True)
    entries = []
    for name, fill, size in SPECS:
        suffix = '_fill1' if fill else ''
        source = f'symbols/android/{name}/materialsymbolsrounded/{name}{suffix}_{size}px.xml'
        with urllib.request.urlopen(BASE + source, timeout=60) as response:
            original = response.read()
        vector = ET.fromstring(original)
        assert vector.tag == 'vector' and all(child.tag == 'path' for child in vector)
        # Compose uses LocalContentColor; notifications require un-tinted white alpha.
        # Keep every original path unchanged, remove the platform theme tint only.
        text = original.decode('utf-8').replace('\n    android:tint="?attr/colorControlNormal"', '')
        notice = f'<!-- Material Symbols Rounded by Google, Apache-2.0.\n     Source commit: {COMMIT}\n     Modified: removed platform tint; paths unchanged. See assets/Material-Symbols-NOTICE.txt. -->\n'
        text = notice + text
        file = destination / f'ms_{name}{suffix}_{size}.xml'
        file.write_text(text, encoding='utf-8', newline='\n')
        entries.append({'file': file.relative_to(ROOT).as_posix(), 'source': BASE + source,
                        'source_sha256': hashlib.sha256(original).hexdigest(),
                        'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                        'name': name, 'fill': fill, 'optical_size': size})
    (ROOT / 'docs/material-symbols.json').write_text(json.dumps({
        'repository': 'https://github.com/google/material-design-icons', 'commit': COMMIT,
        'style': 'Rounded', 'weight': 400, 'grade': 0, 'license': 'Apache-2.0',
        'modification': 'Removed Android platform tint; original paths retained.', 'icons': entries
    }, indent=2) + '\n', encoding='utf-8')
    print(f'Vendored {len(entries)} pinned official vectors; paths unmodified.')


if __name__ == '__main__':
    main()
