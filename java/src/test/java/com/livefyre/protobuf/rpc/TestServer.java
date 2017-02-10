package com.livefyre.protobuf.rpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestServer {

    private Server server;
    private ExecutorService sThreads;

    private ZContext context =  new ZContext();
    private ZMQ.Socket socket;

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

    @Before
    public void setUp(){
        sThreads = Executors.newFixedThreadPool(1);

        sThreads.submit(() -> {
            server = Server.create("tcp://localhost:1234", 1, new Service());
            server.start();
        });

        socket = context.createSocket(ZMQ.REQ);
    }

    @After
    public void tearDown() {
        server.tearDown();
        socket.close();
        context.destroy();
    }

    @Test(expected = Exceptions.InvalidRequestProtoException.class)
    public void testInvalidRequestProto() throws Exceptions.ProtoRpcException, InvalidProtocolBufferException {
        ZMQ.Socket socket = context.createSocket(ZMQ.REQ);
        socket.connect("tcp://localhost:1234");
        socket.send("foo");
        Controller controller = new Controller();
        SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(socket.recv());
        controller.readFrom(response);
        socket.close();
        throw Exceptions.getFrom(controller);
    }

    @Test(expected = Exceptions.MethodNotFoundError.class)
    public void testMethodNotFoundError() throws InvalidProtocolBufferException, Exceptions.ProtoRpcException {
        ZMQ.Socket socket = context.createSocket(ZMQ.REQ);
        socket.connect("tcp://localhost:1234");
        SocketRpcProtos.Request.Builder request = SocketRpcProtos.Request
                .newBuilder()
                .setServiceName("foo")
                .setMethodName("bar")
                .setRequestProto(TestService.Request.getDefaultInstance().toByteString());
        socket.send(request.build().toByteArray());
        Controller controller = new Controller();
        SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(socket.recv());
        controller.readFrom(response);
        socket.close();
        throw Exceptions.getFrom(controller);
    }

    @Test(expected = Exceptions.BadRequestProtoError.class)
    public void testBadRequestProtoError() throws InvalidProtocolBufferException, Exceptions.ProtoRpcException {
        ZMQ.Socket socket = context.createSocket(ZMQ.REQ);
        socket.connect("tcp://localhost:1234");
        SocketRpcProtos.Request.Builder request = SocketRpcProtos.Request
                .newBuilder()
                .setServiceName("com.livefyre.protobuf.rpc.Service")
                .setMethodName("Test")
                .setRequestProto(SocketRpcProtos.Request.getDefaultInstance().toByteString());
        socket.send(request.build().toByteArray());
        Controller controller = new Controller();
        SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(socket.recv());
        controller.readFrom(response);
        socket.close();
        throw Exceptions.getFrom(controller);
    }
}