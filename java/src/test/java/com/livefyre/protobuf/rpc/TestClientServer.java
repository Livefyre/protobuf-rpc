package com.livefyre.protobuf.rpc;

import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;

public class TestClientServer {

    private Client client = null;
    private Server server = null;

    private ExecutorService cThreads = null;
    private ExecutorService sThreads = null;

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

        private Controller newController() {
            Controller controller = new Controller();
            controller.setTimeout(timeout);
            return controller;
        }

        Future<TestService.Response> test(TestService.Request request) {
            Controller controller = newController();
            AsyncRpc<TestService.Response> async = new AsyncRpc<>(controller);
            service.test(controller, request, async.newCallback());
            return async.newFuture();
        }

        Future<TestService.Response> testTimeout(TestService.Request request) {
            Controller controller = newController();
            AsyncRpc<TestService.Response> async = new AsyncRpc<>(controller);
            service.testTimeout(controller, request, async.newCallback());
            return async.newFuture();
        }
    }

    @Before
    public void setUp(){
        sThreads = Executors.newFixedThreadPool(1);
        cThreads = Executors.newFixedThreadPool(1);

        sThreads.submit(() -> {
           server = Server.create("tcp://localhost:1234", 1, new Service());
           server.start();
        });

        client = new Client(new String[]{"tcp://localhost:1234"}, 1, cThreads, 2000);
        client.start();
    }

    @After
    public void tearDown() {
        client.stop();
        server.tearDown();
        sThreads = null;
        cThreads = null;
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
