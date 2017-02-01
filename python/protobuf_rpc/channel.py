from protobuf_rpc.base_channel import ProtoBufRPCChannel
from protobuf_rpc.connection import ZMQConnection, ZMQConnectionPool


class ZMQChannel(ProtoBufRPCChannel):
    def __init__(self, hosts, max_pool_size=100, recv_timeout=2 * 1000):
        self.connection_pool = ZMQConnectionPool(lambda: ZMQConnection(hosts=hosts),
                                                 maxsize=max_pool_size)
        self.recv_timeout = recv_timeout

    def send_rpc_request(self, request, block=True, timeout=None):
        with self.connection_pool.connection(block=block, timeout=timeout) as con:
            con.send(request.SerializeToString())
            try:
                return con.recv(self.recv_timeout)
            except Exception:
                raise
