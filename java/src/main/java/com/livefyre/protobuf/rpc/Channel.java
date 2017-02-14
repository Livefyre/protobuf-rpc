package com.livefyre.protobuf.rpc;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcChannel;
import com.google.protobuf.RpcController;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;
import org.zeromq.ZMsg;

import java.util.Base64;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;


public class Channel implements RpcChannel {
    private static final Logger logger = LoggerFactory.getLogger(Channel.class);
    private final AtomicLong nextId = new AtomicLong(0);
    private final Timer timer;
    private final ExecutorService requestHandlerPool = Executors.newSingleThreadExecutor();
    private final ExecutorService responseHandlerPool;
    private final BlockingQueue<SocketRpcProtos.Request> requestQueue;
    private final ConcurrentHashMap<Long, RequestMetadata> ongoingRequests = new ConcurrentHashMap<>();

    private String[] endpoints;
    private ZContext context;

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

    private class CancelRequestTask extends TimerTask {
        final long id;

        CancelRequestTask(long id) {
            this.id = id;
        }

        @Override
        public void run() {
            logger.warn("canceling request, id -> {}", id);
            RequestMetadata request = ongoingRequests.remove(id);
            if (request != null) {
                cancelRequest(request, Errors.TIMEOUT);
            }
        }
    }

    private void requestHandler() {
        // https://github.com/zeromq/jeromq/wiki/Sharing-ZContext-between-thread
        ZContext shadowContext = ZContext.shadow(context);
        ZMQ.Socket socket = shadowContext.createSocket(ZMQ.DEALER);
        for (String endpoint : endpoints) {
            socket.connect(endpoint);
        }
        LinkedList<SocketRpcProtos.Request> buffer = new LinkedList<>();
        ZMQ.PollItem[] items = new ZMQ.PollItem[] { new ZMQ.PollItem(socket, ZMQ.Poller.POLLIN) };
        while (!isClosed) {
            ZMQ.poll(items, 10);
            if (items[0].isReadable()) {
                ZMsg message = ZMsg.recvMsg(socket);
                ZFrame content = message.pop();
                try {
                    final SocketRpcProtos.Response response = SocketRpcProtos.Response.parseFrom(content.getData());
                    responseHandlerPool.execute(() -> handleResponse(response));
                } catch (InvalidProtocolBufferException e) {
                    logger.warn("unable to parse proto response, content -> {}",
                            new String(Base64.getEncoder().encode(content.getData())));
                } finally {
                    content.destroy();
                    message.destroy();
                }
            }
            requestQueue.drainTo(buffer);
            for (SocketRpcProtos.Request request : buffer) {
                logger.debug("sending request, id -> {}, proto -> {}", request.getId(), request);
                socket.send(request.toByteArray());
            }
            buffer.clear();
        }
        shadowContext.destroy();
        close();
    }

    public static Channel createOrNull(String[] endpoints, int numConcurrentRequests, ExecutorService responseHandlerPool) {
        try {
            return create(endpoints, numConcurrentRequests, responseHandlerPool);
        } catch (ZMQException e) {
            logger.warn("unable to create rpc channel", e);
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
        logger.info("starting client...");
        if (context != null) {
            throw new IllegalStateException("start() called twice.");
        }
        context = new ZContext(1);
        requestHandlerPool.execute(this::requestHandler);
    }

    public void close() {
        logger.info("closing client...");
        isClosed = true;
        cancelAllRequests(Errors.CHANNEL_CLOSED);
        context.close();
        timer.cancel();
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

        logger.debug("queueing request, id -> {}, proto -> {}", id, requestMessage);

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
        logger.info("canceling oustanding requests...");
        for (RequestMetadata request : ongoingRequests.values()) {
            cancelRequest(request, channelError);
        }
        ongoingRequests.clear();
    }

    private void cancelRequest(RequestMetadata request, Errors channelError) {
        request.controller.setFailed(channelError);
        request.controller.cancel();
        request.done.run(null);
    }

    private void handleResponse(SocketRpcProtos.Response response) {
        RequestMetadata request = ongoingRequests.remove(response.getRequestId());
        if (request == null) {
            logger.warn("unknown response, proto -> {}", response);
            return;
        }
        try {
            Message responsePb = null;
            if (response.hasResponseProto()) {
                responsePb = request.responsePrototype.toBuilder()
                        .mergeFrom(response.getResponseProto()).build();
            }
            logger.debug("received response, id -> {}, proto -> {}", response.getRequestId(), responsePb);
            request.controller.readFrom(response);
            if (responsePb == null && request.controller.isOk()) {
                logger.warn("invalid response -> {}", response);
                request.controller.setFailed("invalid response from server.");
            }
            request.done.run(responsePb);
        } catch (InvalidProtocolBufferException e) {
            cancelRequest(request, Errors.INVALID_RESPONSE);
        }
    }
}
