"""A native reload must retain console/latest/debug, without replaying clients."""
from pathlib import Path
import tempfile,unittest
from unittest.mock import patch
from verify_multiplayer import reload_server


class MultiplayerReloadEvidenceTests(unittest.TestCase):
    def test_explicit_native_logging_and_complete_logs_are_required(self):
        for missing in [None,'multiplayer-reload.log','multiplayer-reload.latest.log','multiplayer-reload.debug.log']:
            with self.subTest(missing=missing),tempfile.TemporaryDirectory() as folder:
                home=Path(folder);logging=home/'fixture-log4j.xml';logging.write_text('synthetic logging fixture')
                for name in ['multiplayer-reload.log','multiplayer-reload.latest.log','multiplayer-reload.debug.log']:
                    if name!=missing:(home/name).write_text('synthetic positive log')
                with patch('verify_multiplayer.run') as run,patch('verify_multiplayer.audit') as audit:
                    if missing is None:
                        reload_server(Path('fixture-java'),home,logging)
                        self.assertEqual(3,audit.call_count)
                        self.assertEqual(['-Dlog4j.configurationFile='+logging.resolve().as_uri()],run.call_args.kwargs['extra'])
                    else:
                        with self.assertRaises(FileNotFoundError):reload_server(Path('fixture-java'),home,logging)


if __name__=='__main__':unittest.main()
