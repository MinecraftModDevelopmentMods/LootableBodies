"""Synthetic receipts exercise fail-closed auditing, not native JVM execution."""
import json,tempfile,unittest,os
from pathlib import Path
from native_runtime import digest
from native_evidence import audit_phase,verify_saved_audit,audit_case_mods

class NativeEvidenceTest(unittest.TestCase):
    def fixture(self,home):
        phase='upgrade-near';directory=home/(phase+'.diagnostics');directory.mkdir()
        for name in ['gc.txt','initial-threads.txt','initial-heap-gc.txt']:
            (directory/name).write_text('synthetic '+name+' fixture')
        for name in [phase+'.log',phase+'.latest.log',phase+'.debug.log']:
            (home/name).write_text('LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase='+phase+'\n')
        record={'timed_out':False,'returncode':0,'pid':123,'timeout':240,'cwd':str(home),
                'command':['fixture-java','-Dlootablebodies.phase='+phase,
                    '-Xlog:gc*,safepoint:file=upgrade-near.diagnostics/gc.txt:time,uptime,level,tags',
                    '@libraries/net/minecraftforge/forge/1.21.11-61.2.4/'+('win_args.txt' if os.name=='nt' else 'unix_args.txt'),'--nogui'],
                'evidence':{name:digest(directory/name) for name in ['gc.txt','initial-threads.txt','initial-heap-gc.txt']},'captures':[],'metrics':[]}
        (directory/'result.json').write_text(json.dumps(record));return directory,record
    def test_complete_receipt_retains_all_logs_and_rehashes_every_native_file(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);self.fixture(home);record=audit_phase(home,'upgrade-near')
            proof={'receipts':[record],'positive_complete_logs':[]};self.assertTrue(verify_saved_audit(home,proof))
            (home/'upgrade-near.diagnostics/gc.txt').write_text('changed')
            self.assertFalse(verify_saved_audit(home,proof))
            with self.assertRaises(ValueError):audit_phase(home,'upgrade-near')
    def test_missing_receipt_log_or_declared_diagnostic_is_fatal(self):
        for name in ['upgrade-near.diagnostics/result.json','upgrade-near.debug.log','upgrade-near.diagnostics/gc.txt','upgrade-near.diagnostics/initial-threads.txt','upgrade-near.diagnostics/initial-heap-gc.txt']:
            with self.subTest(name=name),tempfile.TemporaryDirectory() as directory:
                home=Path(directory);self.fixture(home);(home/name).unlink()
                with self.assertRaises((ValueError,OSError)):audit_phase(home,'upgrade-near')
    def test_wrong_case_native_distribution_deadline_or_failed_process_is_fatal(self):
        for field in ['cwd','timeout','command','returncode','timed_out','captures']:
            with self.subTest(field=field),tempfile.TemporaryDirectory() as directory:
                home=Path(directory);folder,record=self.fixture(home)
                record[field]={'cwd':str(home/'other'),'timeout':241,'command':['java','-jar','old.jar'],
                    'returncode':1,'timed_out':True,'captures':[{'file':'missing.txt','returncode':None,'started_seconds':1}]}[field]
                (folder/'result.json').write_text(json.dumps(record))
                with self.assertRaises(ValueError):audit_phase(home,'upgrade-near')
    def test_relocated_artifact_requires_the_actual_case_suffix(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'migration-results/case';home.mkdir(parents=True);folder,record=self.fixture(home)
            record['cwd']='/original/runner/build/migration-results/case';(folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):audit_phase(home,'upgrade-near')
            audit_phase(home,'upgrade-near',relocated=True)
            record['cwd']='/original/runner/build/migration-results/other';(folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):audit_phase(home,'upgrade-near',relocated=True)
    def test_native_process_must_have_exact_qualified_mod_copies(self):
        from release_identity import MAIN_JAR
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);mods=home/'mods';mods.mkdir();main=mods/MAIN_JAR;probe=mods/'lootablebodies-build-tests.jar'
            main.write_bytes(b'synthetic candidate');probe.write_bytes(b'synthetic probe')
            report={'candidate_sha256':digest(main),'probe_sha256':digest(probe)};self.assertEqual(2,len(audit_case_mods(home,report)))
            main.write_bytes(b'changed')
            with self.assertRaises(ValueError):audit_case_mods(home,report)
            main.write_bytes(b'synthetic candidate');extra=mods/'unexpected.jar';extra.write_bytes(b'extra')
            with self.assertRaises(ValueError):audit_case_mods(home,report)
            extra.unlink();probe.unlink()
            with self.assertRaises(ValueError):audit_case_mods(home,report)

    def test_downloaded_linux_receipt_is_audited_independently_of_auditor_os(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'migration-results/case';home.mkdir(parents=True);folder,record=self.fixture(home)
            record['cwd']='/original/runner/build/migration-results/case'
            record['command']=[part.replace('/win_args.txt','/unix_args.txt') for part in record['command']]
            (folder/'result.json').write_text(json.dumps(record));audit_phase(home,'upgrade-near',relocated=True)
            record['command'].append('@libraries/net/minecraftforge/forge/1.21.11-61.2.4/win_args.txt')
            (folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):audit_phase(home,'upgrade-near',relocated=True)

    def test_mod_aliases_cannot_hide_an_extra_loader_archive(self):
        from unittest.mock import patch
        from release_identity import MAIN_JAR
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);mods=home/'mods';mods.mkdir();main=mods/MAIN_JAR;probe=mods/'lootablebodies-build-tests.jar'
            main.write_bytes(b'synthetic candidate');probe.write_bytes(b'synthetic probe')
            report={'candidate_sha256':digest(main),'probe_sha256':digest(probe)}
            (mods/'unexpected.jar').write_bytes(b'extra')
            original=Path.is_symlink
            with patch.object(Path,'is_symlink',lambda path:path.name=='unexpected.jar' or original(path)):
                with self.assertRaises(ValueError):audit_case_mods(home,report)

    def test_old_processor_caps_worker_overrides_and_gc_selection_are_fatal(self):
        for flag in ['-XX:ActiveProcessorCount=2','-Dmax.bg.threads=2','-Dmax.bg.threads=0','-XX:+UseSerialGC','-XX:+UseG1GC','-XX:ParallelGCThreads=2']:
            with self.subTest(flag=flag),tempfile.TemporaryDirectory() as directory:
                home=Path(directory);folder,record=self.fixture(home);record['command'].insert(1,flag)
                (folder/'result.json').write_text(json.dumps(record))
                with self.assertRaises(ValueError):audit_phase(home,'upgrade-near')

    def test_old_single_worker_and_duplicate_overrides_both_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);folder,record=self.fixture(home)
            record['command'].insert(1,'-Dmax.bg.threads=1')
            (folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):audit_phase(home,'upgrade-near')
            record['command'].insert(1,'-Dmax.bg.threads=1')
            (folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):audit_phase(home,'upgrade-near')

    def test_serial_override_does_not_qualify_an_ordinary_game_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);folder,record=self.fixture(home)
            folder.rename(home/'fresh.diagnostics');folder=home/'fresh.diagnostics'
            for suffix in ('log','latest.log','debug.log'):
                (home/('upgrade-near.'+suffix)).rename(home/('fresh.'+suffix))
                (home/('fresh.'+suffix)).write_text('LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase=fresh\n')
            record['command']=[p.replace('upgrade-near','fresh') for p in record['command']]
            (folder/'result.json').write_text(json.dumps(record))
            audit_phase(home,'fresh')
            record['command'].insert(1,'-Dmax.bg.threads=1')
            (folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):audit_phase(home,'fresh')

    def test_omitting_or_emptying_short_phase_diagnostics_cannot_qualify(self):
        for name in ['initial-threads.txt','initial-heap-gc.txt']:
            for empty in [False,True]:
                with self.subTest(name=name,empty=empty),tempfile.TemporaryDirectory() as directory:
                    home=Path(directory);folder,record=self.fixture(home)
                    if empty:
                        (folder/name).write_bytes(b'');record['evidence'][name]=digest(folder/name)
                    else:del record['evidence'][name]
                    (folder/'result.json').write_text(json.dumps(record))
                    with self.assertRaises(ValueError):audit_phase(home,'upgrade-near')

if __name__=='__main__':unittest.main()
