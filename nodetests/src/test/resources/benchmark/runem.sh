# This script assumes that you set the CLASSPATH.

function testOne() {
  echo "$1..."
  echo -n "  node "
  time node $1 # > /dev/null
  echo -n "  java "
  time java com.apigee.noderunner.core.NodeRunner $1 # > /dev/null
}

TIMEFORMAT=%R

testOne buffer_creation.js
testOne client_latency.js
testOne fast_buffer2.js
testOne fast_buffer2_creation.js
testOne fast_buffer_creation.js
#testOne fs-readfile.js
#testOne http_bench.js
#testOne http_server_lag.js
testOne next-tick.js
testOne next-tick-2.js
testOne settimeout.js
testOne string_creation.js
testOne timers.js
testOne url.js
