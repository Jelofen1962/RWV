#!/usr/bin/env python3
"""Shared platform preparation, local building and artifact packaging for RWV."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 32 * 1024 * 1024
PLATFORMS = ('windows', 'linux', 'android')


def validate(name, version, website, trust, platform, app_id='', version_code=1):
    name, version, website = name.strip(), version.strip(), website.strip()
    if not name or len(name) > 150 or any(ord(c) < 32 or ord(c) == 127 or c in '"\\' for c in name):
        raise ValueError('App name must be 1–150 characters, without controls, quotes or backslashes')
    if not re.fullmatch(r'[0-9]{1,5}(\.[0-9]{1,5}){0,3}', version) or any(int(x) > 65535 for x in version.split('.')):
        raise ValueError('Version needs 1–4 numeric components, each 0–65535')
    if any(c.isspace() or ord(c) < 32 for c in website):
        raise ValueError('Website URL must not contain whitespace or controls; use percent encoding')
    url = urllib.parse.urlsplit(website)
    if url.scheme in ('http', 'https'):
        if not url.hostname or url.username is not None or url.password is not None:
            raise ValueError('Website requires a host and must not include credentials')
        _ = url.port
    elif url.scheme == 'file' and platform != 'android':
        if url.netloc not in ('', 'localhost') or not url.path.startswith('/') or not website.startswith('file:///'):
            raise ValueError('Use an absolute local file:/// URL')
    else:
        raise ValueError('Android requires HTTP(S); desktop supports HTTP(S) and absolute file:/// URLs')
    if trust and url.scheme != 'http':
        raise ValueError('Trust HTTP origin requires an HTTP URL')
    if platform == 'android':
        try:
            version_code = int(version_code)
        except (ValueError, TypeError):
            raise ValueError('Android version code must be an integer') from None
        if app_id and not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+', app_id):
            raise ValueError('Android application ID must look like com.example.app')
        if not 1 <= version_code <= 2100000000:
            raise ValueError('Android version code must be 1–2100000000')
    return dict(name=name, version=version, website_url=website,
                trust_http_origin=bool(trust and platform == 'windows'))


def android_id(config, override=''):
    if override:
        return override
    url = urllib.parse.urlsplit(config['website_url'])
    port = url.port or (443 if url.scheme == 'https' else 80)
    identity = f"{config['name']}\0{url.scheme}://{url.hostname}:{port}"
    return 'io.rwv.app.a' + hashlib.sha256(identity.encode()).hexdigest()[:16]


def safe_filename(name):
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name).strip(' .')[:75].rstrip(' .') or 'WebViewApp'
    if re.match(r'(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)', value):
        value = '_' + value
    # Keep under Windows and Linux path component byte limits, even with Unicode.
    return value.encode('utf-8')[:150].decode('utf-8', 'ignore').rstrip(' .') or 'WebViewApp'


def read_icon(source):
    parts = urllib.parse.urlsplit(source)
    if parts.scheme in ('http', 'https'):
        request = urllib.request.Request(source, headers={'User-Agent': 'RWV builder'})
        with urllib.request.urlopen(request, timeout=60) as response:
            if urllib.parse.urlsplit(response.geturl()).scheme not in ('http', 'https'):
                raise ValueError('Invalid icon redirect')
            data = response.read(LIMIT + 1)
    else:
        path = Path(urllib.request.url2pathname(parts.path)) if parts.scheme == 'file' else Path(source)
        if not path.is_absolute():
            path = ROOT / path
        with path.open('rb') as f:
            data = f.read(LIMIT + 1)
    if len(data) > LIMIT or not data.startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError('Icon must be a PNG of at most 32 MiB')
    import io
    with Image.open(io.BytesIO(data)) as img:
        if img.format != 'PNG' or max(img.size) > 8192:
            raise ValueError('Icon must be a PNG with dimensions at most 8192×8192')
        img.load()
        return img.convert('RGBA')


def prepare(args):
    config = validate(args.name, args.version, args.url, args.trust_http, args.platform, args.android_id, args.android_version_code)
    stage = ROOT / '.webview-build' / args.platform
    stage.mkdir(parents=True, exist_ok=True)
    icon = read_icon(args.icon)
    icon_path = stage / 'icon.png'
    ImageOps.pad(icon, (256, 256), method=Image.Resampling.LANCZOS, color=(0, 0, 0, 0)).save(icon_path)
    config_path = stage / 'config.json'
    config_path.write_text(json.dumps(config, ensure_ascii=False), encoding='utf-8')
    if args.platform == 'android':
        generated = stage / 'generated'
        res = generated / 'res'
        assets = generated / 'assets'
        (res / 'values').mkdir(parents=True, exist_ok=True)
        (res / 'drawable-nodpi').mkdir(parents=True, exist_ok=True)
        assets.mkdir(parents=True, exist_ok=True)
        # Android's resource parser requires quotes around literal strings, including apostrophes.
        resources = ET.Element('resources')
        ET.SubElement(resources, 'string', name='app_name', translatable='false', formatted='false').text = '"' + config['name'].replace("'", "\\'") + '"'
        ET.ElementTree(resources).write(res / 'values' / 'rwv.xml', encoding='utf-8', xml_declaration=True)
        ImageOps.pad(icon, (192, 192), method=Image.Resampling.LANCZOS, color=(0, 0, 0, 0)).save(res / 'drawable-nodpi' / 'rwv_icon.png')
        shutil.copyfile(config_path, assets / 'config.json')
        shutil.copyfile(ROOT / 'src/ui/console.js', assets / 'console.js')
        properties = dict(applicationId=android_id(config, args.android_id), versionName=config['version'], versionCode=int(args.android_version_code), cleartext=str(urllib.parse.urlsplit(config['website_url']).scheme == 'http').lower())
        (stage / 'android.properties').write_text(''.join(f'{k}={v}\n' for k, v in properties.items()), encoding='ascii')
    if args.trust_http and args.platform != 'windows':
        print('Note: secure-context HTTP override is Windows-only; ordinary HTTP still works.')
    return config, config_path, icon_path


def run(command, **kwargs):
    subprocess.run(command, check=True, **kwargs)


def build(args):
    config, config_path, icon_path = prepare(args)
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    base = f"{safe_filename(config['name'])}-{config['version']}-{args.platform}"
    if args.platform == 'android':
        gradle = shutil.which('gradle')
        if not gradle:
            raise RuntimeError('Install Gradle 8.9 and JDK 17, with Android SDK 35 / build tools 35.0.0')
        env = dict(os.environ)
        env.pop('RWV_ANDROID_KEYSTORE_PATH', None)
        # Optional durable signing key; the default is a throwaway debug key for sideloading.
        signing = ('RWV_ANDROID_KEYSTORE_BASE64', 'RWV_ANDROID_KEYSTORE_PASSWORD', 'RWV_ANDROID_KEY_ALIAS', 'RWV_ANDROID_KEY_PASSWORD')
        supplied = [bool(env.get(key)) for key in signing]
        if any(supplied) and not all(supplied):
            raise ValueError('Provide all four Android signing environment variables or none')
        with tempfile.TemporaryDirectory(prefix='rwv-signing-') as temp:
            if all(supplied):
                keystore = Path(temp) / 'release.keystore'
                keystore.write_bytes(base64.b64decode(env.pop(signing[0]), validate=True))
                env['RWV_ANDROID_KEYSTORE_PATH'] = str(keystore)
            run([gradle, '--no-daemon', 'testReleaseUnitTest', 'lintRelease', 'assembleRelease'], cwd=ROOT / 'android', env=env)
        artifact = output / f'{base}-universal.apk'
        shutil.copyfile(ROOT / 'android/app/build/outputs/apk/release/app-release.apk', artifact)
    else:
        expected = 'win32' if args.platform == 'windows' else 'linux'
        if not sys.platform.startswith(expected):
            raise RuntimeError(f'Build {args.platform} on a native {args.platform} host or use GitHub Actions')
        target = args.target or ('x86_64-pc-windows-msvc' if args.platform == 'windows' else 'x86_64-unknown-linux-gnu')
        permitted = {'windows': ('x86_64-pc-windows-msvc', 'aarch64-pc-windows-msvc'), 'linux': ('x86_64-unknown-linux-gnu', 'aarch64-unknown-linux-gnu')}
        if target not in permitted[args.platform]:
            raise ValueError('Target architecture does not match selected desktop platform')
        env = dict(os.environ, WEBVIEW_APP_CONFIG=str(config_path), WEBVIEW_APP_ICON=str(icon_path), CARGO_TARGET_DIR=str(ROOT / 'target'))
        run(['rustup', 'target', 'add', '--toolchain', 'stable', target], cwd=ROOT)
        run(['cargo', '+stable', 'test', '--target', target], cwd=ROOT, env=env)
        run(['cargo', '+stable', 'build', '--release', '--target', target], cwd=ROOT, env=env)
        arch = 'arm64' if target.startswith('aarch64') else 'x64'
        binary = ROOT / 'target' / target / 'release' / ('rwv.exe' if args.platform == 'windows' else 'rwv')
        if args.platform == 'windows':
            artifact = output / f'{base}-{arch}.exe'
            shutil.copyfile(binary, artifact)
        else:
            artifact = output / f'{base}-{arch}.tar.gz'
            with tarfile.open(artifact, 'w:gz') as archive:
                archive.add(binary, arcname='rwv')
                archive.add(icon_path, arcname='icon.png')
                archive.add(ROOT / 'docs/linux-runtime.txt', arcname='README.txt')
    if artifact.stat().st_size == 0:
        raise RuntimeError('Empty artifact')
    print(f'Built: {artifact}')
    with artifact.open("rb") as f:
        print(f'SHA-256: {hashlib.file_digest(f, "sha256").hexdigest()}')
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as f:
            f.write(f'artifact={artifact}\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=PLATFORMS, required=True)
    parser.add_argument('--name', default=os.getenv('INPUT_APP_NAME'))
    parser.add_argument('--version', default=os.getenv('INPUT_APP_VERSION'))
    parser.add_argument('--icon', default=os.getenv('INPUT_ICON_SOURCE'))
    parser.add_argument('--url', default=os.getenv('INPUT_WEBSITE_URL'))
    parser.add_argument('--trust-http', action='store_true', default=os.getenv('INPUT_TRUST_HTTP', '').lower() == 'true')
    parser.add_argument('--android-id', default=os.getenv('INPUT_ANDROID_ID', ''))
    parser.add_argument('--android-version-code', default=os.getenv('INPUT_ANDROID_VERSION_CODE') or '1')
    parser.add_argument('--target')
    parser.add_argument('--output', default=str(ROOT / 'dist'))
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--prepare-only', action='store_true')
    modes.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    for key in ('name', 'version', 'icon', 'url'):
        if not getattr(args, key):
            parser.error(f'--{key} is required')
    try:
        if args.validate_only:
            validate(args.name, args.version, args.url, args.trust_http, args.platform, args.android_id, args.android_version_code)
        elif args.prepare_only:
            prepare(args)
        else:
            build(args)
    except (ValueError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Build failed: {error}\n')


if __name__ == '__main__':
    main()
