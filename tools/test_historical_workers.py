import threading
import unittest
from historical_workers import run_origins


class HistoricalWorkersTests(unittest.TestCase):
    def test_two_isolated_workers_keep_each_origin_once_and_never_exceed_two(self):
        lock=threading.Lock();barrier=threading.Barrier(2,timeout=3)
        active=0;peak=0;seen=[]
        def execute(origin):
            nonlocal active,peak
            with lock:active+=1;peak=max(peak,active);seen.append(origin)
            barrier.wait()
            with lock:active-=1
        run_origins(['one','two','three','four'],execute,workers=2)
        self.assertEqual(2,peak);self.assertCountEqual(['one','two','three','four'],seen)

    def test_one_worker_keeps_the_original_serial_order(self):
        seen=[];run_origins(['one','two'],seen.append,workers=1)
        self.assertEqual(['one','two'],seen)

    def test_a_real_failure_is_propagated_and_no_later_batch_starts(self):
        seen=[]
        def execute(origin):
            seen.append(origin)
            if origin=='one':raise ValueError('actual origin failed')
        with self.assertRaisesRegex(RuntimeError,'one'):
            run_origins(['one','two','three','four'],execute,workers=2)
        self.assertNotIn('three',seen);self.assertNotIn('four',seen)

    def test_duplicate_origins_and_unsafe_worker_counts_fail_before_launch(self):
        for origins,workers in [([],2),(['one','one'],2),(['one'],0),(['one'],3),(['one'],True)]:
            with self.subTest(origins=origins,workers=workers),self.assertRaises(ValueError):
                run_origins(origins,lambda origin:self.fail('Worker launched'),workers=workers)

    def test_both_failed_workers_keep_their_distinct_errors(self):
        def execute(origin):raise ValueError(origin+' actual failure')
        with self.assertRaises(RuntimeError) as result:
            run_origins(['one','two'],execute,workers=2)
        self.assertIn('one actual failure',str(result.exception))
        self.assertIn('two actual failure',str(result.exception))
