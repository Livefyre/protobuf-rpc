var zmq = require('zmq');
var pool = require('generic-pool');
var assert = require('assert');
var _ = require('lodash');


var ZMQChannel = function(addresses, min, max, idleTimeoutMillis) {
  this.addresses = addresses = _.isArray(addresses) ? addresses : [addresses];
  this.min = min || 10;
  this.max = max || 10;
  this.idleTimeoutMillis = idleTimeoutMillis || 2000;
  var callbacks = this.callbacks = {};
  var counter = 0;

  assert(this.min <= this.max, 'min must not be greater than max');

  function create(callback) {
    var socket = zmq.socket('req');
    addresses.forEach(function(address) {
      socket.connect('tcp://' + address);
    });
    socket.on('message', function(response) {
      var fn = callbacks[socket.identity];
      fn && fn(response);
      delete callbacks[socket.identity];
      connectionPool.release(socket);
    });
    socket.identity = process.pid + '-' + counter++;
    callback(null, socket);
  }

  function destroy(socket) {
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
  this.connectionPool.acquire(function(err, socket) {
    if (err) {
      return console.log(err);
    }
    socket.send(data);
    this.callbacks[socket.identity] = callback;
  }.bind(this));
};

ZMQChannel.prototype.close = function close() {
  this.callbacks = {};
  this.connectionPool.drain(this.connectionPool.destroyAllNow);
};

module.exports = ZMQChannel;