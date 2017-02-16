package com.livefyre.protobuf.rpc;

import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;

public class TestClient {

    private static final Logger logger = LoggerFactory.getLogger(TestClient.class);

    private Client client;
    private Server server;

    private ExecutorService cThreads;
    private ExecutorService sThreads;

    private class Service extends TestService.Service {

        @Override
        public void test(RpcController controller, TestService.Request request, RpcCallback<TestService.Response> done) {
            TestService.Response.Builder response = TestService.Response.newBuilder().setResponse(request.getQuery());
            done.run(response.build());
        }

        @Override
        public void testTimeout(RpcController controller, TestService.Request request, RpcCallback<TestService.Response> done) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            TestService.Response.Builder response = TestService.Response.newBuilder().setResponse(request.getQuery());
            done.run(response.build());
        }
    }

    private class Client {

        private String[] endpoints = null;
        private ExecutorService pool = null;
        private int concurrency = 0;
        private int timeout = 0;

        private Channel channel = null;
        private TestService.Service service = null;

        Client(String[] endpoints, int concurrency, ExecutorService pool, int timeout) {
            this.endpoints = endpoints;
            this.concurrency = concurrency;
            this.pool = pool;
            this.timeout = timeout;
        }

        void start() {
            channel = Channel.createOrNull(endpoints, concurrency, pool);
            if (channel != null) {
                service = TestService.Service.newStub(channel);
            }
        }

        void stop() {
            channel.close();
        }

        Future<TestService.Response> test(TestService.Request request) {
            Controller controller = new Controller(timeout);
            AsyncRpc<TestService.Response> async = new AsyncRpc<>(controller);
            service.test(controller, request, async.newCallback());
            return async.newFuture();
        }

        Future<TestService.Response> testTimeout(TestService.Request request) {
            Controller controller = new Controller(timeout);
            AsyncRpc<TestService.Response> async = new AsyncRpc<>(controller);
            service.testTimeout(controller, request, async.newCallback());
            return async.newFuture();
        }
    }

    private static int getAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 9999;
    }

    @Before
    public void setUp(){
        sThreads = Executors.newFixedThreadPool(1);
        cThreads = Executors.newFixedThreadPool(1);

        int port = TestClient.getAvailablePort();
        logger.info("using port -> {}", port);

        String endpoint = "tcp://localhost:" + port;

        sThreads.execute(() -> {
           server = Server.create(endpoint, 1, new Service());
           server.start();
        });

        client = new Client(new String[]{endpoint}, 1, cThreads, 2000);
        client.start();
    }

    @After
    public void tearDown() {
        client.stop();
        server.stop();
    }

    @Test
    public void testRequestResponse() throws ExecutionException, InterruptedException {
        TestService.Request request = TestService.Request.newBuilder().setQuery("foo").build();
        Future<TestService.Response> future = client.test(request);
        TestService.Response response = future.get();
        assertEquals(response.getResponse(), "foo");
    }

    @Test(expected = Exceptions.TimeoutException.class)
    public void testRequestTimeout() throws Exception {
        TestService.Request request = TestService.Request.newBuilder().setQuery("foo").build();
        Future<TestService.Response> future = client.testTimeout(request);
        try {
            future.get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            Exceptions.getCauseAndThrow(e);
        }
    }
}
