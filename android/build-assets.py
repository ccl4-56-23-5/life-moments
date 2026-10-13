from pathlib import Path
import base64, json, shutil, re, sys
from PIL import Image

base = Path(__file__).resolve().parent
workspace = base.parent
sys.path.insert(0, str(workspace / 'scripts'))
from build_config import load_config, script
sys.path.insert(0, str(workspace / 'preview'))
from create_demo import create_demo
assets = base / 'app/src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
(assets / 'config.js').write_text(script(load_config(workspace)), encoding='utf-8')
for source, name in [
    ('desktop/app/brand/logo.svg', 'logo.svg'),
    ('preview/maplibre-gl.js', 'maplibre-gl.js'),
    ('preview/maplibre-gl.css', 'maplibre-gl.css'),
    ('desktop/app/licenses/MapLibre-LICENSE.txt', 'MapLibre-LICENSE.txt'),
]:
    shutil.copy2(workspace / source, assets / name)

samples = create_demo(workspace / 'preview/demo-data.json')
photos = [p.copy() for p in samples['photos'] if 'image' in p]
assert len(photos) == 35
for p in photos:
    p['cloudPath'] = '/apps/生活点滴/示例原片/2022/' + p['name']
    for key in ['source_sha256', 'fullImage', 'path']:
        p.pop(key, None)
ids = [p['id'] for p in photos]
people = ['同学 · 示例', '朋友 · 示例', '家人 · 示例']
assignments = {str(p['id']): [people[i % 3]] + ([people[(i+1) % 3]] if i % 5 == 0 else []) for i, p in enumerate(photos)}
index = {
    'people': people, 'assignments': assignments,
    'profiles': {name: {'tags': [['校园'], ['朋友'], ['家人']][i], 'avatar': {'photoId': ids[i], 'x': .15, 'y': .12, 'width': .65, 'height': .65}} for i, name in enumerate(people)},
    'notes': {str(ids[0]): '这条批注仅用于界面演示，真实人物与批注从电脑端索引读取。'},
    'albums': [
        {'name': '校园时光 · 示例', 'ids': ids[:15], 'note': '走过熟悉的校园，记下这一年的光。\n这是演示相册批注。'},
        {'name': '一起庆祝 · 示例', 'ids': ids[15:25], 'note': '相聚的日子，值得留在生活里。'},
        {'name': '2022 年的片段 · 示例', 'ids': ids, 'note': ''},
    ], 'photoLocations': {}, 'gpsCrs': 'WGS84',
}
demo = {'schemaVersion': 2, 'libraryId': 'demo2022', 'cloudRoot': '/apps/生活点滴/示例原片',
        'createdAt': '2026-10-12T00:00:00Z', 'photos': photos, 'index': index}
(assets / 'demo.json').write_text(json.dumps(demo, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
(assets / 'preview-snapshot.json').write_text('null', encoding='utf-8')
drawable = base / 'app/src/main/res/drawable'
drawable.mkdir(parents=True, exist_ok=True)
image = Image.open(workspace / 'desktop/app/brand/logo.png').convert('RGBA')
image.resize((192, 192), Image.Resampling.LANCZOS).save(drawable / 'app_icon.png')
guide = base / '手机端拉取数据教程.html'
if guide.exists():
    text = guide.read_text(encoding='utf-8')
    text = text.replace('app/src/main/assets/', '').replace('../docs/images/guide-', 'guide-')
    text = text.replace('打开生活点滴界面预览', '返回生活点滴')
    text = re.sub(r' onerror="[^"]*"', '', text)
    (assets / 'guide.html').write_text(text, encoding='utf-8')
    for source in (workspace / 'docs/images').glob('guide-*.png'):
        shutil.copy2(source, assets / source.name)
    # The delivered tutorial is one portable HTML file, with its screenshots embedded.
    artifacts = base / 'artifacts'
    artifacts.mkdir(exist_ok=True)
    portable = re.sub(r' onerror="[^"]*"', '', guide.read_text(encoding='utf-8'))
    def embed_image(match):
        source = base / match.group(1)
        mime = 'image/svg+xml' if source.suffix == '.svg' else 'image/png'
        data = base64.b64encode(source.read_bytes()).decode('ascii')
        return 'src="data:' + mime + ';base64,' + data + '"'
    portable = re.sub(r'src="(app/src/main/assets/logo\.svg|\.\./docs/images/guide-[^"]+\.png)"', embed_image, portable)
    portable = portable.replace('<a href="app/src/main/assets/index.html">打开生活点滴界面预览</a>', '手机 App 的“设置 → 手机拉取数据教程”中也可随时查看本文。')
    (artifacts / '手机端拉取数据教程.html').write_text(portable, encoding='utf-8')
print(f'Prepared Android assets, {len(photos)} sample photos. Original files were not touched.')
