import time
import unittest

from mock import patch
from protobuf_rpc import connection
from protobuf_rpc.connection import ZMQConnectionPool, ZMQConnection


class MockClass(ZMQConnection):
    def _zmq_init(self, hosts):
        pass


class TestObjectPool(unittest.TestCase):
    def test_get(self):
        pool = ZMQConnectionPool(lambda: MockClass([], 99), 1)
        with pool.connection() as conn:
            assert isinstance(conn, MockClass)
            last_obj = conn

        self.assertFalse(last_obj.closed)
        # If we get again, it should re-use the same object
        with pool.connection() as obj:
            assert last_obj == obj
            obj.close()

        # We removed in the last go, so it should give us a new one
        # Mock the time function so we can test the timeout
        with patch('protobuf_rpc.connection.time') as t:
            t.time.return_value = 0
            with pool.connection() as obj:
                assert last_obj != obj
                last_obj = obj
                t.time.return_value = 100
                self.assertEqual(t.time(), 100)
                self.assertTrue(obj.closed)

            with pool.connection() as obj:
                assert last_obj != obj

    def test_rotation(self):
        # The pool should rotate connections (not just re-use the same one)
        pool_size = 5
        pool = ZMQConnectionPool(lambda: MockClass([]), pool_size)

        # Fill up the queue
        _conns = [pool.get() for _ in range(pool_size)]
        for conn in _conns:
            pool.put(conn)

        used_conns = set()
        for _ in range(pool_size):
            with pool.connection() as conn:
                self.assertTrue(conn in _conns)
                self.assertTrue(conn not in used_conns, (_, used_conns))
                assert conn not in used_conns
                used_conns.add(conn)

        with pool.connection() as conn:
            assert conn in used_conns

    def test_max_size(self):
        pool_size = 5
        pool = ZMQConnectionPool(lambda: MockClass([]), pool_size)
        _conns = [pool.get() for _ in range(pool_size)]
        self.assertRaises(connection.ConnectionError, pool.get, False)
        pool.put(_conns[0])
        assert pool.get()
