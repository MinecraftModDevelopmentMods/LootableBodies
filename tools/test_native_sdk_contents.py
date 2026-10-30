import tempfile,unittest,zipfile,struct
from pathlib import Path
from native_sdk_contents import archive_seal,verify_generated_archive

class GeneratedSDKTests(unittest.TestCase):
    def jar(self,path,*,linux=False,change=None):
        entries=[('pkg/',b''),('pkg/A.class',b'original class'),('pack.mcmeta',b'original resource')]
        if linux:entries.reverse()
        if change:entries=change(entries)
        with zipfile.ZipFile(path,'x') as archive:
            for name,data in entries:
                item=zipfile.ZipInfo(name,date_time=(2020,1,1,0,0,0))
                item.extra=struct.pack('<HH',0x5455,5)+b'\7'+struct.pack('<I',12345) if linux else struct.pack('<HH',0x000a,32)+b'\0\0\0\0\1\0\x18\0'+b'\0'*24
                if linux and name.endswith('/'):item.date_time=(2026,10,30,11,14,36)
                archive.writestr(item,data)
    def expected(self,path):return dict(archive_seal(path,timestamp_policy='installer-times'),timestamp_policy='installer-times')
    def test_only_directory_times_timestamp_extras_and_order_can_differ(self):
        with tempfile.TemporaryDirectory() as d:
            a,b=Path(d)/'a.jar',Path(d)/'b.jar';self.jar(a);self.jar(b,linux=True)
            self.assertEqual(self.expected(a),self.expected(b));verify_generated_archive(b,self.expected(a))
    def test_changed_missing_extra_or_truncated_contents_are_fatal(self):
        changes=[lambda rows:[(n,b'changed') if n.endswith('.class') else (n,v) for n,v in rows],lambda rows:rows[:-1],lambda rows:rows+[('extra.class',b'extra')],lambda rows:[(n,v[:-1]) if n=='pack.mcmeta' else (n,v) for n,v in rows]]
        for change in changes:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as d:
                a,b=Path(d)/'a.jar',Path(d)/'b.jar';self.jar(a);self.jar(b,linux=True,change=change)
                with self.assertRaises(ValueError):verify_generated_archive(b,self.expected(a))
    def test_unknown_or_truncated_extra_fields_are_fatal(self):
        from native_sdk_contents import timestamp_extra
        for extra in (b'',b'UT\x05\0\7',b'XX\x01\0\0',b'UT\x05\0\0'+b'\0'*4):
            with self.subTest(extra=extra),self.assertRaises(ValueError):timestamp_extra(extra)
    def test_exact_metadata_policy_remains_strict(self):
        with tempfile.TemporaryDirectory() as d:
            a,b=Path(d)/'a.jar',Path(d)/'b.jar';self.jar(a);self.jar(b,linux=True)
            self.assertNotEqual(archive_seal(a,timestamp_policy='exact'),archive_seal(b,timestamp_policy='exact'))
    def test_changed_file_timestamp_and_duplicate_entries_are_fatal(self):
        with tempfile.TemporaryDirectory() as d:
            a,b=Path(d)/'a.jar',Path(d)/'b.jar';self.jar(a)
            with zipfile.ZipFile(a) as left,zipfile.ZipFile(b,'x') as right:
                for entry in left.infolist():
                    if entry.filename.endswith('.class'):entry.date_time=(2021,1,1,0,0,0)
                    right.writestr(entry,left.read(entry))
            with self.assertRaises(ValueError):verify_generated_archive(b,self.expected(a))
            with zipfile.ZipFile(b,'a') as right:right.writestr('pkg/A.class',b'duplicate')
            with self.assertRaises(ValueError):verify_generated_archive(b,self.expected(a))

if __name__=='__main__':unittest.main()
