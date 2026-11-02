import copy,unittest
from local_case_reuse import compare_programs,compare_inputs,DEPENDENCIES

class LocalCaseReuseTests(unittest.TestCase):
    def test_only_unexecuted_stamp_body_change_is_allowed(self):
        old='def patch_stamps(world, disposable):\n return 1\ndef verify(a):\n return patch_stamps(a,a) if a.variant=="stamps" else []\n'
        compare_programs(old,old.replace('return 1','return 2'))
        for changed in (old.replace('world, disposable','world, disposable=None'),old.replace('else []','else [1]'),
                        old.replace('=="stamps"','=="normal"'),old+'\nexec("changed")\n',old.replace('def patch_stamps','def other')):
            with self.subTest(changed=changed),self.assertRaises(ValueError):compare_programs(old,changed)
    def test_each_runtime_candidate_writer_or_logging_input_change_refuses(self):
        old={k:'exact' for k in ('base_source','main_sha256','probe_sha256','sdk_files','java_files')}
        old['frozen_files']={name:'exact' for name in (*DEPENDENCIES,'prepared-worlds.json')}
        compare_inputs(old,copy.deepcopy(old))
        for key in set(old)-{'frozen_files'}:
            new=copy.deepcopy(old);new[key]='changed'
            with self.subTest(key=key),self.assertRaises(ValueError):compare_inputs(old,new)
        for name in old['frozen_files']:
            new=copy.deepcopy(old);new['frozen_files'][name]='changed'
            with self.subTest(name=name),self.assertRaises(ValueError):compare_inputs(old,new)
    def test_missing_dependency_cannot_reuse(self):
        old={k:'exact' for k in ('base_source','main_sha256','probe_sha256','sdk_files','java_files')};old['frozen_files']={}
        with self.assertRaises(ValueError):compare_inputs(old,old)

if __name__=='__main__':unittest.main()
