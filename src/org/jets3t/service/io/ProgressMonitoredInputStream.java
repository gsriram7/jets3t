/*
 * jets3t : Java Extra-Tasty S3 Toolkit (for Amazon S3 online storage service)
 * This is a java.net project, see https://jets3t.dev.java.net/
 * 
 * Copyright 2006 James Murty
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.jets3t.service.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * Input stream wrapper that tracks the number of bytes that have been read through the stream.
 * When data is read through this stream the count of bytes is increased, and at a set minimum 
 * interval (eg after at least 1024 bytes) a {@link BytesTransferredWatcher} implementation
 * is notified of the count of bytes read since the last notification.  
 *  
 * @author James Murty
 */
public class ProgressMonitoredInputStream extends InputStream implements InputStreamWrapper {
    private InputStream inputStream = null;
    private BytesTransferredWatcher bytesTransferredWatcher = null;
    private long minimumBytesBeforeNotification = 1024;
    private long bytesTransferredTotal = 0;
    private long bytesTransferredLastUpdate = 0;

    /**
     * Construts the input stream around an underlying stream and sends notification messages
     * to a watcher at intervals.
     * 
     * @param inputStream
     *        the input stream to wrap, whose byte transfer count will be monitored.
     * @param bytesTransferredWatcher
     *        a notification watcher
     * @param minimumBytesBeforeNotification
     *        the minimum number of bytes that must be transferred before a notification will be triggered
     */
    public ProgressMonitoredInputStream(InputStream inputStream, 
        BytesTransferredWatcher bytesTransferredWatcher, long minimumBytesBeforeNotification) 
    {
        if (inputStream == null) {
            throw new IllegalArgumentException(
                "ProgressMonitoredInputStream cannot run with a null InputStream");
        }
        this.inputStream = inputStream;
        this.bytesTransferredWatcher = bytesTransferredWatcher;
        this.minimumBytesBeforeNotification = minimumBytesBeforeNotification;
    }

    /**
     * Construts the input stream around an underlying stream and sends notification messages
     * to a watcher at minimum byte intervals of 1024.
     * 
     * @param inputStream
     *        the input stream to wrap, whose byte transfer count will be monitored.
     * @param bytesTransferredWatcher
     *        a notification watcher
     */
    public ProgressMonitoredInputStream(InputStream inputStream, 
        BytesTransferredWatcher bytesTransferredWatcher) 
    {
        this(inputStream, bytesTransferredWatcher, 1024);
    }

    /**
     * Checks how many bytes have been transferred since the last notification, and sends a notification
     * message if this number exceeds the minimum bytes transferred value.
     * 
     * @param bytesTransmitted
     */
    public void sendNotificationUpdate(long bytesTransmitted) {
        bytesTransferredTotal += bytesTransmitted;
        
        if (bytesTransferredWatcher != null) {
            // Notify watcher if more than the minimum number of bytes have been transferred since last
            // time, or if a negative update value is received (ie if a transmission is being retried)
            long bytesSinceLastUpdate = bytesTransferredTotal - bytesTransferredLastUpdate;
            if (bytesSinceLastUpdate > minimumBytesBeforeNotification || bytesSinceLastUpdate < 0) {
                bytesTransferredWatcher.bytesTransferredUpdate(bytesSinceLastUpdate);
                bytesTransferredLastUpdate = bytesTransferredTotal;
            }
        }
    }
    
    public int read() throws IOException {
        int read = inputStream.read();
        if (read != -1) {
            sendNotificationUpdate(1);
        }
        return read; 
    }
    
    public int read(byte[] b, int off, int len) throws IOException {
        int read = inputStream.read(b, off, len);
        if (read != -1) {
            sendNotificationUpdate(read);
        }
        return read;
    }
    
    public int available() throws IOException {
        return inputStream.available();
    }
    
    public void close() throws IOException {
        inputStream.close();            
    }
    
    public InputStream getWrappedInputStream() {
        return inputStream;
    }
    
}
