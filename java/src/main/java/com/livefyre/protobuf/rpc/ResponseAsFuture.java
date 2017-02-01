package com.livefyre.protobuf.rpc;

import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class ResponseAsFuture<T extends Message> {

    private final Controller controller;
    private final ExecutorService threadPool;

    ResponseAsFuture(Controller controller, ExecutorService threadPool) {
        this.controller = controller;
        this.threadPool = threadPool;
    }

    public Future<T> generateFuture() {
        return this.threadPool.submit(() -> {
            controller.await();
            return (T) controller.getResponseProto();
        });
    }

    public RpcCallback<T> generateRpcCallback() {
        return new RpcCallback<T>() {
            @Override
            public void run(T t) {
                // do nothing
            }
        };
    }

}
