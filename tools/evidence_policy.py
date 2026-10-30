"""Fail-closed planning for reuse of an explicitly approved qualification receipt.

This plans work; it never starts a build, sets hosted check statuses or publishes.
The approved receipt digest and server-fetched run metadata must come from the
trusted release controller, not from the downloaded receipt itself.
"""
import hashlib,json
from pathlib import Path

IDENTITY_FIELDS=('target','source_tree','artifacts','probe','test_tools','toolchains',
    'sdk','fixture_bundle','source_worlds','fixture_environment','platform','coverage')

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()

def identity(subject):
    if any(name not in subject or not subject[name] for name in IDENTITY_FIELDS):
        raise ValueError('Incomplete qualification identity')
    return hashlib.sha256(json.dumps({name:subject[name] for name in IDENTITY_FIELDS},
        sort_keys=True,separators=(',',':')).encode()).hexdigest().upper()

def plan(subject,receipt_path,approved_sha,evidence_root,api_runs,required_gates):
    def require(value,reason):
        if not value:raise ValueError(reason)
    try:
        root=Path(evidence_root).resolve();path=Path(receipt_path).resolve()
        require(path.is_relative_to(root) and path.is_file(),'Receipt missing or outside owned evidence')
        require(sha(path)==approved_sha,'Receipt differs from independently approved digest')
        receipt=json.loads(path.read_text())
        require(receipt['schema']==1,'Unknown qualification receipt schema')
        require(receipt['identity']==identity(receipt['subject'])==identity(subject),'Qualification inputs changed')
        required=set(required_gates)
        require(required and set(receipt['required_gates'])==required and len(receipt['required_gates'])==len(required),'Coverage contract changed')
        require(set(receipt['gates'])==required and all(receipt['gates'][gate] is True for gate in required),'Missing or failed functional gate')
        allowed={'.github/workflows/'+name for name in ('build.yml','migration-matrix.yml','codeql-analysis.yml','gradle-wrapper-validation.yml','release-workflow-checks.yml')}
        requested=receipt['runs'];actual={run['run']:run for run in api_runs}
        require(requested and len(actual)==len(api_runs)==len(requested),'Missing or duplicate server run metadata')
        require(len({run['run'] for run in requested})==len(requested),'Duplicate qualification run')
        for recorded in requested:
            run=actual.get(recorded['run'])
            require(run is not None and run==recorded,'Run provenance changed')
            require(run['repository']=='MinecraftModDevelopmentMods/LootableBodies','Untrusted qualification repository')
            require(run['head']==receipt['qualified_commit'] and run['workflow'] in allowed,'Stale source or unreviewed workflow')
            require(run['status']=='completed' and run['conclusion']=='success','Qualification run is not successful')
        require(receipt['evidence'],'Missing actual qualification artifacts')
        for name,expected in receipt['evidence'].items():
            target=(root/name).resolve()
            require(target.is_relative_to(root) and target.is_file() and sha(target)==expected,'Missing, escaped or changed qualification artifact: '+name)
        return {'action':'reuse','reason':'All qualification inputs and independently approved evidence match',
            'fresh_execution':False,'evidence_label':'reused original qualification',
            'qualified_commit':receipt['qualified_commit'],'qualification_run_ids':[run['run'] for run in requested],
            'fresh_merge_and_publication_checks_required':True}
    except (OSError,KeyError,TypeError,ValueError) as error:
        return {'action':'run','reason':str(error),'fresh_execution':True}
