var zmq = require('zmq');
var pool = require('generic-pool');
var assert = require('assert');
var util = require('util');
var _ = require('lodash');


function ZMQTimeoutError(data) {
  Error.call(this);
  this.name = this.constructor.name;
  this.data = data;
}
util.inherits(ZMQTimeoutError, Error);


var ZMQChannel = function (addresses, min, max, idleTimeoutMillis, requestTimeoutMillis) {
  this.addresses = addresses = _.isArray(addresses) ? addresses : [addresses];
  this.min = min || 10;
  this.max = max || 10;
  this.idleTimeoutMillis = idleTimeoutMillis || 30000;
  this.requestTimeoutMillis = requestTimeoutMillis || 2000;
  var timers = this.timers = {};
  var callbacks = this.callbacks = {};
  var counter = 0;

  assert(this.min <= this.max, 'min must not be greater than max');

  function create(callback) {
    var socket = zmq.socket('req');
    addresses.forEach(function (address) {
      socket.connect('tcp://' + address);
    });
    socket.on('message', function (response) {
      var fn = callbacks[socket.identity];
      clearTimeout(timers[socket.identity]);
      delete callbacks[socket.identity];
      delete timers[socket.identity];
      connectionPool.release(socket);
      fn && fn(null, response);
    });
    socket.on('error', function (error) {
      var fn = callbacks[socket.identity];
      clearTimeout(timers[socket.identity]);
      delete callbacks[socket.identity];
      delete timers[socket.identity];
      connectionPool.destroy(socket);
      fn && fn(error);
    });
    socket.identity = process.pid + '-' + counter++;
    callback(null, socket);
  }

  function destroy(socket) {
    clearTimeout(timers[socket.identity]);
    delete timers[socket.identity];
    socket.setsockopt(zmq.ZMQ_LINGER, 0);
    socket.close();
  }

  var connectionPool = this.connectionPool = pool.Pool({
    name: 'ZMQConnectionPool',
    create: create,
    destroy: destroy,
    min: this.min,
    max: this.max,
    idleTimeoutMillis: this.idleTimeoutMillis
  });
};

ZMQChannel.prototype.send = function send(data, callback) {
  this.connectionPool.acquire(function (err, socket) {
    if (err) {
      return callback(err);
    }
    socket.send(data);
    this.callbacks[socket.identity] = callback;

    function cleanup() {
      delete this.callbacks[socket.identity];
      delete this.timers[socket.identity];
      this.connectionPool.destroy(socket);
      callback(new ZMQTimeoutError(data));
    }
    this.timers[socket.identity] = setTimeout(cleanup.bind(this), this.requestTimeoutMillis);
  }.bind(this));
};

ZMQChannel.prototype.close = function close() {
  this.callbacks = {};
  this.connectionPool.drain(this.connectionPool.destroyAllNow);
};

module.exports = ZMQChannel;
