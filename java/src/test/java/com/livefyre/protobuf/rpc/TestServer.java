package com.livefyre.protobuf.rpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestServer {

    private static final Logger logger = LoggerFactory.getLogger(TestServer.class);

    private Server server;
    private ExecutorService sThreads;

    private ZContext context =  new ZContext();
    private ZMQ.Socket socket;

    private String endpoint;

    private class Service extends TestService.Service {

        @Override
        public void test(RpcController controller, TestService.Request request, RpcCallback<TestService.Response> done) {
            TestService.Response.Builder response = TestService.Response.newBuilder().setResponse(request.getQuery());
            done.run(response.build());
        }

        @Override
        public void testTimeout(RpcController controller, TestService.Request request, RpcCallback<TestService.Response> done) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            TestService.Response.Builder response = TestService.Response.newBuilder().setResponse(request.getQuery());
            done.run(response.build());
        }
    }

    private static int getAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 9999;
    }

    @Before
    public void setUp(){
        sThreads = Executors.newFixedThreadPool(1);

        int port = TestServer.getAvailablePort();
        logger.info("using port -> {}", port);

        endpoint = "tcp://localhost:" + port;

        sThreads.submit(() -> {
            server = Server.create(endpoint, 1, new Service());
            try {
                server.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @After
    public void tearDown() {
        server.stop();
        socket.close();
    }

    @Test(expected = Exceptions.InvalidRequestProtoException.class)
    public void testInvalidRequestProto() throws Exceptions.ProtoRpcException, InvalidProtocolBufferException {
        socket = context.createSocket(ZMQ.REQ);
        socket.connect(endpoint);
        socket.send("foo");
        Controller controller = new Controller();
        SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(socket.recv());
        controller.readFrom(response);
        throw Exceptions.getFrom(controller);
    }

    @Test(expected = Exceptions.MethodNotFoundError.class)
    public void testMethodNotFoundError() throws InvalidProtocolBufferException, Exceptions.ProtoRpcException {
        socket = context.createSocket(ZMQ.REQ);
        socket.connect(endpoint);
        SocketRpcProtos.Request.Builder request = SocketRpcProtos.Request
                .newBuilder()
                .setId(1)
                .setServiceName("foo")
                .setMethodName("bar")
                .setRequestProto(TestService.Request.getDefaultInstance().toByteString());
        socket.send(request.build().toByteArray());
        Controller controller = new Controller();
        SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(socket.recv());
        controller.readFrom(response);
        throw Exceptions.getFrom(controller);
    }

    @Test(expected = Exceptions.BadRequestProtoError.class)
    public void testBadRequestProtoError() throws InvalidProtocolBufferException, Exceptions.ProtoRpcException {
        socket = context.createSocket(ZMQ.REQ);
        socket.connect(endpoint);
        SocketRpcProtos.Request.Builder request = SocketRpcProtos.Request
                .newBuilder()
                .setId(1)
                .setServiceName("com.livefyre.protobuf.rpc.Service")
                .setMethodName("Test")
                .setRequestProto(SocketRpcProtos.Request.getDefaultInstance().toByteString());
        socket.send(request.build().toByteArray());
        Controller controller = new Controller();
        SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(socket.recv());
        controller.readFrom(response);
        throw Exceptions.getFrom(controller);
    }
}