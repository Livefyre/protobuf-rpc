// https://github.com/orbekk/protobuf-simple-rpc/blob/master/src/main/java/com/orbekk/protobuf/RpcChannel.java
package com.livefyre.protobuf.rpc;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcChannel;
import com.google.protobuf.RpcController;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Channel implements RpcChannel {
    private static final Logger logger = Logger.getLogger(RpcChannel.class.getName());
    private final AtomicLong nextId = new AtomicLong(0);
    private final Timer timer;
    private final ExecutorService responseHandlerPool;
    private final BlockingQueue<SocketRpcProtos.Request> requestQueue;
    private final ConcurrentHashMap<Long, RequestMetadata> ongoingRequests = new ConcurrentHashMap<>();
    private volatile OutgoingHandler outgoingHandler = null;
    private volatile IncomingHandler incomingHandler = null;

    private String[] endpoints;
    private ZContext context = null;
    private ZMQ.Socket socket = null;

    private volatile boolean isClosed = false;

    public enum Errors {
        TIMEOUT, CHANNEL_CLOSED, INVALID_RESPONSE
    }

    private class RequestMetadata {
        final long id;
        final Controller controller;
        final RpcCallback<Message> done;
        final Message responsePrototype;

        RequestMetadata(long id, Controller controller, RpcCallback<Message> done, Message responsePrototype) {
            this.id = id;
            this.controller = controller;
            this.done = done;
            this.responsePrototype = responsePrototype;
        }
    }

    private class ResponseHandler implements Runnable {
        private final SocketRpcProtos.Response response;

        ResponseHandler(SocketRpcProtos.Response response) {
            this.response = response;
        }

        @Override public void run() {
            handleResponse(response);
        }
    }

    private class CancelRequestTask extends TimerTask {
        final long id;

        CancelRequestTask(long id) {
            this.id = id;
        }

        @Override
        public void run() {
            RequestMetadata request = ongoingRequests.remove(id);
            if (request != null) {
                cancelRequest(request, Errors.TIMEOUT);
            }
        }
    }

    private class OutgoingHandler extends Thread {
        private final ZMQ.Socket socket;
        private final BlockingQueue<SocketRpcProtos.Request> requests;

        OutgoingHandler(ZMQ.Socket socket, BlockingQueue<SocketRpcProtos.Request> requests) {
            super("OutgoingHandler");
            this.socket = socket;
            this.requests = requests;
        }

        @Override public void run() {
            try {
                LinkedList<SocketRpcProtos.Request> buffer =
                        new LinkedList<>();
                while (true) {
                    buffer.clear();
                    buffer.add(requests.take());
                    requests.drainTo(buffer);
                    for (SocketRpcProtos.Request request : buffer) {
                        socket.send(request.toByteArray());
                    }
                }
            } catch (InterruptedException e) {
                tryCloseSocket(socket);
            }
        }

        @Override public void interrupt() {
            super.interrupt();
            tryCloseSocket(socket);
        }
    }

    private class IncomingHandler extends Thread {
        private ZMQ.Socket socket;
        private ExecutorService responseHandlerPool;

        IncomingHandler(ZMQ.Socket socket,
                               ExecutorService responseHandlerPool) {
            super("IncomingHandler");
            this.socket = socket;
            this.responseHandlerPool = responseHandlerPool;
        }

        @Override public void run() {
            try {
                ZMQ.PollItem[] items = new ZMQ.PollItem[] { new ZMQ.PollItem(socket, ZMQ.Poller.POLLIN) };
                while (true) {
                    ZMQ.poll(items, 100);
                    if (items[0].isReadable()) {
                        SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(socket.recv());
                        responseHandlerPool.execute(new ResponseHandler(response));
                    }
                }
            } catch (IOException e) {
                responseHandlerPool.shutdown();
                tryCloseSocket(socket);
            }
        }

        @Override public void interrupt() {
            super.interrupt();
            tryCloseSocket(socket);
        }
    }

    public static Channel createOrNull(String[] endpoints, int numConcurrentRequests, ExecutorService responseHandlerPool) {
        try {
            return create(endpoints, numConcurrentRequests, responseHandlerPool);
        } catch (ZMQException e) {
            logger.log(Level.WARNING, "Unable to create RPC channel.", e);
            return null;
        }
    }

    public static Channel create(String[] endpoints, int numConcurrentRequests, ExecutorService responseHandlerPool)
            throws ZMQException {
        Timer timer = new Timer();
        Channel channel = new Channel(timer, endpoints, numConcurrentRequests, responseHandlerPool);
        channel.start();
        return channel;
    }

    Channel(Timer timer, String[] endpoints, int numConcurrentRequests, ExecutorService responseHandlerPool) {
        this.timer = timer;
        this.endpoints = endpoints;
        this.requestQueue = new ArrayBlockingQueue<>(numConcurrentRequests);
        this.responseHandlerPool = responseHandlerPool;
    }

    public void start() throws ZMQException {
        if (outgoingHandler != null) {
            throw new IllegalStateException("start() called twice.");
        }
        context = new ZContext(1);
        socket = context.createSocket(ZMQ.DEALER);
        for (String endpoint : endpoints) {
            socket.connect(endpoint);
        }
        outgoingHandler = new OutgoingHandler(socket, requestQueue);
        incomingHandler = new IncomingHandler(socket, responseHandlerPool);

//        outgoingHandler.setDaemon(true);
//        incomingHandler.setDaemon(true);

        outgoingHandler.start();
        incomingHandler.start();
    }

    public void close() {
        isClosed = true;
        tryCloseSocket(socket);
        context.close();
        outgoingHandler.interrupt();
        incomingHandler.interrupt();
        timer.cancel();
        cancelAllRequests(Errors.CHANNEL_CLOSED);
    }

    private void tryCloseSocket(ZMQ.Socket socket) {
        cancelAllRequests(Errors.CHANNEL_CLOSED);
        socket.close();
    }

    private void addTimeoutHandler(RequestMetadata request) {
        long timeout = request.controller.getTimeout();
        if (timeout > 0) {
            timer.schedule(new CancelRequestTask(request.id), timeout);
        }
    }

    @Override
    public void callMethod(MethodDescriptor method,
                           RpcController controller,
                           Message requestMessage,
                           Message responsePrototype,
                           RpcCallback<Message> done) {
        long id = nextId.incrementAndGet();
        Controller controller_ = (Controller) controller;
        RequestMetadata request_ = new RequestMetadata(id, controller_, done, responsePrototype);
        if (isClosed) {
            cancelRequest(request_, Errors.CHANNEL_CLOSED);
            return;
        }
        addTimeoutHandler(request_);
        ongoingRequests.put(id, request_);

        if (logger.isLoggable(Level.FINER)) {
            logger.finer(String.format("O(%d) => %s(%s)",
                    id, method.getFullName(), requestMessage));
        }

        SocketRpcProtos.Request requestData = SocketRpcProtos.Request.newBuilder()
                .setId(id)
                .setServiceName(method.getService().getFullName())
                .setMethodName(method.getName())
                .setRequestProto(requestMessage.toByteString())
                .build();

        try {
            requestQueue.put(requestData);
        } catch (InterruptedException e) {
            cancelRequest(request_, Errors.CHANNEL_CLOSED);
        }
    }

    private void cancelAllRequests(Errors channelError) {
        for (RequestMetadata request : ongoingRequests.values()) {
            cancelRequest(request, channelError);
        }
    }

    private void cancelRequest(RequestMetadata request, Errors channelError) {
        request.controller.setFailed(channelError);
        request.controller.cancel();
        request.done.run(null);
    }

    private void handleResponse(SocketRpcProtos.Response response) {
        RequestMetadata request =
                ongoingRequests.remove(response.getRequestId());
        if (request == null) {
            logger.info("Unknown request. Possible timeout? " + response);
            return;
        }
        try {
            Message responsePb = null;
            if (response.hasResponseProto()) {
                responsePb = request.responsePrototype.toBuilder()
                        .mergeFrom(response.getResponseProto()).build();
            }
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(String.format("O(%d) <= %s",
                        response.getRequestId(),
                        responsePb));
            }
            request.controller.readFrom(response);
            if (responsePb == null && request.controller.isOk()) {
                logger.warning("Invalid response from server: " + response);
                request.controller.setFailed("invalid response from server.");
            }
            request.done.run(responsePb);
        } catch (InvalidProtocolBufferException e) {
            cancelRequest(request, Errors.INVALID_RESPONSE);
        }
    }
}
