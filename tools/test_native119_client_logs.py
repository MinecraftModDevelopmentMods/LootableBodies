"""Synthetic diagnostic counterexamples; independent native60 is separate proof."""
from pathlib import Path
import hashlib,json,tempfile,unittest
from unittest.mock import patch
import native119_client_logs as logs
from native119_logs import audit as strict_audit

EVENTS=['minecraft:item.brush.brushing','minecraft:item.brush.brush_sand_completed','minecraft:item.goat_horn.play','minecraft:entity.goat.screaming.horn_break','minecraft:music.overworld.cherry_grove']
def lines(events=EVENTS):return ''.join('[12:00:00] [Render thread/WARN] [net.minecraft.client.sounds.SoundEngine]: Missing sound for event: '+name+'\n' for name in events)

class Native119ClientDiagnosticTests(unittest.TestCase):
    def check(self,text):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'client.log';p.write_text(text,encoding='utf-8')
            with patch.object(logs,'validated_baseline',return_value={'events':EVENTS,'proof_sha256':'A'*64}):return logs.audit_client(p)
    def test_complete_proved_native_batch_is_explicitly_classified(self):
        for text in [lines(),lines().replace('[12:00:00]','[12:00:00.123]')]:
            report=self.check(text+'[12:00:01] [Render thread/INFO] Completed synthetic fixture\n')
            self.assertEqual(5,report['classified_native_sound_warnings']);self.assertFalse(report['raw_strict_audit_passed']);self.assertTrue(report['residual_strict_audit_passed'])
    def test_missing_or_changed_control_proof_cannot_qualify(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'client.log';p.write_text(lines(),encoding='utf-8')
            with patch.object(logs,'validated_baseline',side_effect=RuntimeError('Unqualified native client diagnostic proof')):
                with self.assertRaisesRegex(RuntimeError,'Unqualified'):logs.audit_client(p)
    def test_unknown_mutated_partial_or_extra_native_warning_is_fatal(self):
        for text in [lines(EVENTS[:-1]),lines()+lines(EVENTS[:1]),lines().replace('Render thread/WARN','Worker-Main-1/WARN'),lines().replace('minecraft:item.goat_horn.play','anothermod:missing_sound')]:
            with self.subTest(text=text),self.assertRaises(RuntimeError):self.check(text)
    def test_actual_missing_resources_errors_exceptions_and_old_native_errors_stay_fatal(self):
        for failure in ['[12:00:01] [Render thread/WARN] Missing texture: anothermod:lost\n','[12:00:01] [Render thread/WARN] File minecraft:sounds/missing.ogg does not exist, cannot add it to event anothermod:lost\n','[12:00:01] [Render thread/ERROR] actual failure\n','Caused by: java.lang.IllegalStateException: failed inventory\n','AL lib: (EE) alc_cleanup: 1 device not closed\n']:
            for text in [failure,lines()+failure]:
                with self.subTest(failure=failure,text=text),self.assertRaises(RuntimeError):self.check(text)
    def test_generic_and_server_auditors_still_reject_this_raw_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'server.log';p.write_text(lines(),encoding='utf-8')
            with self.assertRaises(RuntimeError):strict_audit(p)
    def test_actual_proof_reader_rejects_changed_resource_proof_target_and_control(self):
        for mutation in [None,'resource','proof','missing','target','control']:
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);definition=root/'sounds.json';definition.write_text('{}');critical=root/'control.fixture';critical.write_text('synthetic independent control input');rawlog=root/'native.log';rawlog.write_text('synthetic native fixture')
                sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest().upper()
                proof={'minecraft':'1.19.4','forge':'45.4.5','platform':'Windows amd64','qualified':True,'no_candidate':True,'no_world':True,'native_client_exit':0,'events':EVENTS,'control_sha256':'C'*64,'sound_definition':str(definition),'critical_files':{str(definition):sha(definition),str(critical):sha(critical)},'raw_control_logs':{str(rawlog):sha(rawlog)},'original_strict_audit_passed':False}
                saved=root/'proof.json';saved.write_text(json.dumps(proof))
                policy={'minecraft':'1.19.4','forge':'45.4.5','platform':'Windows amd64','qualified':True,'proof_sha256':sha(saved),'events':EVENTS,'control_sha256':'C'*64,'sound_definition_sha256':sha(definition)}
                if mutation=='resource':definition.write_text('{"item.brush.brushing":{}}')
                elif mutation=='proof':saved.write_text('{}')
                elif mutation=='missing':critical.unlink()
                elif mutation=='target':policy['forge']='40.3.12'
                elif mutation=='control':critical.write_text('changed control')
                record=root/'policy.json';record.write_text(json.dumps(policy))
                if mutation is None:self.assertEqual(EVENTS,logs.validated_baseline(record,saved)['events'])
                else:
                    with self.assertRaises(RuntimeError):logs.validated_baseline(record,saved)

if __name__=='__main__':unittest.main()
