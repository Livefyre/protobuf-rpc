from protobuf_rpc.controller import SocketRpcController
from protobuf_rpc.error import MethodNotFoundError
from protobuf_rpc.protos.rpc_pb2 import Request, Response, RPC_ERROR, \
    INVALID_REQUEST_PROTO, METHOD_NOT_FOUND, BAD_REQUEST_PROTO
from protobuf_rpc.util import deserialize_string


class Callback(object):
    '''Class to allow execution of client-supplied callbacks.'''

    def __init__(self):
        self.invoked = False
        self.response = None

    def run(self, response):
        self.response = response
        self.invoked = True


class ProtoBufRPCServer(object):
    def handle(self, request):
        try:
            req_obj = self.parse_outer_request(request)
        except Exception as e:
            return self.build_error_response(req_obj, e.message, INVALID_REQUEST_PROTO)

        try:
            method = self.get_method(req_obj.method_name)
            if method is None:
                raise MethodNotFoundError("Method %s not found" % (req_obj.method_name))
        except Exception as e:
            return self.build_error_response(req_obj, e.message, METHOD_NOT_FOUND)

        try:
            req_proto = self.parse_inner_request(req_obj, method)
        except Exception as e:
            return self.build_error_response(e.message, BAD_REQUEST_PROTO)

        try:
            response = self.do_request(method, req_proto)
        except NotImplementedError as e:
            return self.build_error_response(req_obj, e.message, METHOD_NOT_FOUND)
        except Exception as e:
            return self.build_error_response(req_obj, e.message, RPC_ERROR)

        response.request_id = req_obj.id
        return response

    def parse_outer_request(self, request):
        req_obj = Request()
        req_obj.ParseFromString(request)
        return req_obj

    def get_method(self, method_name):
        return self.service.DESCRIPTOR.FindMethodByName(method_name)

    def parse_inner_request(self, request, method):
        return deserialize_string(request.request_proto,
                                  self.service.GetRequestClass(method))

    def do_request(self, method, proto_request):
        controller = SocketRpcController()
        callback = Callback()
        self.service.CallMethod(method, controller, proto_request, callback)
        response = Response()
        response.response_proto = callback.response.SerializeToString()
        return response

    def build_error_response(self, req_obj, error_message, error_code=RPC_ERROR):
        response = Response()
        response.request_id = req_obj.id
        response.error_code = error_code
        error_message = str(error_message)
        response.error_message = error_message
        return response
