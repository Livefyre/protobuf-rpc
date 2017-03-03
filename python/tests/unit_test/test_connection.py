from gevent import monkey; monkey.patch_all()  # @NoMove @IgnorePep8
import os
import time
import unittest

from protobuf_rpc.connection import ZMQConnection

import zmq.green as zmq


def skip_in_cirleci(func):
    if os.uname()[0] == 'Darwin':
        return func
    return unittest.skip('no work on circleci')(func)


class TestConnectionInternals(unittest.TestCase):
    def test_closing_on_old_age(self):
        conn = ZMQConnection([], maxage=0.01)
        self.assertFalse(conn.closed)
        time.sleep(0.1)
        self.assertTrue(conn.closed)


@skip_in_cirleci
class TestConnectionIntegration(unittest.TestCase):

    def setUp(self,):
        self.ctx1 = zmq.Context()
        self.serv_con1 = self.ctx1.socket(zmq.ROUTER)
        self.addr = "tcp://127.0.0.1:12345"
        self.serv_con1.bind(self.addr)
        self.hosts = [("127.0.0.1",12345)]
        self.con = ZMQConnection(self.hosts)

    def tearDown(self):
        self.con.close()
        self.serv_con1.unbind(self.addr)
        self.serv_con1.close()

    def test_send(self):
        mock_msg = "PING"
        self.con.send(mock_msg)
        msg1 = self.serv_con1.recv_multipart()
        self.assertEquals(msg1[2], mock_msg)

    def test_send_recv(self):
        self.hosts = [("127.0.0.1",12345)]
        self.con = ZMQConnection(self.hosts)
        mock_msg = "PING"
        self.con.send(mock_msg)
        [id_, null, req] = self.serv_con1.recv_multipart()
        self.assertEquals(req, mock_msg)
        mock_resp = "PONG"
        self.serv_con1.send_multipart([id_, null, mock_resp])
        resp = self.con.recv()
        self.assertEquals(resp, mock_resp)

    def test_timeout(self,):
        self.hosts = [("127.0.0.1",12345)]
        self.con = ZMQConnection(self.hosts)
        mock_msg = "PING"
        self.con.send(mock_msg)
        [id_, null, req] = self.serv_con1.recv_multipart()
        self.assertEquals(req, mock_msg)
        self.assertRaises(IOError, self.con.recv, 1)
