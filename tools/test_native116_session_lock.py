"""A native lock marker is not permission to exempt saved world data."""
from pathlib import Path
import hashlib,tempfile,unittest
import verify_upgrade_abort as abort

class NativeSessionLockTest(unittest.TestCase):
    def check(self,original,actual,expected):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'session.lock').write_bytes(actual)
            before={} if original is None else {'session.lock':hashlib.sha256(original).hexdigest().upper()}
            after={'session.lock':hashlib.sha256(actual).hexdigest().upper()}
            predicate=getattr(abort,'allowed_native_session_lock',None)
            self.assertIsNotNone(predicate,'Native session-lock comparison is missing')
            self.assertEqual(expected,predicate(before,after,root,original))
    def test_native_overwrite_keeps_every_unwritten_old_timestamp_byte(self):
        self.check(bytes(range(8)),b'\xe2\x98\x83'+bytes(range(3,8)),['session.lock'])
    def test_new_native_lock_has_only_its_exact_three_byte_marker(self):
        self.check(None,b'\xe2\x98\x83',['session.lock'])
    def test_other_changes_and_unproven_originals_fail(self):
        for old,actual in [(bytes(range(8)),b'\xe2\x98\x83'+bytes(range(4,9))),
                           (bytes(range(8)),b'\xe2\x98\x83'),
                           (b'unknown bytes',b'\xe2\x98\x83nown bytes'),
                           (None,b'\xe2\x98\x83extra')]:
            with self.subTest(original=old,actual=actual):self.check(old,actual,[])

if __name__=='__main__':unittest.main()
