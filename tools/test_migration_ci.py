"""Prepared migration inputs must retain every case and reject stale evidence."""
import copy
import importlib
import json
from pathlib import Path
import tempfile
import unittest
from verify_world_upgrades import ORIGINS, PHASES

try:
    ci = importlib.import_module('migration_ci')
except ModuleNotFoundError:
    ci = None


class MigrationCiTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(ci, 'Shared immutable migration preparation is missing')

    def reports(self):
        reports = []
        for shard in range(6):
            origins = ci.partition(list(ORIGINS), shard)
            cases = {origin+'-'+variant: {'phases':list(PHASES), 'source_unchanged':True,
                'origin_jar_sha256':ORIGINS[origin][2]} for origin in origins for variant in ['normal','stamps']}
            condition = {'source_unchanged':True, 'world_data_unchanged':True, 'world_loading_started':False}
            aborts = {name:{'conditions':{fault:dict(condition) for fault in ['damaged-index','blocked-journal']}} for name in cases}
            reports.append({'head':'a'*40,'candidate_sha256':'B'*64,'probe_sha256':'C'*64,
                'cases':cases,'aborts':aborts,'source_seals':{origin:{'level.dat':'D'*64} for origin in origins}})
        return reports

    def test_six_shards_cover_every_origin_exactly_once(self):
        shards = [ci.partition(list(ORIGINS), index) for index in range(6)]
        self.assertEqual(sorted(ORIGINS), sorted(origin for shard in shards for origin in shard))
        self.assertLessEqual(max(map(len,shards))-min(map(len,shards)),1)
        for value in [-1,6]:
            with self.assertRaises(ValueError):ci.partition(list(ORIGINS),value)

    def test_all_760_phases_and_152_refusals_are_required(self):
        result = ci.aggregate(self.reports(), list(ORIGINS), 'a'*40)
        self.assertEqual((76,760,152),(len(result['cases']),result['jvm_phases'],result['abort_cases']))
        native16=[name for name in result['cases'] if not name.startswith(('released-116-','recovery-116-','via116-'))]
        self.assertEqual(60,len(native16));self.assertEqual(600,sum(len(result['cases'][name]['phases']) for name in native16))
        self.assertEqual(120,sum(len(result['aborts'][name]['conditions']) for name in native16))
        earlier=[name for name in native16 if not name.startswith(('released-115-','recovery-115-','via115-'))]
        self.assertEqual(46,len(earlier))
        self.assertEqual(460,sum(len(result['cases'][name]['phases']) for name in earlier))
        self.assertEqual(92,sum(len(result['aborts'][name]['conditions']) for name in earlier))

    def test_two_local_workers_and_twelve_hosted_shards_keep_every_origin(self):
        for total in [2,12]:
            shards=[ci.partition(list(ORIGINS),index,total=total) for index in range(total)]
            self.assertEqual(sorted(ORIGINS),sorted(origin for shard in shards for origin in shard))
            self.assertLessEqual(max(map(len,shards))-min(map(len,shards)),1)

    def test_missing_duplicate_or_short_cases_cannot_pass(self):
        for mutation in ['missing','duplicate','short','bad-abort','wrong-source']:
            reports = self.reports();name = next(iter(reports[0]['cases']))
            if mutation=='missing':del reports[0]['cases'][name]
            if mutation=='duplicate':reports.append(copy.deepcopy(reports[0]))
            if mutation=='short':reports[0]['cases'][name]['phases'].pop()
            if mutation=='bad-abort':reports[0]['aborts'][name]['conditions']['damaged-index']['world_data_unchanged']=False
            if mutation=='wrong-source':reports[0]['cases'][name]['origin_jar_sha256']='0'*64
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):ci.aggregate(reports,list(ORIGINS),'a'*40)

    def test_stale_commit_or_different_candidate_is_rejected(self):
        for key,value in [('head','b'*40),('candidate_sha256','E'*64),('probe_sha256','F'*64)]:
            reports=self.reports();reports[-1][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):ci.aggregate(reports,list(ORIGINS),'a'*40)

    def test_explicit_inputs_round_trip_and_verify_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source';source.mkdir();(source/'build').mkdir()
            (source/'build/main.jar').write_bytes(b'exact candidate')
            bundle=root/'bundle';ci.seal(source,['build/main.jar'],bundle,'a'*40)
            restored=root/'restored';restored.mkdir();ci.restore(restored,bundle,'a'*40)
            self.assertEqual(b'exact candidate',(restored/'build/main.jar').read_bytes())

    def test_changed_archive_wrong_commit_and_existing_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source';source.mkdir();(source/'build').mkdir()
            (source/'build/main.jar').write_bytes(b'exact candidate');bundle=root/'bundle'
            ci.seal(source,['build/main.jar'],bundle,'a'*40);target=root/'target';target.mkdir()
            with self.assertRaises(ValueError):ci.restore(target,bundle,'b'*40)
            (target/'build').mkdir();(target/'build/main.jar').write_bytes(b'other data')
            with self.assertRaises(ValueError):ci.restore(target,bundle,'a'*40)
            archive=bundle/'inputs.tar.gz';archive.write_bytes(archive.read_bytes()+b'changed')
            with self.assertRaises(ValueError):ci.restore(target,bundle,'a'*40)

    def test_parent_paths_and_gradle_caches_cannot_be_exported(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'build').mkdir()
            for bad in ['../private.txt','.gradle/caches/private','agent-notes/private.txt']:
                with self.subTest(path=bad),self.assertRaises(ValueError):ci.seal(root,[bad],root/'bundle','a'*40)

    def test_changed_test_tool_cannot_reuse_prepared_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source';source.mkdir()
            (source/'build').mkdir();(source/'tools').mkdir()
            (source/'build/main.jar').write_bytes(b'candidate')
            (source/'tools/check.py').write_text('original check')
            bundle=root/'bundle';ci.seal(source,['build/main.jar'],bundle,'a'*40)
            (source/'tools/check.py').write_text('different check')
            with self.assertRaises(ValueError):ci.restore(source,bundle,'a'*40)

    def native_inputs(self,root):
        folder=root/'build/native-server-distribution';expected={}
        for name in ['libraries/forge/server.jar','libraries/forge/win_args.txt','libraries/forge/unix_args.txt']:
            path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(name.encode());expected[name]=ci.digest(path)
        return folder,expected

    def test_native_preparation_seals_both_module_argument_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);folder,expected=self.native_inputs(root)
            paths=ci.native_input_paths(root,expected)
            self.assertEqual({(folder/name).relative_to(root).as_posix() for name in expected},set(paths))
            bundle=root/'bundle';ci.seal(root,paths,bundle,'a'*40)
            restored=root/'restored';restored.mkdir();ci.restore(restored,bundle,'a'*40)
            self.assertEqual(set(expected),{p.relative_to(restored/'build/native-server-distribution').as_posix() for p in (restored/'build/native-server-distribution').rglob('*') if p.is_file()})

    def test_missing_or_changed_native_preparation_input_is_fatal(self):
        for mutation in ['missing','changed']:
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);folder,expected=self.native_inputs(root);path=folder/'libraries/forge/unix_args.txt'
                if mutation=='missing':path.unlink()
                else:path.write_bytes(b'other native arguments')
                with self.assertRaises(ValueError):ci.native_input_paths(root,expected)


if __name__=='__main__':unittest.main()
