from pathlib import Path
import tempfile,unittest
from native119_logs import audit

class Native119LogTests(unittest.TestCase):
    def test_ordinary_complete_output_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            log=Path(directory)/'runtime.log';log.write_text('[12:00:00] [main/INFO] Native45 completed\n',encoding='utf-8')
            self.assertEqual(1,audit(log)['lines'])
    def test_an_old_platform_control_is_not_new_target_qualification(self):
        for text in ['AL lib: (EE) alc_cleanup: 1 device not closed\n',
                     '[12:00:00] [main/ERROR] FML appears to be missing any signature data. This is not a good thing\n']:
            with self.subTest(text=text),tempfile.TemporaryDirectory() as directory:
                log=Path(directory)/'runtime.log';log.write_text(text,encoding='utf-8')
                with self.assertRaises(RuntimeError):audit(log)
    def test_corrupt_or_empty_output_is_not_positive_proof(self):
        for raw in [b'',b'[12:00:00] [main/INFO] native text \xff\n']:
            with self.subTest(raw=raw),tempfile.TemporaryDirectory() as directory:
                log=Path(directory)/'runtime.log';log.write_bytes(raw)
                with self.assertRaises((RuntimeError,UnicodeDecodeError)):audit(log)
    def test_actual_exception_or_error_cannot_be_hidden(self):
        for text in ['[12:00:00] [main/ERROR] actual failure\n','Caused by: java.lang.IllegalStateException: actual failure\n','java.lang.AssertionError: failed inventory\n']:
            with self.subTest(text=text),tempfile.TemporaryDirectory() as directory:
                log=Path(directory)/'runtime.log';log.write_text(text,encoding='utf-8')
                with self.assertRaises(RuntimeError):audit(log)

if __name__=='__main__':unittest.main()
