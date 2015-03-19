import random

import zmq.green as zmq


class ZTimeout(IOError):
    pass


class ZMQConnection(object):

    def __init__(self, hosts):
        context = zmq.Context()
        random.shuffle(hosts)
        self.socket = context.socket(zmq.REQ)
        self.socket.setsockopt(zmq.LINGER, 0)
        [
            self.socket.connect("tcp://%s:%s" % (host, port))
            for (host, port) in hosts
        ]
        self.poller = zmq.Poller()
        self.poller.register(self.socket, zmq.POLLIN)

    def send(self, req):
        self.socket.send(req)

    def recv(self, timeout=2 * 1000):
        if self.poller.poll(timeout):
            resp = self.socket.recv()
        else:
            raise ZTimeout("Timeout processing request.")
        return resp

    def close(self,):
        self.socket.close()
