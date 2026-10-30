from pathlib import Path
import copy,json,tempfile,unittest
import evidence_policy as p

class EvidenceReuseTests(unittest.TestCase):
    def fixture(self,root):
        report=root/'report.json';report.write_text('{"all_pass":true}')
        log=root/'complete.log';log.write_text('actual retained complete log')
        subject={name:name+'-sealed' for name in p.IDENTITY_FIELDS}
        subject.update(source_commit='reviewed-commit',target='forge-1.16.5',source_tree='same-tree',
            artifacts={'main':'qualified-main','sources':'qualified-sources'},platform='linux-x86_64')
        runs=[{'repository':'MinecraftModDevelopmentMods/LootableBodies','run':123,'head':'reviewed-commit','status':'completed','conclusion':'success','workflow':'.github/workflows/build.yml'}]
        receipt={'schema':1,'subject':subject,'identity':p.identity(subject),'qualified_commit':'reviewed-commit',
            'label':'qualified original run, not rerun','required_gates':['gameplay','migration'],
            'gates':{'gameplay':True,'migration':True},'runs':copy.deepcopy(runs),
            'evidence':{'report.json':p.sha(report),'complete.log':p.sha(log)}}
        path=root/'receipt.json';path.write_text(json.dumps(receipt));return subject,path,p.sha(path),runs,receipt
    def invoke(self,subject,path,pin,runs,root):return p.plan(subject,path,pin,root,runs,{'gameplay','migration'})
    def test_same_tree_new_merge_commit_reuses_original_proof_honestly(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);subject,path,pin,runs,receipt=self.fixture(root);subject['source_commit']='merged-commit'
            value=self.invoke(subject,path,pin,runs,root)
            self.assertEqual('reuse',value['action']);self.assertFalse(value['fresh_execution']);self.assertEqual('reviewed-commit',value['qualified_commit'])
            self.assertTrue(value['fresh_merge_and_publication_checks_required'])
    def test_every_changed_identity_component_requires_fresh_qualification(self):
        for key in p.IDENTITY_FIELDS:
            with self.subTest(key=key),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);subject,path,pin,runs,receipt=self.fixture(root);subject[key]={'changed':True}
                self.assertEqual('run',self.invoke(subject,path,pin,runs,root)['action'])
    def test_missing_or_tampered_evidence_and_receipt_cannot_reuse(self):
        for mutation in ('missing-log','changed-log','changed-receipt','missing-identity','failed-gate','missing-gate','extra-required-gate','no-files','path-escape'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);subject,path,pin,runs,receipt=self.fixture(root)
                if mutation=='missing-log':(root/'complete.log').unlink()
                if mutation=='changed-log':(root/'complete.log').write_text('tampered')
                if mutation=='changed-receipt':path.write_text('{}')
                if mutation=='missing-identity':del subject['sdk']
                if mutation=='failed-gate':receipt['gates']['gameplay']=False
                if mutation=='missing-gate':del receipt['gates']['migration']
                if mutation=='extra-required-gate':receipt['required_gates'].append('new-test')
                if mutation=='no-files':receipt['evidence']={}
                if mutation=='path-escape':receipt['evidence']['../outside.log']='0'*64
                if mutation in ('failed-gate','missing-gate','extra-required-gate','no-files','path-escape'):
                    path.write_text(json.dumps(receipt));pin=p.sha(path)
                self.assertEqual('run',self.invoke(subject,path,pin,runs,root)['action'])
    def test_fork_failed_stale_missing_or_changed_api_run_cannot_authorise_reuse(self):
        for mutation in ('fork','failed','pending','other-head','other-workflow','missing','other-run','receipt-self-approval'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);subject,path,pin,runs,receipt=self.fixture(root)
                if mutation=='fork':runs[0]['repository']='SkyBlade1978/LootableBodies'
                if mutation=='failed':runs[0]['conclusion']='failure'
                if mutation=='pending':runs[0]['status']='in_progress'
                if mutation=='other-head':runs[0]['head']='old-commit'
                if mutation=='other-workflow':runs[0]['workflow']='unreviewed.yml'
                if mutation=='missing':runs=[]
                if mutation=='other-run':runs[0]['run']=999
                if mutation=='receipt-self-approval':pin='0'*64
                self.assertEqual('run',self.invoke(subject,path,pin,runs,root)['action'])

if __name__=='__main__':unittest.main(verbosity=2)
