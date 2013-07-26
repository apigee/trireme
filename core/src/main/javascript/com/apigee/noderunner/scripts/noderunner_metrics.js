/*
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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