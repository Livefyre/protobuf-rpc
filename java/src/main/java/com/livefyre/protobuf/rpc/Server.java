package com.livefyre.protobuf.rpc;

import com.google.protobuf.*;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.*;

import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Server {

    private static final Logger logger = LoggerFactory.getLogger(Server.class);
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
        logger.info("starting server...");
        context = new ZContext();

        frontend = context.createSocket(ZMQ.ROUTER);
        frontend.bind(address);

        backend = context.createSocket(ZMQ.DEALER);
        backend.bind("inproc://backend");

        isRunning = true;

        for (int i = 0; i < numConcurrency; i++) {
            requestHandlerPool.submit(() -> {
                // https://github.com/zeromq/jeromq/wiki/Sharing-ZContext-between-thread
                try {
                    ZContext shadowContext = ZContext.shadow(context);
                    ZMQ.Socket worker = shadowContext.createSocket(ZMQ.DEALER);
                    worker.connect("inproc://backend");
                    ZMQ.PollItem[] items = new ZMQ.PollItem[] { new ZMQ.PollItem(worker, ZMQ.Poller.POLLIN) };
                    while (isRunning) {
                        ZMQ.poll(items, 10);
                        if (items[0].isReadable()) {
                            try {
                                handleRequest(worker, ZMsg.recvMsg(worker));
                            } catch (Exception e) {
                                logger.warn("unhandled exception processing request", e);
                            }
                        }
                    }
                    logger.info("worker closing...");
                    shadowContext.destroy();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        ZMQ.proxy(frontend, backend, null);

        tearDown();
    }

    public void stop() {
        logger.info("stopping server...");
        isRunning = false;
    }

    private void tearDown() {
        requestHandlerPool.shutdown();
        try {
            requestHandlerPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            requestHandlerPool.shutdownNow();
        }
        context.destroy();
    }

    private void handleRequest(ZMQ.Socket socket, ZMsg zMessage) {
        SocketRpcProtos.Response.Builder response = SocketRpcProtos.Response.newBuilder();

        ZFrame content = zMessage.removeLast();

        SocketRpcProtos.Request request = null;
        try {
            request = SocketRpcProtos.Request.parseFrom(content.getData());
        } catch (InvalidProtocolBufferException e) {
            logger.warn("invalid request proto, b64content -> {}", new String(Base64.getEncoder().encode(content.getData())));
            response.setErrorCode(SocketRpcProtos.ErrorReason.INVALID_REQUEST_PROTO);
            send(socket, zMessage, response);
            return;
        }

        response.setRequestId(request.getId());

        Descriptors.MethodDescriptor method = service.getDescriptorForType().findMethodByName(request.getMethodName());
        if (method == null) {
            logger.warn("method not found, id -> {}, method -> {}, proto -> {}",
                    request.getId(), request.getMethodName(), request);
            response.setErrorCode(SocketRpcProtos.ErrorReason.METHOD_NOT_FOUND);
            send(socket, zMessage, response);
            return;
        }

        Message requestMessage;
        try {
            requestMessage = service.getRequestPrototype(method).toBuilder().mergeFrom(request.getRequestProto()).build();
        } catch (InvalidProtocolBufferException|UninitializedMessageException e) {
            logger.warn("bad request proto, id -> {}, b64proto -> {}", request.getId(),
                    new String(Base64.getEncoder().encode(request.getRequestProto().toByteArray())));
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
            logger.warn("exception invoking service, id -> {}, proto -> ", request.getId(), requestMessage);
            response.setErrorCode(SocketRpcProtos.ErrorReason.RPC_ERROR);
            response.setErrorMessage(e.getMessage());
            send(socket, zMessage, response);
        }
    }

    private void send(ZMQ.Socket socket, ZMsg zMessage, SocketRpcProtos.Response.Builder response) {
        logger.debug("sending response, proto -> {}", response.build());
        zMessage.add(new ZFrame(response.build().toByteArray()));
        zMessage.send(socket);
    }
}
