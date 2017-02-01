// https://github.com/orbekk/protobuf-simple-rpc/blob/master/src/main/java/com/orbekk/protobuf/Rpc.java
package com.livefyre.protobuf.rpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos.Response;

public class Controller implements RpcController {
    private CountDownLatch done = new CountDownLatch(1);
    private volatile String errorMessage = "";
    private volatile boolean hasFailed;
    private volatile boolean canceled;
    private volatile long timeoutMillis = 0;
    private volatile List<RpcCallback<Object>> cancelNotificationListeners = null;
    private volatile Message responseProto;

    public Controller() {
    }

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
    }

    public void setResponseProto(Message responseProto) {
        this.responseProto = responseProto;
    }

    public Message getResponseProto() {
        return responseProto;
    }

    @Override
    public String errorText() {
        return errorMessage;
    }

    public boolean isDone() {
        return done.getCount() == 0;
    }

    public void await() throws InterruptedException {
        done.await();
    }

    public void complete() {
        done.countDown();
    }

    public boolean isOk() {
        return !hasFailed && !canceled;
    }

    @Override
    public boolean failed() {
        return hasFailed;
    }

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

    /** Set the timeout in number of milliseconds.
     *
     * The default timeout is 0, i.e. never time out.
     */
    public void setTimeout(long milliseconds) {
        timeoutMillis = milliseconds;
    }

    @Override
    public void startCancel() {
    }

    @Override public String toString() {
        return String.format("Controller[ok(%s) canceled(%s) failed(%s) error_text(%s)]",
                isOk(), isCanceled(), failed(), errorText());
    }
}
