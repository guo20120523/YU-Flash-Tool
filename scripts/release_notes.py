"""Generate release notes from actual CI evidence; no network or credentials."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
reports = list((root / 'core/build/test-results/test').glob('TEST-*.xml'))
if not reports:
    raise SystemExit('Missing core test XML: refusing release notes')
counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
for report in reports:
    suite = ET.parse(report).getroot()
    for key in counts:
        counts[key] += int(suite.attrib[key])
if counts['tests'] == 0 or counts['failures'] or counts['errors']:
    raise SystemExit('Core tests not successful: refusing release')
issues = ET.parse(root / 'app/build/reports/lint-results-debug.xml').getroot()
severities = [issue.attrib.get('severity') for issue in issues.findall('issue')]
errors = sum(s in ('Error', 'Fatal') for s in severities)
warnings = severities.count('Warning')
if errors:
    raise SystemExit('Lint errors: refusing release')
repo, sha, run = (os.environ[k] for k in ('GITHUB_REPOSITORY', 'GITHUB_SHA', 'GITHUB_RUN_ID'))
attempt = os.environ['GITHUB_RUN_ATTEMPT']
notes = (root / 'docs/RELEASE_NOTES.md').read_text(encoding='utf-8')
notes += f'''
## 本次构建的真实证据

- 完整提交：`{sha}`
- [构建日志](https://github.com/{repo}/actions/runs/{run}) · 第 {attempt} 次尝试
- 核心测试：{counts['tests']} 项，失败 {counts['failures']}，错误 {counts['errors']}，跳过 {counts['skipped']}。
- Android Lint：{errors} 错误、{warnings} 警告，完整报告在附件。
- 主 APK 与仪器测试 APK 编译成功；仪器测试**未执行**。

## 附件

- `YU-Flash-Tool-debug.apk`：主应用。
- `YU-Flash-Tool-androidTest.apk`：仅供测试执行器使用，不是主应用。
- `YU-Flash-Tool-source.zip`：与 APK 相同提交的源码。
- `YU-Flash-Tool-verification.zip`：核心测试与 Lint 报告。
- `BUILD-INFO.txt`、`LICENSE`、`THIRD_PARTY_NOTICES.md`：构建身份及许可材料。
- `SHA256SUMS.txt`：所有上述交付文件的摘要，安装前请核对。
'''
(root / 'deliverables/RELEASE-NOTES.md').write_text(notes, encoding='utf-8')
