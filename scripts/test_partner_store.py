import concurrent.futures
import tempfile
import unittest
from partner_store import Store

class PartnerTest(unittest.TestCase):
    def setUp(self):
        self.dir=tempfile.TemporaryDirectory()
        self.path=self.dir.name+'/partner.sqlite'
        self.store=Store(self.path,3)
    def tearDown(self): self.dir.cleanup()
    def test_capacity_is_atomic(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            states=list(pool.map(lambda i:self.store.apply(str(i),i,1,'reserve'),range(100)))
        self.assertEqual(3,states.count('HELD'))
    def test_replay_payload_and_restart(self):
        self.assertEqual('HELD',self.store.apply('a',1,1,'reserve'))
        with self.assertRaises(ValueError): self.store.apply('a',1,2,'reserve')
        self.assertEqual('CONFIRMED',self.store.apply('a',1,1,'confirm'))
        self.assertEqual('CONFIRMED',Store(self.path).apply('a',1,1,'reserve'))
        self.assertEqual('CONFIRMED',self.store.apply('a',1,1,'cancel'))
    def test_cancel_tombstone_blocks_delayed_reserve(self):
        self.assertEqual('CANCELLED',self.store.apply('a',1,1,'cancel'))
        self.assertEqual('CANCELLED',self.store.apply('a',1,1,'reserve'))
    def test_duplicate_and_release(self):
        self.store.apply('a',1,1,'reserve')
        self.assertEqual('FULL',self.store.apply('b',1,1,'reserve'))
        self.store.apply('a',1,1,'cancel')
        self.assertEqual('HELD',self.store.apply('c',1,1,'reserve'))

if __name__=='__main__': unittest.main()
