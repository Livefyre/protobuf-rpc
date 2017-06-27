var path = require('path');
var Protobuf = require('protobufjs');

var builder = Protobuf.newBuilder();
Protobuf.loadProtoFile({
  root: __dirname,
  file: 'rpc.proto'
}, builder);
var root = builder.build();

var serviceAndMethodNameRegEx = /^\.(.*)\.(.*)$/;

function rpcImpl(channel) {
  return function(method, request, callback) {
    var serviceAndMethodNames = extractServiceAndMethodNames(method);

    var headers = request.__headers;
    delete request.__headers;
    var request = new root.protobuf.socketrpc.Request({
      service_name: serviceAndMethodNames.serviceName,
      method_name: serviceAndMethodNames.methodName,
      request_proto: request.encode().toBuffer(),
    });
    if (headers) {
        request.set('headers', headers);
    }

    function cb(error, response) {
      if (error) {
        return callback(error);
      }
      var response = root.protobuf.socketrpc.Response.decode(response);
      if (response.error_message) {
        return callback(response.error_message);
      }
      callback(null, response.response_proto.toBase64());
    }

    function extractServiceAndMethodNames(str) {
      var matches = serviceAndMethodNameRegEx.exec(str);
      return {
        serviceName: matches[1],
        methodName: matches[2]
      };
    }

    channel.send(request.encode().toBuffer(), cb);
  };
}

module.exports = {
  ProtoBuf: Protobuf,
  rpcImpl: rpcImpl,
  channels: require('./channels'),
  models: root
};
