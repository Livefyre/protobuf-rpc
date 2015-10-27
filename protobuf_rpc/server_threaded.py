import threading

from protobuf_rpc.base_server import ProtoBufRPCServer
import zmq
from zmq.devices.monitoredqueuedevice import ThreadMonitoredQueue


class ThreadedGServer(ProtoBufRPCServer):
    WORKER_URL = "inproc://worker"

    def __init__(self, host, port, service, poolsize=10, worker_socket=zmq.REP):
        self.host = host
        self.port = port
        self.num_workers = poolsize
        self.stop_event = threading.Event()
        self.service = service
        self.worker_socket = worker_socket

    def serve_forever(self):
        dev = ThreadMonitoredQueue(zmq.ROUTER, zmq.DEALER, zmq.PUB, 'in', 'out')

        dev.bind_in("tcp://%s:%d" % (self.host, self.port))
        dev.bind_out(self.WORKER_URL)
        dev.start()

        threads = []
        for _ in range(self.num_workers):
            thread = threading.Thread(target=self.worker, args=(self.stop_event,))
            thread.daemon = True
            thread.start()
            threads.append(thread)

        try:
            while True:
                self.stop_event.wait(1)
        except KeyboardInterrupt:
            self.stop_event.set()

        for thread in threads:
            thread.join()

    def worker(self, stop_event):
        socket = zmq.Context.instance().socket(self.worker_socket)
        socket.connect(self.WORKER_URL)
        poller = zmq.Poller()
        poller.register(socket, zmq.POLLIN)

        while not stop_event.is_set():
            try:
                if not poller.poll(1000):
                    continue
                msg = socket.recv_multipart()
            except zmq.ZMQError:
                if socket.closed:
                    break
                stop_event.set()
                raise

            response = self.handle(msg[-1])
            socket.send_multipart(msg[:-1] + [response.SerializeToString()])
