class ProtobufRPCClient(object):
    def __init__(self, channel, service):

        self.channel = channel
        self.service = service

        self.controller = SocketRpcController()
        for method_descr in self.service.DESCRIPTOR.methods:
            setattr(self, method_descr.name, self._execute(method_descr))

        self.log_level = log_level

    def _execute(self, method_descr):
        def inner(payload, allowed_error_codes=None):
            request = method_descr.input_type._concrete_class(payload=payload)
            rpc_request = self.create_rpc_request(method_descr, request)
            rpc_request.allowed_error_codes.extend(allowed_error_codes)
            try:
                return getattr(self.service, method_descr.name)(self.controller, request).payload
            except RpcError, e:
                if not allowed_error_codes or e.application_error_code not in allowed_error_codes:
                    raise
                return None
        return inner

    def __getattr__(self, key):
        raise OperationNotSupported(key)

    def create_rpc_request(self, method, request):
        rpcRequest = Request()
        rpcRequest.request_proto = request.SerializeToString()
        rpcRequest.service_name = method.containing_service.full_name
        rpcRequest.method_name = method.name
        rpcRequest.allowed_error_codes.extend(ErrorReason.values())
        return rpcRequest
