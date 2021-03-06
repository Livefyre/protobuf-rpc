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

import java.util.ArrayList;
import java.util.Base64;
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
        final TimerTask timerTask;

        RequestMetadata(long id, Controller controller, RpcCallback<Message> done, Message responsePrototype, TimerTask timerTask) {
            this.id = id;
            this.controller = controller;
            this.done = done;
            this.responsePrototype = responsePrototype;
            this.timerTask = timerTask;
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
        ArrayList<SocketRpcProtos.Request> buffer = new ArrayList<>();
        ZMQ.PollItem[] items = new ZMQ.PollItem[] { new ZMQ.PollItem(socket, ZMQ.Poller.POLLIN) };
        while (!isClosed) {
            ZMQ.poll(items, 10);
            if (items[0].isReadable()) {
                ZMsg message = ZMsg.recvMsg(socket);
                ZFrame content = message.getLast();
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
                ZMsg message = new ZMsg();
                message.add(new ZFrame(""));
                message.add(new ZFrame(request.toByteArray()));
                message.send(socket);
            }
            buffer.clear();
        }
        shadowContext.destroy();
        isClosed = true;
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

    private void start() throws ZMQException {
        logger.info("starting client...");
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

    private TimerTask createTimerTask(long id, Controller controller) {
        long timeout = controller.timeoutMillis;
        if (timeout == 0) {
            return null;
        }
        TimerTask task = new CancelRequestTask(id);
        timer.schedule(task, timeout);
        return task;
    }

    private void cancelTimerTask(RequestMetadata request) {
        if (request.timerTask != null) {
            request.timerTask.cancel();
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
        RequestMetadata request_ = new RequestMetadata(id, controller_, done, responsePrototype, createTimerTask(id, controller_));
        if (isClosed) {
            cancelTimerTask(request_);
            cancelRequest(request_, Errors.CHANNEL_CLOSED);
            return;
        }
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
        request.controller.startCancel(channelError);
        request.done.run(null);
    }

    private void handleResponse(SocketRpcProtos.Response response) {
        RequestMetadata request = ongoingRequests.remove(response.getRequestId());
        if (request == null) {
            logger.warn("unknown response, proto -> {}", response);
            return;
        }
        cancelTimerTask(request);
        try {
            Message responsePb = null;
            if (response.hasResponseProto()) {
                responsePb = request.responsePrototype.toBuilder()
                        .mergeFrom(response.getResponseProto()).build();
            }
            logger.debug("received response, id -> {}, proto -> {}", response.getRequestId(), responsePb);
            request.controller.readFrom(response);
            request.done.run(responsePb);
        } catch (InvalidProtocolBufferException e) {
            cancelRequest(request, Errors.INVALID_RESPONSE);
        }
    }
}
