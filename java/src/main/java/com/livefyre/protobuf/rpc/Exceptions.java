package com.livefyre.protobuf.rpc;

import com.googlecode.protobuf.socketrpc.SocketRpcProtos;

public class Exceptions {

    // base exception
    static class ProtoRpcException extends RuntimeException {
        ProtoRpcException(String message) {
            super(message);
        }
    }

    // channel exceptions
    static class TimeoutException extends ProtoRpcException {
        TimeoutException(String message) {
            super(message);
        }
    }

    static class ChannelClosedException extends ProtoRpcException {
        ChannelClosedException(String message) {
            super(message);
        }
    }

    static class BadResponseProtoError extends ProtoRpcException {
        BadResponseProtoError(String message) {
            super(message);
        }
    }

    // server exceptions
    static class InvalidRequestProtoException extends ProtoRpcException {
        InvalidRequestProtoException(String message) { super(message); }
    }
    static class RpcError extends ProtoRpcException {
        RpcError(String message) {
            super(message);
        }
    }

    static class RpcFailedError extends ProtoRpcException {
        RpcFailedError(String message) {
            super(message);
        }
    }

    static class BadRequestDataError extends ProtoRpcException {
        BadRequestDataError(String message) {
            super(message);
        }
    }

    static class BadRequestProtoError extends ProtoRpcException {
        BadRequestProtoError(String message) {
            super(message);
        }
    }

    static class ServiceNotFoundError extends ProtoRpcException {
        ServiceNotFoundError(String message) {
            super(message);
        }
    }

    static class MethodNotFoundError extends ProtoRpcException {
        MethodNotFoundError(String message) {
            super(message);
        }
    }

    public static ProtoRpcException getFrom(Controller controller) {
        SocketRpcProtos.ErrorReason error_code = controller.rpcError();
        // client errors
        if (error_code == null) {
            if (controller.channelError() == Channel.Errors.TIMEOUT) {
                return new TimeoutException(controller.errorText());
            }
            if (controller.channelError() == Channel.Errors.CHANNEL_CLOSED) {
                return new ChannelClosedException(controller.errorText());
            }
            if (controller.channelError() == Channel.Errors.INVALID_RESPONSE) {
                return new BadResponseProtoError(controller.errorText());
            }
            return new RpcError("Unknown Error");
        }
        // server errors
        switch (error_code) {
            case INVALID_REQUEST_PROTO:
                return new InvalidRequestProtoException(controller.errorText());
            case RPC_FAILED:
                return new RpcFailedError(controller.errorText());
            case BAD_REQUEST_DATA:
                return new BadRequestDataError(controller.errorText());
            case BAD_REQUEST_PROTO:
                return new BadRequestProtoError(controller.errorText());
            case SERVICE_NOT_FOUND:
                return new ServiceNotFoundError(controller.errorText());
            case METHOD_NOT_FOUND:
                return new MethodNotFoundError(controller.errorText());
            default:
                return new RpcError(controller.errorText());
        }
    }

    public static ProtoRpcException getCause(Exception e) {
        Throwable t = e.getCause();
        if (t instanceof TimeoutException) {
            return (TimeoutException) t;
        }
        if (t instanceof ChannelClosedException) {
            return (ChannelClosedException) t;
        }
        if (t instanceof BadResponseProtoError) {
            return (BadResponseProtoError) t;
        }
        if (t instanceof  InvalidRequestProtoException) {
            return (InvalidRequestProtoException) t;
        }
        if (t instanceof RpcError) {
            return (RpcError) t;
        }
        if (t instanceof RpcFailedError) {
            return (RpcFailedError) t;
        }
        if (t instanceof BadRequestDataError) {
            return (BadRequestDataError) t;
        }
        if (t instanceof  BadRequestProtoError) {
            return (BadRequestProtoError) t;
        }
        if (t instanceof ServiceNotFoundError) {
            return (ServiceNotFoundError) t;
        }
        if (t instanceof MethodNotFoundError) {
            return (MethodNotFoundError) t;
        }
        return (ProtoRpcException) e;
    }

}
