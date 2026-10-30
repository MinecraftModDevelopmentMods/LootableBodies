"""Synthetic source directories test routing/seals, not historical conversion."""
import json,tempfile,unittest
from pathlib import Path
from native_runtime import fingerprint
from prepared_worlds import load

class PreparedWorldTest(unittest.TestCase):
    def fixture(self,root):
        world=root/'world';world.mkdir();(world/'level.dat').write_bytes(b'synthetic immutable input')
        expected={'one':('1.16.5','2.4.7.116051','A'*64),'two':('1.10.2','2.2.0','B'*64)}
        records={name:{'world':str(world.resolve()),'files':fingerprint(world),'minecraft':mc,'mod_version':version,'jar_sha256':sha} for name,(mc,version,sha) in expected.items()}
        second=root/'second';second.mkdir();(second/'level.dat').write_bytes(b'separate synthetic source')
        records['two'].update(world=str(second.resolve()),files=fingerprint(second))
        path=root/'inputs.json';record={'schema':1,'target_data_version':2730,'origins':records};path.write_text(json.dumps(record))
        return path,record,expected,world
    def test_exact_subset_reuses_original_paths_and_seals(self):
        with tempfile.TemporaryDirectory() as directory:
            path,record,expected,world=self.fixture(Path(directory));before=fingerprint(world)
            sources,proof=load(path,expected,['one']);self.assertEqual(world.resolve(),sources['one'][0]);self.assertEqual(before,sources['one'][1]['world']);self.assertEqual(2,proof['all_origins']);self.assertEqual(before,fingerprint(world))
    def test_missing_extra_or_wrong_target_manifest_is_fatal(self):
        for change in ['missing','extra','target']:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as directory:
                path,record,expected,world=self.fixture(Path(directory))
                if change=='missing':record['origins'].pop('two')
                elif change=='extra':record['origins']['extra']=record['origins']['one']
                else:record['target_data_version']=2586
                path.write_text(json.dumps(record))
                with self.assertRaises(ValueError):load(path,expected,['one'])
    def test_missing_added_or_changed_source_bytes_are_fatal(self):
        for change in ['missing','added','changed']:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as directory:
                path,record,expected,world=self.fixture(Path(directory))
                if change=='missing':(world/'level.dat').unlink()
                elif change=='added':(world/'unexpected').write_bytes(b'extra')
                else:(world/'level.dat').write_bytes(b'changed')
                with self.assertRaises(ValueError):load(path,expected,['one'])
    def test_wrong_source_jar_version_or_minecraft_is_fatal(self):
        for field in ['jar_sha256','mod_version','minecraft']:
            with self.subTest(field=field),tempfile.TemporaryDirectory() as directory:
                path,record,expected,world=self.fixture(Path(directory));record['origins']['one'][field]='wrong';path.write_text(json.dumps(record))
                with self.assertRaises(ValueError):load(path,expected,['one'])
    def test_duplicate_origin_selection_and_duplicate_json_keys_are_fatal(self):
        with tempfile.TemporaryDirectory() as directory:
            path,record,expected,world=self.fixture(Path(directory))
            with self.assertRaises(ValueError):load(path,expected,['one','one'])
            path.write_text('{"schema":1,"schema":1}')
            with self.assertRaises(ValueError):load(path,expected,['one'])
    def test_two_names_cannot_satisfy_coverage_with_one_source_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            path,record,expected,world=self.fixture(Path(directory));record['origins']['two']['world']=record['origins']['one']['world'];path.write_text(json.dumps(record))
            with self.assertRaises(ValueError):load(path,expected,['one'])

if __name__=='__main__':unittest.main()
