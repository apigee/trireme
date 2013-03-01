/*
 * This module defines some functions that should be global to all scripts to make existing
 * Node code work.
 */

exports.DTRACE_HTTP_SERVER_REQUEST = function(req, conn) {
}

exports.DTRACE_HTTP_SERVER_RESPONSE = function(conn) {
}

exports.DTRACE_HTTP_CLIENT_REQUEST = function(req, conn) {
}

exports.DTRACE_HTTP_CLIENT_RESPONSE = function(conn, req) {
}

exports.DTRACE_NET_SERVER_CONNECTION = function(conn) {
}

exports.DTRACE_NET_STREAM_END = function(stream) {
}

exports.COUNTER_NET_SERVER_CONNECTION = function(conn) {
}

exports.COUNTER_NET_SERVER_CONNECTION_CLOSE = function(conn) {
}

exports.COUNTER_HTTP_SERVER_RESPONSE = function() {
}

exports.COUNTER_HTTP_SERVER_REQUEST = function() {
}

exports.COUNTER_HTTP_CLIENT_REQUEST = function(conn) {
}

exports.COUNTER_HTTP_CLIENT_RESPONSE = function() {
}