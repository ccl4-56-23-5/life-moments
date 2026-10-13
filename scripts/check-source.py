"""Check staged source for accidentally included local data before publishing."""
import json, subprocess, sys
from pathlib import Path
from urllib.parse import urlparse, parse_qs

root = Path(__file__).resolve().parent.parent
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')
paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode('utf-8').split('\0')
paths = [path for path in paths if path]
if not paths:
    raise SystemExit('请先暂存要备份的源码。')
forbidden = ['desktop/artifacts/', 'desktop/out/', 'desktop/vendor/', 'android/artifacts/', 'android/app/build/', 'android/.gradle/', 'tools/local/', 'releases/', 'backups/']
secrets = []
config = root / 'config.local.json'
if config.exists():
    url = json.loads(config.read_text(encoding='utf-8-sig')).get('mapStyleUrl', '')
    if url:
        secrets.append(url.encode())
        secrets.extend(v.encode() for v in parse_qs(urlparse(url).query).get('key', []) if v)
problems = []
total = 0
for path in paths:
    file = root / path
    if path.startswith(tuple(forbidden)) or path.endswith(('.keystore', '.jks', '.pfx', '.pem')) or Path(path).name in ('config.local.json', 'local.properties'):
        problems.append((path, '本地配置、凭据或构建目录'))
    data = file.read_bytes()
    total += len(data)
    if len(data) > 20 * 1024 * 1024:
        problems.append((path, '大文件应放在发布附件中'))
    if any(secret in data for secret in secrets):
        problems.append((path, '包含本地地图服务配置'))
if problems:
    print(json.dumps({'问题': problems}, ensure_ascii=False, indent=2))
    raise SystemExit(1)
print(json.dumps({'结果': '通过', '源码文件数': len(paths), '总字节数': total, '检查': ['本地目录排除', '凭据文件排除', '本地地图配置排除', '大文件排除']}, ensure_ascii=False))
