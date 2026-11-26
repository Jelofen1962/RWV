import argparse
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import json
import shutil
import subprocess
import sys
import os
import base64
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('builder', ROOT / 'scripts/build.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class BuilderTest(unittest.TestCase):
    def config(self, platform='android', name='Test & App', version='1.2.3', url='https://example.com/dashboard', trust=False, app_id='', version_code=1, fullscreen=False):
        return builder.validate(name, version, url, trust, platform, app_id, version_code, fullscreen)

    def test_all_platforms(self):
        for platform in builder.PLATFORMS:
            self.assertEqual(self.config(platform)['version'], '1.2.3')

    def test_fullscreen_config_for_all_platforms(self):
        for platform in builder.PLATFORMS:
            self.assertIs(self.config(platform)['fullscreen'], False)
            self.assertIs(self.config(platform, fullscreen=True)['fullscreen'], True)
            self.assertEqual(builder.android_id(self.config(platform)), builder.android_id(self.config(platform, fullscreen=True)))

    def test_fullscreen_generated_config_for_each_platform(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'src/ui').mkdir(parents=True)
            for file in ('console.js', 'splash.html'):
                shutil.copyfile(ROOT / 'src/ui' / file, root / 'src/ui' / file)
            for platform in builder.PLATFORMS:
                for enabled in (False, True):
                    args = argparse.Namespace(platform=platform, name='App', version='1', url='https://example.com', trust_http=False, android_id='', android_version_code=1, fullscreen=enabled, icon=str(ROOT / 'icons/example.png'))
                    with patch.object(builder, 'ROOT', root):
                        config, path, _ = builder.prepare(args)
                    self.assertIs(json.loads(path.read_text())['fullscreen'], enabled)
                    if platform == 'android':
                        self.assertIs(json.loads((path.parent / 'generated/assets/config.json').read_text())['fullscreen'], enabled)

    def test_file_url_desktop_only(self):
        for platform in ('windows', 'linux'):
            self.assertEqual(self.config(platform, url='file:///tmp/index.html')['website_url'], 'file:///tmp/index.html')
        with self.assertRaises(ValueError):
            self.config(url='file:///tmp/index.html')

    def test_http_trust_only_windows(self):
        for platform in builder.PLATFORMS:
            self.assertEqual(self.config(platform, url='http://localhost:3355', trust=True)['trust_http_origin'], platform == 'windows')
    def test_trust_ignored_for_https_and_desktop_file_urls(self):
        for platform in builder.PLATFORMS:
            for url in ('https://example.com', 'HTTPS://example.com'):
                with self.subTest(platform=platform, url=url):
                    self.assertIs(self.config(platform, url=url, trust=True)['trust_http_origin'], False)
        for platform in ('windows', 'linux'):
            self.assertIs(self.config(platform, url='file:///tmp/index.html', trust=True)['trust_http_origin'], False)
        with self.assertRaises(ValueError):
            self.config(url='file:///tmp/index.html', trust=True)

    def test_trust_workflow_environment_and_validation(self):
        env = dict(os.environ, INPUT_APP_NAME='App', INPUT_APP_VERSION='1', INPUT_ICON_SOURCE='icons/example.png', INPUT_WEBSITE_URL='https://example.com', INPUT_TRUST_HTTP='true', INPUT_FULLSCREEN='true', INPUT_ANDROID_ID='', INPUT_ANDROID_VERSION_CODE='1')
        for platform in builder.PLATFORMS:
            with self.subTest(platform=platform):
                result = subprocess.run([sys.executable, str(ROOT / 'scripts/build.py'), '--platform', platform, '--validate-only'], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('Trust HTTP origin ignored', result.stdout)

    def test_trust_generated_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'src/ui').mkdir(parents=True)
            for file in ('console.js', 'splash.html'):
                shutil.copyfile(ROOT / 'src/ui' / file, root / 'src/ui' / file)
            for platform in builder.PLATFORMS:
                for url in ('https://example.com', 'http://example.com'):
                    with self.subTest(platform=platform, url=url):
                        args = argparse.Namespace(platform=platform, name='App', version='1', url=url, trust_http=True, android_id='', android_version_code=1, fullscreen=True, icon=str(ROOT / 'icons/example.png'))
                        with patch.object(builder, 'ROOT', root):
                            config, path, _ = builder.prepare(args)
                        expected = platform == 'windows' and url.startswith('http:')
                        self.assertIs(config['trust_http_origin'], expected)
                        self.assertIs(json.loads(path.read_text())['trust_http_origin'], expected)
                        if platform == 'android':
                            self.assertIs(json.loads((path.parent / 'generated/assets/config.json').read_text())['trust_http_origin'], False)

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
            shutil.copyfile(ROOT / 'src/ui/splash.html', root / 'src/ui/splash.html')
            args = argparse.Namespace(platform='android', name="Test's & App", version='1.2', url='http://10.0.2.2:3355', trust_http=False, android_id='com.example.test', android_version_code=4, fullscreen=True, icon=str(ROOT / 'icons/example.png'))
            with patch.object(builder, 'ROOT', root):
                config, path, icon = builder.prepare(args)
                self.assertEqual(json.loads(path.read_text()), config)
                self.assertTrue(config['fullscreen'])
                generated = root / '.webview-build/android/generated'
                self.assertTrue((generated / 'assets/config.json').exists())
                self.assertTrue((generated / 'assets/console.js').exists())
                splash = (generated / 'assets/splash.html').read_text()
                self.assertIn('Test&#x27;s &amp; App', splash)
                self.assertIn('powered by <strong>Jelofen1962</strong>', splash)
                self.assertIn('font-src data: https://fonts.gstatic.com;', splash)
                self.assertIn('https://fonts.googleapis.com/css2?', splash)
                self.assertNotIn('__APP_ICON__', splash)
                self.assertNotIn('__APP_NAME__', splash)
                self.assertIn(base64.b64encode(icon.read_bytes()).decode(), splash)
                self.assertFalse((generated / 'assets/Poppins-OFL.txt').exists())
                self.assertEqual(ET.parse(generated / 'res/values/rwv.xml').getroot()[0].text, '"Test\\\'s & App"')
                properties = (path.parent / 'android.properties').read_text()
                self.assertIn('applicationId=com.example.test\n', properties)
                self.assertIn('versionCode=4\n', properties)
                self.assertIn('cleartext=true\n', properties)

    def test_android_embedded_font_and_cdn_fallback(self):
        css = "@font-face{font-family:'RWV Poppins';src:url(data:font/ttf;base64,AA==)}"
        license_text = b'SIL OPEN FONT LICENSE'
        icon = ROOT / 'icons/example.png'
        with patch.object(builder, 'fetch_poppins', return_value=(css, license_text)):
            splash, license_result = builder.android_splash('<test>&', icon, True)
        self.assertEqual(license_result, license_text)
        self.assertIn(css, splash)
        self.assertIn("font-family: 'RWV Poppins'", splash)
        self.assertNotIn('https://fonts.googleapis.com/css2?', splash)
        self.assertIn('&lt;test&gt;&amp;', splash)
        with patch.object(builder, 'fetch_poppins', side_effect=OSError('offline')):
            splash, license_result = builder.android_splash('Offline', icon, True)
        self.assertIsNone(license_result)
        self.assertIn('https://fonts.googleapis.com/css2?', splash)
        self.assertIn('Roboto, sans-serif', splash)

    def test_android_navigation_bar_api_compatibility(self):
        res = ROOT / 'android/app/src/main/res'
        for path in res.glob('values*/styles.xml'):
            qualifier = path.parent.name
            api = int(qualifier.rsplit('-v', 1)[1]) if '-v' in qualifier else 23
            styles = ET.parse(path).getroot()
            for item in styles.findall(".//item[@name='android:windowLightNavigationBar']"):
                self.assertGreaterEqual(api, 27, str(path))
        base = ET.parse(res / 'values/styles.xml').getroot().find("style[@name='BaseAppTheme']")
        self.assertEqual(base.find("item[@name='android:navigationBarColor']").text, '#202020')
        theme = ET.parse(res / 'values-v27/styles.xml').getroot().find("style[@name='AppTheme']")
        self.assertEqual(theme.get('parent'), 'BaseAppTheme')
        self.assertEqual(theme.find("item[@name='android:navigationBarColor']").text, '#FFFFFF')
        self.assertEqual(theme.find("item[@name='android:windowLightNavigationBar']").text, 'true')
        self.assertFalse((res / 'values-v26/styles.xml').exists())

    def test_android_launch_theme(self):
        res = ROOT / 'android/app/src/main/res'
        styles = ET.parse(res / 'values-v31/styles.xml').getroot()
        launch = styles.find("style[@name='LaunchTheme']")
        self.assertEqual(launch.find("item[@name='android:windowSplashScreenBackground']").text, '#FFFFFF')
        self.assertEqual(launch.find("item[@name='android:windowSplashScreenAnimatedIcon']").text, '@drawable/rwv_empty_splash')
        ns = '{http://schemas.android.com/apk/res/android}'
        self.assertEqual(ET.parse(res / 'drawable/rwv_empty_splash.xml').getroot().find('solid').get(ns + 'color'), '#00000000')
        manifest = ET.parse(ROOT / 'android/app/src/main/AndroidManifest.xml').getroot()
        self.assertEqual(manifest.find('application/activity').get(ns + 'theme'), '@style/LaunchTheme')


if __name__ == '__main__':
    unittest.main()
