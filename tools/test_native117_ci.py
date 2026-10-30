import hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import native117_ci as ci

class Native117CITests(unittest.TestCase):
    def test_only_the_six_accepted_bytes_can_be_promoted(self):
        with tempfile.TemporaryDirectory() as d,patch.object(ci,'PROJECT',Path(d)):
            target=Path(d)/'bundle';target.mkdir();expected={}
            for name in ['main.jar','sources.jar','javadoc.jar','release.pom','SHA256SUMS','CHANGELOG.txt']:
                data=('approved '+name).encode();(target/name).write_bytes(data);expected[name]=hashlib.sha256(data).hexdigest().upper()
            metadata=Path(d)/'.github';metadata.mkdir()
            (metadata/'release117.json').write_text(json.dumps({'version':ci.MOD_VERSION,'files':expected}))
            self.assertEqual(expected,ci.check_bundle(target))
            for name in expected:
                f=target/name;data=f.read_bytes();f.write_bytes(data+b'changed')
                with self.assertRaises(ValueError):ci.check_bundle(target)
                f.write_bytes(data)
            (target/'extra.txt').write_text('unexpected')
            with self.assertRaises(ValueError):ci.check_bundle(target)
    def test_unknown_scenario_fails_before_a_game_command(self):
        with tempfile.TemporaryDirectory() as d,patch.object(ci,'PROJECT',Path(d)),patch.object(ci,'python') as game,patch.dict('os.environ',{'JAVA_HOME_16_X64':d}):
            # Scenario outputs are confined to the checkout, including failures.
            import os
            previous=Path.cwd()
            try:
                os.chdir(d)
                with self.assertRaises(ValueError):ci.scenario('unknown')
            finally:os.chdir(previous)
            game.assert_not_called()
