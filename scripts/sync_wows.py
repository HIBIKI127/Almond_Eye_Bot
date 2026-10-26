"""Download the site's complete ship dataset once; bot chats read only the local snapshot.
Usage: python scripts/sync_wows.py [--server ASIA|RU|PT|RUPT] [--storage-directory .]
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request
import uuid

BASE = 'https://iwarship.net/wowsdb/'
LIMIT = 40 * 1024 * 1024

def fetch(path):
    request = urllib.request.Request(BASE + path, headers={'User-Agent': 'AlmondEyeBot-LocalSnapshot/1.0'})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise ValueError('Source exceeds 40MB: ' + path)
    return data

def parse(data):
    result = json.loads(data.decode('utf-8-sig'))
    return json.loads(result) if isinstance(result, str) else result

def synchronize(storage, server):
    html = fetch('index')
    match = re.search(rb'const\s+serverVersion\s*=\s*(\{[^;]+\})\s*;', html)
    if not match:
        raise ValueError('No serverVersion found; website format may have changed')
    versions = json.loads(match.group(1))
    version = versions[server]
    version_id = int(version['versionId'])
    family = 'lesta' if server in ('RU', 'RUPT') else 'wg'
    names_raw = fetch('data/ship-name/' + family)
    translations_raw = fetch('data/i18n/' + family + '/zh')
    details_raw = fetch('data/get-ship-detail/' + str(version_id))
    names, translations, details = parse(names_raw), parse(translations_raw), parse(details_raw)
    if not all(isinstance(value, dict) and value for value in (names, translations, details)):
        raise ValueError('Invalid or empty API response')
    ships = {}
    categories = {}
    for category, value in details.items():
        rows = json.loads(value) if isinstance(value, str) else value
        if not isinstance(rows, list):
            raise ValueError('Unexpected category format: ' + category)
        categories[category] = len(rows)
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get('index'), str):
                raise ValueError('Ship record missing index: ' + category)
            code = row['index']
            ship = ships.setdefault(code, {'id': code, 'names': names.get(code, {}), 'sections': {}})
            ship['sections'].setdefault(category, []).append(row)
    if not ships or not categories.get('hull'):
        raise ValueError('Snapshot has no hull data')
    now = dt.datetime.now(dt.timezone.utc)
    metadata = {'source': BASE + 'index', 'server': server, 'versionId': version_id,
                'gameVersion': version['gameVersion'], 'downloadedAt': now.isoformat(),
                'shipCount': len(ships), 'categories': categories,
                'detailBytes': len(details_raw), 'sha256': hashlib.sha256(details_raw).hexdigest()}
    root = (Path(storage).resolve() / 'data' / 'wows')
    root.mkdir(parents=True, exist_ok=True)
    snapshot_name = str(version_id) + '-' + now.strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    folder = root / snapshot_name
    folder.mkdir()
    for name, data in {'index.html': html, 'names.json': names_raw, 'i18n-zh.json': translations_raw,
                       'details.json': details_raw}.items():
        (folder / name).write_bytes(data)
    # Keep every source record and every field, grouped by ship for local lookups.
    (folder / 'ships.json').write_text(json.dumps({'metadata': metadata, 'ships': list(ships.values())}, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    (folder / 'metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
    pointer = root / ('current-' + uuid.uuid4().hex + '.tmp')
    pointer.write_text(json.dumps({'snapshot': snapshot_name}), encoding='utf-8')
    os.replace(pointer, root / 'current.json')
    return metadata

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', choices=['ASIA', 'RU', 'PT', 'RUPT'], default='ASIA')
    parser.add_argument('--storage-directory', default='.')
    args = parser.parse_args()
    print(json.dumps(synchronize(args.storage_directory, args.server), ensure_ascii=True, indent=2))
