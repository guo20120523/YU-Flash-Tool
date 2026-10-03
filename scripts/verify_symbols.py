"""Offline icon-resource integrity checks. No SDK, network, Root or device IO."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    manifest = json.loads((ROOT / 'docs/material-symbols.json').read_text(encoding='utf-8'))
    require(manifest['style'] == 'Rounded' and manifest['weight'] == 400 and manifest['grade'] == 0, 'Wrong Symbols style')
    icons = manifest['icons']
    require(len(icons) == 23, 'Expected 23 pinned vectors')
    resources = ROOT / 'app/src/main/res'
    known = set()
    for item in icons:
        p = ROOT / item['file']
        require(hashlib.sha256(p.read_bytes()).hexdigest() == item['sha256'], f'Changed resource: {p.name}')
        require(f"/{manifest['commit']}/symbols/android/" in item['source'], 'Unpinned/non-Symbols source')
        v = ET.parse(p).getroot()
        require(v.tag == 'vector' and A + 'tint' not in v.attrib, 'Unexpected vector or platform tint')
        require(v.attrib[A + 'width'] == f"{item['optical_size']}dp", 'Wrong optical size')
        require(all(c.tag == 'path' and c.attrib[A + 'pathData'] for c in v), 'Empty vector path')
        known.add(p.stem)
    require({p.stem for p in (resources / 'drawable').glob('ms_*.xml')} == known, 'Untracked symbol vector')
    symbols = (ROOT / 'app/src/main/java/io/yu/flash/ui/Symbols.kt').read_text(encoding='utf-8')
    used = set(re.findall(r'R\.drawable\.(ms_\w+)', symbols))
    require(used == known, 'Missing/unknown Compose symbol mapping')
    for name in ['home', 'assignment', 'settings', 'info']:
        require(f'ms_{name}_24' in known and f'ms_{name}_fill1_24' in known, 'Missing navigation state')
    original = ET.parse(resources / 'drawable/ms_flash_on_fill1_48.xml').getroot().find('path').attrib[A + 'pathData']
    for file in ['ic_launcher_foreground.xml', 'ic_launcher_monochrome.xml']:
        v = ET.parse(resources / 'drawable' / file).getroot()
        require(v.attrib[A + 'viewportWidth'] == '108', 'Wrong adaptive canvas')
        group = v.find('group')
        require(group is not None and group.attrib[A + 'scaleX'] == '0.05' and group.attrib[A + 'scaleY'] == '0.05', 'Wrong safe-zone scale')
        require(group.attrib[A + 'translateX'] == '30' and group.attrib[A + 'translateY'] == '30', 'Wrong safe-zone offset')
        require(group.find('path').attrib[A + 'pathData'] == original, 'Launcher no longer uses official path')
    for api in [26, 33]:
        v = ET.parse(resources / f'mipmap-anydpi-v{api}/ic_launcher.xml').getroot()
        require(v.tag == 'adaptive-icon' and v.find('foreground') is not None, 'Missing adaptive launcher')
        require((v.find('monochrome') is not None) == (api == 33), 'Wrong themed icon API')
    app_manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    app = app_manifest.find('application')
    require(app.attrib[A + 'icon'] == '@mipmap/ic_launcher' and app.attrib[A + 'roundIcon'] == '@mipmap/ic_launcher', 'Launcher not wired')
    require(all(p.attrib[A + 'name'] != 'android.permission.INTERNET' for p in app_manifest.findall('uses-permission')), 'Unexpected network permission')
    for p in (ROOT / 'app/src/main/java').rglob('*.kt'):
        text = p.read_text(encoding='utf-8')
        require(not re.search(r'android\.R\.drawable|material\.icons|Icons\.(?:Default|Filled|Outlined|Rounded)', text), f'Legacy icon in {p.name}')
    notice = (ROOT / 'app/src/main/assets/Material-Symbols-NOTICE.txt').read_text(encoding='utf-8')
    require(manifest['commit'] in notice and 'Google' in notice and 'END OF TERMS AND CONDITIONS' in notice, 'Missing offline attribution/license')
    require(not list(resources.glob('font/*')), 'Unexpected icon-font dependency')
    print('PASS: 23 pinned vectors, all symbol mappings, 4 navigation pairs, adaptive/monochrome launchers, offline license and no legacy/network icon dependencies.')
    print('Static resource checks only; Android rendering, launcher masks and accessibility require device/emulator validation.')


if __name__ == '__main__':
    main()
