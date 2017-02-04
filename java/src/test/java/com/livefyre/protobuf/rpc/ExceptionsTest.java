package com.livefyre.protobuf.rpc;

import com.googlecode.protobuf.socketrpc.SocketRpcProtos;
import org.junit.Test;

public class ExceptionsTest {

    @Test(expected = Exceptions.TimeoutException.class)
    public void testClientErrors() throws Exception {
        Controller controller = new Controller();
        controller.setFailed(Channel.Errors.TIMEOUT);
        throw Exceptions.getFrom(controller);
    }

    @Test(expected = Exceptions.BadRequestDataError.class)
    public void testServerErrors() throws Exception {
        Controller controller = new Controller();
        SocketRpcProtos.Response response = SocketRpcProtos.Response
                .newBuilder()
                .setErrorCode(SocketRpcProtos.ErrorReason.BAD_REQUEST_DATA)
                .build();
        controller.readFrom(response);
        throw Exceptions.getFrom(controller);
    }
}
