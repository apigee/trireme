var net = require('net');
var util = require('util');
var EventEmitter = require('events').EventEmitter;

function v8Debugger() {
    this.seq = 0;
    EventEmitter.call(this);
}

util.inherits(v8Debugger, EventEmitter);

(function() {
    this.attach = function() {
        var self = this;

        //starts v8 debug agent
        process.kill(process.id, 'SIGUSR1');

        this.debugsrv = net.connect(5858, function() {
            self.emit('attached');
        });

        this.debugsrv.on('data', function(response) {
            response = response + '';
            try {
                response = JSON.parse(response);
            } catch(e) {
                //console.log(e.stack);
                return;
            }

            self.emit(response.command, response);
            console.log(response);
        });

        this.debugsrv.on('end', function() {
            self.emit('detached');

            //stops v8 debug agent NODEJS doesn't work this way as for v0.6.14 
            //there is no way to stop v8 debug agent once it's started.
            //process.kill(process.id, 'SIGUSR1');
        });

        /*var writefn = this.debugsrv.write;
        this.debugsrv.write = function(data) {
            writefn.call(self.debugsrv, JSON.stringify(data));
        };*/
    };

    this.detach = function() {
        this.debugsrv.end();
    };

    this.scripts = function() {
        var request = {
            seq: this.seq++,
            type: 'request',
            command: 'scripts',
            arguments: {}
        };

        this.debugsrv.write(JSON.stringify(request));
    };
}).call(v8Debugger.prototype);

module.exports = new v8Debugger();
