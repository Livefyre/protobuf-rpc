// https://github.com/orbekk/protobuf-simple-rpc/blob/master/src/main/java/com/orbekk/protobuf/Rpc.java
package com.livefyre.protobuf.rpc;

import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Controller implements RpcController {
    private volatile String errorMessage = "";
    private volatile boolean hasFailed;
    private volatile boolean canceled;
    private volatile long timeoutMillis = 0;
    private volatile List<RpcCallback<Object>> cancelNotificationListeners = null;
    private volatile Channel.Errors channelError = null;
    private volatile SocketRpcProtos.ErrorReason rpcError = null;

    public Controller() {}

    public Controller(Controller other) {
        copyFrom(other);
    }

    public void copyFrom(Controller other) {
        errorMessage = other.errorMessage;
        hasFailed = other.hasFailed;
        canceled = other.canceled;
        if (other.cancelNotificationListeners != null) {
            for (RpcCallback<Object> listener : other.cancelNotificationListeners) {
                notifyOnCancel(listener);
            }
        }
    }

    public void writeTo(Response.Builder response) {
        response.setHasFailed(hasFailed);
        response.setCanceled(canceled);
        response.setErrorMessage(errorMessage);
    }

    public void readFrom(Response response) {
        hasFailed = response.getHasFailed();
        canceled = response.getCanceled();
        errorMessage = response.getErrorMessage();
        rpcError = response.getErrorCode();
    }

    @Override
    public String errorText() {
        return errorMessage;
    }

    public Channel.Errors channelError() { return channelError; }

    public SocketRpcProtos.ErrorReason rpcError() { return rpcError; }

    public boolean isOk() {
        return !hasFailed && !canceled;
    }

    @Override
    public boolean failed() { return hasFailed; }

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void notifyOnCancel(RpcCallback<Object> listener) {
        if (cancelNotificationListeners == null) {
            cancelNotificationListeners =
                    Collections.synchronizedList(
                            new ArrayList<>());
        }
        cancelNotificationListeners.add(listener);
    }

    @Override
    public void reset() {
        copyFrom(new Controller());
    }

    @Override
    public void setFailed(String message) {
        hasFailed = true;
        errorMessage = message;
    }

    public void setFailed(Channel.Errors channelError) {
        this.channelError = channelError;
        hasFailed = true;
    }

    public void cancel() {
        canceled = true;
        if (cancelNotificationListeners != null) {
            for (RpcCallback<Object> listener :
                    cancelNotificationListeners) {
                listener.run(null);
            }
        }
    }

    public long getTimeout() {
        return timeoutMillis;
    }

    public void setTimeout(long milliseconds) {
        timeoutMillis = milliseconds;
    }

    @Override
    public void startCancel() {}

    @Override public String toString() {
        return String.format("Controller[ok(%s) canceled(%s) failed(%s) error_text(%s)]",
                isOk(), isCanceled(), failed(), errorText());
    }
}
