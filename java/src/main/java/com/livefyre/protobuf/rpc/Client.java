package com.livefyre.protobuf.rpc;

import com.livefyre.protobuf.rpc.examples.Search;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Client {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        String[] endpoints = {"tcp://localhost:1234"};
        Channel channel = Channel.createOrNull(endpoints, 3);
        ExecutorService handlerPool = Executors.newSingleThreadExecutor();
        if (channel != null) {
            Search.SearchService service = Search.SearchService.newStub(channel);
            Controller controller = new Controller();
            controller.setTimeout(2000);
            Search.SearchRequest request = Search.SearchRequest
                    .newBuilder()
                    .setQuery("matt")
                    .build();
            ResponseAsFuture<Search.SearchResponse> wraps = new ResponseAsFuture<>(controller, handlerPool);
            Future<Search.SearchResponse> future = wraps.generateFuture();
            service.search(controller, request, wraps.generateRpcCallback());
            Search.SearchResponse response = future.get();
            System.out.println(response.getResponse());
        }
    }
}
