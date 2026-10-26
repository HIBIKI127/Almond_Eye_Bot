import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import sync_wows

class SyncTests(unittest.TestCase):
    def payloads(self):
        detail = {'hull': json.dumps([{'index': 'ONE', 'health': 123}, {'index': 'TWO', 'health': 456}]),
                  'artillery': json.dumps([{'index': 'ONE', 'shotDelay': 5.5}, {'index': 'ONE', 'shotDelay': 4}])}
        return {'index': b'const serverVersion = {"ASIA":{"versionId":999,"gameVersion":"future"},"RU":{"versionId":998,"gameVersion":"other"}};',
                'data/ship-name/wg': json.dumps({'ONE': {'zh': '测试舰'}}).encode(),
                'data/i18n/wg/zh': b'{"labels":"test"}',
                'data/get-ship-detail/999': json.dumps(detail).encode()}
    def test_preserves_all_rows_and_fields_and_uses_current_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(sync_wows, 'fetch', side_effect=lambda path: self.payloads()[path]):
                metadata = sync_wows.synchronize(temporary, 'ASIA')
            root = Path(temporary) / 'data/wows'
            folder = root / json.loads((root/'current.json').read_text(encoding='utf-8'))['snapshot']
            data = json.loads((folder/'ships.json').read_text(encoding='utf-8'))
            self.assertEqual(2, metadata['shipCount'])
            self.assertEqual('future', metadata['gameVersion'])
            self.assertEqual(999, metadata['versionId'])
            self.assertEqual(2, len(data['ships'][0]['sections']['artillery']))
            self.assertEqual(4, data['ships'][0]['sections']['artillery'][1]['shotDelay'])
            self.assertEqual(2, metadata['categories']['hull'])
            self.assertTrue((folder/'details.json').exists())
    def test_failed_or_malformed_download_keeps_current_pointer(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(sync_wows, 'fetch', side_effect=lambda path: self.payloads()[path]): sync_wows.synchronize(temporary, 'ASIA')
            pointer = Path(temporary)/'data/wows/current.json'
            previous = pointer.read_bytes()
            for error in [OSError('network unavailable'), ValueError('malformed source')]:
                with patch.object(sync_wows, 'fetch', side_effect=error):
                    with self.assertRaises(type(error)): sync_wows.synchronize(temporary, 'ASIA')
                self.assertEqual(previous, pointer.read_bytes())
            bad = self.payloads(); bad['data/get-ship-detail/999'] = b'{"hull":"[]"}'
            with patch.object(sync_wows, 'fetch', side_effect=lambda path: bad[path]):
                with self.assertRaises(ValueError): sync_wows.synchronize(temporary, 'ASIA')
            self.assertEqual(previous, pointer.read_bytes())

if __name__ == '__main__': unittest.main()
