import unittest
from protobuf_rpc.pool import ObjectPool


class MockClass(object):
    pass


class TestObjectPool(unittest.TestCase):
    def test_get(self):
        pool = ObjectPool(MockClass, 1)
        with pool.get() as conn:
            assert isinstance(conn, MockClass)
            last_obj = conn

        # If we get again, it should re-use the same object
        with pool.get() as obj:
            assert last_obj == obj
            raise pool.Remove()

        # We removed in the last go, so it should give us a new one
        # Mock the time function so we can test the timeout
        pool.get_time = lambda: 0
        with pool.get() as obj:
            assert last_obj != obj
            last_obj = obj

        pool.get_time = lambda: 100
        with pool.get() as obj:
            assert last_obj != obj

    def test_rotation(self):
        # The pool should rotate connections (not just re-use the same one)
        used_conns = set()
        pool_size = 5
        pool = ObjectPool(MockClass, pool_size)

        # Fill up the queue
        _conns = [pool.take() for _ in range(pool_size)]
        for conn in _conns:
            pool.release(conn)

        for _ in range(pool_size):
            with pool.get() as conn:
                assert conn not in used_conns
                used_conns.add(conn)

        with pool.get() as conn:
            assert conn in used_conns

    def test_max_size(self):
        pool_size = 5
        pool = ObjectPool(MockClass, pool_size)
        _conns = [pool.take() for _ in range(pool_size)]
        self.assertRaises(pool.ConnectionError, pool.take)
        pool.release(_conns[0])
        assert pool.take()
