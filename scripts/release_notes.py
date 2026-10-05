"""Generate signed-release notes from this run's evidence, never pinned test counts.

Offline only. Does not run instrumentation, access devices or handle private keys.
"""
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def core_counts(root):
    reports = sorted((root / 'core/build/test-results/test').glob('TEST-*.xml'))
    if not reports:
        raise ValueError('Missing core test XML: refusing release notes')
    counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for report in reports:
        suite = ET.parse(report).getroot()
        if suite.tag != 'testsuite':
            raise ValueError(f'Unexpected core report format: {report.name}')
        for key in counts:
            value = int(suite.attrib[key])
            if value < 0:
                raise ValueError('Negative core test count')
            counts[key] += value
    counts['passed'] = counts['tests'] - counts['failures'] - counts['errors'] - counts['skipped']
    if counts['passed'] <= 0 or counts['failures'] or counts['errors']:
        raise ValueError('Core tests not successful: refusing release')
    return counts


def lint_counts(root, variant):
    report = root / f'app/build/reports/lint-results-{variant}.xml'
    issues = ET.parse(report).getroot()
    if issues.tag != 'issues':
        raise ValueError('Unexpected lint report format')
    severities = [issue.attrib.get('severity') for issue in issues.findall('issue')]
    result = {
        'errors': sum(severity in ('Error', 'Fatal') for severity in severities),
        'warnings': severities.count('Warning'),
        'other': sum(severity not in ('Error', 'Fatal', 'Warning') for severity in severities),
    }
    if result['errors']:
        raise ValueError(f'{variant} Lint errors: refusing release')
    return result


