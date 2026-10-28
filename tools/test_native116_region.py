from pathlib import Path
import tempfile,unittest
from nbt_regions import read_region,write_disposable_region,Tag

class Native116RegionTest(unittest.TestCase):
    def test_native_zero_byte_placeholder_is_read_only_and_later_data_is_read(self):
        # Native RegionFile control in UpgradeJournalTest proves this exact
        # empty-file form. Do not turn a lookup into a world-file rewrite.
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'r.0.0.mca';path.write_bytes(b'');stamp=path.stat().st_mtime_ns
            self.assertEqual({},read_region(path));self.assertEqual(b'',path.read_bytes());self.assertEqual(stamp,path.stat().st_mtime_ns)
            root=Tag(10,{'DataVersion':Tag(3,2586),'Level':Tag(10,{'xPos':Tag(3,0),'zPos':Tag(3,0)})})
            write_disposable_region(path,{0:{'name':'','root':root,'compression':2,'timestamp':b'\0'*4}})
            self.assertEqual(root,read_region(path)[0]['root'])
    def test_every_nonempty_truncated_header_remains_fatal(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'r.0.0.mca'
            for size in (1,4096,8191,8193):
                path.write_bytes(b'\0'*size)
                with self.subTest(size=size),self.assertRaises(ValueError):read_region(path)

if __name__=='__main__':unittest.main()
