package com.livefyre.protobuf.rpc;

import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;

import java.util.concurrent.CompletableFuture;

public class AsyncRpc<T extends Message> {

    private final CompletableFuture<T> future = new CompletableFuture<>();
    private Controller controller;

    AsyncRpc(Controller controller) {
        this.controller = controller;
    }

    public CompletableFuture<T> newFuture() {
        return future;
    }

    public RpcCallback<T> newCallback() {
        return new RpcCallback<T>() {
            @Override
            public void run(T t) {
                if (!controller.isOk()) {
                    future.completeExceptionally(Exceptions.getFrom(controller));
                } else { future.complete(t); }
            }
        };
    }

}