def generate(root=ROOT, env=None):
    env = os.environ if env is None else env
    counts = core_counts(root)
    lint = {variant: lint_counts(root, variant) for variant in ('debug', 'release')}
    output = root / 'deliverables'
    assets = (
        'YU-Flash-Tool.apk', 'YU-Flash-Tool-androidTest.apk',
        'YU-Flash-Tool-source.zip', 'YU-Flash-Tool-verification.zip',
        'BUILD-INFO.txt', 'LICENSE', 'THIRD_PARTY_NOTICES.md',
        'SIGNING-CERTIFICATE-SHA256.txt', 'APKSIGNER.txt', 'APK-BADGING.txt',
    )
    for name in assets:
        path = output / name
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f'Missing or empty verified release asset: {name}')
    fingerprint = env['YU_RELEASE_CERT_SHA256'].upper()
    if not re.fullmatch(r'[0-9A-F]{64}', fingerprint):
        raise ValueError('Invalid public signing fingerprint')
    actual = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$',
                        (output / 'APKSIGNER.txt').read_text(encoding='utf-8'), re.M)
    if len(actual) != 1 or actual[0].upper() != fingerprint:
        raise ValueError('Signer evidence does not match expected public certificate')
    badging = (output / 'APK-BADGING.txt').read_text(encoding='utf-8')
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != ('io.yu.flash', '6', '1.0.1'):
        raise ValueError('APK identity/version mismatch')
    if re.search(r'^application-debuggable(?:\s|$)', badging, re.M):
        raise ValueError('Debuggable APK cannot be a formal release')
    repo, sha, run, attempt = (env[key] for key in (
        'GITHUB_REPOSITORY', 'GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))
    server = env.get('GITHUB_SERVER_URL', 'https://github.com').rstrip('/')
    editorial = (root / 'docs/RELEASE_NOTES.md').read_text(encoding='utf-8')
    # Do not append stale preview instructions or fixed old test counts to 1.0.1.
    # Maintainers own the feature narrative; this script owns per-run evidence.
    if re.match(r'^#\s+1\.0\.1(?:\s|·|$)', editorial):
        notes = editorial.rstrip() + '\n'
    else:
        notes = '''# 1.0.1 · 正式签名发行

- 版本码 6，应用包名 `io.yu.flash`，主附件为 `YU-Flash-Tool.apk`。
- 使用持久 RSA-4096 发布证书，构建后验证签名身份、版本和非 Debuggable 属性。
- 每次成功主分支运行分别保留源码、报告、许可、摘要与独立正式 Release，不覆盖历史构建。
- “正式签名”描述发行方式，不代表真机兼容性、稳定性或刷写安全已经验收。

> 本应用包含可实际覆盖分区的实验性 raw 写入。此构建不执行设备、Root、写入、断电或恢复测试。
> 不要将签名通过误认为设备安全认证；有写入意图/不确定状态时，不要为安装新包而卸载、清数据或盲目重启。
'''
    suites = []
    for report in sorted((root / 'core/build/test-results/test').glob('TEST-*.xml')):
        suite = ET.parse(report).getroot()
        a = suite.attrib
        suites.append(f"  - `{a.get('name', report.stem)}`：{a['tests']} 项，失败 {a['failures']}，错误 {a['errors']}，跳过 {a['skipped']}。")
    suite_detail = '\n'.join(suites)
    notes += f'''
## 本次构建的真实证据

- 完整提交：`{sha}`
- [构建日志]({server}/{repo}/actions/runs/{run}) · 第 {attempt} 次尝试
- 核心测试：共 {counts['tests']} 项，通过 {counts['passed']}，失败 {counts['failures']}，错误 {counts['errors']}，跳过 {counts['skipped']}；取自本次 XML 报告，不固定数量。
{suite_detail}
  - `DirectFlashTest` 覆盖当前直接路线的内存模拟；旧策略/传输测试仍执行，但不证明活动写入受旧检查保护。以上均未执行真实 Root/dd。
- Android Debug Lint：{lint['debug']['errors']} 错误、{lint['debug']['warnings']} 警告、{lint['debug']['other']} 其他记录。
- Android Release Lint：{lint['release']['errors']} 错误、{lint['release']['warnings']} 警告、{lint['release']['other']} 其他记录。完整报告见附件。
- 主 Release APK 与 Debug 仪器测试 APK 已编译；仪器测试**未执行**，不宣称 UI/视觉或真机测试通过。
- APK 已通过 `apksigner verify`；唯一签名证书 SHA-256：`{fingerprint}`。
- 主 APK 的包名 `io.yu.flash`、版本 `1.0.1`、版本码 `6` 与非 Debuggable 属性已核对。

## 签名与安装注意

沿用 1.0.0 的固定发布证书，版本码递增；符合 Android 更新条件时可覆盖已安装的同签名正式版。与旧 Debug 预览签名不同，通常不能直接覆盖安装旧预览。
不要因为签名冲突贸然卸载或清数据，尤其有写入意图、未解决事务或不确定状态时；先保留记录、备份与独立救援方式。
同证书与版本规则是后续正常更新的必要条件，不是设备兼容性或刷写安全保证。

## 附件与复核

- `YU-Flash-Tool.apk`：持久证书签名、非 Debuggable 主应用；最低 API 26，目标 API 35。
- `YU-Flash-Tool-androidTest.apk`：Debug 仪器测试包，仅编译，非主应用；不能用来证明签名 Release 已做仪器测试。
- `YU-Flash-Tool-source.zip`：由上述同一提交导出的源码，不含密钥。
- `YU-Flash-Tool-verification.zip`：本次核心测试与 Debug/Release Lint 报告。
- `BUILD-INFO.txt`：提交、工作流运行、版本、签名指纹、工具链及验证边界。
- `APKSIGNER.txt`、`APK-BADGING.txt`：本次 APK 签名与包身份检查输出。
- `SIGNING-CERTIFICATE-SHA256.txt`：公开签名证书指纹（不是 APK 文件哈希）。
- `LICENSE`、`THIRD_PARTY_NOTICES.md`：许可材料。
- `RELEASE-NOTES.md`：本说明；`SHA256SUMS.txt` 覆盖以上全部交付文件（摘要表自身除外）。

下载后运行 `sha256sum --check SHA256SUMS.txt`，并以 Android SDK `apksigner verify --print-certs YU-Flash-Tool.apk` 独立核对公开证书指纹。
各文件哈希用于核对下载一致性，不独立证明源码、运行环境或硬件行为可信。
'''
    (output / 'RELEASE-NOTES.md').write_text(notes, encoding='utf-8')


if __name__ == '__main__':
    try:
        generate()
    except (KeyError, ValueError, OSError, ET.ParseError) as error:
        raise SystemExit(str(error)) from None
