var assert = require('assert');
var path = require('path');

var trireme = process.binding('trireme-native-support');

// This should work
trireme.loadJars([
    path.join(__dirname, '../testjar.jar')
]);

// This will fail and cause the script to exit because of missing classes on the path
require('test-jar-module');



