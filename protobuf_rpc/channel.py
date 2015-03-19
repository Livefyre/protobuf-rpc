from protobuf_rpc.base_channel import ProtoBufRPCChannel
from protobuf_rpc.connection import ZMQConnection
from protobuf_rpc.pool import ObjectPool


class ZMQChannel(ProtoBufRPCChannel):
    def __init__(self, hosts, max_pool_size=100, recv_timeout=2 * 1000):
        self.connection_pool = ObjectPool(lambda: ZMQConnection(hosts=hosts),
                                          maxsize=max_pool_size)
        self.recv_timeout = recv_timeout

    def send_rpc_request(self, request):
        with self.connection_pool.get() as con:
            error = None
            con.send(request.SerializeToString())
            try:
                resp = con.recv(self.recv_timeout)
            except IOError as ioe:
                try:
                    con.close()
                except:
                    pass
                error = ioe
                raise ObjectPool.Remove
        if error is not None:
            raise error
        return resp
