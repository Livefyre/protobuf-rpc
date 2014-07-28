Service Implementations for Google Protobufs
=============================================

This is a collection of transport implementations for Google Protobuf Services:

https://developers.google.com/protocol-buffers/docs/proto#services

Build:
-------------------

```sh
make env
```



Build Protobufs:
-------------------

```sh
protoc --proto_path=example/search/ --python_out=example/search/ example/search/SearchService.proto
```

Build Server:
-------------------

```sh
make test-server
```

Build Client:
-------------------

```sh
make test-client
```
