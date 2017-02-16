// https://github.com/orbekk/protobuf-simple-rpc/blob/master/src/main/java/com/orbekk/protobuf/Rpc.java
package com.livefyre.protobuf.rpc;

import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos.Response;

import java.util.concurrent.CompletableFuture;

public class Controller implements RpcController {
    final long timeoutMillis;
    private volatile ControllerState state =
            new ControllerState(false, false, null, null, null);
    private final CompletableFuture<Message> future = new CompletableFuture<>();

    private class ControllerState {
        final boolean hasFailed;
        final boolean isCanceled;
        final Channel.Errors channelError;
        final SocketRpcProtos.ErrorReason rpcError;
        final String errorMessage;

        ControllerState(boolean hasFailed, boolean isCanceled, SocketRpcProtos.ErrorReason rpcError, String errorMessage, Channel.Errors channelError) {
            this.hasFailed = hasFailed;
            this.isCanceled = isCanceled;
            this.rpcError = rpcError;
            this.errorMessage = errorMessage;
            this.channelError = channelError;
        }
    }

    public Controller() {
        timeoutMillis = 0;
    }

    public Controller(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void writeTo(Response.Builder response) {
        response.setHasFailed(state.hasFailed);
        response.setCanceled(state.isCanceled);
        response.setErrorMessage(state.errorMessage);
    }

    public void readFrom(Response response) {
        state = new ControllerState(response.getHasFailed(),
                response.getCanceled(), response.getErrorCode(), response.getErrorMessage(), null);
    }

    @Override
    public String errorText() { return state.errorMessage; }

    public Channel.Errors channelError() { return state.channelError; }

    public SocketRpcProtos.ErrorReason rpcError() { return state.rpcError; }

    public boolean isOk() {
        return !failed() && !isCanceled();
    }

    @Override
    public boolean failed() { return state.hasFailed; }

    @Override
    public boolean isCanceled() {
        return state.isCanceled;
    }

    @Override
    public void notifyOnCancel(RpcCallback<Object> listener) {}

    @Override
    public void reset() {
        state = null;
    }

    @Override
    public void setFailed(String message) {
        state = new ControllerState(true, false, null, message, null);
    }

    @Override
    public void startCancel() {}

    public void startCancel(Channel.Errors channelError) {
        state = new ControllerState(true, true, null, null, channelError);
    }

    @Override public String toString() {
        return String.format("Controller[ok(%s) canceled(%s) failed(%s) error_text(%s)]",
                isOk(), isCanceled(), failed(), errorText());
    }

    public <T extends Message> CompletableFuture<T> newFuture() {
        return (CompletableFuture<T>) future;
    }

    public <T extends Message> RpcCallback<T> newCallback() {
        return new RpcCallback<T>() {
            @Override
            public void run(T t) {
                if (!isOk()) {
                    future.completeExceptionally(Exceptions.getFrom(Controller.this));
                } else { future.complete(t); }
            }
        };
    }
}
