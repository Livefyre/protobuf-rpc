package com.livefyre.protobuf.rpc;

import com.google.protobuf.*;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import org.zeromq.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {

    private volatile boolean isRunning = false;
    private final String address;
    private final int numConcurrency;
    private final ExecutorService requestHandlerPool;
    private final Service service;

    private ZContext context = null;
    private ZMQ.Socket frontend = null;
    private ZMQ.Socket backend = null;

    Server(String address, int numConcurrency, Service service) {
        this.address = address;
        this.numConcurrency = numConcurrency;
        this.requestHandlerPool = Executors.newFixedThreadPool(numConcurrency);
        this.service = service;
    }

    public static Server create(String address, int numConcurrency, Service service) {
        return new Server(address, numConcurrency, service);
    }

    void start() {
        context = new ZContext();

        frontend = context.createSocket(ZMQ.ROUTER);
        frontend.bind(address);

        backend = context.createSocket(ZMQ.DEALER);
        backend.bind("inproc://backend");

        isRunning = true;

        for (int i = 0; i < numConcurrency; i++) {
            requestHandlerPool.submit(() -> {
                ZMQ.Socket worker = context.createSocket(ZMQ.DEALER);
                worker.connect("inproc://backend");
                ZMQ.PollItem[] items = new ZMQ.PollItem[] { new ZMQ.PollItem(worker, ZMQ.Poller.POLLIN) };
                while (isRunning) {
                    ZMQ.poll(items, 100);
                    if (items[0].isReadable()) {
                        handleRequest(worker, ZMsg.recvMsg(worker));
                    }
                }
                worker.close();
            });
        }

        ZMQ.proxy(frontend, backend, null);

        tearDown();
    }

    void tearDown() {
        isRunning = false;
        requestHandlerPool.shutdown();
        frontend.close();
        backend.close();
        context.destroy();
    }

    private void handleRequest(ZMQ.Socket socket, ZMsg zMessage) {
        SocketRpcProtos.Response.Builder response = SocketRpcProtos.Response.newBuilder();

        ZFrame content = zMessage.removeLast();

        SocketRpcProtos.Request request = null;
        try {
            request = SocketRpcProtos.Request.parseFrom(content.getData());
        } catch (InvalidProtocolBufferException e) {
            response.setErrorCode(SocketRpcProtos.ErrorReason.INVALID_REQUEST_PROTO);
            send(socket, zMessage, response);
        }

        response.setRequestId(request.getId());

        Descriptors.MethodDescriptor method = service.getDescriptorForType().findMethodByName(request.getMethodName());
        if (method == null) {
            response.setErrorCode(SocketRpcProtos.ErrorReason.METHOD_NOT_FOUND);
            send(socket, zMessage, response);
            return;
        }

        Message requestMessage;
        try {
            requestMessage = service.getRequestPrototype(method).toBuilder().mergeFrom(request.getRequestProto()).build();
        } catch (InvalidProtocolBufferException e) {
            response.setErrorCode(SocketRpcProtos.ErrorReason.BAD_REQUEST_PROTO);
            send(socket, zMessage, response);
            return;
        }

        Controller controller = new Controller();
        try {
            service.callMethod(method, controller, requestMessage, new RpcCallback<Message>() {
                @Override
                public void run(Message message) {
                    response.setResponseProto(message.toByteString());
                    send(socket, zMessage, response);
                }
            });
        } catch (Exception e) {
            response.setErrorCode(SocketRpcProtos.ErrorReason.RPC_ERROR);
            response.setErrorMessage(e.getMessage());
            send(socket, zMessage, response);
        }
    }

    private void send(ZMQ.Socket socket, ZMsg zMessage, SocketRpcProtos.Response.Builder response) {
        zMessage.add(new ZFrame(response.build().toByteArray()));
        zMessage.send(socket);
    }
}
