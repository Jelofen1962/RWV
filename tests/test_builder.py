import argparse
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('builder', ROOT / 'scripts/build.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class BuilderTest(unittest.TestCase):
    def config(self, platform='android', name='Test & App', version='1.2.3', url='https://example.com/dashboard', trust=False, app_id='', version_code=1):
        return builder.validate(name, version, url, trust, platform, app_id, version_code)

    def test_all_platforms(self):
        for platform in builder.PLATFORMS:
            self.assertEqual(self.config(platform)['version'], '1.2.3')

    def test_file_url_desktop_only(self):
        for platform in ('windows', 'linux'):
            self.assertEqual(self.config(platform, url='file:///tmp/index.html')['website_url'], 'file:///tmp/index.html')
        with self.assertRaises(ValueError):
            self.config(url='file:///tmp/index.html')

    def test_http_trust_only_windows(self):
        for platform in builder.PLATFORMS:
            self.assertEqual(self.config(platform, url='http://localhost:3355', trust=True)['trust_http_origin'], platform == 'windows')
        with self.assertRaises(ValueError):
            self.config(trust=True)

    def test_reject_invalid_config(self):
        for args in ({'name':' '}, {'name':'bad\nname'}, {'version':'1.2.3.4.5'}, {'version':'65536'}, {'version':'-1'}, {'url':'https://user:pass@host'}, {'url':'https:///nohost'}, {'url':'https://example.com:99999'}, {'url':'javascript:alert(1)'}, {'url':'https://exa mple.com'}, {'app_id':'bad-id'}, {'version_code':0}):
            with self.subTest(args=args), self.assertRaises(ValueError):
                self.config(**args)

    def test_stable_android_id(self):
        a = builder.android_id(self.config())
        b = builder.android_id(self.config(version='2', url='https://example.com/another'))
        self.assertEqual(a, b)
        self.assertNotEqual(a, builder.android_id(self.config(url='https://example.com:8443')))
        self.assertEqual(builder.android_id(self.config(), 'com.example.app'), 'com.example.app')
        self.assertRegex(a, r'^io\.rwv\.app\.a[0-9a-f]{16}$')

    def test_safe_file_names(self):
        self.assertEqual(builder.safe_filename('CON'), '_CON')
        self.assertEqual(builder.safe_filename('...'), 'WebViewApp')
        self.assertNotIn('/', builder.safe_filename('../a/b'))
        self.assertLessEqual(len(builder.safe_filename('測' * 200).encode()), 150)

    def test_icon_valid_and_invalid(self):
        image = builder.read_icon(str(ROOT / 'icons/example.png'))
        self.assertEqual(image.mode, 'RGBA')
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'fake.png'
            p.write_text('not a PNG')
            with self.assertRaises(ValueError):
                builder.read_icon(str(p))

    def test_android_generation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'src/ui').mkdir(parents=True)
            (root / 'src/ui/console.js').write_text('console.log("test")')
            args = argparse.Namespace(platform='android', name="Test's & App", version='1.2', url='http://10.0.2.2:3355', trust_http=False, android_id='com.example.test', android_version_code=4, icon=str(ROOT / 'icons/example.png'))
            with patch.object(builder, 'ROOT', root):
                config, path, icon = builder.prepare(args)
                self.assertEqual(json.loads(path.read_text()), config)
                generated = root / '.webview-build/android/generated'
                self.assertTrue((generated / 'assets/config.json').exists())
                self.assertTrue((generated / 'assets/console.js').exists())
                self.assertEqual(ET.parse(generated / 'res/values/rwv.xml').getroot()[0].text, '"Test\\\'s & App"')
                properties = (path.parent / 'android.properties').read_text()
                self.assertIn('applicationId=com.example.test\n', properties)
                self.assertIn('versionCode=4\n', properties)
                self.assertIn('cleartext=true\n', properties)


if __name__ == '__main__':
    unittest.main()
