from pathlib import Path
import unittest
from native37_control_evidence import residual

class Native37ControlEvidenceTests(unittest.TestCase):
    def sample(self):
        stack=(Path(__file__).parent/'fixtures/native37-delayed-movement-stack.txt').read_text()
        return '[12:00:00] [Server thread/FATAL] [net.minecraft.util.thread.BlockableEventLoop]: Error executing task on Server\n'+stack
    def test_exact_eight_native_control_blocks_are_identified_without_changing_raw_logs(self):
        text=self.sample()*8+'[12:00:01] [Server thread/INFO] [minecraft/MinecraftServer]: Stopping server\n'
        original=text;clean,count=residual(text)
        self.assertEqual(8,count);self.assertEqual(original,text);self.assertNotIn('NullPointerException',clean)
    def test_other_frames_exception_text_or_incomplete_counts_fail(self):
        for text in (self.sample()*7,(self.sample()*8).replace('DistanceManager.java:230','DistanceManager.java:231'),(self.sample()*8).replace('objectset','somethingelse')):
            with self.subTest(text=text[:40]),self.assertRaises(ValueError):residual(text)
    def test_a_clean_native_platform_does_not_require_manufactured_errors(self):
        text='[12:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: Clean control\n'
        self.assertEqual((text,0),residual(text))
