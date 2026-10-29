"""Workflow globs must retain complete native timeout evidence on both outcomes."""
import glob
from pathlib import Path
import tempfile
import unittest


def paths_for(text, artifact):
    lines=text.splitlines()
    name='name: '+artifact
    start=next(i for i,line in enumerate(lines) if line.strip()==name)
    path=next(i for i in range(start+1,len(lines)) if lines[i].strip()=='path: |')
    result=[]
    for line in lines[path+1:]:
        if not line.startswith('            '):
            break
        result.append(line.strip())
    return result


class RuntimeEvidenceUploadsTests(unittest.TestCase):
    def check_paths(self, workflow, artifact, prefix):
        text=(Path(__file__).resolve().parents[1]/'.github/workflows'/workflow).read_text()
        patterns=paths_for(text,artifact)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            required=[prefix+'/case/upgrade-far-reload.diagnostics/'+name for name in
                      ['result.json','gc.txt','threads-01.txt','heap-gc-01.txt']]
            required += [prefix+'/case/logs/debug-1.log.gz',prefix+'/case/logs/latest.log']
            for name in required:
                target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text('retained')
            selected={Path(path).relative_to(root).as_posix() for pattern in patterns
                      for path in glob.glob(str(root/pattern),recursive=True) if Path(path).is_file()}
            self.assertTrue(set(required)<=selected,
                            'Native runtime evidence omitted: '+str(sorted(set(required)-selected)))

    def test_successful_shards_keep_gc_thread_heap_and_compressed_logs(self):
        for prefix in ['build/migration-results','build/migration-abort']:
            with self.subTest(prefix=prefix):
                self.check_paths('migration-matrix.yml',
                    'LootableBodies-1.16.5-upgrade-shard-${{ matrix.shard }}-${{ github.sha }}',prefix)

    def test_failed_shards_keep_the_same_complete_diagnostics(self):
        for prefix in ['build/migration-results','build/migration-abort']:
            with self.subTest(prefix=prefix):
                self.check_paths('migration-matrix.yml',
                    'LootableBodies-1.16.5-upgrade-shard-failure-${{ matrix.shard }}-${{ github.sha }}',prefix)

    def test_main_failures_keep_native_process_evidence(self):
        self.check_paths('build.yml','LootableBodies-1.16.5-diagnostics-${{ github.sha }}',
                         'build/packaged-server')


if __name__ == '__main__':
    unittest.main()
