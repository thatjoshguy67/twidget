"""Check the actual packaged native templates, not just their .ux sources."""
from pathlib import Path
import json
import zipfile

root = Path(__file__).resolve().parents[2]
with zipfile.ZipFile(root / 'app/src/debug/assets/coloros-cards.rpk') as package:
    assert package.testzip() is None
    manifest = json.loads(package.read('manifest.json'))
    assert manifest['package'] == 'com.tjg.twidget.coloroscards'
    for path, entry in manifest['router']['widgets'].items():
        assert entry['providerUri'] == 'content://com.tjg.twidget.debug.coloros.lockcards'
        assert entry['type'] == 'lite'
        assert entry['forceInspirationTheme'] is True
        assert f"{path}/{entry['component']}.template.json" in package.namelist()
        css = json.loads(package.read(f"{path}/{entry['component']}.css.json"))
        card = next(iter(css.values()))['.card']
        if path in ('cards/followers', 'cards/brief'):
            assert card['backdropFilter'] == 'host-blur'
            assert card['themeBackgroundColor'] == 'uxCardColorBackground'
        else:
            assert 'backdropFilter' not in card
print('Verified compiled RPK: home blur opt-in and unchanged lock template surfaces')
