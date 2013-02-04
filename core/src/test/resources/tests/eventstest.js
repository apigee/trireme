var assert = require('assert');
var EventEmitter = require('events').EventEmitter;

var inherits = require('util').inherits;

function FakeEmitter() {
    EventEmitter.call(this);
}
inherits(FakeEmitter, EventEmitter);
FakeEmitter.prototype.foo = function() { return 'bar'; };

var emitter = new FakeEmitter();

assert.ok(emitter instanceof EventEmitter);

assert.equal(emitter.foo(), 'bar');

assert.equal(emitter.listeners('someEvent').length, 0);

emitter.on('someEvent', function() {
    console.log('someEvent:', arguments);
})

assert.equal(emitter.listeners('someEvent').length, 1);

emitter.emit('someEvent', 'arg 0', 1, {}, [3]);
