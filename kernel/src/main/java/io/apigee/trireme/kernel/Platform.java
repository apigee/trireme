/**
 * Copyright 2014 Apigee Corporation.
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
 
 package io.apigee.trireme.kernel;
 
 public class Platform
 {
     private static final Platform myself = new Platform();
     
     private String platform;
     private boolean windows;
     private boolean posix = true;
     
     private Platform()
     {
         // Must be one of'darwin', 'freebsd', 'linux', 'sunos' or 'win32' as per
         // http://nodejs.org/api/process.html#process_process_platform
         String OS = System.getProperty("os.name").toLowerCase();
         if (OS.contains("mac")) {
             platform = "darwin";
         } else if (OS.contains("freebsd")) {
             platform = "freebsd";
         } else if (OS.contains("linux")) {
             platform = "linux";
         } else if (OS.contains("sunos")) {
             platform = "sunos";
         } else if (OS.contains("win")) {
             platform = "win32";
             windows = true;
             posix = false;
         } else {
             platform = "?";
         }
     }
     
     public static Platform get() {
         return myself;
     }
     
     public String getPlatform() {
         return platform;
     }
     
     public boolean isWindows() {
         return windows;
     }
     
     public boolean isPosixFilesystem() {
         return posix;
     }
 }
