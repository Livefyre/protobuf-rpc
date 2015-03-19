from contextlib import contextmanager
import time


class ObjectPool(object):
    """
    TODOD: implement spin down of unused objects
    """

    class Remove(Exception):
        pass

    class ConnectionError(Exception):
        pass

    def __init__(self, conn_factory, maxsize=None, maxage=60):
        self.connections = []
        self.maxsize = maxsize
        self.maxage = maxage
        self.conn_factory = conn_factory
        self.leased = set([])

    @contextmanager
    def get(self, block=True, timeout=None):
        try:
            obj = self.take(block, timeout)
            yield obj
            self.release(obj)
        except self.Remove:
            self.remove(obj)

    def get_time(self):
        return time.time()

    def remove(self, obj):
        self.leased.remove(obj)

    def take(self, block=True, timeout=None):
        try:
            while True:
                last_used, connection = self.connections.pop(0)
                if self.get_time() - last_used <= self.maxage:
                    break
        except IndexError:
            connection = self.make_connection()
        self.leased.add(connection)
        return connection

    def make_connection(self):
        if len(self.connections) + len(self.leased) >= self.maxsize:
            raise self.ConnectionError("Too many connections")
        return self.conn_factory()

    def release(self, obj):
        self.leased.remove(obj)
        self.connections.append((self.get_time(), obj))
