from Queue import Queue, Empty
import contextlib
from logging import getLogger
import random
import time
from gevent.monkey import saved


LOG = getLogger(__name__)


if bool(saved):
    LOG.info('using zmq.green...')
    import zmq.green as zmq
else:
    import zmq


class ZMQConnection(object):

    def __init__(self, hosts, maxidle=None, timeout=2 * 1000, maxage=60):
        self._last_used = self._born = time.time()
        self._closed = False
        self.maxidle = maxidle
        self.maxage = maxage
        self.timeout = timeout
        self._zmq_init(hosts)

    def _zmq_init(self, hosts):
        context = zmq.Context()
        random.shuffle(hosts)
        self.socket = context.socket(zmq.REQ)
        self.socket.setsockopt(zmq.LINGER, 0)
        for (host, port) in hosts:
            self.socket.connect("tcp://%s:%s" % (host, port))
        self.poller = zmq.Poller()
        self.poller.register(self.socket, zmq.POLLIN)

    def send(self, req):
        self.socket.send(req)
        self._last_used = time.time()

    def recv(self, timeout=None):
        if self.poller.poll(timeout or self.timeout):
            resp = self.socket.recv()
        else:
            self.close()
            raise TimeoutError("Timeout processing request.")
        self._last_used = time.time()
        return resp

    def close(self):
        try:
            self.socket.close()
        except:
            pass
        self._closed = True

    @property
    def closed(self):
        if self._closed:
            return self._closed
        t = time.time()
        died_of_old_age = self.maxage and t - self._born > self.maxage
        died_of_boredom = self.maxidle and t - self._last_used > self.maxidle
        if died_of_old_age:
            self.close()
            return True
        if died_of_boredom:
            self.close()
            return True
        return False


class ConnectionError(IOError):
    pass


class ZMQConnectionPool(object):

    def __init__(self, create_connection, maxsize=100):
        self.maxsize = maxsize
        self.pool = Queue()
        self.size = 0
        self.create_connection = create_connection

    def get(self, block=True, timeout=None):
        pool = self.pool
        if self.size >= self.maxsize or pool.qsize():
            # we're over limit or there are already created objects in the queue
            try:
                conn = pool.get(block=block, timeout=timeout)
            except Empty:
                raise ConnectionError("Too many connections")
            # we got a connection, but it must be valid!
            # a null connection means we need to create a new one
            if conn and not conn.closed:
                return conn
            # we didn't get a valid connection, add one.
        else:
            # we have to room to grow, so reserve a spot!
            self.size += 1
        try:
            conn = self.create_connection()
        except:
            self.size -= 1
            raise
        return conn

    def put(self, item):
        self.pool.put(item)

    @contextlib.contextmanager
    def connection(self, **kwargs):
        """
        :yield: ZMQConnection
        """
        conn = None
        try:
            conn = self.get(**kwargs)
            yield conn
        except:
            # if we had problems let's discard
            if conn:
                conn.close()
            raise
        finally:
            if conn and conn.closed:
                # this "returns" to the pool, but will result
                # in a new connection
                conn = None
            self.put(conn)


class TimeoutError(IOError):
    pass
