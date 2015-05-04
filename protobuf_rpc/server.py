if True:
    from gevent import monkey
    monkey.patch_all()

from gevent.event import Event
from gevent.pool import Pool
from protobuf_rpc.base_server import ProtoBufRPCServer
import zmq.green as zmq


class GServer(ProtoBufRPCServer):
    def __init__(self, host, port, service, poolsize=128):
        self.gpool = Pool(poolsize)
        self.stop_event = Event()
        context = zmq.Context()
        self.port = port
        self.addr = "tcp://%s:%s" % (host, port)
        self.socket = context.socket(zmq.ROUTER)
        self.socket.bind(self.addr)
        self.service = service

    def serve_forever(self,):
        while not self.stop_event.is_set():
            try:
                msg = self.socket.recv_multipart()
            except zmq.ZMQError:
                if self.socket.closed:
                    break
                raise
            self.gpool.spawn(self.handle_request, msg)

    def shutdown(self,):
        self.socket.unbind(self.addr)
        self.socket.close()
        self.stop_event.set()

    def handle_request(self, msg):
        assert len(msg) == 3
        (id_, null, request) = msg
        assert null == ''
        response = self.handle(request)
        self.socket.send_multipart([id_, null, response.SerializeToString()])
