package com.livefyre.protobuf.rpc;

import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.livefyre.protobuf.rpc.examples.Search;

import java.util.concurrent.*;

public class Client {

    private String[] endpoints = null;
    private ExecutorService pool = null;
    private int concurrency = 0;
    private int timeout = 0;

    private Channel channel = null;
    private Search.SearchService service = null;

    private class SearchService extends Search.SearchService {

        @Override
        public void search(RpcController controller, Search.SearchRequest request, RpcCallback<Search.SearchResponse> done) {
            Search.SearchResponse.Builder response = Search.SearchResponse.newBuilder().setResponse(request.getQuery());
            done.run(response.build());
        }
    }

    Client(String[] endpoints, int concurrency, ExecutorService pool, int timeout) {
        this.endpoints = endpoints;
        this.concurrency = concurrency;
        this.pool = pool;
        this.timeout = timeout;
    }

    void start() {
        channel = Channel.createOrNull(endpoints, concurrency, pool);
        if (channel != null) {
            service = Search.SearchService.newStub(channel);
        }
    }

    Future<Search.SearchResponse> search(Search.SearchRequest request) {
        Controller controller = newController();
        AsyncRpc<Search.SearchResponse> async = new AsyncRpc<>(controller);
        service.search(controller, request, async.newCallback());
        return async.newFuture();
    }

    private Controller newController() {
        Controller controller = new Controller();
        controller.setTimeout(timeout);
        return controller;
    }

    void runServer() {
        ExecutorService serverThread = Executors.newFixedThreadPool(1);

        serverThread.submit(() -> {
            Server server = Server.create("tcp://localhost:1234", 1, new SearchService());
            server.start();
        });
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        Client c = new Client(new String[]{"tcp://localhost:1234"}, 3, Executors.newSingleThreadExecutor(), 2000);
        c.runServer();
        c.start();

        Search.SearchRequest request = Search.SearchRequest
                .newBuilder()
                .setQuery("matt")
                .build();

        Future<Search.SearchResponse> future = c.search(request);
        Search.SearchResponse response = future.get();

        System.out.println(response.getResponse());
    }
}
