var v8debugger = require('./v8-debugger');

function DebuggerAgent() {
    this.scripts = [];
    this.enabled = false;
}

(function(){
    this.enable = function(params, sendResult, sendEvent) {
        var self = this;

        v8debugger.on('attached', function() {
            v8debugger.on('scripts', function(scripts) {
                for (var i = 0, len = self.scripts.length; i < len; i++) {
                    var script = self.scripts[i];

                    var endLine = script.lineOffset + script.lineCount - 1;
                    var endColumn = endLine.length;

                    sendEvent({
                        method: 'Debugger.scriptParsed',
                        params: {
                            scriptId: script.id,
                            url: 'http://node-webkit-agent/scripts/pid/' + process.pid,
                            startLine: script.lineOffset,
                            startColumn: script.columnOffset,
                            endLine: endLine,
                            endColumn: endColumn
                        }
                    });
                }

                self.enabled = true; 
                sendResult({result: self.enabled});
            });

            v8debugger.scripts();
        });

        v8debugger.attach();
    };

    this.disable = function(params, sendResult, sendEvent) {
        var self = this;

        v8debugger.on('detached', function() {
            self.enabled = false;
            self.scripts = [];
            sendResult({result: self.enabled});
        });

        v8debugger.detach();
    };

    /*this.causesRecompilation = function(params, sendResult) {
        sendResult({result: false});
    };

    this.supportsNativeBreakpoints = function(params, sendResult) {
        sendResult({result: false});
    };*/

    this.canSetScriptSource = function(params, sendResult) {
        sendResult({result: true});
    };

    this.getScriptSource = function(params, sendResult) {
        console.log('DebuggerAgent.getScriptSource');
        console.log(params);
    };

}).call(DebuggerAgent.prototype);

module.exports = new DebuggerAgent();
