"""Configuration kept outside shared source; only public map style URLs reach UI."""
import json, os
from pathlib import Path

def load_config(root):
    path = Path(root) / 'config.local.json'
    config = json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else {}
    url = os.environ.get('LIFE_MOMENTS_MAP_STYLE_URL', config.get('mapStyleUrl', ''))
    if url and not url.startswith('https://'):
        raise ValueError('mapStyleUrl must use HTTPS')
    return {'mapStyleUrl': url}

def script(config):
    return 'window.lifeMomentsConfig=' + json.dumps(config, ensure_ascii=False).replace('<', '\\u003c') + ';'
